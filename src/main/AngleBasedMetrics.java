package main;

public class AngleBasedMetrics implements Comparable<Object> {
    public double curvativeAngle;
    public double curvativeDistance;
	public AngleBasedMetrics(double curvativeAngle, double curvativeDistance) {
		this.curvativeAngle = curvativeAngle;
		this.curvativeDistance = curvativeDistance;
	}
	@Override
	public String toString() {
		return "" + curvativeAngle
				+ ":" + curvativeDistance;
	}
	public static AngleBasedMetrics fromString(String str) {
		String[] split = str.split(":");
		return new AngleBasedMetrics(Double.parseDouble(split[0]), Double.parseDouble(split[1]));
	}
	@Override
	public int compareTo(Object arg0) {
		if ( arg0 instanceof AngleBasedMetrics) {
			if (this.curvativeAngle > ((AngleBasedMetrics)arg0).curvativeAngle) {
				return +1;
			} else if (this.curvativeAngle < ((AngleBasedMetrics)arg0).curvativeAngle) {
				return -1;
			} else {
				return 0;
			}
		} else {
			return -1;
		}
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		long temp;
		temp = Double.doubleToLongBits(curvativeAngle);
		result = prime * result + (int) (temp ^ (temp >>> 32));
		temp = Double.doubleToLongBits(curvativeDistance);
		result = prime * result + (int) (temp ^ (temp >>> 32));
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		AngleBasedMetrics other = (AngleBasedMetrics) obj;
		if (Double.doubleToLongBits(curvativeAngle) != Double
				.doubleToLongBits(other.curvativeAngle))
			return false;
		if (Double.doubleToLongBits(curvativeDistance) != Double
				.doubleToLongBits(other.curvativeDistance))
			return false;
		return true;
	}
}
