package dev.mineagent.runtime.core.config;

import java.util.*;

/** Uses the same provider order, agent override and vision role as request routing. */
public record ModelCapabilityTarget(String kind,String base,String model,ModelCapabilityProfiles.Profile profile) {
    public static ModelCapabilityTarget select(Map<String,String> values,UUID world,UUID agent,String explicitModel,String capability){
        var choice=AgentModelSettings.read(values,world,agent);
        String vision=explicitModel.isBlank()&&"vision".equals(capability)?values.getOrDefault("provider.openai.role.vision","").strip():"";
        if(!vision.isEmpty()||choice.mode().equals("CUSTOM")){
            String base=values.getOrDefault("provider.openai.baseUrl","");
            if(!Boolean.parseBoolean(values.getOrDefault("provider.openai.enabled","true"))||!WebSettingsCatalog.safeUrl(base))throw new IllegalStateException("AGENT_MODEL_API_CHANGED");
            if(choice.mode().equals("CUSTOM")&&!AgentModelSettings.origin(base).equals(AgentModelSettings.origin(choice.baseUrl())))throw new IllegalStateException("AGENT_MODEL_API_CHANGED");
            return target(values,"openai-compatible",base,!explicitModel.isBlank()?explicitModel:!vision.isEmpty()?vision:choice.model());
        }
        for(String provider:ProviderOrder.parse(values.getOrDefault("provider.priority",ProviderOrder.DEFAULT))){
            String prefix=provider.equals("openai-compatible")?"provider.openai.":"provider.ollama.";
            String base=values.getOrDefault(prefix+"baseUrl",""),model=values.getOrDefault(prefix+"model","");
            if(Boolean.parseBoolean(values.getOrDefault(prefix+"enabled","true"))&&!base.isBlank()&&AgentModelSettings.validModel(model))return target(values,provider,base,explicitModel.isBlank()?model:explicitModel);
        }
        throw new IllegalArgumentException("MODEL_API_NOT_CONFIGURED");
    }
    private static ModelCapabilityTarget target(Map<String,String> values,String kind,String base,String model){
        if(!WebSettingsCatalog.safeUrl(base)||base.isBlank()||!AgentModelSettings.validModel(model))throw new IllegalArgumentException("MODEL_ID_NOT_CONFIGURED");
        return new ModelCapabilityTarget(kind,base,model,ModelCapabilityProfiles.read(values,base,model));
    }
}
