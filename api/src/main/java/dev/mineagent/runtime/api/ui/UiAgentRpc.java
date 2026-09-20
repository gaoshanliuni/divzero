package dev.mineagent.runtime.api.ui;
import java.util.*;
/** Bounded transport. IDs/revisions come from authoritative sessions, never model output. */
public final class UiAgentRpc {
    private UiAgentRpc(){}
    private static void context(UUID id,UUID session,String view,long page,long control,long task){
        Objects.requireNonNull(id);Objects.requireNonNull(session);
        if(view==null||view.isBlank()||view.length()>128||page<1||control<1||task<1)throw new IllegalArgumentException("UI_RPC_CONTEXT");
    }
    public record Command(UUID requestId,UUID sessionId,String viewId,long pageGeneration,long controlEpoch,long taskRevision,String kind,String actionJson){
        public Command{context(requestId,sessionId,viewId,pageGeneration,controlEpoch,taskRevision);
            if(!Set.of("inspect","presentationInspect","act","capture","captureChunk").contains(kind)||actionJson==null||actionJson.length()>32768||(Set.of("inspect","presentationInspect","capture").contains(kind)&&!actionJson.isEmpty()))throw new IllegalArgumentException("UI_RPC_ACTION");}
    }
    public record Reply(UUID requestId,UUID sessionId,String viewId,long pageGeneration,long controlEpoch,long taskRevision,String resultJson){
        public Reply{context(requestId,sessionId,viewId,pageGeneration,controlEpoch,taskRevision);
            if(resultJson==null||resultJson.isBlank()||resultJson.length()>65536)throw new IllegalArgumentException("UI_RPC_REPLY_SIZE");}
        public static Reply forCommand(Command c,String result){return new Reply(c.requestId(),c.sessionId(),c.viewId(),c.pageGeneration(),c.controlEpoch(),c.taskRevision(),result);}
    }
}
