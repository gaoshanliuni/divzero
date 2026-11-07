package dev.mineagent.runtime.worker;
import dev.mineagent.runtime.api.model.ModelImage;
import java.util.*;

/** Bounded IPC serialization. This data must not be put in textual prompts or diagnostic toString output. */
public final class WorkerModelImages {
    private WorkerModelImages(){}
    public static List<Map<String,String>> encode(List<ModelImage> images){
        ModelImage.validateBatch(images);
        return images.stream().map(i->Map.of("mimeType",i.mimeType(),"base64",Base64.getEncoder().encodeToString(i.bytes()))).toList();
    }
    public static List<ModelImage> decode(Object value){
        try{
            if(!(value instanceof List<?> list)||list.size()>ModelImage.MAX_COUNT)throw new IllegalArgumentException();
            var result=new ArrayList<ModelImage>();
            for(Object entry:list){
                if(!(entry instanceof Map<?,?> map)||!(map.get("mimeType") instanceof String mime)||!(map.get("base64") instanceof String data)||data.length()>5_592_408)throw new IllegalArgumentException();
                result.add(new ModelImage(mime,Base64.getDecoder().decode(data)));
            }
            ModelImage.validateBatch(result);return List.copyOf(result);
        }catch(RuntimeException invalid){throw new IllegalArgumentException("MODEL_IMAGE_PAYLOAD");}
    }
}
