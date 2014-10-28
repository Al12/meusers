package main;
import org.apache.hadoop.io.Text;


public class EventRecord {
    String type;
    long time;
    int x;
    int y;

    public EventRecord(String type, long time, int x, int y) {
        super();
        this.type = type;
        this.time = time;
        this.x = x;
        this.y = y;
    }

    public EventRecord(Text text) {
        super();
        String[] data = text.toString().split(",");
        this.type = data[0];
        this.time = Long.parseLong(data[1]);
        this.x = Integer.parseInt(data[2]);
        this.y = Integer.parseInt(data[3]);
    }

    @Override
    public String toString() {
        return type + "," + time + "," + x + "," + y;
    }

    public Text toText() {
        return new Text(this.toString());
    }

    public boolean almostEqual(EventRecord other, double e) {
        if (!this.type.equals(other.type))
            return false;
        if (Math.abs(this.x - other.x) > e)
            return false;
        if (Math.abs(this.y - other.y) > e)
            return false;
        return true;
    }
}