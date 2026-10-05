package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-world model checkpoints: bounded asynchronous load, immutable history and atomic promotion. */
public final class LegacyModelStore {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DivZero-policy-io");t.setDaemon(true);return t;});
    private static volatile UUID world;
    private static LegacyPolicy serving;
    private static CompletableFuture<LegacyPolicy> loading;
    private static Path root;
    private static String status="NOT_LOADED";
    private LegacyModelStore(){}
    public static void initialize(){
        UUID current=NativeRuntime.data().identity();if(current.equals(world))return;
        world=current;serving=null;status="LOADING";
        root=MinecraftServer.getServer().worldServerForDimension(0).getSaveHandler().getWorldDirectory().toPath().resolve("data/divzero-policy");
        final Path file=root.resolve("active.json");
        loading=CompletableFuture.supplyAsync(()->{
            try{
                if(!Files.exists(file))return LegacyPolicy.get();
                if(Files.size(file)>1024*1024)throw new IllegalStateException("MODEL_CHECKPOINT_SIZE");
                JsonObject data=new JsonParser().parse(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getAsJsonObject();
                if(data.get("schema").getAsInt()!=1)throw new IllegalStateException("MODEL_CHECKPOINT_SCHEMA");
                LegacyPolicy policy=LegacyPolicy.parse(data.get("model").getAsString());
                if(!policy.hash().equals(data.get("sha256").getAsString()))throw new IllegalStateException("MODEL_CHECKPOINT_HASH");return policy;
            }catch(Exception error){throw new CompletionException(error);}
        },IO);
    }
    public static LegacyPolicy current(){
        initialize();if(serving!=null)return serving;
        if(!loading.isDone())throw new IllegalStateException("神经网络模型正在载入，请稍后再试");
        try{serving=loading.join();status=serving.hash().equals(LegacyPolicy.get().hash())?"PRETRAINED":"VALIDATED_SELF_PLAY";return serving;}
        catch(CompletionException error){status="CHECKPOINT_FAILED";throw new IllegalStateException("模型存档校验失败，已保留原文件");}
    }
    public static String status(){initialize();if(loading.isDone()&&serving==null)try{current();}catch(IllegalStateException ignored){}return status;}
    public static CompletableFuture<Boolean> promote(LegacyPolicy policy,JsonObject receipt,AtomicBoolean cancelled){
        final Path folder=root;final UUID expected=world;
        JsonObject checkpoint=new JsonObject();checkpoint.addProperty("schema",1);checkpoint.addProperty("sha256",policy.hash());checkpoint.addProperty("model",policy.json());checkpoint.add("validation",new JsonParser().parse(receipt.toString()));
        byte[] bytes=new GsonBuilder().setPrettyPrinting().create().toJson(checkpoint).getBytes(StandardCharsets.UTF_8);
        return CompletableFuture.supplyAsync(()->{
            try{
                Files.createDirectories(folder);Path history=folder.resolve(policy.hash()+".json");
                if(!Files.exists(history))Files.write(history,bytes,StandardOpenOption.CREATE_NEW);
                Path temporary=Files.createTempFile(folder,"policy-",".pending");
                try{Files.write(temporary,bytes);if(cancelled.get()||!expected.equals(world))return false;Files.move(temporary,folder.resolve("active.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return true;}
                finally{Files.deleteIfExists(temporary);}
            }catch(Exception error){throw new CompletionException(error);}
        },IO);
    }
    public static void accept(LegacyPolicy policy){serving=policy;status="VALIDATED_SELF_PLAY";}
    public static CompletableFuture<Void> receipt(String name,JsonObject value){
        final Path directory=root;byte[] bytes=new GsonBuilder().setPrettyPrinting().create().toJson(value).getBytes(StandardCharsets.UTF_8);
        return CompletableFuture.runAsync(()->{try{Files.createDirectories(directory);Files.write(directory.resolve(name+".json"),bytes,StandardOpenOption.CREATE_NEW);}catch(Exception error){throw new CompletionException(error);}},IO);
    }
    public static void stop(){world=null;serving=null;loading=null;status="NOT_LOADED";}
}
