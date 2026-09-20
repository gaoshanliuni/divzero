package dev.mineagent.runtime.core.task;
/** Combine real native use lifecycle and independently observed effects; never equate start with completion. */
public final class ItemUseProof {
    private ItemUseProof(){}
    public static String status(boolean accepted,boolean timed,boolean using,boolean nativeFinished,boolean changed){
        if(!accepted)return "USE_REJECTED";
        if(using)return "PENDING";
        if(timed&&!nativeFinished)return "USE_INTERRUPTED";
        return changed?"VERIFIED":"USE_NO_CHANGE";
    }
}
