import java.io.IOException;
import java.util.Scanner;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.util.Bytes;

public class CollectedClicksCompilation {

	public static String userId;
	public static HTable tableIn;
	public static HTable tableOut;
	private static Scanner keyboard;
	public static String userMetrics;
	public static long fullSize;
	public static AbmProbabilityDistributionFunction userCurvativeAngleDistr;
	public static AbmProbabilityDistributionFunction userCurvativeDistanceDistr;
	
	public static String getMetadata(String name, Result r) {
		return Bytes.toString(r.getValue(Bytes.toBytes("metadata"), Bytes.toBytes(name)));
	}
	
	public static void workWithNextRecord(byte[] row) throws IOException {
		Get g = new Get(row);	
		Result r = tableIn.get(g);
		System.out.println("SID: "+Bytes.toString(row));
		String uid = getMetadata("uid", r);
		if ( (uid != null) && !uid.equals("undefined")) {
			System.out.println("Last UID: "+uid);			
		}
		System.out.println(getMetadata("info", r));
		System.out.println(getMetadata("timestart", r));
		System.out.println(getMetadata("timefinish", r));		
		int size = Bytes.toInt(r.getValue(Bytes.toBytes("metadata"), Bytes.toBytes("size")));
		int clicks = Bytes.toInt(r.getValue(Bytes.toBytes("metadata"), Bytes.toBytes("clicks")));
		System.out.println(size + " metrics records | "+clicks+" valid point-and-clicks");
		String recordMetricsString = Bytes.toString(r.getValue(Bytes.toBytes("data"), Bytes.toBytes("metrics")));		
		if (!userMetrics.isEmpty()) {
			//show distance between distributions
			String[] split = recordMetricsString.split(",");
			AngleBasedMetrics[] recordMetrics = new AngleBasedMetrics[split.length];
			for(int i=0;i<split.length;++i) {
				recordMetrics[i] = AngleBasedMetrics.fromString(split[i]);
			}
			AbmProbabilityDistributionFunction curvativeAngleDistr = new AbmProbabilityDistributionFunction(120, 180, 20);
			AbmProbabilityDistributionFunction curvativeDistanceDistr = new AbmProbabilityDistributionFunction(0, 0.35, 20);			
			for ( AngleBasedMetrics metrics : recordMetrics ) {
				curvativeAngleDistr.newRecord(metrics.curvativeAngle * 180 / Math.PI);						
				curvativeDistanceDistr.newRecord(metrics.curvativeDistance);
			}
			curvativeAngleDistr.calculateProbabilities();
			curvativeDistanceDistr.calculateProbabilities();
			curvativeAngleDistr.plotCDF();
			curvativeDistanceDistr.plotCDF();
			System.out.println("Distance to already recorded "+userId+"'s metrics: "+curvativeAngleDistr.distanceTo(userCurvativeAngleDistr) + "|"+curvativeDistanceDistr.distanceTo(userCurvativeDistanceDistr));
		}
		System.out.println("Is it user "+userId + "'s record? (Y/N)");
		String answer = keyboard.next();
		if ( answer.toLowerCase().equals("y")) {
			if ( !userMetrics.isEmpty() ) {
				userMetrics = userMetrics.concat(",");
			}
			userMetrics = userMetrics.concat(recordMetricsString);
						
			String[] split = recordMetricsString.split(",");
			AngleBasedMetrics metrics;
			for(int i=0;i<split.length;++i) {	
				metrics = AngleBasedMetrics.fromString(split[i]);
				userCurvativeAngleDistr.newRecord(metrics.curvativeAngle * 180 / Math.PI);
				userCurvativeDistanceDistr.newRecord(metrics.curvativeDistance);
			}
			userCurvativeAngleDistr.calculateProbabilities();
			userCurvativeDistanceDistr.calculateProbabilities();
						
			fullSize += size;
			
			//storing the userId for future launches of this program
			Put p = new Put(row);
			p.add(Bytes.toBytes("metadata"), Bytes.toBytes("uid"), Bytes.toBytes(userId));
			tableIn.put(p);
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
			tableIn = new HTable(conf, "collectedclicks");
			Scan scan = new Scan();
			scan.setFilter(new FirstKeyOnlyFilter());
			ResultScanner scanner = tableIn.getScanner(scan);			
			userMetrics = "";
			userCurvativeAngleDistr = new AbmProbabilityDistributionFunction(120, 180, 20);
			userCurvativeDistanceDistr = new AbmProbabilityDistributionFunction(0, 0.35, 20);
			fullSize = 0;
			for (Result rr : scanner) {
			  byte[] key = rr.getRow();
			  workWithNextRecord(key);			  
			}
			tableIn.close();
			if ( userMetrics.length() > 0 ) {
				System.out.println("Writing "+fullSize+" collected metrics records to hbase");
				conf = HBaseConfiguration.create();
				tableOut = new HTable(conf, "userclicks");
				byte[] row = Bytes.toBytes(userId);
				Put p = new Put(row);
				p.add(Bytes.toBytes("data"), Bytes.toBytes("metrics"), Bytes.toBytes(userMetrics));
				tableOut.put(p);
				tableOut.close();
			}
		} catch (IOException e) {
			System.err.println("Failed to scan table");
			e.printStackTrace();
		}
		keyboard.close();		
	}

}
