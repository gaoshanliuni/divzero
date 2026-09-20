package dev.mineagent.runtime.api.model;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Immutable bounded image input. Encoded bytes belong in Provider image parts, never prompt text or toString logs. */
public record ModelImage(String mimeType,byte[] bytes) {
    public static final int MAX_BYTES=2_097_152,MAX_PIXELS=1_048_576;
    private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    public ModelImage {
        if(!"image/png".equals(mimeType)||bytes==null||bytes.length<33||bytes.length>MAX_BYTES||!Arrays.equals(Arrays.copyOf(bytes,8),PNG))throw new IllegalArgumentException("MODEL_IMAGE_FORMAT");
        var header=ByteBuffer.wrap(bytes);
        if(header.getInt(8)!=13||header.getInt(12)!=0x49484452)throw new IllegalArgumentException("MODEL_IMAGE_HEADER");
        int width=header.getInt(16),height=header.getInt(20);
        if(width<1||height<1||(long)width*height>MAX_PIXELS)throw new IllegalArgumentException("MODEL_IMAGE_PIXELS");
        bytes=bytes.clone();
    }
    @Override public byte[] bytes(){return bytes.clone();}
    public int width(){return ByteBuffer.wrap(bytes).getInt(16);}
    public int height(){return ByteBuffer.wrap(bytes).getInt(20);}
    @Override public String toString(){return "ModelImage[mimeType="+mimeType+", bytes="+bytes.length+", content=REDACTED]";}
}
