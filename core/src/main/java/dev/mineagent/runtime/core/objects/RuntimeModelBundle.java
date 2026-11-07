package dev.mineagent.runtime.core.objects;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.*;
import java.nio.charset.StandardCharsets;

import java.util.*;
/** Only the declared render resource and its PNG cross the wire; server JS is never included. */
public final class RuntimeModelBundle {
    public static final int MAX_TEXTURE_BYTES=1_048_576,MAX_BYTES=RuntimeMesh.MAX_BYTES+MAX_TEXTURE_BYTES+72;
    private final byte[] bytes,texture;private final RuntimeMesh mesh;private final String sha256;
    private RuntimeModelBundle(byte[] bytes,byte[] texture,RuntimeMesh mesh,String id){this.bytes=bytes;this.texture=texture;this.mesh=mesh;sha256=ResourceIds.require(id);}
    public byte[] bytes(){return bytes.clone();}public byte[] texture(){return texture.clone();}public RuntimeMesh mesh(){return mesh;}public String sha256(){return sha256;}
    public int size(){return bytes.length;}
    public byte[] chunk(int offset,int length){if(offset<0||offset>=bytes.length||length<1||length>24576)throw new IllegalArgumentException("OBJECT_CHUNK_RANGE");return Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+length));}
    public static RuntimeModelBundle create(byte[] model,byte[] png){return create(model,png,ResourceIds.create());}
    private static RuntimeModelBundle create(byte[] model,byte[] png,String id){
        if(model==null||model.length==0||model.length>RuntimeMesh.MAX_BYTES||png==null||png.length>MAX_TEXTURE_BYTES)throw new IllegalArgumentException("OBJECT_ASSET_BUDGET");
        var mesh=RuntimeMesh.parse(new String(model,StandardCharsets.UTF_8));if(mesh.texture().isEmpty()!= (png.length==0))throw new IllegalArgumentException("OBJECT_TEXTURE_REQUIRED");
        if(png.length>0)validatePng(png);
        var bytes=ByteBuffer.allocate(72+model.length+png.length).putInt(0x44565A32).put(id.getBytes(StandardCharsets.US_ASCII)).putInt(model.length).put(model).put(png).array();return new RuntimeModelBundle(bytes,png.clone(),mesh,id);
    }
    public static RuntimeModelBundle decode(byte[] bytes,String expected){
        ResourceIds.require(expected);if(bytes==null||bytes.length<5||bytes.length>MAX_BYTES)throw new IllegalArgumentException("OBJECT_ASSET_SIZE");var input=ByteBuffer.wrap(bytes);int first=input.getInt(),offset=4,length=first;
        if(first==0x44565A32){if(bytes.length<73)throw new IllegalArgumentException("OBJECT_ASSET_HEADER");byte[] id=new byte[64];input.get(id);if(!expected.equals(new String(id,StandardCharsets.US_ASCII)))throw new IllegalArgumentException("OBJECT_ASSET_ID");length=input.getInt();offset=72;}
        if(length<1||length>RuntimeMesh.MAX_BYTES||length>bytes.length-offset)throw new IllegalArgumentException("OBJECT_ASSET_LENGTH");var parsed=create(Arrays.copyOfRange(bytes,offset,offset+length),Arrays.copyOfRange(bytes,offset+length,bytes.length),expected);return new RuntimeModelBundle(bytes.clone(),parsed.texture,parsed.mesh,expected);
    }
    public static RuntimeModelBundle load(RuntimePackage pack,RuntimeDefinition definition,String path,ContentAddressedStore store)throws Exception{
        var model=read(pack,definition,path,"application/json",RuntimeMesh.MAX_BYTES,store);var mesh=RuntimeMesh.parse(new String(model,StandardCharsets.UTF_8));
        var refs=new ArrayList<String>();refs.add(pack.canonicalSha256());refs.add(pack.resources().get(path).sha256());if(!mesh.texture().isEmpty())refs.add(pack.resources().get(mesh.texture()).sha256());return create(model,mesh.texture().isEmpty()?new byte[0]:read(pack,definition,mesh.texture(),"image/png",MAX_TEXTURE_BYTES,store),store.namedId("render_bundle",refs));
    }
    private static byte[] read(RuntimePackage pack,RuntimeDefinition definition,String path,String media,int max,ContentAddressedStore store)throws Exception{
        RuntimeEntrypoint.requireRelativePath(path);var ref=pack.resources().get(path);
        if(!definition.resourcePaths().contains(path)||ref==null||ref.side()==RuntimeResourceSide.SERVER||!ref.mediaType().equals(media)||ref.size()<1||ref.size()>max)throw new IllegalArgumentException("OBJECT_RESOURCE_NOT_DECLARED");
        byte[] bytes=store.read(ref.sha256());if(bytes.length!=ref.size()||bytes.length>max)throw new IllegalArgumentException("OBJECT_RESOURCE_SIZE");return bytes;
    }
    private static void validatePng(byte[] p){
        byte[] magic={(byte)137,80,78,71,13,10,26,10};if(p.length<33||!Arrays.equals(magic,Arrays.copyOf(p,8))||p[12]!='I'||p[13]!='H'||p[14]!='D'||p[15]!='R')throw new IllegalArgumentException("OBJECT_PNG_FORMAT");
        var b=ByteBuffer.wrap(p);int w=b.getInt(16),h=b.getInt(20);if(w<1||h<1||w>1024||h>1024)throw new IllegalArgumentException("OBJECT_PNG_DIMENSIONS");
    }
}
