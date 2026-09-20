package dev.mineagent.runtime.core.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Private plaintext inside SecretChannel only; binds encrypted input to operation, world, service and revision. */
public record NativeSecretPayload(UUID operation,UUID world,UUID instance,long revision,String value,String targetKey) {
    public static final Set<String> KEYS=Set.of("provider.openai.apiKey","provider.asr.apiKey");
    public NativeSecretPayload(UUID operation,UUID world,UUID instance,long revision,String value){this(operation,world,instance,revision,value,"provider.openai.apiKey");}
    public NativeSecretPayload{Objects.requireNonNull(operation);Objects.requireNonNull(world);Objects.requireNonNull(instance);if(targetKey==null)targetKey="provider.openai.apiKey";if(revision<0||value==null||value.length()>4096||!KEYS.contains(targetKey))throw new IllegalArgumentException("SECRET_INPUT_INVALID");}
    public String encode()throws Exception{return new ObjectMapper().writeValueAsString(this);}
    public static NativeSecretPayload decode(String text,UUID operation,UUID world,UUID instance,long revision)throws Exception{
        if(text.length()>10000)throw new IllegalArgumentException("SECRET_ENVELOPE_INVALID");var value=new ObjectMapper().readValue(text,NativeSecretPayload.class);
        if(!value.operation.equals(operation)||!value.world.equals(world)||!value.instance.equals(instance)||value.revision!=revision)throw new IllegalArgumentException("SECRET_CONTEXT_CHANGED");return value;
    }
    @Override public String toString(){return "NativeSecretPayload[operation="+operation+", revision="+revision+", value=REDACTED]";}
}
