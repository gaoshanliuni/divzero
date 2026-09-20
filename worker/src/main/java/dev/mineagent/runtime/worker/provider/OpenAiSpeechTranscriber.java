package dev.mineagent.runtime.worker.provider;

import dev.mineagent.runtime.core.config.SpeechProviderConfig;
import dev.mineagent.runtime.core.conversation.SpeechWav;
import dev.mineagent.runtime.api.model.ModelResponse;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** One explicit WAV transcription. No text-provider fallback, retries, disk upload or redirects. */
public final class OpenAiSpeechTranscriber {
    public record Transcript(String text,String requestedModel,String responseModel){}
    public Transcript transcribe(SpeechProviderConfig config,byte[] wav)throws Exception{
        if(!config.configured())throw new IllegalArgumentException("ASR_NOT_CONFIGURED");SpeechWav.validate(wav);
        String boundary="MineAgentAsr"+UUID.randomUUID().toString().replace("-","");var out=new ByteArrayOutputStream();
        field(out,boundary,"model",config.model());field(out,boundary,"response_format","json");if(!config.language().isBlank())field(out,boundary,"language",config.language());
        out.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.writeBytes(wav);out.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        URI base=URI.create(config.baseUrl().endsWith("/")?config.baseUrl():config.baseUrl()+"/");
        var request=HttpRequest.newBuilder(base.resolve("audio/transcriptions")).timeout(Duration.ofSeconds(60)).header("Content-Type","multipart/form-data; boundary="+boundary).header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if(!config.apiKey().isBlank())request.header("Authorization","Bearer "+config.apiKey());
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        var future=client.sendAsync(request.build(),info->new BoundedBody());
        try{
            var response=future.get(65,TimeUnit.SECONDS);
                if(response.statusCode()<200||response.statusCode()>=300)throw new ProviderRequestException(response.statusCode(),"ASR_HTTP_REJECTED");
                byte[] bytes=response.body();
                var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);var text=json.path("text");
                if(!text.isTextual()||text.textValue().isBlank()||text.textValue().length()>16384)throw new IllegalArgumentException("ASR_TRANSCRIPT_INVALID");
                String reported=json.path("model").asText("");if(!config.apiKey().isBlank()&&reported.contains(config.apiKey()))reported="";
                return new Transcript(text.textValue(),ModelResponse.safeModelName(config.model()),ModelResponse.safeModelName(reported));
        }catch(TimeoutException timeout){future.cancel(true);throw new java.net.http.HttpTimeoutException("ASR_PROVIDER_TIMEOUT");}
        catch(ExecutionException failure){if(failure.getCause() instanceof java.net.http.HttpTimeoutException)throw new java.net.http.HttpTimeoutException("ASR_PROVIDER_TIMEOUT");throw new IllegalArgumentException("ASR_RESPONSE_FAILED");}
        finally{future.cancel(true);client.shutdownNow();}
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]>{
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> chunks){for(var chunk:chunks){if(chunk.remaining()>262144-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("ASR_RESPONSE_TOO_LARGE"));return;}byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);}subscription.request(1);}
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private static void field(ByteArrayOutputStream out,String boundary,String key,String value){out.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));}
}
