package main;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;

import jnisvmlight.KernelParam;
import jnisvmlight.LabeledFeatureVector;
import jnisvmlight.SVMLightInterface;
import jnisvmlight.SVMLightModel;
import jnisvmlight.TrainingParameters;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
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

public class SVMTeacher extends Configured implements Tool {

    /*
     * static { try { NativeUtils.loadLibraryFromJar("/svmlight-64.dll"); //
     * during runtime. .DLL within .JAR } catch (IOException e1) { throw new
     * RuntimeException(e1); } }
     */

    public static final int maxMetricsPerId = 20000;
    public static final int maxMetricsPositive = 100000;
    public static final int maxMetricsNegative = 200000;

    static class SVMTeacherMapper extends TableMapper<Text, Text> {

        @Override
        public void map(ImmutableBytesWritable row, Result values,
                Context context) throws IOException, InterruptedException {
            System.out.println("map: userId: " + Bytes.toString(row.get()));
            if (!Bytes.toString(row.get()).equals(
                    context.getConfiguration().get("userId"))) {
                byte[] data = values.getValue(Bytes.toBytes("data"),
                        Bytes.toBytes("metrics"));
                String dataFull = Bytes.toString(data);
                String[] dataSplitted = dataFull.split(",");
                if (dataSplitted.length <= maxMetricsPerId) {
                    //just load them all
                    context.write(new Text(""), new Text(data));
                    System.out.println("Loaded " + dataSplitted.length
                            + " metrics records");
                } else {
                    //too many for this record. more data is nice, will make program slower
                    //while less data is enough for one record
                    StringBuilder dataTrimmed = new StringBuilder(
                            maxMetricsPerId * ((17 + 2) * 2 + 1));
                    for (int i = 0; i < maxMetricsPerId; ++i) {
                        dataTrimmed.append(dataSplitted[i]);
                        if (i < maxMetricsPerId - 1) {
                            dataTrimmed.append(",");
                        }
                    }
                    context.write(new Text(""),
                            new Text(dataTrimmed.toString()));
                    System.out.println("Loaded " + maxMetricsPerId
                            + " metrics records");
                }
            }
        }
    }

    static class SVMTeacherReducer extends Reducer<Text, Text, Text, Text> {
        private AngleBasedMetrics[] negativeMetricsSet;
        private int negativeMetricsSetSize;

        @Override
        public void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {
            int negativeExampleSources = 0;
            for (Text data : values) {
                ++negativeExampleSources;
                String[] dataSplit = data.toString().split(",");
                if (negativeMetricsSetSize + dataSplit.length < maxMetricsNegative) {
                    // load all
                    for (String str : dataSplit) {
                        negativeMetricsSet[negativeMetricsSetSize++] = AngleBasedMetrics
                                .fromString(str);
                        if (negativeMetricsSetSize % 100 == 0) {
                            System.out
                                    .println("Sample "
                                            + negativeMetricsSetSize
                                            + ": "
                                            + negativeMetricsSet[negativeMetricsSetSize - 1]
                                                    .toString());
                        }
                    }
                } else {
                    // TODO: check if this stuff works
                    // load equal part, filling remaining positions replacing
                    // old stuff
                    int loaded = 0;
                    int toLoad = Math.min(dataSplit.length, maxMetricsNegative
                            / negativeExampleSources);
                    while (negativeMetricsSetSize < maxMetricsNegative) {
                        negativeMetricsSet[negativeMetricsSetSize++] = AngleBasedMetrics
                                .fromString(dataSplit[loaded]);
                        ++loaded;
                    }
                    int randomPos = (int) Math.min(
                            Math.floor(Math.random() * maxMetricsNegative),
                            maxMetricsNegative - 1);
                    int randomOffset = Math.round(maxMetricsNegative / toLoad);
                    while (loaded < toLoad) {
                        negativeMetricsSet[randomPos] = AngleBasedMetrics
                                .fromString(dataSplit[loaded]);
                        ++loaded;
                        randomPos = (randomPos + randomOffset)
                                % maxMetricsNegative;
                    }
                }
            }
            StringBuilder negativeMetrics = new StringBuilder(
                    negativeMetricsSetSize * ((17 + 2) * 2 + 1));
            for (int i = 0; i < negativeMetricsSetSize; ++i) {
                negativeMetrics.append(negativeMetricsSet[i].toString());
                if (i < negativeMetricsSetSize - 1) {
                    negativeMetrics.append(",");
                }
            }
            context.write(new Text("negativeMetricsSet"), new Text(
                    negativeMetrics.toString()));
            System.out.println("Negative metrics examples set ready");
        }

        @Override
        public void setup(Context context) throws IOException,
                InterruptedException {
            super.setup(context);
            negativeMetricsSet = new AngleBasedMetrics[maxMetricsNegative];
            negativeMetricsSetSize = 0;
        }

    }

