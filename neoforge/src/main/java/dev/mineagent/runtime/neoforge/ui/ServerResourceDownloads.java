package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.resources.ResourceLibrary;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Pull-based, owner-bound file transfer. No shell, executable launch or client filesystem paths. */
public final class ServerResourceDownloads {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private static final Map<UUID,Transfer> LIVE=new ConcurrentHashMap<>();
    private static final class Transfer {
        final ServerPlayer viewer;final UUID agent,world;final ResourceLibrary.Resource resource;final BooleanSupplier current;
        final CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();long offset;boolean reading;
        Transfer(ServerPlayer p,UUID agent,ResourceLibrary.Resource resource,BooleanSupplier current){viewer=p;this.agent=agent;world=resource.world();this.resource=resource;this.current=current;}
        boolean valid(){var server=viewer.level().getServer();return current.getAsBoolean()&&server.getPlayerList().getPlayer(viewer.getUUID())==viewer&&MineAgentRuntimeServices.worldId(server).equals(world)&&ServerChatAccess.canContinue(viewer,agent);}
    }
    public static CompletableFuture<Map<String,Object>> download(ServerPlayer p,UUID agent,ResourceLibrary.Resource resource,BooleanSupplier current){
        var server=p.level().getServer();var transfer=new Transfer(p,agent,resource,current);UUID id=UUID.randomUUID();
        server.execute(()->{try{if(!resource.owner().equals(p.getUUID())||!transfer.valid())throw new SecurityException("RESOURCE_DOWNLOAD_SCOPE");if(LIVE.values().stream().filter(t->t.viewer==p).count()>=2)throw new IllegalStateException("RESOURCE_DOWNLOAD_BUSY");LIVE.put(id,transfer);send(p,id,Map.of("kind","download_begin","name",resource.name(),"total",resource.bytes(),"resource",resource.id().toString()));}catch(Exception e){transfer.result.completeExceptionally(e);}});
        transfer.result.orTimeout(10,TimeUnit.MINUTES).whenComplete((r,e)->LIVE.remove(id,transfer));return transfer.result;
    }
    public static boolean reply(ServerPlayer p,UiPayloads.Command packet){
        var transfer=LIVE.get(packet.requestId());if(transfer==null)return false;if(transfer.viewer!=p)return true;
        try{
            if(!transfer.valid())throw new IllegalStateException("RESOURCE_DOWNLOAD_CONTEXT_CHANGED");var data=JSON.readTree(packet.json());if(data.has("error"))throw new IllegalStateException("RESOURCE_DOWNLOAD_CLIENT_FAILED: "+data.path("error").asText());
            if(!data.path("offset").isIntegralNumber()||data.path("offset").longValue()!=transfer.offset||transfer.reading)throw new IllegalArgumentException("RESOURCE_DOWNLOAD_SEQUENCE");
            if(transfer.offset==transfer.resource.bytes()&&data.path("saved").asBoolean()){
                var result=new LinkedHashMap<String,Object>(ResourceLibrary.view(transfer.resource));result.put("status","DOWNLOADED");result.put("clientSaved",true);result.put("executed",false);transfer.result.complete(result);return true;
            }
            long start=transfer.offset;var server=p.level().getServer();Path root=server.getServerDirectory().resolve("mineagent-runtime-data");transfer.reading=true;
            CompletableFuture.supplyAsync(()->{try{var library=new ResourceLibrary(root,transfer.world,p.getUUID());int length=(int)Math.min(48*1024,transfer.resource.bytes()-start);byte[] bytes=new byte[length];try(var channel=FileChannel.open(library.data(transfer.resource.id()),StandardOpenOption.READ)){channel.position(start);var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())if(channel.read(buffer)<0)throw new java.io.EOFException("RESOURCE_DOWNLOAD_SOURCE_CHANGED");}return bytes;}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((bytes,error)->server.execute(()->{
                transfer.reading=false;if(transfer.result.isDone())return;try{if(error!=null)throw new CompletionException(error);if(!transfer.valid())throw new IllegalStateException("RESOURCE_DOWNLOAD_CONTEXT_CHANGED");transfer.offset+=bytes.length;send(p,packet.requestId(),Map.of("kind","download_chunk","offset",start,"total",transfer.resource.bytes(),"data",Base64.getEncoder().encodeToString(bytes)));}catch(Exception e){transfer.result.completeExceptionally(e);}
            }));
        }catch(Exception e){transfer.result.completeExceptionally(e);}return true;
    }
    private static void send(ServerPlayer p,UUID id,Map<String,Object> data)throws Exception{net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new UiPayloads.Event(id,"resourceView",JSON.writeValueAsString(data)));}
    private ServerResourceDownloads(){}
}
