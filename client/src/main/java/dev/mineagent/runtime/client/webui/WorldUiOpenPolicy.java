package dev.mineagent.runtime.client.webui;

/** Side-effect-free cold-host admission; the caller retains exact connection/world/Screen identity. */
public final class WorldUiOpenPolicy {
    private WorldUiOpenPolicy(){}
    public enum Next { CANCEL, TIMEOUT, WAIT, OPEN_HOST, OPEN_CONTENT }
    public static Next next(boolean sameContext,long now,long deadline,boolean started,boolean browserPresent,
                            boolean ready,boolean sessionReady,boolean transferIdle,long nextHostAttempt){
        if(!sameContext)return Next.CANCEL;
        if(now>=deadline)return Next.TIMEOUT;
        if(started)return Next.WAIT;
        if(!browserPresent)return now>=nextHostAttempt?Next.OPEN_HOST:Next.WAIT;
        if(!ready||!sessionReady||!transferIdle)return Next.WAIT;
        return Next.OPEN_CONTENT;
    }
}