    private static AngleBasedMetrics[] positiveMetricsSet;
    private static int positiveMetricsSetSize;
    private static AngleBasedMetrics[] negativeMetricsSet;
    private static int negativeMetricsSetSize;

    public static LabeledFeatureVector makeFeatureVectorFromBlockDistance(
            int sign, Block block) {
        int nDims = Block.blockSize;
        int[] dims = new int[nDims];
        double[] values = new double[nDims];
        // Fill the vectors
        for (int j = 0; j < nDims; ++j) {
            dims[j] = j + 1;
            values[j] = block.records[j].curvativeDistance;
        }

        // Store dimension/value pairs in new LabeledFeatureVector object
        return new LabeledFeatureVector(sign, dims, values);
    }
    
    public static String modelFileName(String uid) {
        return uid+"_model.dat";
    }

    public static void trainSVM(String uid) {
        // make the training set
        // TODO: only curvativeDistance is used now, add angles and combine 2 SVM's results
        // using only curvative distance now, angle will be done later and with
        // another svm
        // The trainer interface with the native communication to the SVM-light
        // shared libraries
        SVMLightInterface trainer = new SVMLightInterface();
        int trainDataHalfSize = 100; // blocks. should be 300, but i don't have
                                     // that many data now
        // The training data
        LabeledFeatureVector[] traindata = new LabeledFeatureVector[2 * trainDataHalfSize];

        // Sort all feature vectors in ascending order of feature dimensions
        // before training the model
        SVMLightInterface.SORT_INPUT_VECTORS = true;

        // AbmPDF debugging
        AbmProbabilityDistributionFunction positive = new AbmProbabilityDistributionFunction(
                0, 0.35, 20);
        AbmProbabilityDistributionFunction negative = new AbmProbabilityDistributionFunction(
                0, 0.35, 20);

        // Constructing the training set
        System.out.print("\nPREPARING TRAINING DATA ..");
        // Positive half
        for (int i = 0; i < trainDataHalfSize; i++) {
            Block block = constructBlock(positiveMetricsSet,
                    positiveMetricsSetSize);

            // AbmPDF debugging
            for (int j = 0; j < Block.blockSize; ++j) {
                positive.newRecord(block.records[j].curvativeDistance);
            }

            traindata[i] = makeFeatureVectorFromBlockDistance(+1, block);

            // Use cosine similarities (LinearKernel with L2-normalized input
            // vectors)
            traindata[i].normalizeL2();
        }
        System.out.print(".");
        // Negative half
        for (int i = 0; i < trainDataHalfSize; i++) {
            Block block = constructBlock(negativeMetricsSet,
                    negativeMetricsSetSize);

            // AbmPDF debugging
            for (int j = 0; j < Block.blockSize; ++j) {
                negative.newRecord(block.records[j].curvativeDistance);
            }

            traindata[trainDataHalfSize + i] = makeFeatureVectorFromBlockDistance(
                    -1, block);

            // Use cosine similarities (LinearKernel with L2-normalized input
            // vectors)
            traindata[trainDataHalfSize + i].normalizeL2();
        }
        System.out.println(" DONE.");

        // Initialize a new TrainingParamteres object with the default SVM-light
        // values
        TrainingParameters tp = new TrainingParameters();

        tp.getKernelParameters().kernel_type = KernelParam.RBF;

        // Switch on some debugging output
        // tp.getLearningParameters().verbosity = 1;

        System.out.print("\nTRAINING SVM-light MODEL ..");
        SVMLightModel model = trainer.trainModel(traindata, tp);
        System.out.println(" DONE.");

        // Use this to store a model to a file or read a model from a URL.
        model.writeModelToFile(modelFileName(uid));
        // model = SVMLightModel.readSVMLightModelFromURL(new
        // java.io.File("jni_model.dat").toURL());

        // AbmPDF debugging
        positive.calculateProbabilities();
        negative.calculateProbabilities();
        System.out.println("Distance between PDF's: "
                + positive.distanceTo(negative));
        double avg;
        int n = 20;
        avg = 0;
        for (int i = 0; i < n; ++i) {
            AbmProbabilityDistributionFunction blockPDF = new AbmProbabilityDistributionFunction(
                    0, 0.35, 20);
            Block block = constructBlock(positiveMetricsSet,
                    positiveMetricsSetSize);
            for (int j = 0; j < Block.blockSize; ++j) {
                blockPDF.newRecord(block.records[j].curvativeDistance);
            }
            blockPDF.calculateProbabilities();
            avg += positive.distanceTo(blockPDF);
        }
        System.out
                .println("Distance between random positive block and positive PDF: "
                        + avg / n);
        avg = 0;
        for (int i = 0; i < n; ++i) {
            AbmProbabilityDistributionFunction blockPDF = new AbmProbabilityDistributionFunction(
                    0, 0.35, 20);
            Block block = constructBlock(negativeMetricsSet,
                    negativeMetricsSetSize);
            for (int j = 0; j < Block.blockSize; ++j) {
                blockPDF.newRecord(block.records[j].curvativeDistance);
            }
            blockPDF.calculateProbabilities();
            avg += positive.distanceTo(blockPDF);
        }
        System.out
                .println("Distance between random negative block and positive PDF: "
                        + avg / n);
        avg = 0;
        for (int i = 0; i < n; ++i) {
            AbmProbabilityDistributionFunction blockPDF = new AbmProbabilityDistributionFunction(
                    0, 0.35, 20);
            Block block = constructBlock(negativeMetricsSet,
                    negativeMetricsSetSize);
            for (int j = 0; j < Block.blockSize; ++j) {
                blockPDF.newRecord(block.records[j].curvativeDistance);
            }
            blockPDF.calculateProbabilities();
            avg += negative.distanceTo(blockPDF);

        }
        System.out
                .println("Distance between random negative block and negative PDF: "
                        + avg / n);
        avg = 0;
        for (int i = 0; i < n; ++i) {
            AbmProbabilityDistributionFunction blockPDF = new AbmProbabilityDistributionFunction(
                    0, 0.35, 20);
            Block block = constructBlock(positiveMetricsSet,
                    positiveMetricsSetSize);
            for (int j = 0; j < Block.blockSize; ++j) {
                blockPDF.newRecord(block.records[j].curvativeDistance);
            }
            blockPDF.calculateProbabilities();
            avg += negative.distanceTo(blockPDF);
        }
        System.out
                .println("Distance between random positive block and positive PDF: "
                        + avg / n);

        // Simple validating
        System.out.println("\nVALIDATING SVM-light MODEL by training data..");
        int precision = 0;
        int frr = 0;
        int far = 0;
        double treshhold = 0;
        for (int i = 0; i < 1000; i++) {
            // Classify a test vector using the Java object
            int rnd = (int) Math.floor(Math.random() * trainDataHalfSize * 2);
            LabeledFeatureVector data = traindata[rnd];
            double d = model.classify(data);
            if ((data.getLabel() < 0 && d < treshhold)
                    || (data.getLabel() > 0 && d > treshhold)) {
                precision++;
            }
            if (data.getLabel() < 0 && d > treshhold) {
                ++far;
            }
            if (data.getLabel() > 0 && d < treshhold) {
                ++frr;
            }
            if (i % 10 == 0) {
                System.out.print(".");
            }
        }
        System.out.println(" DONE.");
        System.out.println("\n" + ((double) precision / 1000) + " PRECISION.");
        System.out.println("FAR: " + ((double) far / 500) + ", FRR: "
                + ((double) frr / 500));

        // Use the classifier on the randomly created feature vectors
        System.out.println("\nVALIDATING SVM-light MODEL in Java..");
        precision = 0;
        frr = 0;
        far = 0;
        for (int i = 0; i < 1000; i++) {

            // Classify a test vector using the Java object
            int sign;
            Block block;
            if (i >= 500) {
                block = constructBlock(positiveMetricsSet,
                        positiveMetricsSetSize);
                sign = +1;
            } else {
                block = constructBlock(negativeMetricsSet,
                        negativeMetricsSetSize);
                sign = -1;
            }

            LabeledFeatureVector data = makeFeatureVectorFromBlockDistance(
                    sign, block);
            data.normalizeL2();
            double d = model.classify(data);
            if ((data.getLabel() < 0 && d < treshhold)
                    || (data.getLabel() > 0 && d > treshhold)) {
                precision++;
            }
            if (data.getLabel() < 0 && d > treshhold) {
                ++far;
            }
            if (data.getLabel() > 0 && d < treshhold) {
                ++frr;
            }
            if (i % 10 == 0) {
                System.out.print(".");
            }
        }
        System.out.println(" DONE.");
        System.out.println("\n" + ((double) precision / 1000) + " PRECISION.");
        System.out.println("FAR: " + ((double) far / 500) + ", FRR: "
                + ((double) frr / 500));

        System.out.println("\nVALIDATING SVM-light MODEL in Native Mode..");
        precision = 0;
        frr = 0;
        far = 0;
        for (int i = 0; i < 1000; i++) {
            // Classify a test vector using the Java object
            int sign;
            Block block;
            if (i >= 500) {
                block = constructBlock(positiveMetricsSet,
                        positiveMetricsSetSize);
                sign = +1;
            } else {
                block = constructBlock(negativeMetricsSet,
                        negativeMetricsSetSize);
                sign = -1;
            }

            LabeledFeatureVector data = makeFeatureVectorFromBlockDistance(
                    sign, block);
            data.normalizeL2();
            // Classify a test vector using the Native Interface
            double d = trainer.classifyNative(data);
            if ((data.getLabel() < 0 && d < treshhold)
                    || (data.getLabel() > 0 && d > treshhold)) {
                precision++;
            }
            if (data.getLabel() < 0 && d > treshhold) {
                ++far;
            }
            if (data.getLabel() > 0 && d < treshhold) {
                ++frr;
            }
            if (i % 10 == 0) {
                System.out.print(".");
            }
        }
        System.out.println(" DONE.");
        System.out.println("\n" + ((double) precision / 1000) + " PRECISION.");
        System.out.println("FAR: " + ((double) far / 500) + ", FRR: "
                + ((double) frr / 500));
    }

