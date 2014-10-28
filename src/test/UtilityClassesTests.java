package test;

import static org.junit.Assert.*;

import main.AbmProbabilityDistributionFunction;
import main.AngleBasedMetrics;
import main.EventRecord;

import org.apache.hadoop.io.Text;
import org.junit.Test;

public class UtilityClassesTests {

    @Test
    public void eventRecordToFromText() {
        EventRecord event = new EventRecord("test", 10, 0, 1);
        Text text = event.toText();
        assertEquals(event.toString(), text.toString());

        EventRecord sameEvent = new EventRecord(text);
        assertEquals(event.toString(), sameEvent.toString());
        assertTrue(event.almostEqual(sameEvent, 0.000001));
    }

    @Test
    public void eventRecordDifferent() {
        EventRecord event = new EventRecord("test", 10, 0, 1);

        EventRecord differentEvent = new EventRecord("click", 10, 0, 1);
        assertFalse(event.almostEqual(differentEvent, 0.000001));

        differentEvent = new EventRecord("test", 15, 0, 1);
        // time isn't checked
        assertTrue(event.almostEqual(differentEvent, 0.000001));

        differentEvent = new EventRecord("test", 10, 1, 1);
        assertFalse(event.almostEqual(differentEvent, 0.000001));

        differentEvent = new EventRecord("test", 10, 1, 2);
        // too small difference
        assertTrue(event.almostEqual(differentEvent, 1.5));
    }

    @Test
    public void angleBasedMetrics() {
        double curvativeAngle = Math.PI;
        double curvativeDistance = 0.5;
        AngleBasedMetrics metrics = new AngleBasedMetrics(curvativeAngle,
                curvativeDistance);
        AngleBasedMetrics sameMetrics = new AngleBasedMetrics(Math.PI, 0.5);
        assertEquals(metrics, sameMetrics);
        // compareTo
        assertEquals(0, metrics.compareTo(sameMetrics));
        assertEquals(+1,
                metrics.compareTo(new AngleBasedMetrics(Math.PI / 2, 0.5)));
        assertEquals(-1,
                metrics.compareTo(new AngleBasedMetrics(3 * Math.PI / 2, 0.5)));
        // toString and back
        assertEquals(metrics, AngleBasedMetrics.fromString(metrics.toString()));
    }

    @Test
    public void abmPDF() {
        double intervalStart = 0;
        double intervalEnd = 100;
        int bins = 100;
        AbmProbabilityDistributionFunction PDF1 = new AbmProbabilityDistributionFunction(
                intervalStart, intervalEnd, bins);
        double[] records = new double[1000];
        for (int i = 0; i < records.length; ++i) {
            // uniform distribution
            records[i] = intervalStart + (1.0 * i / records.length)
                    * (intervalEnd - intervalStart);
        }
        for (int i = 0; i < records.length; ++i) {
            PDF1.newRecord(records[i]);
        }
        PDF1.calculateProbabilities();
        double probsSum = 0;
        for (int i = 0; i < bins; ++i) {
            // since the distribution is uniform, all n probabilities are same
            // and ~equal to 1/n
            assertTrue(Math.abs((1.0 / bins) - PDF1.getProbabilities()[i]) < 0.01);
            probsSum += PDF1.getProbabilities()[i];
        }
        // sum of all probabilities is always 1
        assertTrue(Math.abs(1.0 - probsSum) < 0.001);

        AbmProbabilityDistributionFunction PDF2 = new AbmProbabilityDistributionFunction(
                intervalStart, intervalEnd, bins);
        for (int i = 0; i < records.length; ++i) {
            PDF2.newRecord(records[(i + 100) % records.length]);
        }
        PDF2.calculateProbabilities();
        // distance between same distributions
        assertTrue(PDF1.distanceTo(PDF2) < 0.001);
        
        AbmProbabilityDistributionFunction PDF3 = new AbmProbabilityDistributionFunction(
                intervalStart, intervalEnd, bins);
        // ___|___
        //min   max
        for (int i = 0; i < records.length; ++i) {
            PDF3.newRecord(records[(int)records.length/2]);
        }
        PDF3.calculateProbabilities();
        // distance between [very] different distributions
        assertTrue(PDF1.distanceTo(PDF3) > 1.0);
    }

}
