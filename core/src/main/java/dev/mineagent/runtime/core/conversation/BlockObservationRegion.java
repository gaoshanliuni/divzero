package dev.mineagent.runtime.core.conversation;
/** A coordinate scope, not an allocation. Reads MUST use bounded pages over this long cursor. */
public record BlockObservationRegion(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,int afterTicks) {
    public static final int MAX_EDGE=4096;
    public BlockObservationRegion {
        long x=(long)maxX-minX+1,y=(long)maxY-minY+1,z=(long)maxZ-minZ+1;
        if(x<1||y<1||z<1||x>MAX_EDGE||y>MAX_EDGE||z>MAX_EDGE||afterTicks<0||afterTicks>200)throw new IllegalArgumentException("AGENT_BLOCK_REGION_BOUNDS");
    }
    /** Retained source compatibility: distance is no longer an arbitrary 32-block restriction. */
    public void requireNear(int x,int y,int z){}
    public long volume(){return ((long)maxX-minX+1)*((long)maxY-minY+1)*((long)maxZ-minZ+1);}
    public int[] at(long offset){if(offset<0||offset>=volume())throw new IllegalArgumentException("AGENT_BLOCK_CURSOR");long width=(long)maxX-minX+1,depth=(long)maxZ-minZ+1;return new int[]{(int)(minX+offset%width),(int)(minY+offset/width/depth),(int)(minZ+offset/width%depth)};}
}