    public static Block constructBlock(AngleBasedMetrics[] source,
            int sourceSize) {
        AngleBasedMetrics[] block = new AngleBasedMetrics[Block.blockSize];
        int index;
        for (int i = 0; i < Block.blockSize; ++i) {
            index = (int) Math.min(Math.floor(Math.random() * sourceSize),
                    sourceSize - 1);
            block[i] = source[index];
        }
        return new Block(block, true);
    }

    @Override
    public int run(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.printf(
                    "Usage: %s [generic options] <userId> <output>\n",
                    getClass().getSimpleName());
            ToolRunner.printGenericCommandUsage(System.err);
            return -1;
        }
        String userId = args[0];
        Job job = new Job(getConf(), "SVM teacher");
        job.setJarByClass(getClass());
        job.getConfiguration().set("userId", userId);
        // FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));
        Scan scan = new Scan();
        scan.addFamily(Bytes.toBytes("data"));

        TableMapReduceUtil.initTableMapperJob("userclicks", scan,
                SVMTeacherMapper.class, Text.class, Text.class, job);
        job.setNumReduceTasks(1);
        job.setReducerClass(SVMTeacherReducer.class);
        // job.setMapOutputKeyClass(Text.class);
        // job.setMapOutputValueClass(Text.class);
        // job.setOutputKeyClass(Text.class);
        // job.setOutputValueClass(Text.class);
        if (!job.waitForCompletion(true))
            return 1;

