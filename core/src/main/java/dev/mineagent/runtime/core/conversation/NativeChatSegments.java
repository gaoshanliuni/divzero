package dev.mineagent.runtime.core.conversation;
/** Game chat segments use UTF-16 offsets for storage, but never split a Unicode surrogate pair. */
public final class NativeChatSegments {
    private NativeChatSegments(){}
    public static int nextLength(String text,int maximum,boolean finished){
        if(text.isEmpty())return 0;int end=Math.min(text.length(),maximum);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        if(!finished&&text.length()<maximum){int sentence=-1;for(int i=0;i<end;i++)if("\n。！？.!?".indexOf(text.charAt(i))>=0)sentence=i+1;return sentence<0?0:sentence;}
        return end;
    }
}
