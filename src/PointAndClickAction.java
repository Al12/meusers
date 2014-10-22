import java.util.ArrayList;
import java.util.List;


public class PointAndClickAction {
	String sid;
	long startTime;
	long endTime;
	List<AngleBasedMetrics> records;
	int loadedPoints;
	int x1,y1,x2,y2,x3,y3;
	public PointAndClickAction(String sid) {
		super();
		this.sid = sid;
		startTime = 0;
		endTime = 0;
		records = new ArrayList<>();
		loadedPoints = 0;
	}
	public void addNextPoint(long time, int x, int y) {
		if ((startTime == 0 ) || ( time < startTime ))	startTime = time;
		if ((endTime == 0 ) || ( time > endTime ))	endTime = time;
		++loadedPoints;
		if ( loadedPoints == 1 ) {
			x1 = x;
			y1 = y;
		}
		if ( loadedPoints == 2 ) {
			x2 = x;
			y2 = y;
		}
		if ( loadedPoints == 3 ) {
			x3 = x;
			y3 = y;
			//double direction = Math.atan2(y2 - y1, x2 - x1);						
			double AC = Math.sqrt(Math.pow(x3-x1,2)+Math.pow(y3-y1,2));
			double AB = Math.sqrt(Math.pow(x2-x1,2)+Math.pow(y2-y1,2));
			double BC = Math.sqrt(Math.pow(x3-x2,2)+Math.pow(y3-y2,2));
			//double curvativeAngle = Math.atan2(y3-y2, x3-x2) + Math.PI - direction;
			if ( ((x2-x1)*(x3-x2)+(y2-y1)*(y3-y2))/(AB*BC) > 1) {
				System.out.println("ab*bc > 1 with "+x1+":"+y1+","+x2+":"+y2+","+x3+":"+y3);
			}
			double curvativeAngle = Math.PI - Math.acos(Math.min(1.0, ((x2-x1)*(x3-x2)+(y2-y1)*(y3-y2))/(AB*BC)));
			//angle b/w AB and AC, smaller that direction
			double alpha = Math.acos(Math.min(1.0, ((x2-x1)*(x3-x1)+(y2-y1)*(y3-y1))/(AB*AC)));
			double curvativeDistance = (AB * Math.sin(alpha)) / AC;	//it was different in text, but like this on graphs
			//loadedPoints = 0;
			if ( AC > 0 ) {
				//ac = 0 is a bad case, let's just skip it
				records.add(new AngleBasedMetrics(curvativeAngle, curvativeDistance));
			}
			x1 = x2;
			y1 = y2;
			x2 = x3;
			y2 = y3;
			loadedPoints = 2;	//use them all 3 times instead of one!
		}
	}	
	public boolean isValid() {
		//only one or two movement seems to little
		return (records.size() > 2);
	}
	
}
