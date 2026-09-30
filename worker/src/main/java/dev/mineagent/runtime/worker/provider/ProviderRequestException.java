package dev.mineagent.runtime.worker.provider;

/** A received HTTP rejection is distinct from a failed/partial stream with unknown outcome. */
public final class ProviderRequestException extends RuntimeException {
    private final int statusCode;
    private final long retryAfterMillis;
    private final boolean contextTooLarge;
    private java.util.Map<String,Object> diagnostics=java.util.Map.of();
    public java.util.Map<String,Object> diagnostics(){return diagnostics;}
    public ProviderRequestException withoutSecret(String secret){if(secret!=null&&!secret.isEmpty()){var safe=new java.util.LinkedHashMap<String,Object>();diagnostics.forEach((k,v)->safe.put(k,v instanceof String s?s.replace(secret,"[REDACTED]"):v));diagnostics=java.util.Map.copyOf(safe);}return this;}
    public ProviderRequestException(int statusCode,String message){this(statusCode,message,0,false);}
    private ProviderRequestException(int statusCode,String message,long retryAfterMillis,boolean contextTooLarge){
        super(message);this.statusCode=statusCode;this.retryAfterMillis=retryAfterMillis;this.contextTooLarge=contextTooLarge;
    }
    public ProviderRequestException(String message,Throwable cause){super(message,cause);statusCode=0;retryAfterMillis=0;contextTooLarge=false;}
    public int statusCode(){return statusCode;}
    public long retryAfterMillis(){return retryAfterMillis;}
    public boolean contextTooLarge(){return contextTooLarge;}
    public static ProviderRequestException rejected(int status,String retryAfter,byte[] body){
        boolean context=status==413;
        var details=new java.util.LinkedHashMap<String,Object>();
        try{
            var root=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);var error=root.has("error")?root.path("error"):root;
            for(String key:java.util.List.of("message","code","type","param"))if(error.path(key).isValueNode()&&!error.path(key).isNull())details.put(key.equals("message")?"diagnostic":key.equals("param")?"field":"provider"+Character.toUpperCase(key.charAt(0))+key.substring(1),dev.mineagent.runtime.core.conversation.ToolFailure.safe(error.path(key).asText()));
            if(error.isTextual())details.put("diagnostic",dev.mineagent.runtime.core.conversation.ToolFailure.safe(error.asText()));
            String code=error.path("code").asText(error.path("type").asText());
            context|=java.util.Set.of("context_length_exceeded","context_window_exceeded","max_context_length_exceeded","input_too_long").contains(code);
        }catch(Exception ignored){String text=new String(body,java.nio.charset.StandardCharsets.UTF_8).replaceAll("<[^>]{0,512}>"," ").strip();if(!text.isBlank())details.put("diagnostic",dev.mineagent.runtime.core.conversation.ToolFailure.safe("HTTP "+status+": "+text));}
        long delay=0;
        try{delay=Math.multiplyExact(Long.parseLong(retryAfter.trim()),1000L);}catch(Exception number){
            try{delay=java.time.Duration.between(java.time.Instant.now(),java.time.ZonedDateTime.parse(retryAfter,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();}catch(Exception ignored){}
        }
        // Very long provider delays remain visible failures instead of being retried before Retry-After.
        var result=new ProviderRequestException(status,context?"PROVIDER_CONTEXT_TOO_LARGE":"PROVIDER_HTTP_REJECTED",Math.max(0,delay),context);
        details.putIfAbsent("diagnostic","Provider rejected the model request with HTTP "+status);result.diagnostics=java.util.Map.copyOf(details);return result;
    }
}
