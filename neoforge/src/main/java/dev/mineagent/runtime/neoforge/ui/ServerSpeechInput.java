package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.network.SpeechInputPayloads;
import dev.mineagent.runtime.core.config.SpeechProviderConfig;
import dev.mineagent.runtime.core.conversation.*;
import dev.mineagent.runtime.client.audio.VoiceChunkAssembler;
import dev.mineagent.runtime.api.permission.PermissionAction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated bounded upload -> one ASR request -> private result. Never sends chat itself. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ServerSpeechInput implements AutoCloseable {
    private static final Map<MinecraftServer,ServerSpeechInput> LIVE=new IdentityHashMap<>();
    private final MinecraftServer server;private final SpeechInputStore store;private final Map<UUID,Upload> uploads=new LinkedHashMap<>();private boolean closed;
    private static final class Upload{
        final SpeechInputStore.Job job;final ServerPlayer viewer;final ConversationFocusRegistry.Focus focus;final SpeechProviderConfig config;final AtomicBoolean permit=new AtomicBoolean(true);final long deadline=System.currentTimeMillis()+120000;
        final VoiceChunkAssembler assembler=new VoiceChunkAssembler(SpeechWav.MAX_BYTES,40,java.time.Clock.systemUTC());boolean dispatched;
        Upload(SpeechInputStore.Job job,ServerPlayer viewer,ConversationFocusRegistry.Focus focus,SpeechProviderConfig config){this.job=job;this.viewer=viewer;this.focus=focus;this.config=config;}
        boolean current(){return permit.get()&&focus.current()&&System.currentTimeMillis()<deadline;}
    }
    private ServerSpeechInput(MinecraftServer server)throws Exception{this.server=server;store=new SpeechInputStore(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server),java.time.Clock.systemUTC());}
    private static synchronized ServerSpeechInput get(MinecraftServer server){return LIVE.computeIfAbsent(server,s->{try{return new ServerSpeechInput(s);}catch(Exception e){throw new IllegalStateException("ASR_STORE_UNAVAILABLE",e);}});}
    public static void handle(ServerPlayer viewer,SpeechInputPayloads.Command p){
        var server=viewer.level().getServer();try{
            if(!server.isSameThread()||viewer instanceof MineAgentPlayer||!p.world().equals(MineAgentRuntimeServices.worldId(server))||!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.CHAT))throw new SecurityException("ASR_FORBIDDEN");
            get(server).accept(viewer,p);
        }catch(Exception failure){String code=failure.getMessage();if(code==null||!code.matches("ASR_[A-Z0-9_]{1,70}"))code="ASR_REQUEST_REJECTED";send(viewer,new SpeechInputPayloads.Reply(p.operation(),p.world(),p.agent(),p.conversation(),p.context(),"FAILED",code,"",""));}
    }
    private void accept(ServerPlayer viewer,SpeechInputPayloads.Command p)throws Exception{
        if(closed)throw new IllegalStateException("ASR_RUNTIME_CLOSED");var conversations=ServerConversations.get(server);conversations.store().get(viewer.getUUID(),p.agent(),p.conversation());
        if(p.action().equals("begin")){
            var existing=store.find(viewer.getUUID(),p.agent(),p.conversation(),p.operation());if(existing.isPresent()){
                var old=existing.get();if(!old.scope().context().equals(p.context())||old.configRevision()!=p.revision()||!old.audioHash().equals(p.sha256())||old.audioBytes()!=p.audioBytes())throw new IllegalArgumentException("ASR_OPERATION_REUSED");reply(viewer,old,true);return;
            }
            var focus=conversations.focused(viewer).filter(f->f.agentId().equals(p.agent())&&f.conversationId().equals(p.conversation())&&f.contextId().equals(p.context())).orElseThrow(()->new SecurityException("ASR_FOCUS_CHANGED"));
            if(!conversations.store().get(viewer.getUUID(),p.agent(),p.conversation()).state().equals("ACTIVE"))throw new IllegalStateException("ASR_CONVERSATION_READ_ONLY");
            var config=MineAgentRuntimeServices.config(server).speechProvider();if(!config.configured())throw new IllegalStateException("ASR_NOT_CONFIGURED");if(config.revision()!=p.revision())throw new IllegalStateException("ASR_CONFIG_CHANGED");
            if(uploads.size()>=8||uploads.values().stream().anyMatch(u->u.permit.get()&&u.focus==focus))throw new IllegalStateException("ASR_BUSY");
            if(p.audioBytes()<3244||p.count()!=(p.audioBytes()+SpeechWav.CHUNK_BYTES-1)/SpeechWav.CHUNK_BYTES)throw new IllegalArgumentException("ASR_UPLOAD_SIZE");
            var result=store.begin(p.operation(),new SpeechInputStore.Scope(viewer.getUUID(),p.agent(),p.conversation(),p.context()),config,p.sha256(),p.audioBytes());
            if(result.fresh())uploads.put(p.operation(),new Upload(result.job(),viewer,focus,config));
            reply(viewer,result.job(),true);return;
        }
        var job=store.get(viewer.getUUID(),p.agent(),p.conversation(),p.operation());if(!job.scope().context().equals(p.context()))throw new SecurityException("ASR_CONTEXT_CHANGED");
        if(p.action().equals("status")){reply(viewer,job);return;}
        if(p.action().equals("cancel")||p.action().equals("discard")){
            var upload=uploads.get(p.operation());if(upload!=null){upload.permit.set(false);upload.assembler.clear();if(!upload.dispatched)uploads.remove(p.operation());}
            store.outcome(p.operation(),p.action().equals("discard")?"DISCARDED":"CANCELLED","","","USER_CANCELLED");reply(viewer,store.get(viewer.getUUID(),p.agent(),p.conversation(),p.operation()));return;
        }
        var upload=uploads.get(p.operation());if(upload==null||upload.dispatched){reply(viewer,job);return;}
        if(!upload.current()||upload.viewer!=viewer){fail(upload,"CANCELLED","ASR_FOCUS_CHANGED");return;}
        if(!job.audioHash().equals(p.sha256())||job.audioBytes()!=p.audioBytes()||p.count()!=(p.audioBytes()+SpeechWav.CHUNK_BYTES-1)/SpeechWav.CHUNK_BYTES||p.data().length<1){fail(upload,"FAILED","ASR_UPLOAD_CHANGED");return;}
        try{var complete=upload.assembler.accept(p.sha256(),p.index(),p.count(),p.data());if(complete.isEmpty())return;var bytes=complete.get();if(bytes.length!=job.audioBytes())throw new IllegalArgumentException();SpeechWav.validate(bytes);
            if(!store.outcome(job.operation(),"TRANSCRIBING","","",""))return;upload.dispatched=true;reply(viewer,store.get(viewer.getUUID(),p.agent(),p.conversation(),p.operation()));
            var currentConfig=MineAgentRuntimeServices.config(server);java.util.concurrent.CompletableFuture<dev.mineagent.runtime.api.worker.WorkerEnvelope> future;
            try{future=MineAgentRuntimeServices.worker(server).transcribe(upload.config,job.operation(),bytes,()->upload.current()&&currentConfig.speechProvider().enabled());}
            catch(Exception failed){upload.dispatched=false;fail(upload,"FAILED","ASR_DISPATCH_FAILED");return;}
            future.whenComplete((response,error)->server.execute(()->{
                if(closed)return;try{
                    if(!upload.current()||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer||!MineAgentRuntimeServices.config(server).speechProvider().enabled()){fail(upload,"CANCELLED","ASR_FOCUS_CHANGED");return;}
                    if(error!=null||response==null||!response.type().equals("asr.result")){String code=response==null?"ASR_TRANSPORT_FAILED":String.valueOf(response.payload().getOrDefault("message","ASR_REQUEST_FAILED"));fail(upload,"FAILED",code.matches("ASR_[A-Z0-9_]{1,70}")?code:"ASR_REQUEST_FAILED");return;}
                    String text=String.valueOf(response.payload().getOrDefault("text",""));if(text.isBlank()||text.length()>16384)throw new IllegalArgumentException();
                    store.outcome(job.operation(),"READY",text,String.valueOf(response.payload().getOrDefault("responseModel","")),"");reply(viewer,store.get(viewer.getUUID(),p.agent(),p.conversation(),job.operation()));
                }catch(Exception invalid){fail(upload,"FAILED","ASR_RESULT_REJECTED");}finally{uploads.remove(job.operation(),upload);}
            }));
        }catch(Exception invalid){fail(upload,"FAILED","ASR_AUDIO_INVALID");}
    }
    private void fail(Upload upload,String state,String error){upload.permit.set(false);upload.assembler.clear();try{store.outcome(upload.job.operation(),state,"","",error);if(server.getPlayerList().getPlayer(upload.viewer.getUUID())==upload.viewer)reply(upload.viewer,store.get(upload.job.scope().viewer(),upload.job.scope().agent(),upload.job.scope().conversation(),upload.job.operation()));}catch(Exception ignored){}if(!upload.dispatched)uploads.remove(upload.job.operation(),upload);}
    private void reply(ServerPlayer viewer,SpeechInputStore.Job job){reply(viewer,job,false);}
    private void reply(ServerPlayer viewer,SpeechInputStore.Job job,boolean mayUpload){send(viewer,new SpeechInputPayloads.Reply(job.operation(),MineAgentRuntimeServices.worldId(server),job.scope().agent(),job.scope().conversation(),job.scope().context(),job.state(),job.errorCode(),job.state().equals("READY")?job.text():"",dev.mineagent.runtime.api.model.ModelResponse.safeModelName(job.requestedModel()),mayUpload));}
    private static void send(ServerPlayer viewer,SpeechInputPayloads.Reply reply){net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer,reply);}
    public static void verifySource(MinecraftServer server,UUID viewer,UUID agent,UUID conversation,UUID operation)throws Exception{var job=get(server).store.get(viewer,agent,conversation,operation);if(!job.state().equals("READY"))throw new IllegalStateException("ASR_SOURCE_NOT_READY");}
    public static void discard(MinecraftServer server,UUID viewer,UUID agent,UUID conversation,UUID operation)throws Exception{var runtime=get(server);runtime.store.get(viewer,agent,conversation,operation);runtime.store.outcome(operation,"DISCARDED","","","USER_DISCARDED");var upload=runtime.uploads.get(operation);if(upload!=null){upload.permit.set(false);upload.assembler.clear();if(!upload.dispatched)runtime.uploads.remove(operation,upload);}}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){ServerSpeechInput runtime;synchronized(ServerSpeechInput.class){runtime=LIVE.get(event.getServer());}if(runtime==null||runtime.closed||event.getServer().getTickCount()%20!=0)return;boolean enabled=MineAgentRuntimeServices.config(event.getServer()).speechProvider().enabled();for(var upload:List.copyOf(runtime.uploads.values()))if(upload.permit.get()&&(!upload.current()||!enabled))runtime.fail(upload,"CANCELLED","ASR_REQUEST_EXPIRED");}
    public static synchronized void stop(MinecraftServer server){var runtime=LIVE.remove(server);if(runtime!=null)runtime.close();}
    @Override public void close(){if(closed)return;closed=true;for(var upload:uploads.values()){upload.permit.set(false);upload.assembler.clear();try{store.outcome(upload.job.operation(),"INTERRUPTED","","","SERVER_STOPPED");}catch(Exception ignored){}}uploads.clear();try{store.close();}catch(Exception ignored){}}
}
