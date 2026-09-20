package dev.mineagent.runtime.neoforge.client.webui;
/** Fixture shutdown must not race active browser RPCs or the integrated server's disconnect. */
final class YsmRecoveryStopPolicy {
    private YsmRecoveryStopPolicy(){}
    static boolean mayStage(int now,int browserClosedAt){return browserClosedAt>=0&&now-browserClosedAt>=20;}
    static boolean mayStop(boolean staged,boolean hasConnection,boolean hasLevel){return staged&&!hasConnection&&!hasLevel;}
}
