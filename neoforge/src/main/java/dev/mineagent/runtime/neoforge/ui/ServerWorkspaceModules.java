package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.memory.MemoryKind;
import dev.mineagent.runtime.api.media.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.memory.DialogueMemoryStore;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Built-in workspace modules. Transport trust is checked separately from each domain's permissions. */
final class ServerWorkspaceModules implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MinecraftServer server;
    private final ExecutorService io=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("workspace-data").factory());
    private final ServerWorkspaceBackups backups;
    private List<Map<String,Object>> mods=List.of();
    private CompletableFuture<Map<String,Object>> indexing;
    ServerWorkspaceModules(MinecraftServer server){this.server=server;backups=new ServerWorkspaceBackups(server);}
    static boolean operator(ServerPlayer viewer){return viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);}
    static void require(ServerPlayer viewer,PermissionAction permission){if(!MineAgentRuntimeServices.permissions(viewer.level().getServer()).allowed(viewer.getUUID(),operator(viewer),permission))throw new SecurityException("WORKSPACE_PERMISSION_DENIED");}
    CompletableFuture<Map<String,Object>> handle(ServerPlayer viewer,UUID operation,Map<String,String> args,boolean write,BooleanSupplier permit)throws Exception{
        if(!permit.getAsBoolean()||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer)throw new SecurityException("WORKSPACE_CONTEXT_CHANGED");
        return switch(args.getOrDefault("module","")){
            case "memory"->memory(viewer,args,write,permit);
            case "contents"->ServerCreatedContents.handle(viewer,operation,args,write,permit);
            case "media"->CompletableFuture.completedFuture(media(viewer,args,write));
            case "backups"->{require(viewer,PermissionAction.RESTORE_BACKUP);yield CompletableFuture.completedFuture(backups.handle(viewer,operation,args,write,permit));}
            case "mods"->mods(viewer,args,write,permit);
            case "diagnostics"->{if(write||!operator(viewer))throw new SecurityException("WORKSPACE_PERMISSION_DENIED");keys(args,"module","offset");int offset=offset(args);var runtime=Runtime.getRuntime();yield CompletableFuture.completedFuture(Map.of("usedMemoryBytes",runtime.totalMemory()-runtime.freeMemory(),"maxMemoryBytes",runtime.maxMemory(),"threads",java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount(),"events",page(MineAgentRuntimeServices.audit(server).recent(offset+9),offset)));}
            default->throw new IllegalArgumentException("WORKSPACE_MODULE_UNKNOWN");
        };
    }
    private CompletableFuture<Map<String,Object>> memory(ServerPlayer viewer,Map<String,String> args,boolean write,BooleanSupplier permit)throws Exception{
        require(viewer,PermissionAction.CHAT);String scope=args.getOrDefault("scope","dialogue");UUID owner=viewer.getUUID(),world=MineAgentRuntimeServices.worldId(server);
        if(scope.equals("legacy")){
            var service=MineAgentRuntimeServices.memories(server);boolean op=operator(viewer);
            if(!write){keys(args,"module","scope","offset","query");String query=query(args);return CompletableFuture.completedFuture(page(service.visibleTo(owner,op).stream().filter(e->(e.key()+" "+e.value()).toLowerCase(Locale.ROOT).contains(query)).toList(),offset(args)));}
            String action=args.getOrDefault("kind","");
            if(action.equals("save")){
                keys(args,"module","scope","kind","id","revision","type","topic","value");
                if(args.get("id").isBlank()){var type=MemoryKind.valueOf(args.get("type"));if(type==MemoryKind.WORLD_FACT&&!op)throw new SecurityException("WORKSPACE_PERMISSION_DENIED");var entry=service.create(owner,type,args.get("topic"),args.get("value"));return CompletableFuture.completedFuture(Map.of("status","APPLIED","entry",entry));}
                var id=UUID.fromString(args.get("id"));var entry=service.get(id).orElseThrow();var result=service.update(id,Long.parseLong(args.get("revision")),op||entry.ownerPlayerId().equals(owner),args.get("value"));if(!result.accepted())throw new IllegalStateException(result.errorCode());return CompletableFuture.completedFuture(Map.of("status","APPLIED"));
            }
            if(!action.equals("forget"))throw new IllegalArgumentException("MEMORY_ACTION");keys(args,"module","scope","kind","id","revision");var id=UUID.fromString(args.get("id"));var entry=service.get(id).orElseThrow();var result=service.delete(id,Long.parseLong(args.get("revision")),op||entry.ownerPlayerId().equals(owner));if(!result.accepted())throw new IllegalStateException(result.errorCode());return CompletableFuture.completedFuture(Map.of("status","APPLIED"));
        }
        if(!scope.equals("dialogue"))throw new IllegalArgumentException("MEMORY_SCOPE");UUID agent=UUID.fromString(args.get("agentId"));
        if(MineAgentRuntimeServices.bodies(server).definitions().stream().noneMatch(a->a.agentId().equals(agent)))throw new IllegalArgumentException("MEMORY_AGENT_UNAVAILABLE");
        if(!write)keys(args,"module","scope","agentId","offset","query");
        else if(args.get("kind").equals("save"))keys(args,"module","scope","agentId","kind","type","topic","value","ttlSeconds","revision");
        else if(args.get("kind").equals("forget"))keys(args,"module","scope","agentId","kind","id","revision");
        else throw new IllegalArgumentException("MEMORY_ACTION");
        var file=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");
        return CompletableFuture.supplyAsync(()->{try(var store=new DialogueMemoryStore(file,world,owner,agent,java.time.Clock.systemUTC())){
            server.submit(()->{if(!permit.getAsBoolean()||server.getPlayerList().getPlayer(owner)!=viewer)throw new SecurityException("WORKSPACE_CONTEXT_CHANGED");require(viewer,PermissionAction.CHAT);}).join();
            if(!write){int offset=offset(args);var inspected=store.inspect(args.get("query"),offset);var result=new LinkedHashMap<String,Object>(page((List<?>)inspected.get("entries"),0));int count=((List<?>)result.get("items")).size(),total=(Integer)inspected.get("total");result.put("total",total);result.put("nextOffset",offset+count<total?offset+count:-1);return result;}
            if(args.get("kind").equals("forget"))return Map.<String,Object>of("status","APPLIED","entry",store.forget(UUID.fromString(args.get("id")),Long.parseLong(args.get("revision"))));
            return Map.<String,Object>of("status","APPLIED","entry",store.remember(args.get("type"),args.get("topic"),args.get("value"),Long.parseLong(args.get("ttlSeconds")),"player:workspace",List.of(),Long.parseLong(args.get("revision"))));
        }catch(Exception failure){throw new CompletionException(failure);}},io);
    }
    private Map<String,Object> media(ServerPlayer viewer,Map<String,String> args,boolean write)throws Exception{
        var service=MineAgentRuntimeServices.media(server);service.setExplicitlyAllowedHosts(MineAgentRuntimeServices.mediaAllowedHosts(server));
        if(!write){keys(args,"module","offset","query");String q=query(args);return page(service.all().stream().filter(e->(e.title()+" "+e.sourceUrl()).toLowerCase(Locale.ROOT).contains(q)).map(e->{var item=JSON.convertValue(e,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});item.put("runtime",MineAgentRuntimeServices.mediaCoordinator(server).state(e.mediaId()));return item;}).toList(),offset(args));}
        String action=args.get("kind");boolean manager=MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),operator(viewer),PermissionAction.CONTROL_PUBLIC_MEDIA);
        if(action.equals("create")){keys(args,"module","kind","type","title","url");if(!manager)throw new SecurityException("WORKSPACE_PERMISSION_DENIED");var entry=service.add(viewer.getUUID(),MediaKind.valueOf(args.get("type")),args.get("title"),args.get("url"));broadcastMedia();return Map.of("status","APPLIED","entry",entry);}
        keys(args,"module","kind","id","revision","positionMillis");UUID id=UUID.fromString(args.get("id"));var entry=service.get(id).orElseThrow();long revision=Long.parseLong(args.get("revision"));boolean authorized=manager||entry.ownerPlayerId().equals(viewer.getUUID());
        long position=Long.parseLong(args.get("positionMillis"));if(position<0)throw new IllegalArgumentException("MEDIA_POSITION_INVALID");
        if(action.equals("pause"))position=MineAgentRuntimeServices.mediaCoordinator(server).position(entry);
        if(action.equals("play")&&entry.screenBinding().isBlank())throw new IllegalStateException("MEDIA_BINDING_REQUIRED");
        var result=switch(action){
            case "bind_here"->service.bind(id,revision,authorized,new MediaScreenBinding(viewer.level().dimension().identifier().toString(),viewer.blockPosition().getX(),viewer.blockPosition().getY(),viewer.blockPosition().getZ()).encoded());
            case "play","pause"->service.updatePlayback(id,revision,authorized,action.equals("play"),position,entry.playbackRate());
            default->throw new IllegalArgumentException("MEDIA_ACTION");
        };
        if(!result.accepted())throw new IllegalStateException(result.errorCode());
        if(result.entry().playing())MineAgentRuntimeServices.mediaCoordinator(server).start(result.entry());else MineAgentRuntimeServices.mediaCoordinator(server).stop(id);
        broadcastMedia();return Map.of("status","APPLIED","entry",result.entry());
    }
    private void broadcastMedia(){for(var player:server.getPlayerList().getPlayers())if(!(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))MineAgentNetwork.sendMediaStateTo(player);}
    private CompletableFuture<Map<String,Object>> mods(ServerPlayer viewer,Map<String,String> args,boolean write,BooleanSupplier permit){
        require(viewer,PermissionAction.CHAT);
        if(!write){keys(args,"module","offset","query");String q=query(args);return CompletableFuture.completedFuture(page(mods.stream().filter(e->e.toString().toLowerCase(Locale.ROOT).contains(q)).toList(),offset(args)));}
        keys(args,"module","kind");if(!"index".equals(args.get("kind")))throw new IllegalArgumentException("MOD_INDEX_ACTION");
        if(indexing!=null&&!indexing.isDone())return CompletableFuture.completedFuture(Map.of("status","INDEXING"));
        indexing=new CompletableFuture<>();var current=indexing;
        MineAgentRuntimeServices.worker(server).indexMods(server.getServerDirectory().resolve("mods")).whenComplete((results,error)->server.execute(()->{
            if(error!=null){current.completeExceptionally(error);return;}
            var out=new ArrayList<Map<String,Object>>();for(var result:results)if(result.type().equals("mod.index.result")){var values=new LinkedHashMap<String,Object>();for(String key:List.of("modId","displayName","version","sha256","classCount","sourceCount"))values.put(key,result.payload().getOrDefault(key,""));out.add(values);}mods=List.copyOf(out);current.complete(Map.of("status","APPLIED","total",mods.size()));
        }));return CompletableFuture.completedFuture(Map.of("status","INDEXING"));
    }
    Map<String,Object> indexState(){if(indexing==null)return Map.of("status","IDLE");if(!indexing.isDone())return Map.of("status","INDEXING");try{return indexing.join();}catch(Exception failure){return Map.of("status","FAILED","error","MOD_INDEX_FAILED");}}
    static int offset(Map<String,String> args){int value=Integer.parseInt(args.getOrDefault("offset","0"));if(value<0||value>1_000_000)throw new IllegalArgumentException("WORKSPACE_OFFSET");return value;}
    static String query(Map<String,String> args){String value=args.getOrDefault("query","");if(value.length()>128)throw new IllegalArgumentException("WORKSPACE_QUERY");return value.toLowerCase(Locale.ROOT);}
    static void keys(Map<String,String> args,String... allowed){if(!args.keySet().equals(Set.of(allowed)))throw new IllegalArgumentException("WORKSPACE_ARGUMENTS");}
    static Map<String,Object> page(List<?> entries,int offset){var items=new ArrayList<Object>();int bytes=0;for(var entry:entries.stream().skip(offset).limit(8).toList()){try{int size=JSON.writeValueAsBytes(entry).length;if(bytes+size>20000){if(items.isEmpty())throw new IllegalStateException("WORKSPACE_ENTRY_TOO_LARGE");break;}bytes+=size;items.add(entry);}catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException(error);}}return Map.of("items",items,"total",entries.size(),"nextOffset",offset+items.size()<entries.size()?offset+items.size():-1);}
    void tick(){backups.tick();}
    public void close(){backups.close();io.close();}
}
