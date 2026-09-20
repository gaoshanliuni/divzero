package dev.mineagent.runtime.core.conversation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Fixed input contract: at most 30 seconds, mono PCM16 at 16 kHz; no arbitrary audio containers. */
public final class SpeechWav {
    public static final int MAX_PCM_BYTES=30*16000*2,MAX_BYTES=44+MAX_PCM_BYTES,CHUNK_BYTES=24*1024;
    private SpeechWav(){}
    public static byte[] encode(byte[] pcm){
        if(pcm==null||pcm.length<3200||pcm.length>MAX_PCM_BYTES||pcm.length%2!=0)throw new IllegalArgumentException("ASR_RECORDING_LENGTH");
        var b=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+pcm.length).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);return b.array();
    }
    public static void validate(byte[] wav){
        if(wav==null||wav.length<3244||wav.length>MAX_BYTES)throw new IllegalArgumentException("ASR_WAV_INVALID");var b=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if(!new String(wav,0,4,StandardCharsets.US_ASCII).equals("RIFF")||!new String(wav,8,8,StandardCharsets.US_ASCII).equals("WAVEfmt ")||!new String(wav,36,4,StandardCharsets.US_ASCII).equals("data")||b.getInt(4)!=wav.length-8||b.getInt(16)!=16||b.getShort(20)!=1||b.getShort(22)!=1||b.getInt(24)!=16000||b.getInt(28)!=32000||b.getShort(32)!=2||b.getShort(34)!=16||b.getInt(40)!=wav.length-44||(wav.length-44)%2!=0)throw new IllegalArgumentException("ASR_WAV_INVALID");
    }
}
