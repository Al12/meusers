
public class AngleBasedMetrics {
	double curvativeAngle;
	double curvativeDistance;
	public AngleBasedMetrics(double curvativeAngle, double curvativeDistance) {
		this.curvativeAngle = curvativeAngle;
		this.curvativeDistance = curvativeDistance;
	}
	@Override
	public String toString() {
		return "" + curvativeAngle
				+ ":" + curvativeDistance;
	}
	
}
