package dev.mineagent.runtime.client.webui;
import java.util.HashMap;
import java.util.Map;
/** Ownership is fixed at physical key-down, not reconsidered on repeats or after a Screen change. */
public final class WorkspaceShortcut {
    public enum Result { PASS, CONSUME, TOGGLE }
    private final Map<Long,Boolean> held=new HashMap<>();
    public static boolean shown(boolean requested,boolean interactionScreen,boolean diagnosticScreen){return diagnosticScreen||requested&&interactionScreen;}
    public Result event(long key,int action,boolean matches,boolean eligible,boolean composing){
        if(action==0)return Boolean.TRUE.equals(held.remove(key))?Result.CONSUME:Result.PASS;
        if(action!=1&&action!=2)return Result.PASS;
        Boolean owned=held.get(key);if(owned!=null)return owned?Result.CONSUME:Result.PASS;
        boolean claim=action==1&&matches&&eligible&&!composing;held.put(key,claim);
        return claim?Result.TOGGLE:Result.PASS;
    }
    public void reset(){held.clear();}
}
