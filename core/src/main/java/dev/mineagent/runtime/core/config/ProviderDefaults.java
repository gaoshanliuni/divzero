package dev.mineagent.runtime.core.config;
import java.util.*;
public final class ProviderDefaults {
    private ProviderDefaults(){}
    public static String model(String address){
        String host;try{host=Objects.toString(java.net.URI.create(address).getHost(),"").toLowerCase(Locale.ROOT);}catch(Exception bad){host="";}
        return switch(host){case "api.deepseek.com"->"deepseek-flash";case "open.bigmodel.cn","api.z.ai"->"glm-4.6";case "localhost","127.0.0.1"->"qwen3:8b";default->"gpt-4.1-mini";};
    }
    public static void fill(Map<String,String> values){
        if(values.getOrDefault("provider.openai.model","").isBlank()) values.put("provider.openai.model",model(values.getOrDefault("provider.openai.baseUrl","")));
        if(values.getOrDefault("provider.ollama.model","").isBlank()) values.put("provider.ollama.model","qwen3:8b");
    }
    public static void update(Map<String,String> before,Map<String,String> next,Map<String,String> patch){
        if(patch.containsKey("provider.openai.baseUrl")&&!patch.containsKey("provider.openai.model")&&before.getOrDefault("provider.openai.model","").equals(model(before.getOrDefault("provider.openai.baseUrl",""))))next.put("provider.openai.model",model(next.getOrDefault("provider.openai.baseUrl","")));
        fill(next);
    }
}
