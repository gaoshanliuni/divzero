package dev.mineagent.runtime.core.conversation;

/** Small loaded-world snapshots; limits apply before any Minecraft block access. */
public record BlockObservationRegion(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,int afterTicks) {
    public BlockObservationRegion {
        long x=(long)maxX-minX+1,y=(long)maxY-minY+1,z=(long)maxZ-minZ+1;
        if(x<1||y<1||z<1||x>65||y>65||z>65||x*y*z>4096||afterTicks<0||afterTicks>200)
            throw new IllegalArgumentException("AGENT_BLOCK_REGION_BOUNDS");
    }
    public void requireNear(int x,int y,int z){
        if(Math.abs((long)minX-x)>32||Math.abs((long)maxX-x)>32||Math.abs((long)minY-y)>32||Math.abs((long)maxY-y)>32||Math.abs((long)minZ-z)>32||Math.abs((long)maxZ-z)>32)
            throw new IllegalArgumentException("AGENT_BLOCK_REGION_TOO_FAR");
    }
    public int volume(){return (maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);}
}