        // here starts main code, which isn't related to MapReduce at all
        // init
        positiveMetricsSet = new AngleBasedMetrics[maxMetricsPositive];
        negativeMetricsSet = new AngleBasedMetrics[maxMetricsNegative];
        positiveMetricsSetSize = 0;
        negativeMetricsSetSize = 0;
        // get negative examples
        String uri = args[1] + "/part-r-00000";
        Configuration conf = new Configuration();
        FileSystem fs = FileSystem.get(URI.create(uri), conf);
        Path path = new Path(uri);
        BufferedReader br = new BufferedReader(new InputStreamReader(
                fs.open(path)));
        String all = br.readLine();
        if (all == null) {
            return 3;
        }
        String[] splitted = all.split(",");
        // fix first entry - remove text
        splitted[0] = splitted[0].split("\t")[1];
        for (int i = 0; i < Math.min(maxMetricsNegative, splitted.length); ++i) {
            negativeMetricsSet[i] = AngleBasedMetrics.fromString(splitted[i]);
        }
        negativeMetricsSetSize = Math.min(maxMetricsNegative, splitted.length);
        System.out.println("Negative metrics examples set loaded, size = "
                + negativeMetricsSetSize);
        // get positive examples
        conf = HBaseConfiguration.create();
        HTable table = new HTable(conf, "userclicks");
        byte[] row = Bytes.toBytes(userId);
        Get g = new Get(row);
        Result r = table.get(g);
        String[] dataSplit = Bytes.toString(
                r.getValue(Bytes.toBytes("data"), Bytes.toBytes("metrics")))
                .split(",");
        for (int i = 0; i < Math.min(maxMetricsPositive, dataSplit.length); ++i) {
            positiveMetricsSet[i] = AngleBasedMetrics.fromString(dataSplit[i]);
        }
        positiveMetricsSetSize = Math.min(maxMetricsPositive, dataSplit.length);
        table.close();
        System.out.println("Positive metrics examples set loaded, size = "
                + positiveMetricsSetSize);
        // TODO: change limit back to 20 from 2
        if (Math.min(positiveMetricsSetSize, negativeMetricsSetSize) < 2 * Block.blockSize) {
            System.err
                    .println("Not enough click metrics records collected for training ("
                            + positiveMetricsSetSize
                            + " positive examples, "
                            + negativeMetricsSetSize + " negative examples)");
            return 2;
        }
        trainSVM(userId);
        return 0;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        exitCode = ToolRunner.run(new SVMTeacher(), args);

        System.exit(exitCode);
    }

}
