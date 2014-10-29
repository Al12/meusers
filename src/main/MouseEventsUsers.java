package main;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

public class MouseEventsUsers extends Configured implements Tool {

    static final double detectionThreshhold = 0.02;

    public static class MouseEventsUsersMapper extends TableMapper<Text, Text> {

        private String getFromResult(String family, String name, Result r) {
            return Bytes.toString(r.getValue(Bytes.toBytes(family),
                    Bytes.toBytes(name)));
        }

        private String getData(String name, Result r) {
            return getFromResult("data", name, r);
        }

        @Override
        public void map(ImmutableBytesWritable row, Result values,
                Context context) throws IOException, InterruptedException {
            if (getFromResult("type", "type", values).equals("mouseover")) {
                // mouseovers are buggy and are less dependent from user than
                // mousemoves
                // skip them
                return;
            }
            EventRecord event = new EventRecord(getFromResult("type", "type",
                    values), Long.parseLong(getData("time", values)),
                    Integer.parseInt(getData("x", values)),
                    Integer.parseInt(getData("y", values)));
            context.write(new Text(getFromResult("id", "sid", values)),
                    event.toText());
        }
    }

    static class MouseEventsUsersReducer extends
            Reducer<Text, Text, Text, Text> {
        private HTable table;
        private long mousemoveDelayLim = 50; // ms, set in calcMoveSpeed

        @Override
        public void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {
            String sid = key.toString();
            MarkingOnEvents marking = new MarkingOnEvents();
            List<EventRecord> events = new ArrayList<>();
            int scrollEvents = 0;
            for (Text value : values) {
                EventRecord lastEvent = new EventRecord(value);
                events.add(lastEvent);
                if (lastEvent.type.equals("wheel")) {
                    ++scrollEvents;
                }
            }            
            Collections.sort(events, new EventRecordComparatorTime());
            // search sequences...
            int count = events.size();
            if (count > 10) {
                markSuspiciousEvents(marking, events);
            }
            
            System.out.println(sid);
            // group users...
            double movementSpeed = calculateMovementSpeed(events);
            System.out.println("Mouse movement speed: " + movementSpeed);
            int clickAndSelects = searchClickSelects(events, 50);
            // for future user identification
            List<PointAndClickAction> pointAndClicks = extractPointAndClickActions(
                    events, sid, 5000, 50);
            // counting some data about found point-and-clicks
            int valid = 0;
            int totalMovements = 0;
            for (PointAndClickAction action : pointAndClicks) {
                if (action.isValid()) {
                    ++valid;
                    totalMovements += action.records.size();
                }
            }
            printPointAndClickStatistics(events.size(), pointAndClicks);
            
            String status;
            Date sessionStart = new Date(events.get(0).time);
            Date sessionFinish = new Date(events.get(events.size() - 1).time);
            status = "\n" + sessionStart.toString() + "\n"
                    + sessionFinish.toString() + "\n";
            status += getUserGroup(events, movementSpeed, scrollEvents,
                    clickAndSelects) + "\n";
            if (marking.getMarkedFraction(count) >= detectionThreshhold) {
                status += "Bot user";
                status += " ("
                        + Math.round(marking.getMarkedFraction(count) * 10000)
                        * 0.01 + "%+";
            } else {
                status += "Human";
                status += " ("
                        + Math.round(marking.getMarkedFraction(count) * 10000)
                        * 0.01 + "%";
            }
            status += " of detected activity is suspicious)";
            context.write(new Text(sid), new Text(status));
            // collecting data on clicks for future use in SVM training
            if (pointAndClicks.size() > 15) {
                // low amounts of clicks are useless, boring and may be abnormal
                byte[] row = Bytes.toBytes(sid);
                Put p = new Put(row);
                p.add(Bytes.toBytes("metadata"), Bytes.toBytes("timestart"),
                        Bytes.toBytes(sessionStart.toString()));
                p.add(Bytes.toBytes("metadata"), Bytes.toBytes("timefinish"),
                        Bytes.toBytes(sessionFinish.toString()));
                if (marking.getMarkedFraction(count) >= detectionThreshhold) {
                    p.add(Bytes.toBytes("metadata"), Bytes.toBytes("info"),
                            Bytes.toBytes("bot"));
                } else {
                    p.add(Bytes.toBytes("metadata"), Bytes.toBytes("info"),
                            Bytes.toBytes("human"));
                }
                p.add(Bytes.toBytes("metadata"), Bytes.toBytes("size"),
                        Bytes.toBytes(totalMovements));
                p.add(Bytes.toBytes("metadata"), Bytes.toBytes("clicks"),
                        Bytes.toBytes(valid));
                String allMetrics = "";
                for (PointAndClickAction action : pointAndClicks) {
                    if (action.isValid()) {
                        for (AngleBasedMetrics metrics : action.records) {
                            allMetrics = allMetrics.concat(metrics.toString()
                                    + ",");
                        }
                    }
                }
                if (totalMovements >= 1) {
                    allMetrics = allMetrics.substring(0,
                            allMetrics.length() - 2);
                }
                p.add(Bytes.toBytes("data"), Bytes.toBytes("metrics"),
                        Bytes.toBytes(allMetrics));
                table.put(p);
            }
        }

        private void markSuspiciousEvents(MarkingOnEvents marking,
                List<EventRecord> events) {
            int count = events.size();
            for (int sequenceLength = 1; sequenceLength <= Math.min(
                    count / 2, 50); ++sequenceLength) {
                // 50 from performance reasons, also from bot's programming
                // reasons
                if (marking.getMarkedFraction(count) >= detectionThreshhold) {
                    // enough, we already know that this user is bot
                    break;
                }
                for (int start = 0; start < count - sequenceLength * 2; ++start) {
                    eventSubseqence subsequence = new eventSubseqence(
                            events, start, sequenceLength);
                    if (subsequence.checkForwardForSameSeqences(2, 25)) {
                        if ((subsequence.foundEqualSeqences + 1)
                                * sequenceLength < 10) {
                            // for simple repeated actions (like
                            // autoclicker)
                            // - why would anybody use the bot for less than
                            // 10 clicks?
                            // for longer 3+ clicks repeated sequences -
                            // lower than 3
                            // still seems unrealistic for bot
                            continue;
                        }
                        if (subsequence.checkPeriods(50)) {
                            if (subsequence.foundEqualSeqences > 0) {
                                // debugging
                                for (int i = start; i < start
                                        + sequenceLength; ++i) {
                                    System.out.print(events.get(i)
                                            .toString() + " ");
                                }
                                System.out.println();
                                System.out.println(" is equal to");
                                for (int j = 0; j < subsequence.foundEqualSeqences; ++j) {
                                    for (int i = start + sequenceLength
                                            * (j + 1); i < start
                                            + sequenceLength * (j + 2); ++i) {
                                        System.out.print(events.get(i)
                                                .toString() + " ");
                                    }
                                    if (j < subsequence.foundEqualSeqences - 1) {
                                        System.out.print(" and");
                                    }
                                    System.out.println();
                                }
                            }
                            if (!marking.isFullyMarked(start,
                                    (subsequence.foundEqualSeqences + 1)
                                            * sequenceLength)) {
                                marking.mark(
                                        start,
                                        (subsequence.foundEqualSeqences + 1)
                                                * sequenceLength);
                            }
                            if (marking.getMarkedFraction(count) >= detectionThreshhold)
                                break;
                        }
                    }
                }
            }
        }

        private void printPointAndClickStatistics(int eventsTotal, 
                List<PointAndClickAction> pointAndClicks) {
            // collecting some statistics, for debugging...
            System.out.println(pointAndClicks.size()
                    + " point-and-click actions per " + eventsTotal
                    + " events");
            double avg = 0;
            int valid = 0;
            for (PointAndClickAction action : pointAndClicks) {
                if (action.isValid()) {
                    avg += action.records.size();
                    ++valid;
                }
            }
            System.out
                    .println(Math.round(100.0 * valid / pointAndClicks.size())
                            + "% of found point-and-click actions are valid");
            System.out.println(Math.round(avg / valid)
                    + "+2 events in each valid action at average");
            double curvativeAngleAvg = 0;
            double curvativeDistanceAvg = 0;
            double curvativeAngleDisp = 0;
            double curvativeDistanceDisp = 0;
            int totalMovements = 0;
            for (PointAndClickAction action : pointAndClicks) {
                if (action.isValid()) {
                    for (AngleBasedMetrics metrics : action.records) {
                        curvativeAngleAvg += metrics.curvativeAngle;
                        curvativeDistanceAvg += metrics.curvativeDistance;
                        ++totalMovements;
                    }
                }
            }
            curvativeAngleAvg /= totalMovements;
            curvativeDistanceAvg /= totalMovements;
            for (PointAndClickAction action : pointAndClicks) {
                if (action.isValid()) {
                    for (AngleBasedMetrics metrics : action.records) {
                        curvativeAngleDisp += Math.pow(curvativeAngleAvg
                                - metrics.curvativeAngle, 2);
                        curvativeDistanceDisp += Math.pow(curvativeDistanceAvg
                                - metrics.curvativeDistance, 2);
                    }
                }
            }
            curvativeAngleDisp = Math.sqrt(curvativeAngleDisp / totalMovements);
            curvativeDistanceDisp = Math.sqrt(curvativeDistanceDisp
                    / totalMovements);
            System.out.println("Averages:");
            System.out.println("curvativeAngle = " + curvativeAngleAvg + "+"
                    + curvativeAngleDisp);
            System.out.println("curvativeDistance = " + curvativeDistanceAvg
                    + "+" + curvativeDistanceDisp);
            AbmProbabilityDistributionFunction curvativeAngleDistr = new AbmProbabilityDistributionFunction(
                    120, 180, 20);
            AbmProbabilityDistributionFunction curvativeDistanceDistr = new AbmProbabilityDistributionFunction(
                    0, 0.35, 20);
            for (PointAndClickAction action : pointAndClicks) {
                if (action.isValid()) {
                    for (AngleBasedMetrics metrics : action.records) {
                        curvativeAngleDistr.newRecord(metrics.curvativeAngle
                                * 180 / Math.PI);
                        curvativeDistanceDistr
                                .newRecord(metrics.curvativeDistance);
                    }
                }
            }
            curvativeAngleDistr.calculateProbabilities();
            curvativeDistanceDistr.calculateProbabilities();
            // curvativeAngleDistr.plotDF();
            curvativeAngleDistr.plotCDF();
            // curvativeDistanceDistr.plotDF();
            curvativeDistanceDistr.plotCDF();
        }

        @Override
        public void setup(Context context) throws IOException,
                InterruptedException {
            super.setup(context);
            Configuration config = HBaseConfiguration.create(context
                    .getConfiguration());
            this.table = new HTable(config, "collectedclicks");
        }

        @Override
        public void cleanup(Context context) throws IOException,
                InterruptedException {
            super.cleanup(context);
            table.close();
        }

        private class EventRecordComparatorTime implements
                Comparator<EventRecord> {

            @Override
            public int compare(EventRecord e1, EventRecord e2) {
                return (int) (e1.time - e2.time);
            }

        }

        private class eventSubseqence {
            List<EventRecord> source;
            int start;
            int length;
            long timeAfter;
            int foundEqualSeqences;

            public eventSubseqence(List<EventRecord> source, int start,
                    int length) {
                super();
                this.source = source;
                this.length = length;
                this.start = start;
                if (!isValid()) {
                    throw new IllegalArgumentException("[" + start + "+"
                            + length + "]/[" + source.size() + "]");
                }
                if (start + length < source.size() - 1) {
                    timeAfter = source.get(start + length).time
                            - source.get(start + length - 1).time;
                } else {
                    timeAfter = -1;
                }
                foundEqualSeqences = -1;
            }

            public boolean isValid() {
                if (start < 0)
                    return false;
                if (length < 1)
                    return false;
                if (start + length > source.size())
                    return false;
                return true;
            }

            public boolean isEqual(eventSubseqence other, double dx, double dt) {
                if (this.length != other.length)
                    return false;
                if (this.source != other.source)
                    return false;
                if (this.isValid() != other.isValid())
                    return false;
                for (int i = 0; i < length; ++i) {
                    if (!source.get(start + i).almostEqual(
                            source.get(other.start + i), dx))
                        return false;
                    if (i < length - 1) {
                        // also, check timings
                        long dtThis = source.get(this.start + i + 1).time
                                - source.get(this.start + i).time;
                        long dtOther = source.get(other.start + i + 1).time
                                - source.get(other.start + i).time;
                        if (Math.abs(dtThis - dtOther) > dt)
                            return false;
                    }
                }
                return true;
            }

            public boolean checkForwardForSameSeqences(double dx, double dt) {
                foundEqualSeqences = 0;
                if (this.isSlowMoveSequence(dx)) {
                    // these can be produced by humans, and most likely won't be
                    // produced by bots
                    return false;
                }
                if (this.isStaleScrollingSeqence(dx)) {
                    // humans can easily do this. just scroll down with same
                    // speed
                    // and don't move the mouse
                    return false;
                }
                while (true) {
                    if (start + (2 + foundEqualSeqences) * length > source
                            .size())
                        break; // list end
                    eventSubseqence next = new eventSubseqence(source, start
                            + length * (1 + foundEqualSeqences), length);
                    if (this.isEqual(next, dx, dt)) {
                        ++foundEqualSeqences;
                    } else {
                        break;
                    }
                }
                if (detectedSlowMoveSequence(dx, length
                        * (foundEqualSeqences + 1))) {
                    // same, but on full list. mostly for length == 1 case.
                    return false;
                }
                return (foundEqualSeqences != 0);
            }

            public boolean checkPeriods(double dt) {
                if (foundEqualSeqences < 1)
                    return false;
                if (foundEqualSeqences > 1) {
                    long otherTime;
                    for (int i = 0; i < foundEqualSeqences - 1; ++i) {
                        if (start + length * (i + 2) < source.size() - 1) {
                            otherTime = source.get(start + length * (i + 2)).time
                                    - source.get(start + length * (i + 2) - 1).time;
                        } else {
                            otherTime = -1;
                        }
                        if (otherTime != -1) {
                            if (Math.abs(this.timeAfter - otherTime) > dt)
                                return false;
                        }
                    }
                }
                if (foundEqualSeqences == 1) {
                    return true; // this check can't be done, but another says
                                 // that he is a bot
                    // throw (new
                    // IllegalArgumentException("Checking periods with 1 successor"));
                }
                return true;
            }

            private boolean isSlowMoveSequence(double dx) {
                if (length <= 1)
                    return false;
                for (int i = 0; i < length; ++i) {
                    if (!source.get(start + i).type.equals("mousemove"))
                        return false;
                    if (i < length - 1) {
                        if (Math.abs(source.get(start + i + 1).x
                                - source.get(start + i).x) > dx)
                            return false;
                        if (Math.abs(source.get(start + i + 1).y
                                - source.get(start + i).y) > dx)
                            return false;
                    }
                }
                return true;
            }

            private boolean detectedSlowMoveSequence(double dx, int length) {
                for (int i = 0; i < length; ++i) {
                    if (!source.get(start + i).type.equals("mousemove"))
                        return false;
                    if (i < length - 1) {
                        if (Math.abs(source.get(start + i + 1).x
                                - source.get(start + i).x) > dx)
                            return false;
                        if (Math.abs(source.get(start + i + 1).y
                                - source.get(start + i).y) > dx)
                            return false;
                    }
                }
                return true;
            }

            private boolean isStaleScrollingSeqence(double dx) {
                int scrolls = 0;
                int deviations = 0;
                int baseX = -1;
                int baseY = -1;
                for (int i = 0; i < length; ++i) {
                    if (source.get(start + i).type.equals("wheel")) {
                        ++scrolls;
                        if (baseX == -1)
                            baseX = source.get(start + i).x;
                        if (baseY == -1)
                            baseY = source.get(start + i).y;
                        if (Math.abs(source.get(start + i).x - baseX)
                                + Math.abs(source.get(start + i).y - baseY) > dx) {
                            baseX = source.get(start + i).x;
                            baseY = source.get(start + i).y;
                            ++deviations;
                        }
                    }
                }
                return ((scrolls / length > 0.95) && (deviations / scrolls < 0.05));
            }
        }

        private double calculateMovementSpeed(List<EventRecord> source) {
            int mousemoveDelayMax = 200; // ms
            int mousemoveDelayMin = mousemoveDelayMax;
            int mousemoveDelayEntries = 0;
            long mousemoveDelayAvg = 0;
            // 1. find out mousemove delay
            long prevTime = -1;
            EventRecord event;
            long dTime;
            for (int i = 0; i < source.size(); ++i) {
                event = source.get(i);
                if (!event.type.equals("mousemove"))
                    continue;
                if (prevTime == -1) {
                    // start of mouse moving events sequence
                    prevTime = event.time;
                } else {
                    dTime = event.time - prevTime;
                    if (dTime < mousemoveDelayMax) {
                        if (mousemoveDelayAvg < Long.MAX_VALUE / 2) {
                            // collecting for future average. won't work nice
                            // with big data
                            // should be replaced with BigInts or some nice
                            // collector
                            ++mousemoveDelayEntries;
                            mousemoveDelayAvg += dTime;
                        }
                        if (dTime < mousemoveDelayMin) {
                            mousemoveDelayMin = (int) (dTime);
                        }
                        prevTime = event.time;
                    } else {
                        // too long. it's not the same movement.
                        prevTime = -1;
                    }
                }
            }
            mousemoveDelayAvg = Math.round(mousemoveDelayAvg
                    / mousemoveDelayEntries);
            // debugging
            System.out.println("MousemoveDelayMin " + mousemoveDelayMin + "ms"); // surprisingly
                                                                                 // low
            System.out.println("MousemoveDelayAvg " + mousemoveDelayAvg + "ms");
            // 2. getting movespeed
            // 0..[min..<-avg->..lim]....
            mousemoveDelayLim = (mousemoveDelayAvg + (mousemoveDelayAvg - mousemoveDelayMin));
            double dCoords;
            double movespeedAvg = 0;
            long movespeedEntries = 0;
            int prevX = 0;
            int prevY = 0;
            prevTime = -1;  //means "previous event is wrong"
            for (int i = 0; i < source.size(); ++i) {
                event = source.get(i);
                if (!event.type.equals("mousemove"))
                    continue;
                if (prevTime == -1) {
                    prevTime = event.time;
                    prevX = event.x;
                    prevY = event.y;
                } else {
                    dTime = event.time - prevTime;
                    if ((dTime < mousemoveDelayLim) && (dTime > 0)) {
                        dCoords = Math.sqrt(Math.pow(event.x - prevX, 2)
                                + Math.pow(event.y - prevY, 2));
                        if ((movespeedAvg < Double.MAX_VALUE - 10000)
                                && (movespeedEntries < Long.MAX_VALUE - 1)) {
                            // same problem with collecting
                            movespeedAvg += (dCoords / dTime);
                            ++movespeedEntries;
                        } else {
                            // no reason to move forward
                            break;
                        }
                        prevTime = event.time;
                        prevX = event.x;
                        prevY = event.y;
                    } else {
                        // too long. it's not the same movement.
                        prevTime = -1;
                    }
                }
            }
            return (movespeedAvg / movespeedEntries);
        }

        private int searchClickSelects(List<EventRecord> source, double minDx) {
            int lastMouseDownX = 0;
            int lastMouseDownY = 0;
            int clickAndSelects = 0;
            double totalDistanceSelected = 0;
            boolean mouseDown = false;
            double distance;
            EventRecord event;
            for (int i = 0; i < source.size(); ++i) {
                event = source.get(i);
                if (mouseDown) {
                    if (event.type.equals("mouseup")) {
                        mouseDown = false;
                        distance = Math.sqrt(Math.pow(event.x - lastMouseDownX,
                                2) + Math.pow(event.y - lastMouseDownY, 2));
                        if (distance > minDx) {
                            ++clickAndSelects;
                            totalDistanceSelected += distance;
                        }
                    }
                } else {
                    if (event.type.equals("mousedown")) {
                        mouseDown = true;
                        lastMouseDownX = event.x;
                        lastMouseDownY = event.y;
                    }
                }
            }
            System.out.println("Click-and-selects " + clickAndSelects
                    + ", totalDistanceSelected = " + totalDistanceSelected);
            return clickAndSelects;
        }

        private String getUserGroup(List<EventRecord> source, double ms,
                int scrolls, int selects) {
            String groups = "";
            if (scrolls > source.size() * 0.01) {
                if (!groups.isEmpty())
                    groups += ", ";
                groups += "Scrollers ("
                        + Math.round(100.0 * scrolls / source.size())
                        + "% of events are scrolling)";
            }
            if (ms > 1.5) {
                if (!groups.isEmpty())
                    groups += ", ";
                groups += "Fast movers (average mouse movement speed "
                        + Math.round(1000 * ms) + " px/sec)";
            }
            if (selects > source.size() * 0.002) {
                if (!groups.isEmpty())
                    groups += ", ";
                groups += "Click-and-selecters (" + selects
                        + " click-and-select actions total)";
            }
            if (groups.isEmpty()) {
                return "No specific group";
            } else {
                return groups;
            }
        }

        public List<PointAndClickAction> extractPointAndClickActions(
                List<EventRecord> source, String sid, long clickDt, long dt) {
            EventRecord clickEvent;
            List<PointAndClickAction> storage = new ArrayList<>();
            for (int i = 0; i < source.size(); ++i) {
                clickEvent = source.get(i);
                if (clickEvent.type.equals("click")) {
                    PointAndClickAction action = new PointAndClickAction(sid);
                    // actually, order is wrong, but this stuff is symmetric
                    action.addNextPoint(clickEvent.time, clickEvent.x,
                            clickEvent.y);
                    //going backwards from click to collect all related mousemove events
                    int j = i - 1;
                    long nextTime = clickEvent.time;
                    int nextX = clickEvent.x;
                    int nextY = clickEvent.y;
                    EventRecord event;
                    long requiredDt = clickDt;  //time between click and last move may be long
                    while (j > 0) {
                        event = source.get(j);
                        if (event.type.equals("mousemove")) {
                            //ignore all other types
                            if (nextTime - event.time < requiredDt) {
                                if ((event.x != nextX) || (event.y != nextY)) {
                                    action.addNextPoint(event.time, event.x,
                                            event.y);
                                    nextTime = event.time;
                                    nextX = event.x;
                                    nextY = event.y;
                                    requiredDt = dt;    //time between mousemoves should be short
                                }
                            } else {
                                // too long time.
                                break;
                            }
                        }
                        --j;
                    }
                    storage.add(action);
                }
            }
            return storage;
        }

    }

    @Override
    public int run(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.printf(
                    "Usage: %s [generic options] <tablename> <output>\n",
                    getClass().getSimpleName());
            ToolRunner.printGenericCommandUsage(System.err);
            return -1;
        }
        String tableName = args[0];
        Job job = new Job(getConf(), "Mouse events users analysis");
        job.setJarByClass(getClass());
        // FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));
        Scan scan = new Scan();
        scan.addFamily(Bytes.toBytes("id"));
        scan.addFamily(Bytes.toBytes("type"));
        scan.addFamily(Bytes.toBytes("data"));

        TableMapReduceUtil.initTableMapperJob(tableName, scan,
                MouseEventsUsersMapper.class, Text.class, Text.class, job);
        job.setReducerClass(MouseEventsUsersReducer.class);
        // job.setMapOutputKeyClass(Text.class);
        // job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        return job.waitForCompletion(true) ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new MouseEventsUsers(), args);
        System.exit(exitCode);
    }

}