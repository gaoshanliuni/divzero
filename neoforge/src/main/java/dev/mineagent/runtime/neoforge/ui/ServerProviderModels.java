package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.worker.provider.ProviderModelCatalog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;
import java.util.concurrent.*;

/** Game-thread cache, fetched off-thread; never serialize its credential identity. */
public final class ServerProviderModels {
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final ExecutorService IO=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"mineagent-provider-model-list");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final class State { final String identity;final UUID epoch=UUID.randomUUID();String status="LOADING",error="";int http;long started=System.currentTimeMillis(),finished;List<String> models=List.of();State(String id){identity=id;} }
    private ServerProviderModels(){}
    private static boolean allowed(ServerPlayer p){return !(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)&&MineAgentRuntimeServices.permissions(p.level().getServer()).allowed(p.getUUID(),p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.MANAGE_PROVIDERS);}
    private static String identity(String url,String key){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((url.length()+":"+url+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("MODELS_IDENTITY");}}
    public static void changed(ServerPlayer p,Set<String> keys){
        if(!keys.contains("provider.openai.apiKey")&&!keys.contains("provider.openai.baseUrl"))return;
        // Configuration is already persisted. A metadata lookup must not change its success receipt.
        try{view(p,true,0,"");}catch(RuntimeException unavailable){/* The explicit picker reports context/permission failures. */}
    }
    public static Map<String,Object> view(ServerPlayer p,boolean refresh,int offset,String query){
        var server=p.level().getServer();if(!server.isSameThread()||!allowed(p))throw new SecurityException("MODELS_FORBIDDEN");
        if(offset<0||query==null||query.length()>128)throw new IllegalArgumentException("MODELS_ARGUMENTS");
        var config=MineAgentRuntimeServices.config(server);var snapshot=config.providerSnapshot();String url=snapshot.values().getOrDefault("provider.openai.baseUrl",""),key=snapshot.values().getOrDefault("provider.openai.apiKey","");String id=identity(url,key);var state=STATES.get(server);
        if(state==null||!state.identity.equals(id)||refresh&&!state.status.equals("LOADING")&&System.currentTimeMillis()-state.started>=3000){
            state=new State(id);STATES.put(server,state);var flight=state;
            if(url.isBlank()||key.isBlank()){state.status="NOT_CONFIGURED";state.error=url.isBlank()?"MODELS_URL_REQUIRED":"MODELS_KEY_REQUIRED";}
            else try{IO.execute(()->{var result=ProviderModelCatalog.fetch(url,key);server.execute(()->{if(STATES.get(server)!=flight)return;var latest=config.providerSnapshot();if(!identity(latest.values().getOrDefault("provider.openai.baseUrl",""),latest.values().getOrDefault("provider.openai.apiKey","")).equals(id)){STATES.remove(server);return;}flight.models=result.models();flight.error=result.error();flight.http=result.httpStatus();flight.status=result.error().isEmpty()?"READY":"ERROR";flight.finished=System.currentTimeMillis();});});}
            catch(RejectedExecutionException busy){state.status="ERROR";state.error="MODELS_BUSY";}
        }
        String filter=query.toLowerCase(Locale.ROOT);var rows=state.models.stream().filter(n->n.toLowerCase(Locale.ROOT).contains(filter)).toList();var result=new LinkedHashMap<String,Object>();
        result.put("status",state.status);result.put("error",state.error);result.put("httpStatus",state.http);result.put("catalog",state.epoch);result.put("models",rows.stream().skip(offset).limit(48).toList());result.put("total",rows.size());result.put("offset",offset);result.put("nextOffset",(long)offset+48<rows.size()?offset+48:-1);result.put("selected",snapshot.values().getOrDefault("provider.openai.model",""));result.put("selectedInCatalog",state.models.contains(snapshot.values().getOrDefault("provider.openai.model","")));result.put("revision",snapshot.revision());result.put("baseUrl",dev.mineagent.runtime.core.config.WebSettingsCatalog.safeUrl(url)?url:"");result.put("fetchedAt",state.finished);return result;
    }
}
