package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.api.model.ModelRequest;
import dev.mineagent.runtime.api.ui.UiCapture;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import net.minecraft.server.MinecraftServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in development evidence, never changes the model request/response or fabricates visual success. */
public final class CaptureModelSmokeEvidence {
    private static final AtomicInteger sequence=new AtomicInteger();
    private CaptureModelSmokeEvidence(){}
    public static int request(MinecraftServer server,UUID task,ModelRequest request,String configuredModel){
        if(!Boolean.getBoolean("mineagent.captureAgentSmoke")||request.images().isEmpty())return 0;
        try{
            int index=sequence.incrementAndGet();Path root=server.getServerDirectory().resolve("capture-model-evidence").resolve(task.toString());Files.createDirectories(root);
            var image=request.images().getFirst();String sha=UiCapture.sha256(image.bytes());Files.write(root.resolve("image-"+sha+".png"),image.bytes());
            Files.writeString(root.resolve("request-"+index+".json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("taskId",task,"configuredModel",configuredModel,
                    "imageSha256",sha,"bytes",image.bytes().length,"width",image.width(),"height",image.height(),"prompt",request.prompt(),"mode","ACTUAL_CAPTURE_RPC_TO_WORKER_IMAGE_INPUT")));
            return index;
        }catch(Exception failure){throw new IllegalStateException("IMAGE_EVIDENCE_FAILED",failure);}
    }
    public static void response(MinecraftServer server,UUID task,int index,WorkerEnvelope response){
        if(index==0)return;
        try{Files.writeString(server.getServerDirectory().resolve("capture-model-evidence").resolve(task.toString()).resolve("response-"+index+".json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response));}
        catch(Exception failure){throw new IllegalStateException("IMAGE_EVIDENCE_FAILED",failure);}
    }
}
