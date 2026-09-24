package dev.mineagent.runtime.worker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Credentialed GET only. No chat/completions, redirects, raw error bodies or credential logging. */
public final class ProviderModelCatalog {
    public static final int MAX_BYTES=2*1024*1024, MAX_MODELS=4096;
    public record Result(List<String> models,String error,int httpStatus) {
        public Result { models=List.copyOf(models); }
    }
    private static final ObjectMapper JSON=new ObjectMapper();
    private ProviderModelCatalog(){}
    public static URI endpoint(String base){
        if(base==null||base.isBlank())throw new IllegalArgumentException("MODELS_URL_REQUIRED");
        URI u=URI.create(base.strip());
        if(!Set.of("https","http").contains(Objects.toString(u.getScheme(),"").toLowerCase(Locale.ROOT))||u.getHost()==null||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null)throw new IllegalArgumentException("MODELS_URL_INVALID");
        String path=u.getRawPath();return URI.create(u.toString()+(path==null||path.isEmpty()?"/v1/":path.equals("/")?"v1/":path.endsWith("/")?"":"/")).resolve("models");
    }
    public static boolean keyOptional(String base){try{return Set.of("localhost","127.0.0.1","::1","[::1]").contains(URI.create(base).getHost());}catch(Exception e){return false;}}
    public static Result fetch(String base,String key){
        try {
            URI url=endpoint(base);
            if((key==null||key.isBlank())&&!keyOptional(base))return new Result(List.of(),"MODELS_KEY_REQUIRED",0);
            try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build()){
                var builder=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15)).header("Accept","application/json");if(key!=null&&!key.isBlank())builder.header("Authorization","Bearer "+key);var request=builder.GET().build();
                var response=client.send(request,info->new LimitedBody());int status=response.statusCode();
                if(status!=200)return new Result(List.of(),status==401||status==403?"MODELS_AUTH_FAILED":status==404||status==405?"MODELS_UNSUPPORTED":status==429?"MODELS_RATE_LIMITED":status>=300&&status<400?"MODELS_REDIRECT_DENIED":"MODELS_HTTP_ERROR",status);
                var root=JSON.readTree(response.body());var rows=root==null?null:root.get("data");
                if(rows==null||!rows.isArray()||rows.size()>MAX_MODELS)return new Result(List.of(),"MODELS_INVALID_RESPONSE",status);
                var models=new TreeSet<String>();
                for(var row:rows){var node=row.get("id");if(node==null||!node.isTextual())return new Result(List.of(),"MODELS_INVALID_RESPONSE",status);String id=node.textValue();
                    if(id.isBlank()||id.length()>256||id.codePoints().anyMatch(Character::isISOControl)||(key!=null&&!key.isBlank()&&id.contains(key))||id.startsWith("sk-"))return new Result(List.of(),"MODELS_INVALID_RESPONSE",status);models.add(id);
                }
                return new Result(List.copyOf(models),models.isEmpty()?"MODELS_EMPTY":"",status);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return new Result(List.of(),"MODELS_INTERRUPTED",0);}
        catch(HttpTimeoutException timeout){return new Result(List.of(),"MODELS_TIMEOUT",0);}
        catch(Exception failed){return new Result(List.of(),"MODELS_UNAVAILABLE",0);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> next=HttpResponse.BodySubscribers.ofByteArray();private Flow.Subscription subscription;private long bytes;private boolean failed;
        public CompletionStage<byte[]> getBody(){return next.getBody();}
        public void onSubscribe(Flow.Subscription value){subscription=value;next.onSubscribe(value);}
        public void onNext(List<ByteBuffer> items){if(failed)return;for(var item:items)bytes+=item.remaining();if(bytes>MAX_BYTES){failed=true;subscription.cancel();next.onError(new IllegalArgumentException("MODELS_RESPONSE_TOO_LARGE"));}else next.onNext(items);}
        public void onError(Throwable error){if(!failed){failed=true;next.onError(error);}}
        public void onComplete(){if(!failed)next.onComplete();}
    }
}
