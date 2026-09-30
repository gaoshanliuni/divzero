package dev.mineagent.runtime.worker.provider;

/** A received HTTP rejection is distinct from a failed/partial stream with unknown outcome. */
public final class ProviderRequestException extends RuntimeException {
    private final int statusCode;
    private final long retryAfterMillis;
    private final boolean contextTooLarge;
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
        if(status==400)try{
            var error=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("error");
            String code=error.path("code").asText(error.path("type").asText());
            context=java.util.Set.of("context_length_exceeded","context_window_exceeded","max_context_length_exceeded","input_too_long").contains(code);
        }catch(Exception ignored){}
        long delay=0;
        try{delay=Math.multiplyExact(Long.parseLong(retryAfter.trim()),1000L);}catch(Exception number){
            try{delay=java.time.Duration.between(java.time.Instant.now(),java.time.ZonedDateTime.parse(retryAfter,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();}catch(Exception ignored){}
        }
        // Very long provider delays remain visible failures instead of being retried before Retry-After.
        return new ProviderRequestException(status,context?"PROVIDER_CONTEXT_TOO_LARGE":"PROVIDER_HTTP_REJECTED",Math.max(0,delay),context);
    }
}
