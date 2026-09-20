package dev.mineagent.runtime.api.ui;
import dev.mineagent.runtime.api.model.ModelImage;
import java.security.MessageDigest;
import java.util.*;

/** Image metadata is data, not authority. Transport always retains the enclosing AGENT RPC session/epoch fence. */
public final class UiCapture {
    public static final int CHUNK_BYTES=16_384;
    private UiCapture(){}
    public record Manifest(UUID captureId,String documentId,String viewportHash,String layoutHash,int width,int height,
                           double frameX,double frameY,double scaleX,double scaleY,int pngLength,String sha256){
        public Manifest{
            Objects.requireNonNull(captureId);
            if(documentId==null||documentId.isBlank()||documentId.length()>128||!hash(viewportHash)||!hash(layoutHash)||!hash(sha256)
                    ||width<1||height<1||(long)width*height>ModelImage.MAX_PIXELS||pngLength<33||pngLength>ModelImage.MAX_BYTES)
                throw new IllegalArgumentException("UI_CAPTURE_MANIFEST");
            for(double n:new double[]{frameX,frameY,scaleX,scaleY})if(!Double.isFinite(n))throw new IllegalArgumentException("UI_CAPTURE_COORDINATES");
            if(frameX<0||frameY<0||frameX>8192||frameY>8192||scaleX<=0||scaleY<=0||scaleX>16||scaleY>16)throw new IllegalArgumentException("UI_CAPTURE_COORDINATES");
        }
        public int chunks(){return (pngLength+CHUNK_BYTES-1)/CHUNK_BYTES;}
    }
    public record Chunk(UUID captureId,int index,String base64){
        public Chunk{Objects.requireNonNull(captureId);if(index<0||index>=128||base64==null||base64.isEmpty()||base64.length()>21848)throw new IllegalArgumentException("UI_CAPTURE_CHUNK");}
    }
    public record Image(Manifest manifest,ModelImage png){
        public Image{Objects.requireNonNull(manifest);Objects.requireNonNull(png);
            byte[] bytes=png.bytes();if(bytes.length!=manifest.pngLength||png.width()!=manifest.width||png.height()!=manifest.height||!sha256(bytes).equals(manifest.sha256))throw new IllegalArgumentException("UI_CAPTURE_INTEGRITY");}
    }
    public static Image image(UUID id,String document,String viewportHash,String layoutHash,double x,double y,double sx,double sy,byte[] bytes){
        var png=new ModelImage("image/png",bytes);return new Image(new Manifest(id,document,viewportHash,layoutHash,png.width(),png.height(),x,y,sx,sy,bytes.length,sha256(bytes)),png);
    }
    public static Chunk chunk(Image image,int index){
        if(index<0||index>=image.manifest.chunks())throw new IllegalArgumentException("UI_CAPTURE_CHUNK_INDEX");
        byte[] bytes=image.png.bytes();int start=index*CHUNK_BYTES;
        return new Chunk(image.manifest.captureId,index,Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,start,Math.min(start+CHUNK_BYTES,bytes.length))));
    }
    public static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
}
