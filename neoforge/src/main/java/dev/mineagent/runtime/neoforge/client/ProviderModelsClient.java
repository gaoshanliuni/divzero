package dev.mineagent.runtime.neoforge.client;
import dev.mineagent.runtime.neoforge.network.*;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;
import java.util.concurrent.*;
public final class ProviderModelsClient {
    private record Pending(Object connection,UUID world,UUID instance,CompletableFuture<String> result){}
    private static final Map<UUID,Pending> PENDING=new LinkedHashMap<>();
    private ProviderModelsClient(){}
    public static CompletableFuture<String> query(boolean refresh,int offset,String filter){
        var mc=Minecraft.getInstance();PENDING.values().removeIf(p->p.result.isDone());
        try{if(mc.getConnection()==null||PENDING.size()>=8)throw new IllegalStateException("MODELS_BUSY");var values=PanelSnapshotInbox.snapshot().values();var world=UUID.fromString(values.get("security.worldId"));var instance=UUID.fromString(values.get("security.configInstance"));var id=UUID.randomUUID();var future=new CompletableFuture<String>();PENDING.put(id,new Pending(mc.getConnection(),world,instance,future));ClientPacketDistributor.sendToServer(new ProviderModelsPayloads.Request(id,world,instance,refresh,offset,filter));return future.orTimeout(5,TimeUnit.SECONDS);}
        catch(Exception error){return CompletableFuture.failedFuture(new IllegalStateException("MODELS_CONTEXT_UNAVAILABLE"));}
    }
    public static void accept(ProviderModelsPayloads.Response r){var p=PENDING.remove(r.request());if(p==null)return;var mc=Minecraft.getInstance();var values=PanelSnapshotInbox.snapshot().values();if(mc.getConnection()!=p.connection||!r.world().equals(p.world)||!r.instance().equals(p.instance)||!r.world().toString().equals(values.get("security.worldId"))||!r.instance().toString().equals(values.get("security.configInstance"))){p.result.completeExceptionally(new IllegalStateException("MODELS_CONTEXT_CHANGED"));return;}p.result.complete(r.state());}
}
