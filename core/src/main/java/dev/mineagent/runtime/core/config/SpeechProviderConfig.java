package dev.mineagent.runtime.core.config;

/** Independent ASR credentials. No implicit reuse of a text Provider key or URL. */
public record SpeechProviderConfig(long revision,boolean enabled,String baseUrl,String model,String language,String apiKey){
    public SpeechProviderConfig{
        if(revision<0||baseUrl==null||model==null||language==null||apiKey==null||!WebSettingsCatalog.safeUrl(baseUrl)||model.length()>256||model.chars().anyMatch(Character::isISOControl)||!language.matches("(?:[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})?)?")||apiKey.length()>4096)throw new IllegalArgumentException("ASR_CONFIG_INVALID");
    }
    public boolean configured(){return enabled&&!baseUrl.isBlank()&&!model.isBlank();}
    @Override public String toString(){return "SpeechProviderConfig[revision="+revision+", enabled="+enabled+", credentials=REDACTED]";}
}
