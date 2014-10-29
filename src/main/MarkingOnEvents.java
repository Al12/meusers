package main;
import java.util.LinkedList;
import java.util.List;



// used to count how many actions are suspicious (bot-like)
// slow bicycle because of list inside, but should be enough for this task
public class MarkingOnEvents {
    class Range {
        int start;
        int end;

        public Range(int start, int end) {
            this.start = start;
            this.end = end;
            if (end < start) {
                throw new IllegalArgumentException(this.toString());
            }
        }

        public boolean overlaps(Range other) {
            if (this.start <= other.start) {
                // this's on left
                return (this.end >= other.start);
            } else {
                // this's on right
                return (this.start <= other.end);
            }
        }
        
        public boolean canBeMerged(Range other) {
            if (this.start <= other.start) {
                // this's on left
                return (this.end >= (other.start - 1));
            } else {
                // this's on right
                return (this.start <= (other.end + 1));
            }
        }

        public Range mergeWith(Range other) {
            if (!canBeMerged(other)) {
                throw new IllegalArgumentException(this.toString()
                        + " and " + other.toString() + " don't overlap");
            }
            if (other.start < this.start)
                this.start = other.start;
            if (other.end > this.end)
                this.end = other.end;
            return this;
        }

        @Override
        public String toString() {
            return "Range [" + start + "," + end + "]";
        }

    }

    List<Range> marks;  //is always sorted
    boolean fractionCached;
    double cachedFraction;  //return value of getMarkedFraction
    int fractionCachedForLength; //value of fullLength can change, so it needs to be saved

    public MarkingOnEvents() {
        super();
        this.marks = new LinkedList<Range>();
        fractionCached = false;
    }

    public void mark(int start, int length) {
        fractionCached = false;
        Range newRange = new Range(start, start + length - 1);
        for (Range r : marks) {
            if (r.canBeMerged(newRange)) {
                r.mergeWith(newRange);
                int index = marks.indexOf(r);
                while (index < marks.size() - 1) {
                    Range next = marks.get(index + 1);
                    if (r.canBeMerged(next)) {
                        r.mergeWith(next);
                        marks.remove(next);
                    } else {
                        break;
                    }
                }
                /*
                 * impossible, since list is sorted and if r+new overlaps
                 * r-1, then r-1 overlaps new 
                 * while( index > 0) { index =
                 * marks.indexOf(r); Range prev = marks.get(index - 1); if (
                 * r.canBeMerged(prev)) { r.mergeWith(prev);
                 * marks.remove(prev); } else { break; } }
                 */
                return;
            }
        }
        // no overlaps
        int position = 0;
        for (Range r : marks) {
            if (r.start < newRange.start) {
                ++position;
            } else {
                break;
            }
        }
        marks.add(position, newRange);
    }

    public boolean isFullyMarked(int start, int length) {
        for (Range r : marks) {
            if ((r.start <= start) && (r.end >= start + length - 1)) {
                return true;
            }
        }
        return false;
    }

    public double getMarkedFraction(int fullLength) {
        if (fractionCached && (fullLength == fractionCachedForLength)) {
            return cachedFraction;
        }
        int length = 0;
        for (Range r : marks) {
            length += (r.end + 1 - r.start);
        }
        if (length > fullLength) {
            throw new IllegalArgumentException("Marked length " + length
                    + ", when full length is " + fullLength);
        }
        fractionCached = true;
        fractionCachedForLength = fullLength;
        cachedFraction = 1.0 * length / fullLength;
        return cachedFraction;
    }
}