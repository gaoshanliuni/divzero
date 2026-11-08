package dev.mineagent.runtime.core.objects;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.RuntimeEntrypoint;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public render data plus a world/instance reference; never executable code or authority. */
public record RuntimeItemBinding(UUID world,UUID instance,String part,String packageHash,String modelPath,String assetHash,String modelSource,int chargeTicks) {
    public static final int MAX_SOURCE=8192,MAX_BINDING=12288;
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public RuntimeItemBinding {
        Objects.requireNonNull(world);Objects.requireNonNull(instance);
        if(chargeTicks<0||chargeTicks>200)throw new IllegalArgumentException("RUNTIME_ITEM_CHARGE_BOUNDS");
        if(part==null||!part.matches("[A-Za-z0-9_.-]{1,64}")||packageHash==null||!packageHash.matches("[a-f0-9]{64}")||modelPath==null||modelPath.length()>128||!modelPath.endsWith(".json"))throw new IllegalArgumentException("RUNTIME_ITEM_BINDING");
        RuntimeEntrypoint.requireRelativePath(modelPath);
        if(modelSource==null||modelSource.getBytes(StandardCharsets.UTF_8).length>MAX_SOURCE)throw new IllegalArgumentException("RUNTIME_ITEM_MODEL_BUDGET");
        dev.mineagent.runtime.api.packages.ResourceIds.require(assetHash);
        // Texture bytes are fetched from the verified package using assetHash, never embedded in every stack.
        RuntimeMesh.parse(modelSource);
    }
    public RuntimeItemBinding(UUID world,UUID instance,String part,String packageHash,String modelPath,String assetHash,String modelSource){this(world,instance,part,packageHash,modelPath,assetHash,modelSource,0);}
    public static RuntimeItemBinding create(UUID world,UUID instance,String part,String hash,String path,String source){return create(world,instance,part,hash,path,source,0);}
    public static RuntimeItemBinding create(UUID world,UUID instance,String part,String hash,String path,String source,int chargeTicks){return new RuntimeItemBinding(world,instance,part,hash,path,dev.mineagent.runtime.api.packages.ResourceIds.create(),source,chargeTicks);}
    public String encode(){try{String out=JSON.writeValueAsString(this);if(out.getBytes(StandardCharsets.UTF_8).length>MAX_BINDING)throw new IllegalArgumentException("RUNTIME_ITEM_BINDING_BUDGET");return out;}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    public static RuntimeItemBinding parse(String value){try{if(value==null||value.getBytes(StandardCharsets.UTF_8).length>MAX_BINDING)throw new IllegalArgumentException();return JSON.readValue(value,RuntimeItemBinding.class);}catch(Exception e){throw new IllegalArgumentException("RUNTIME_ITEM_BINDING_INVALID",e);}}
    public RuntimeMesh mesh(){return RuntimeMesh.parse(modelSource);}
}
