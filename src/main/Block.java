package main;
import java.util.Arrays;


public class Block {
	public final static int blockSize = 405;
	public AngleBasedMetrics[] records;
	public Block(AngleBasedMetrics[] records, boolean sort) {
		this.records = records;
		if (sort) {
			Arrays.sort(records);
		}
	}
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("Block [sample records: ");
		for( int i=0;i<blockSize;i+=Math.min(1, Math.floor(blockSize/10))) {
			sb.append(" "+records[i].toString());
		}
		sb.append("]");
		return sb.toString();
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(records);
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
		Block other = (Block) obj;
		if (!Arrays.equals(records, other.records))
			return false;
		return true;
	}
	
	
}
