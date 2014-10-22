
public class AbmProbabilityDistributionFunction {
	double intervalStart;
	double intervalEnd;
	int bins;
	double[] probabilities;
	int[] cache;
	long records;
	public AbmProbabilityDistributionFunction(double intervalStart,
			double intervalEnd, int bins) {
		this.intervalStart = intervalStart;
		this.intervalEnd = intervalEnd;
		this.bins = bins;
		probabilities = new double[bins];
		cache = new int[bins];
		for(int i=0;i<bins;++i) {
			cache[i] = 0;
		}
		records = 0;
	}
	public boolean newRecord(double record) {
		if ( record < intervalStart )	return false;
		if ( record > intervalEnd )	return false;
		int i = (int)Math.min(bins - 1, Math.round(Math.floor(bins * (record - intervalStart) / (intervalEnd - intervalStart))));
		++cache[i];
		++records;
		return true;
	}
	public void calculateProbabilities() {
		for(int i=0;i<bins;++i) {
			probabilities[i] = 1.0 * cache[i] / records;
		}
	}
	private void plot(double[] source, double dy) {
		for(double h=1.0;h>-dy/2;h-=dy) {
			if ( h>=1.0) {
				System.out.print("1.00\t");
			} else {
				if ( Math.round(h*100) < 10) {
					System.out.print("0.0"+Math.round(h*100)+"\t");
				} else {
					System.out.print("0."+Math.round(h*100)+"\t");
				}
			}
			for(int i=0;i<bins;++i) {
				if ((source[i] >= h) && (source[i] < h + dy)) {
					System.out.print("*");
				} else {
					System.out.print(" ");
				}
			}
			System.out.println();
		}
		System.out.print("    \t"+intervalStart);
		for(int i=0;i<bins;++i) {
			if (( i > String.valueOf(intervalStart).length()) && 
					( i<bins - String.valueOf(intervalEnd).length())) {
				System.out.print(".");
			}
		}
		System.out.println(intervalEnd);
	}
	public void plotDF() {
		System.out.println("Distribution");
		plot(probabilities, Math.min(Math.max(1.0/bins, 0.02), 0.1));
	}
	public void plotCDF() {
		System.out.println("Cumulative distribution");
		double[] sum = new double[bins];
		for(int i=0;i<bins;++i) {
			if ( i > 0 ) {
				sum[i] = sum[i-1] + probabilities[i]; 
			} else {
				sum[i] = probabilities[i];
			}
		}
		plot(sum, 0.1);
	}
	public double distanceTo(AbmProbabilityDistributionFunction other) {
		if (( this.bins != other.bins ) || 
			(this.intervalStart != other.intervalStart) ||
			(this.intervalEnd != other.intervalEnd)) {
			throw new IllegalArgumentException("Getting distance between different distributions "+this.toString()+" and "+other.toString());
		}
		double dist = 0;
		for(int i=0;i<bins;++i) {
			dist += Math.abs(this.probabilities[i] - other.probabilities[i]);
		}
		return dist;
	}
	@Override
	public String toString() {
		return "AbmProbabilityDistributionFunction [intervalStart="
				+ intervalStart + ", intervalEnd=" + intervalEnd + ", bins="
				+ bins + "]";
	}
	
}
