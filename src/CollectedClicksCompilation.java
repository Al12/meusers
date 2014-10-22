import java.io.IOException;
import java.util.Scanner;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.util.Bytes;

public class CollectedClicksCompilation {

	public static String userId;
	public static HTable table;
	private static Scanner keyboard;
	
	public static String getMetadata(String name, Result r) {
		return Bytes.toString(r.getValue(Bytes.toBytes("metadata"), Bytes.toBytes(name)));
	}
	
	public static void workWithNextRecord(byte[] row) throws IOException {
		Get g = new Get(row);	
		Result r = table.get(g);
		System.out.println("SID: "+Bytes.toString(row));
		System.out.println(getMetadata("info", r));
		System.out.println(getMetadata("timestart", r));
		System.out.println(getMetadata("timefinish", r));
		System.out.println(Bytes.toInt(r.getValue(Bytes.toBytes("metadata"), Bytes.toBytes("size")))+ " metrics records");
		System.out.println("Is it user "+userId + "'s record? (Y/N)");
		String answer = keyboard.next();
		if ( answer.toLowerCase().equals("y")) {
			//somehow add this data to new storage, marked with userId instead of sid
		}
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		System.out.println("Enter the id of user, whose clicks will be collected:");
		keyboard = new Scanner(System.in);
		userId = keyboard.next();		
		Configuration conf = HBaseConfiguration.create();		
		try {
			table = new HTable(conf, "collectedclicks");
			Scan scan = new Scan();
			scan.setFilter(new FirstKeyOnlyFilter());
			ResultScanner scanner = table.getScanner(scan);
			for (Result rr : scanner) {
			  byte[] key = rr.getRow();
			  workWithNextRecord(key);			  
			}
		} catch (IOException e) {
			System.err.println("Failed to scan table");
			e.printStackTrace();
		}
		keyboard.close();
	}

}
