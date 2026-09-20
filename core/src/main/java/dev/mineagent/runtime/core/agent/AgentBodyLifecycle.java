package dev.mineagent.runtime.core.agent;

/** Per Java body instance, not a persisted task or agent identity. */
public final class AgentBodyLifecycle {
    private boolean ticked,dead,endReturn,stopping;
    private int lastTick;
    private long ticks;
    public boolean enterTick(int serverTick){if(stopping||ticked&&lastTick==serverTick)return false;ticked=true;lastTick=serverTick;ticks++;return true;}
    public long ticks(){return ticks;}
    public boolean acceptDeath(){if(dead||endReturn)return false;dead=true;return true;}
    public boolean deathAccepted(){return dead;}
    public boolean deferContainerClose(boolean removed){return dead&&!removed;}
    public boolean acceptEndReturn(){if(dead||endReturn)return false;endReturn=true;return true;}
    public boolean endReturnAccepted(){return endReturn;}
    public static boolean preserveHardcoreSpectator(boolean loaded,boolean hardcore,boolean spectator){return loaded&&hardcore&&spectator;}
    public boolean beginStop(){if(stopping)return false;stopping=true;return true;}
    public boolean stopping(){return stopping;}
}
