package dev.mineagent.runtime.core.task;
import java.util.UUID;

/** A local affirmative consent is necessary; a server packet alone cannot arm the client. Times are monotonic millis. */
public final class PlayerControlLease {
    public enum Phase { ARMED,RUNNING,STOPPED }
    private final UUID operation,consent;private final long armedAt;
    private Phase phase=Phase.ARMED;private long startedAt,renewedAt,sequence;
    public PlayerControlLease(UUID operation,UUID consent,long now){this.operation=java.util.Objects.requireNonNull(operation);this.consent=java.util.Objects.requireNonNull(consent);armedAt=now;}
    public boolean matches(UUID op,UUID token){return operation.equals(op)&&consent.equals(token);}
    public boolean grant(UUID op,UUID token,long seq,long now){if(!matches(op,token)||phase!=Phase.ARMED||seq<1||!valid(now,true))return false;phase=Phase.RUNNING;startedAt=renewedAt=now;sequence=seq;return true;}
    public boolean renew(UUID op,UUID token,long seq,long now){if(!matches(op,token)||phase!=Phase.RUNNING||seq<=sequence||!valid(now,true))return false;renewedAt=now;sequence=seq;return true;}
    public boolean valid(long now,boolean current){if(!current||now<armedAt||phase==Phase.ARMED&&now-armedAt>8000||phase==Phase.RUNNING&&(now-renewedAt>1500||now-startedAt>45000))stop();return phase!=Phase.STOPPED;}
    public void stop(){phase=Phase.STOPPED;}
    public Phase phase(){return phase;}
    public UUID operation(){return operation;}
    public UUID consent(){return consent;}
}
