package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.core.conversation.*;
import dev.mineagent.runtime.core.config.ConversationBudget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-world private conversation authority; no selected browser window owns an accepted generation. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ServerConversations implements AutoCloseable {
    private static final Map<MinecraftServer,ServerConversations> LIVE=new IdentityHashMap<>();
    private final MinecraftServer server;private final ConversationStore store;private final ConversationSummaryStore summaries;private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();private boolean closed;
    private final Map<UUID,Flight> flights=new LinkedHashMap<>();
    private static final class NativeReply{final ServerPlayer viewer;final UUID agent,conversation,assistant;final String name;int offset,thinkingOffset;long lastSent,thinkingLastSent;NativeReply(ServerPlayer viewer,UUID agent,UUID conversation,UUID assistant,String name){this.viewer=viewer;this.agent=agent;this.conversation=conversation;this.assistant=assistant;this.name=name;}}
    private record PendingNative(ServerPlayer viewer,UUID agent,UUID conversation,String text,long expires,boolean automatic,long accessRevision){}
    private final Map<UUID,PendingNative> pendingNative=new LinkedHashMap<>();
    private final Map<UUID,NativeReply> nativeReplies=new LinkedHashMap<>();
    private final Map<UUID,VoiceFlight> voices=new LinkedHashMap<>();
    private static final class VoiceFlight{final UUID op;final ServerPlayer viewer;final ConversationFocusRegistry.Focus focus;final AtomicBoolean permit=new AtomicBoolean(true);final long deadline=System.currentTimeMillis()+90000;VoiceFlight(UUID op,ServerPlayer viewer,ConversationFocusRegistry.Focus focus){this.op=op;this.viewer=viewer;this.focus=focus;}}
    private final ConversationFocusRegistry focus=new ConversationFocusRegistry(java.time.Clock.systemUTC());
    private static final class Flight {final UUID op,agent;final ServerPlayer viewer;final Object level;final java.util.List<java.util.Map<String,Object>> tools=new java.util.ArrayList<>();final StringBuilder reply=new StringBuilder();long rounds,calls;dev.mineagent.runtime.core.memory.PlayerPreferenceStore.Snapshot preferences;final AtomicBoolean permit=new AtomicBoolean(true);final StringBuilder buffer=new StringBuilder(),thinkingBuffer=new StringBuilder();int thinkingLength;boolean thinkingActive,thinkingDirty,roundThinking;volatile String error="";UUID summaryJob;int summaryCalls;Flight(UUID op,ServerPlayer viewer,UUID agent){this.op=op;this.agent=agent;this.viewer=viewer;this.level=viewer.level();}}
    private record Generation(UUID viewer,dev.mineagent.runtime.api.agent.AgentDefinition agent,UUID conversation,ConversationStore.Turn turn,dev.mineagent.runtime.core.agent.AgentPersonaService.Persona persona,String original,ConversationBudget policy,ConversationContext.Plan initial,dev.mineagent.runtime.core.memory.PlayerPreferenceStore.Snapshot preferences){int budget(){return Math.max(1024,policy.contextTokenBudget()-12288);}}
    private ServerConversations(MinecraftServer server)throws Exception{this.server=server;var path=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");store=ConversationStore.open(path,MineAgentRuntimeServices.worldId(server),java.time.Clock.systemUTC());summaries=ConversationSummaryStore.open(path,MineAgentRuntimeServices.worldId(server),java.time.Clock.systemUTC());}
    public static synchronized ServerConversations get(MinecraftServer server){return LIVE.computeIfAbsent(server,s->{try{return new ServerConversations(s);}catch(Exception e){throw new IllegalStateException("CONVERSATION_STORE_UNAVAILABLE",e);}});}
    public static synchronized void stop(MinecraftServer server){var r=LIVE.remove(server);if(r!=null)r.close();}
    public ConversationStore store(){return store;}
    public void disableNativeRoute(ServerPlayer viewer){thread();var selected=focused(viewer);if(selected.isPresent())focus.nativeInput(viewer.getUUID(),viewer,selected.get().contextId(),false);}
    public Optional<ConversationFocusRegistry.Focus> focused(ServerPlayer viewer){thread();return focus.current(viewer.getUUID(),viewer);}
    public void disconnect(ServerPlayer viewer){ServerChatAccess.disconnect(viewer);pendingNative.values().removeIf(v->v.viewer()==viewer);focus.disconnect(viewer.getUUID(),viewer);nativeReplies.values().removeIf(v->v.viewer==viewer);for(var f:List.copyOf(flights.values()))if(f.viewer==viewer)failGeneration(f,"CONVERSATION_DISCONNECTED");}
    public static String nativeError(Throwable error){String code=Objects.toString(error.getMessage(),"");return code.matches("(?:CONVERSATION|MODEL|SUMMARY|PROVIDER|SERVICE|STALE_CONVERSATION)_[A-Z_]{1,80}")?code:"CONVERSATION_REQUEST_FAILED";}
    /** An explicit @ is a choice of Agent, not implicit permission to continue some unrelated latest web conversation. */
    public void submitNative(ServerPlayer viewer,UUID agent,String text,boolean explicitMention)throws Exception{
        submitNative(viewer,agent,text,explicitMention,UUID.randomUUID());
    }
    public void submitNative(ServerPlayer viewer,UUID agent,String text,boolean explicitMention,UUID op)throws Exception{
        thread();if(!ServerChatAccess.admitOrAsk(viewer,agent,text,()->submitNative(viewer,agent,text,explicitMention,op)))return;
        if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),dev.mineagent.runtime.api.permission.PermissionAction.CHAT))throw new SecurityException("CONVERSATION_FORBIDDEN");
        var definition=requireAgent(agent);var selected=focused(viewer).filter(f->f.nativeInput()&&f.agentId().equals(agent));ConversationStore.Conversation c;
        c=selected.isPresent()?store.get(viewer.getUUID(),agent,selected.orElseThrow().conversationId()):null;
        if(c==null||!c.state().equals("ACTIVE")){if(!explicitMention)throw new IllegalStateException("CONVERSATION_SELECTION_REQUIRED");c=store.nativeConversation(viewer.getUUID(),agent);}
        submitNativeTo(viewer,agent,text,c,op);
    }
    private void submitNativeTo(ServerPlayer viewer,UUID agent,String text,ConversationStore.Conversation c,UUID op)throws Exception{
        thread();var definition=requireAgent(agent);if(!c.state().equals("ACTIVE"))throw new IllegalStateException("CONVERSATION_READ_ONLY");
        if(!c.activeOperation().isEmpty()){
            pendingNative.entrySet().removeIf(e->e.getValue().expires()<System.currentTimeMillis());if(pendingNative.values().stream().filter(v->v.viewer()==viewer&&v.automatic()).count()>=32)throw new IllegalStateException("CONVERSATION_QUEUE_FULL");UUID pending=UUID.randomUUID();pendingNative.put(pending,new PendingNative(viewer,agent,c.conversationId(),text,Long.MAX_VALUE,true,ServerChatAccess.policy(server,agent).revision()));
            var button=net.minecraft.network.chat.Component.literal("[打断并发送这条消息]").withStyle(style->style.withColor(net.minecraft.ChatFormatting.YELLOW).withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/ai interrupt "+agent+" pending:"+pending)));
            viewer.sendSystemMessage(net.minecraft.network.chat.Component.literal("["+definition.displayName()+"] 正在处理上一条消息，等待处理。 ").append(button).append(net.minecraft.network.chat.Component.literal(" [取消发送这条消息]").withStyle(style->style.withColor(net.minecraft.ChatFormatting.GRAY).withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/ai cancel_send "+pending)))));return;
        }
        if(nativeReplies.size()>=16)throw new IllegalStateException("CONVERSATION_GENERATION_BUDGET");
        var accepted=write(viewer,op,Map.of("kind","send","agentId",agent.toString(),"conversationId",c.conversationId().toString(),"expectedRevision",Long.toString(c.revision()),"text",text),true);if("true".equals(accepted.get("duplicate")))return;
        var context=store.context(viewer.getUUID(),agent,c.conversationId(),null).orElseThrow();if(!context.operationId().equals(op))throw new IllegalStateException("CONVERSATION_CONTEXT_CHANGED");
        nativeReplies.put(op,new NativeReply(viewer,agent,c.conversationId(),context.assistantMessageId(),definition.displayName()));
        viewer.sendSystemMessage(net.minecraft.network.chat.Component.literal("[你 → "+definition.displayName()+"] "+text));
        viewer.sendSystemMessage(net.minecraft.network.chat.Component.literal("["+definition.displayName()+"] 正在处理… ").append(net.minecraft.network.chat.Component.literal("[打断]").withStyle(style->style.withColor(net.minecraft.ChatFormatting.YELLOW).withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/ai interrupt "+agent+" active:"+op)))));
        pollNativeReplies();
    }
    public int interruptNative(ServerPlayer viewer,UUID agent,String next)throws Exception{
        thread();requireAgent(agent);UUID queuedConversation=null;
        if(next!=null&&next.startsWith("active:")){UUID target=UUID.fromString(next.substring(7));var active=nativeReplies.get(target);if(active==null||active.viewer!=viewer||!active.agent.equals(agent))throw new IllegalStateException("CONVERSATION_BUTTON_EXPIRED");if(!store.get(viewer.getUUID(),agent,active.conversation).activeOperation().equals(target.toString()))throw new IllegalStateException("CONVERSATION_BUTTON_EXPIRED");queuedConversation=active.conversation;next="";}
        if(next!=null&&next.startsWith("pending:")){var item=pendingNative.remove(UUID.fromString(next.substring(8)));if(item==null||item.viewer()!=viewer||!item.agent().equals(agent)||item.expires()<System.currentTimeMillis())throw new IllegalStateException("CONVERSATION_PENDING_EXPIRED");next=item.text();queuedConversation=item.conversation();}
        var selected=focused(viewer).filter(f->f.agentId().equals(agent));var targets=queuedConversation!=null?List.of(store.get(viewer.getUUID(),agent,queuedConversation)):selected.isPresent()?List.of(store.get(viewer.getUUID(),agent,selected.get().conversationId())):store.list(viewer.getUUID(),agent,"ACTIVE","",0,20).conversations();
        for(var c:targets){
            if(c.activeOperation().isEmpty())continue;UUID target=UUID.fromString(c.activeOperation());
            write(viewer,UUID.randomUUID(),Map.of("kind","cancel","agentId",agent.toString(),"conversationId",c.conversationId().toString(),"targetOperation",target.toString()),true);
            var f=flights.remove(target);if(f!=null)f.permit.set(false);
        }
        pollNativeReplies();viewer.sendSystemMessage(net.minecraft.network.chat.Component.literal("["+requireAgent(agent).displayName()+"] 已打断；已发生的游戏/电脑操作不会回滚。"));
        if(next!=null&&!next.isBlank()){if(queuedConversation!=null)submitNativeTo(viewer,agent,next,store.get(viewer.getUUID(),agent,queuedConversation),UUID.randomUUID());else submitNative(viewer,agent,next,true);}return 1;
    }
    public Map<String,Object> richMessage(ServerPlayer viewer,UUID agent,com.fasterxml.jackson.databind.JsonNode a){
        thread();String text=a.path("text").asText();if(text.isBlank()||text.length()>2048)throw new IllegalArgumentException("AGENT_CHAT_TEXT");
        String color=a.path("color").asText("#FFFFFF");if(!color.matches("#[a-fA-F0-9]{6}"))throw new IllegalArgumentException("AGENT_CHAT_COLOR");
        var buttons=a.path("buttons");if(!buttons.isMissingNode()&&(!buttons.isArray()||buttons.size()>8))throw new IllegalArgumentException("AGENT_CHAT_BUTTONS");
        var message=net.minecraft.network.chat.Component.literal("["+requireAgent(agent).displayName()+"] "+text).withStyle(style->style.withColor(Integer.parseInt(color.substring(1),16)));
        pendingNative.entrySet().removeIf(e->e.getValue().expires()<System.currentTimeMillis());
        for(var b:buttons){String label=b.path("label").asText(),value=b.path("value").asText(),action=b.path("action").asText();if(label.isBlank()||label.length()>80||value.isBlank()||value.length()>2048)throw new IllegalArgumentException("AGENT_CHAT_BUTTON");
            net.minecraft.network.chat.ClickEvent click;
            switch(action){case "copy"->click=new net.minecraft.network.chat.ClickEvent.CopyToClipboard(value);case "suggest"->click=new net.minecraft.network.chat.ClickEvent.SuggestCommand(value);case "confirm"->{if(pendingNative.size()>1024)throw new IllegalStateException("AGENT_CHAT_PENDING_BUDGET");UUID id=UUID.randomUUID();pendingNative.put(id,new PendingNative(viewer,agent,null,"玩家点击选择："+value,System.currentTimeMillis()+300000,false,0));click=new net.minecraft.network.chat.ClickEvent.RunCommand("/ai interrupt "+agent+" pending:"+id);}default->throw new IllegalArgumentException("AGENT_CHAT_BUTTON_ACTION");}
            message.append(net.minecraft.network.chat.Component.literal(" ["+label+"]").withStyle(style->style.withUnderlined(true).withClickEvent(click)));
        }
        viewer.sendSystemMessage(message);return Map.of("status","SENT","buttons",buttons.size());
    }
    public static void accessChanged(MinecraftServer server,UUID agent){var runtime=LIVE.get(server);if(runtime==null)return;for(var flight:List.copyOf(runtime.flights.values()))if(flight.agent.equals(agent)&&!ServerChatAccess.canContinue(flight.viewer,agent))runtime.failGeneration(flight,"CONVERSATION_RESPONSE_PERMISSION_REVOKED");runtime.pendingNative.values().removeIf(p->p.agent().equals(agent)&&!ServerChatAccess.canContinue(p.viewer(),agent));}
    public int cancelQueued(ServerPlayer viewer,UUID id){thread();var item=pendingNative.get(id);if(item==null||item.viewer()!=viewer||!item.automatic())return 0;pendingNative.remove(id);viewer.sendSystemMessage(net.minecraft.network.chat.Component.literal("已取消发送这条消息。"));return 1;}
    private void pollQueued(){for(var entry:List.copyOf(pendingNative.entrySet())){var item=entry.getValue();if(!item.automatic())continue;if(server.getPlayerList().getPlayer(item.viewer().getUUID())!=item.viewer()){pendingNative.remove(entry.getKey());continue;}try{var c=store.get(item.viewer().getUUID(),item.agent(),item.conversation());if(!c.state().equals("ACTIVE")){pendingNative.remove(entry.getKey());continue;}if(!c.activeOperation().isEmpty()||nativeReplies.values().stream().anyMatch(n->n.conversation.equals(c.conversationId())))continue;pendingNative.remove(entry.getKey());if(!ServerChatAccess.canContinue(item.viewer(),item.agent()))throw new IllegalStateException("CONVERSATION_RESPONSE_PERMISSION_CHANGED");ServerChatAccess.accepted(item.viewer(),item.agent(),item.text(),()->submitNativeTo(item.viewer(),item.agent(),item.text(),c,entry.getKey()));}catch(Exception error){pendingNative.remove(entry.getKey());item.viewer().sendSystemMessage(net.minecraft.network.chat.Component.literal("排队消息未发送："+nativeError(error)));}}}
    public int deleteNative(ServerPlayer viewer)throws Exception{UUID agent=ServerChatSettings.select(viewer,"");var c=focused(viewer).filter(f->f.agentId().equals(agent)).map(f->{try{return store.get(viewer.getUUID(),agent,f.conversationId());}catch(Exception e){throw new IllegalStateException(e);}}).orElseGet(()->{try{return store.nativeConversation(viewer.getUUID(),agent);}catch(Exception e){throw new IllegalStateException(e);}});write(viewer,UUID.randomUUID(),Map.of("kind","delete","agentId",agent.toString(),"conversationId",c.conversationId().toString(),"expectedRevision",Long.toString(c.revision())),true);viewer.sendSystemMessage(net.minecraft.network.chat.Component.literal("对话已删除；后续对话不会携带此会话上下文。"));return 1;}
    private net.minecraft.network.chat.Component colored(UUID agent,String text){return colored(agent,net.minecraft.network.chat.Component.literal(text));}
    private net.minecraft.network.chat.Component colored(UUID agent,net.minecraft.network.chat.MutableComponent message){String color=MineAgentRuntimeServices.config(server).snapshot().values().getOrDefault("agent."+agent+".chatColor","#FFFFFF");return message.withStyle(style->style.withColor(color.matches("#[A-Fa-f0-9]{6}")?Integer.parseInt(color.substring(1),16):0xFFFFFF));}
    private void pollNativeReplies(){
        for(var entry:List.copyOf(nativeReplies.entrySet())){var n=entry.getValue();if(server.getPlayerList().getPlayer(n.viewer.getUUID())!=n.viewer){nativeReplies.remove(entry.getKey());continue;}
            try{var usage=store.context(n.viewer.getUUID(),n.agent,n.conversation,n.assistant).orElseThrow();boolean done=!Set.of("PENDING","GENERATING").contains(usage.requestState());
                var thinking=store.thinking(n.viewer.getUUID(),n.agent,n.conversation,n.assistant);
                if(thinking.textLength()>n.thinkingOffset){
                    for(int part=0;part<4&&n.thinkingOffset<thinking.textLength();part++){
                        var chunk=store.thinkingChunk(n.viewer.getUUID(),n.agent,n.conversation,n.assistant,thinking.revision(),n.thinkingOffset,512);
                        int count=NativeChatSegments.nextLength(chunk.text(),240,done||!thinking.active()||System.currentTimeMillis()-n.thinkingLastSent>800);
                        if(count==0)break;n.viewer.sendSystemMessage(colored(n.agent,net.minecraft.network.chat.Component.translatableWithFallback("mineagent.chat.thinking","[%s][思考]%s",n.name,chunk.text().substring(0,count))));n.thinkingOffset+=count;n.thinkingLastSent=System.currentTimeMillis();
                    }
                    if(n.thinkingOffset<thinking.textLength())continue;
                }
                var message=store.message(n.viewer.getUUID(),n.agent,n.conversation,n.assistant);if(message.textLength()>n.offset){var chunk=store.chunk(n.viewer.getUUID(),n.agent,n.conversation,n.assistant,message.revision(),n.offset,512);String remaining=chunk.text();int count=NativeChatSegments.nextLength(remaining,240,done||System.currentTimeMillis()-n.lastSent>800);if(count>0){n.viewer.sendSystemMessage(colored(n.agent,"["+n.name+"] "+remaining.substring(0,count)));n.offset+=count;n.lastSent=System.currentTimeMillis();}}
                if(done&&n.offset>=message.textLength()){nativeReplies.remove(entry.getKey());if(!usage.requestState().equals("COMPLETE"))n.viewer.sendSystemMessage(net.minecraft.network.chat.Component.literal("["+n.name+"] 本次未完成："+usage.errorCode()+" ").append(net.minecraft.network.chat.Component.literal("[核对后继续]").withStyle(style->style.withColor(net.minecraft.ChatFormatting.YELLOW).withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/ai interrupt "+n.agent+" 请先inspect_operations和实际状态，核对上次失败结果；不要重放已执行或UNKNOWN的写操作，再继续未完成部分。")))));}
            }catch(Exception failure){nativeReplies.remove(entry.getKey());n.viewer.sendSystemMessage(net.minecraft.network.chat.Component.literal("["+n.name+"] "+nativeError(failure)));}
        }
    }
    public static void disconnectIfPresent(MinecraftServer server,ServerPlayer viewer){ServerConversations r;synchronized(ServerConversations.class){r=LIVE.get(server);}if(r!=null)r.disconnect(viewer);}
    private Map<String,String> focusedValue(ConversationFocusRegistry.Focus f){return Map.of("contextId",f.contextId().toString(),"agentId",f.agentId().toString(),"conversationId",f.conversationId().toString(),"nativeInput",Boolean.toString(f.nativeInput()));}
    private void thread(){if(closed||!server.isSameThread())throw new IllegalStateException("CONVERSATION_RUNTIME_UNAVAILABLE");}
    private Map<String,String> value(Object value)throws Exception{return Map.of("state",json.writeValueAsString(value));}
    public void authorize(ServerPlayer viewer,Map<String,String> args)throws Exception{thread();String kind=args.get("kind");if(Set.of("auditCandidates","auditPreview","auditStart","auditJobs").contains(kind))return;if(Set.of("auditStep","auditJob").contains(kind)){store.auditJob(viewer.getUUID(),UUID.fromString(args.get("agentId")),UUID.fromString(args.get("jobId")));return;}if(!Set.of("list","create").contains(kind))store.get(viewer.getUUID(),UUID.fromString(args.get("agentId")),UUID.fromString(args.get("conversationId")));}
    public Map<String,String> read(ServerPlayer viewer,Map<String,String> args)throws Exception{
        thread();UUID agent=UUID.fromString(args.get("agentId"));String kind=args.get("kind");
        if(kind.equals("auditCandidates")){
            var page=store.auditCandidates(viewer.getUUID(),Long.parseLong(args.getOrDefault("before","0")));var rows=new ArrayList<Map<String,Object>>();
            for(var candidate:page.candidates()){
                if(candidate.sourceConversationId().isEmpty()){rows.add(Map.of("state","INVALID_SOURCE_ID","sourceConversationId","","lastPromptSequence",candidate.lastPromptSequence()));continue;}
                UUID source=UUID.fromString(candidate.sourceConversationId());var preview=store.auditPreview(viewer.getUUID(),agent,source,auditIdentity(viewer,agent,source));
                rows.add(json.convertValue(preview,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
            }
            return value(Map.of("candidates",rows,"nextBefore",page.nextBefore(),"auditAvailable",page.auditAvailable()));
        }
        if(kind.equals("auditPreview")){UUID source=UUID.fromString(args.get("sourceConversationId"));return value(store.auditPreview(viewer.getUUID(),agent,source,auditIdentity(viewer,agent,source)));}
        if(kind.equals("auditJobs"))return value(Map.of("jobs",store.auditJobs(viewer.getUUID(),agent,Long.parseLong(args.getOrDefault("before","0")))));
        if(kind.equals("auditJob"))return value(store.auditJob(viewer.getUUID(),agent,UUID.fromString(args.get("jobId"))));
        if(kind.equals("list"))return value(store.list(viewer.getUUID(),agent,args.getOrDefault("state","ACTIVE"),args.getOrDefault("search",""),Long.parseLong(args.getOrDefault("before","0")),20));
        UUID id=UUID.fromString(args.get("conversationId"));return switch(kind){
            case "get"->value(store.get(viewer.getUUID(),agent,id));
            case "messages"->{var page=store.messages(viewer.getUUID(),agent,id,Long.parseLong(args.getOrDefault("before","0")),20);yield value(Map.of("conversation",page.conversation(),"messages",page.messages(),"nextBefore",page.nextBefore(),"summary",summaryView(viewer.getUUID(),agent,id,null),"context",contextView(viewer.getUUID(),agent,id,null),"voiceJobs",store.voiceJobs(viewer.getUUID(),agent,id,page.messages().stream().map(ConversationStore.Message::messageId).toList()),"thinking",store.thinkingPage(viewer.getUUID(),agent,id,page.messages()),"voiceOutputEnabled",Boolean.parseBoolean(MineAgentRuntimeServices.config(server).snapshot().values().getOrDefault("voice.output.enabled","true"))));}
            case "context"->value(contextView(viewer.getUUID(),agent,id,args.containsKey("messageId")?UUID.fromString(args.get("messageId")):null));
            case "summary"->value(summaryView(viewer.getUUID(),agent,id,args.containsKey("summaryId")?UUID.fromString(args.get("summaryId")):null));
            case "message"->value(store.chunk(viewer.getUUID(),agent,id,UUID.fromString(args.get("messageId")),Long.parseLong(args.get("messageRevision")),Integer.parseInt(args.get("offset")),4096));
            case "thinking"->value(store.thinkingChunk(viewer.getUUID(),agent,id,UUID.fromString(args.get("messageId")),Long.parseLong(args.get("messageRevision")),Integer.parseInt(args.get("offset")),4096));
            case "auditSource"->value(store.auditSource(viewer.getUUID(),agent,id,UUID.fromString(args.get("messageId"))));
            default->throw new IllegalArgumentException("CONVERSATION_READ_KIND");
        };
    }
    private ConversationAuditImporter.Identity auditIdentity(ServerPlayer viewer,UUID agent,UUID source)throws Exception{
        var persisted=store.auditIdentity(viewer.getUUID(),agent,source);
        if(persisted.isPresent())return new ConversationAuditImporter.Identity(MineAgentRuntimeServices.worldId(server),viewer.getUUID(),agent,source,persisted.get().revision(),"PERSISTED_CONVERSATION");
        var live=MineAgentRuntimeServices.conversations(server).session(source).orElse(null);
        return live!=null&&live.playerId().equals(viewer.getUUID())&&live.agentId().equals(agent)
                ?new ConversationAuditImporter.Identity(MineAgentRuntimeServices.worldId(server),viewer.getUUID(),agent,source,live.revision(),"LIVE_SERVER_SESSION"):null;
    }
    private Map<String,Object> contextView(UUID viewer,UUID agent,UUID conversation,UUID assistant)throws Exception{
        var usage=store.context(viewer,agent,conversation,assistant);var result=new LinkedHashMap<String,Object>();
        result.put("currentBudget",ConversationBudget.from(MineAgentRuntimeServices.config(server).snapshot()));
        result.put("estimateMode",ConversationBudget.ESTIMATE_MODE);
        result.put("state",usage.isEmpty()?"NO_TURN":usage.get().budget()==null?"LEGACY_UNRECORDED":"RECORDED");
        if(usage.isPresent()){result.put("turn",usage.get());result.put("summaryBatches",summaries.requestStats(viewer,agent,conversation,usage.get().operationId()));}
        return result;
    }
    private Map<String,Object> summaryView(UUID viewer,UUID agent,UUID conversation,UUID summaryId)throws Exception{
        store.get(viewer,agent,conversation);var latest=summaryId==null?summaries.recent(viewer,agent,conversation,1):List.of(summaries.get(viewer,agent,conversation,summaryId));
        if(latest.isEmpty())return Map.of("state","NOT_NEEDED_YET");var s=latest.getFirst();boolean valid=s.state().equals("READY")&&summaries.valid(store,viewer,agent,s);var value=new LinkedHashMap<String,Object>();
        value.put("summaryId",s.summaryId());value.put("requestId",s.requestId());value.put("revision",s.revision());value.put("state",s.state().equals("READY")&&!valid?"STALE_SOURCE":s.state());value.put("sourceValid",valid);value.put("start",s.start());value.put("end",s.end());value.put("targetSequence",s.targetSequence());value.put("parentId",s.parentId()==null?"":s.parentId().toString());value.put("sourceHash",s.sourceHash());value.put("rawHash",s.rawHash());value.put("text",valid?s.text():"");value.put("sources",s.sources());value.put("error",s.error());value.put("provider",s.provider());value.put("model",s.model());value.put("inputBudget",s.inputBudget());value.put("outputBudget",s.outputBudget());if(s.modelReceipt()!=null)value.put("modelReceipt",s.modelReceipt());return value;
    }
    public Map<String,String> write(ServerPlayer viewer,UUID operation,Map<String,String> args)throws Exception{
        return write(viewer,operation,args,false);
    }
    private Map<String,String> write(ServerPlayer viewer,UUID operation,Map<String,String> args,boolean nativeChat)throws Exception{
        thread();UUID agent=UUID.fromString(args.get("agentId"));String kind=args.get("kind");
        if(kind.equals("auditStart")){if(!"true".equals(args.get("confirmed")))throw new IllegalArgumentException("AUDIT_CONFIRM_REQUIRED");UUID source=UUID.fromString(args.get("sourceConversationId"));return value(store.auditStart(viewer.getUUID(),agent,source,auditIdentity(viewer,agent,source),Long.parseLong(args.get("upperSequence")),Long.parseLong(args.get("availableRecords")),args.get("identityHash")));}
        if(kind.equals("auditStep"))return value(store.auditStep(viewer.getUUID(),agent,UUID.fromString(args.get("jobId")),Long.parseLong(args.get("cursor"))));
        if(kind.equals("create")){requireAgent(agent);return value(store.create(viewer.getUUID(),agent,operation,args.get("title")));}
        UUID id=UUID.fromString(args.get("conversationId"));store.get(viewer.getUUID(),agent,id);
        if(kind.equals("focus")){var f=focus.select(viewer,store.get(viewer.getUUID(),agent,id),UUID.fromString(args.get("contextId")),1800000);return value(focusedValue(f));}
        if(kind.equals("route")){var f=focused(viewer).filter(v->v.conversationId().equals(id)&&v.agentId().equals(agent)&&v.contextId().toString().equals(args.get("contextId"))).orElseThrow(()->new IllegalStateException("STALE_CONVERSATION_FOCUS"));boolean enabled="true".equals(args.get("enabled"));if(enabled&&!store.get(viewer.getUUID(),agent,id).state().equals("ACTIVE"))throw new IllegalStateException("CONVERSATION_READ_ONLY");if(enabled){store.bindNative(viewer.getUUID(),agent,id);ServerChatSettings.defaultReply(viewer,agent.toString());}else if(agent.equals(ServerChatSettings.defaultAgent(viewer)))ServerChatSettings.defaultReply(viewer,"off");focus.nativeInput(viewer.getUUID(),viewer,f.contextId(),enabled);return value(focusedValue(f));}
        if(kind.equals("unfocus")){focus.clear(viewer.getUUID(),viewer,UUID.fromString(args.get("contextId")));return value(Map.of("cleared",true));}
        if(kind.equals("voice"))return startVoice(viewer,agent,id,operation,args);
        if(kind.equals("voiceCancel")){UUID target=UUID.fromString(args.get("targetOperation"));var job=store.cancelVoice(viewer.getUUID(),agent,id,target);var active=voices.get(target);if(active!=null)active.permit.set(false);return value(Map.of("status",job.orElseThrow().state(),"operationId",target));}
        if(Set.of("rename","archive","delete","restore").contains(kind)){long expected=Long.parseLong(args.get("expectedRevision"));var before=store.get(viewer.getUUID(),agent,id);if(before.revision()!=expected)throw new IllegalStateException("STALE_CONVERSATION_REVISION");if(kind.equals("delete")){if(!before.activeOperation().isEmpty()){UUID target=UUID.fromString(before.activeOperation());var flight=flights.get(target);if(flight!=null){flight.permit.set(false);retire(flight);}store.cancel(viewer.getUUID(),agent,id,UUID.randomUUID(),target);nativeReplies.remove(target);}pendingNative.values().removeIf(v->v.viewer()==viewer&&id.equals(v.conversation()));}return value(store.change(viewer.getUUID(),agent,id,operation,expected,kind,args.getOrDefault("title","")));}
        if(kind.equals("cancel")){UUID target=UUID.fromString(args.get("targetOperation"));var pendingFlight=flights.get(target);if(pendingFlight!=null){synchronized(pendingFlight.buffer){pendingFlight.permit.set(false);}flush(pendingFlight);}var state=store.cancel(viewer.getUUID(),agent,id,operation,target);var flight=flights.get(target);if(flight!=null){flight.permit.set(false);if(flight.summaryJob!=null)summaries.fail(flight.summaryJob,"CANCELLED","USER_CANCELLED");}return value(state);}
        if(kind.equals("speechDiscard")){ServerSpeechInput.discard(server,viewer.getUUID(),agent,id,UUID.fromString(args.get("speechOperation")));return value(Map.of("state","DISCARDED"));}
        if(!kind.equals("send"))throw new IllegalArgumentException("CONVERSATION_WRITE_KIND");
        if(!ServerChatAccess.admitOrAsk(viewer,agent,args.get("text"),()->write(viewer,operation,args,nativeChat)))return Map.of("state",json.writeValueAsString(store.get(viewer.getUUID(),agent,id)),"waitingForOwner","true");
        var definition=requireAgent(agent);var persona=MineAgentRuntimeServices.personas(server).forModel(definition);String original=args.get("text");
        UUID speech=args.containsKey("speechOperation")?UUID.fromString(args.get("speechOperation")):null;if(speech!=null)ServerSpeechInput.verifySource(server,viewer.getUUID(),agent,id,speech);var input=new dev.mineagent.runtime.api.interaction.InteractionInput(viewer.getUUID(),speech==null?(nativeChat?dev.mineagent.runtime.api.interaction.InteractionSource.CHAT:dev.mineagent.runtime.api.interaction.InteractionSource.CONTROL_CENTER):dev.mineagent.runtime.api.interaction.InteractionSource.VOICE,original,agent);
        var config=MineAgentRuntimeServices.config(server);var snapshot=config.snapshot();var settings=snapshot.values();var policy=ConversationBudget.from(snapshot);
        var turn=store.begin(viewer.getUUID(),agent,id,operation,Long.parseLong(args.get("expectedRevision")),original,persona.revision(),policy,input.source(),speech);
        if(!turn.dispatch())return Map.of("state",json.writeValueAsString(store.get(viewer.getUUID(),agent,id)),"operationId",operation.toString(),"duplicate","true");
        try{
            if(flights.size()>=16)throw new IllegalStateException("CONVERSATION_GENERATION_BUDGET");
            if(String.valueOf(dev.mineagent.runtime.core.config.WebSettingsCatalog.routing(snapshot).get("textProvider")).isBlank())throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
            int budget=Math.max(1024,policy.contextTokenBudget()-12288);
            var preferences=MineAgentRuntimeServices.preferences(server).snapshot(viewer.getUUID(),MineAgentRuntimeServices.worldId(server),agent,"CONVERSATION");var plan=ConversationContext.build(store,viewer.getUUID(),definition,id,turn,persona,original,budget,null,preferences.section());var flight=new Flight(operation,viewer,agent);flight.preferences=preferences;flights.put(operation,flight);
            store.recordContext(operation,plan);
            MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"CONVERSATION_GENERATION_ACCEPTED",operation.toString(),json.writeValueAsString(Map.of("conversationId",id,"agentId",agent,"personaRevision",persona.revision(),"estimatedTokens",plan.estimatedTokens(),"estimateMode",plan.estimateMode(),"omittedThrough",plan.omittedThrough(),"summaryStatus",plan.summaryStatus(),"budget",policy,"inputSource",input.source(),"speechOperation",speech==null?"":speech)));
            var generation=new Generation(viewer.getUUID(),definition,id,turn,persona,original,policy,plan,preferences);
            advance(flight,generation,null);
            return Map.of("state",json.writeValueAsString(store.get(viewer.getUUID(),agent,id)),"operationId",operation.toString(),"duplicate","false","contextStatus",plan.summaryStatus(),"omittedThrough",Long.toString(plan.omittedThrough()),"estimatedTokens",Integer.toString(plan.estimatedTokens()));
        }catch(Exception failure){var f=flights.remove(operation);if(f!=null)f.permit.set(false);String code=failure.getMessage();if(code==null||!dev.mineagent.runtime.core.memory.PlayerPreferenceStore.ERRORS.contains(code)&&!Set.of("PROVIDER_NOT_CONFIGURED","CURRENT_CONTEXT_EXCEEDS_BUDGET","CONVERSATION_CONTEXT_BUDGET","CONVERSATION_GENERATION_BUDGET").contains(code))code=summaryError(failure).equals("SUMMARY_GENERATION_FAILED")?"CONVERSATION_DISPATCH_FAILED":summaryError(failure);store.finish(operation,"FAILED",null,code);return Map.of("state",json.writeValueAsString(store.get(viewer.getUUID(),agent,id)),"operationId",operation.toString(),"acceptedError",code);}
    }
    private Map<String,String> startVoice(ServerPlayer viewer,UUID agent,UUID conversation,UUID operation,Map<String,String> args)throws Exception{
        var selected=focused(viewer).filter(f->f.conversationId().equals(conversation)&&f.agentId().equals(agent)&&f.contextId().toString().equals(args.get("contextId"))).orElseThrow(()->new IllegalStateException("STALE_CONVERSATION_FOCUS"));
        var config=MineAgentRuntimeServices.config(server);var settings=config.snapshot().values();
        if(!Boolean.parseBoolean(settings.getOrDefault("voice.output.enabled","true")))throw new IllegalStateException("CONVERSATION_VOICE_DISABLED");
        if(store.voiceJob(viewer.getUUID(),agent,conversation,operation).isPresent())return value(Map.of("status","VOICE_ALREADY_REQUESTED","duplicate",true));
        if(voices.size()>=16||voices.values().stream().anyMatch(v->v.permit.get()&&v.focus==selected))throw new IllegalStateException("CONVERSATION_VOICE_BUSY");
        var voiceSettings=new ConversationStore.VoiceSettings(settings.getOrDefault("agent."+agent+".voice",settings.getOrDefault("voice.default","zh-CN-XiaoxiaoNeural")),settings.getOrDefault("voice.rate","+0%"),settings.getOrDefault("voice.pitch","+0Hz"),settings.getOrDefault("voice.volume","+0%"));
        var claim=store.claimVoice(viewer.getUUID(),agent,conversation,UUID.fromString(args.get("messageId")),operation,selected.contextId(),voiceSettings);
        if(!claim.dispatch())return value(Map.of("status","VOICE_ALREADY_REQUESTED","duplicate",true));
        var flight=new VoiceFlight(operation,viewer,selected);voices.put(operation,flight);
        try{
            var worker=MineAgentRuntimeServices.worker(server);
            worker.synthesize(claim.text(),voiceSettings.voice(),voiceSettings.rate(),voiceSettings.pitch(),voiceSettings.volume(),()->flight.permit.get()&&System.currentTimeMillis()<flight.deadline&&selected.current()&&Boolean.parseBoolean(config.snapshot().values().getOrDefault("voice.output.enabled","true"))).whenComplete((reply,error)->{
                if(error!=null||reply==null||!reply.type().equals("tts.result")){server.execute(()->finishVoice(flight,"FAILED",dev.mineagent.runtime.core.config.ServiceCallBudget.responseError(reply,"TTS_NOT_DELIVERED")));return;}
                try{
                    String hash=String.valueOf(reply.payload().get("sha256"));if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
                    var path=worker.contentPath(hash);long size=java.nio.file.Files.size(path);if(size<1||size>8*1024*1024)throw new IllegalArgumentException();
                    byte[] bytes=java.nio.file.Files.readAllBytes(path);if(bytes.length!=size||!hash.equals(dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes)))throw new IllegalArgumentException();
                    server.execute(()->{
                        if(closed)return;
                        try{
                            if(!flight.permit.get()||!selected.current()||System.currentTimeMillis()>=flight.deadline||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer){finishVoice(flight,"CANCELLED","CONVERSATION_FOCUS_CHANGED");return;}
                            if(!Boolean.parseBoolean(config.snapshot().values().getOrDefault("voice.output.enabled","true"))){finishVoice(flight,"CANCELLED","VOICE_OUTPUT_DISABLED");return;}
                            if(!store.voiceOutcome(operation,"AUDIO_READY",hash,"")){finishVoice(flight,"CANCELLED","VOICE_REQUEST_NOT_PENDING");return;}
                            int count=(bytes.length+24575)/24576;
                            for(int i=0;i<count;i++)net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer,new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.ConversationVoiceChunk(operation,MineAgentRuntimeServices.worldId(server),agent,conversation,selected.contextId(),hash,i,count,Arrays.copyOfRange(bytes,i*24576,Math.min(bytes.length,(i+1)*24576))));
                            store.voiceOutcome(operation,"TRANSFER_SENT","","");voiceResult(viewer,selected,operation,"TRANSFER_SENT","");
                        }catch(Exception failure){finishVoice(flight,"FAILED","TTS_TRANSFER_FAILED");}
                        finally{voices.remove(operation,flight);}
                    });
                }catch(Exception invalid){server.execute(()->finishVoice(flight,"FAILED","TTS_AUDIO_READ_FAILED"));}
            });
        }catch(Exception failure){finishVoice(flight,"FAILED","TTS_DISPATCH_FAILED");}
        return value(Map.of("status","VOICE_REQUESTED","duplicate",false));
    }
    private void finishVoice(VoiceFlight flight,String state,String error){
        voices.remove(flight.op,flight);if(closed)return;
        if(!flight.permit.get()||!flight.focus.current()){state="CANCELLED";error="CONVERSATION_FOCUS_CHANGED";}
        flight.permit.set(false);
        try{store.voiceOutcome(flight.op,state,"",error);var stored=store.voiceJob(flight.viewer.getUUID(),flight.focus.agentId(),flight.focus.conversationId(),flight.op);if(stored.isPresent()){state=stored.get().state();error=stored.get().errorCode();}}
        catch(Exception ignored){state="UNKNOWN";error="VOICE_STATE_WRITE_FAILED";/* No retry of synthesis to repair persistence. */}
        voiceResult(flight.viewer,flight.focus,flight.op,state,error);
    }
    private void reconcileVoices(){
        boolean enabled=voices.isEmpty()||Boolean.parseBoolean(MineAgentRuntimeServices.config(server).snapshot().values().getOrDefault("voice.output.enabled","true"));
        for(var voice:voices.values())if(voice.permit.get()&&(!voice.focus.current()||!enabled||System.currentTimeMillis()>=voice.deadline)){voice.permit.set(false);try{store.voiceOutcome(voice.op,"CANCELLED","",System.currentTimeMillis()>=voice.deadline?"VOICE_REQUEST_EXPIRED":enabled?"CONVERSATION_FOCUS_CHANGED":"VOICE_OUTPUT_DISABLED");}catch(Exception ignored){}}
    }
    private void advance(Flight flight,Generation g,ConversationSummaryStore.Summary previous)throws Exception{
        thread();if(!flight.permit.get()||!store.pending(flight.op)){retire(flight);return;}
        if(g.initial().omittedThrough()==0){startReply(flight,g,g.initial());return;}
        if(previous==null)previous=summaries.latest(store,g.viewer(),g.agent().agentId(),g.conversation(),g.initial().historyEnd(),g.initial().summaryBudget()).orElse(null);
        long target=g.initial().omittedThrough();if(previous!=null&&previous.endOffset()>0)target=Math.max(target,previous.endSequence());
        if(previous!=null&&previous.endOffset()==0&&previous.endSequence()>target){
            if(!summaries.valid(store,g.viewer(),g.agent().agentId(),previous))throw new IllegalStateException("SUMMARY_SOURCE_CHANGED");
            var plan=ConversationContext.build(store,g.viewer(),g.agent(),g.conversation(),g.turn(),g.persona(),g.original(),g.budget(),previous,g.preferences().section());
            if(!plan.summaryStatus().equals("READY"))throw new IllegalStateException("SUMMARY_COVERAGE_INCOMPLETE");startReply(flight,g,plan);return;
        }
        int maximum=g.policy().summaryMaxCalls(),input=g.policy().summaryInputBudget();
        if(maximum==0)throw new IllegalStateException("SUMMARY_DISABLED");if(flight.summaryCalls>=maximum)throw new IllegalStateException("SUMMARY_CALL_BUDGET_EXHAUSTED");
        var prepared=summaries.prepare(store,g.viewer(),g.agent().agentId(),g.conversation(),flight.op,flight.summaryCalls++,target,input,g.initial().summaryBudget(),previous);var job=prepared.summary();
        if(!prepared.dispatch()){if(job.state().equals("READY")&&summaries.valid(store,g.viewer(),g.agent().agentId(),job)){advance(flight,g,job);return;}throw new IllegalStateException("SUMMARY_OUTCOME_NOT_REPLAYABLE");}
        flight.summaryJob=job.summaryId();var settings=MineAgentRuntimeServices.config(server).snapshot();String model=String.valueOf(dev.mineagent.runtime.core.config.WebSettingsCatalog.routing(settings).get("textModel"));var selectedModel=dev.mineagent.runtime.core.config.AgentModelSettings.read(settings.values(),MineAgentRuntimeServices.worldId(server),g.agent().agentId());if(selectedModel.mode().equals("CUSTOM"))model=selectedModel.model();
        MineAgentRuntimeServices.audit(server).record(g.viewer().toString(),"CONVERSATION_SUMMARY_DISPATCH",job.summaryId().toString(),json.writeValueAsString(Map.of("conversationId",g.conversation(),"requestId",flight.op,"revision",job.revision(),"start",job.start(),"end",job.end(),"sourceHash",job.sourceHash(),"configuredModel",model)));
        MineAgentRuntimeServices.worker(server).summarizeConversation(MineAgentRuntimeServices.config(server),job.summaryId(),job.prompt(),flight.permit::get,MineAgentRuntimeServices.worldId(server),g.agent().agentId()).whenComplete((response,error)->server.execute(()->{
            if(closed)return;try{
                if(!flight.permit.get()||!store.pending(flight.op)){summaries.fail(job.summaryId(),"CANCELLED","REQUEST_CANCELLED");retire(flight);return;}
                if(error!=null||response==null||!response.type().equals("model.result")){String code=dev.mineagent.runtime.core.config.ServiceCallBudget.responseError(response,"SUMMARY_PROVIDER_FAILED");summaries.fail(job.summaryId(),"FAILED",code);failGeneration(flight,code);return;}
                var completed=summaries.complete(store,job.summaryId(),String.valueOf(response.payload().getOrDefault("text","")),ConversationModelReceipt.from(response.payload()));flight.summaryJob=null;
                MineAgentRuntimeServices.audit(server).record(g.viewer().toString(),"CONVERSATION_SUMMARY_READY",job.summaryId().toString(),json.writeValueAsString(Map.of("conversationId",g.conversation(),"requestId",flight.op,"sourceHash",completed.sourceHash(),"rawHash",completed.rawHash(),"end",completed.end())));
                advance(flight,g,completed);
            }catch(Exception failed){String code=summaryError(failed);try{summaries.fail(job.summaryId(),"FAILED",code);}catch(Exception ignored){}failGeneration(flight,code);}
        }));
    }
    private static String summaryError(Throwable error){String code=error.getMessage();return code!=null&&code.matches("SUMMARY_[A-Z_]{1,60}")?code:"SUMMARY_GENERATION_FAILED";}
    private void retire(Flight f){f.permit.set(false);flights.remove(f.op,f);}
    private void failGeneration(Flight f,String code){synchronized(f.buffer){f.permit.set(false);}try{flush(f);store.finish(f.op,"FAILED",null,code);}catch(Exception ignored){}retire(f);}
    private boolean live(Flight f){return !closed&&f.permit.get()&&ServerChatAccess.canContinue(f.viewer,f.agent)&&server.getPlayerList().getPlayer(f.viewer.getUUID())==f.viewer&&f.viewer.level()==f.level&&(f.preferences==null||MineAgentRuntimeServices.preferences(server).current(f.preferences));}
    private void startReply(Flight f,Generation g,ConversationContext.Plan plan)throws Exception{
        if(!live(f)||!store.pending(f.op)){failGeneration(f,"CONVERSATION_CONTEXT_CHANGED");return;}
        store.recordContext(f.op,plan);agentRound(f,g,plan);
    }
    private void agentRound(Flight f,Generation g,ConversationContext.Plan plan)throws Exception{
        if(!live(f)||!store.pending(f.op)){failGeneration(f,"CONVERSATION_CONTEXT_CHANGED");return;}

        var latestPersona=MineAgentRuntimeServices.personas(server).forModel(g.agent());String personaRefresh=latestPersona.revision()==g.persona().revision()?"":"\n[本轮最新人设覆盖上文旧版本，仅影响表达与角色扮演]"+dev.mineagent.runtime.core.agent.PersonaPrompt.section(g.agent(),latestPersona);String prompt=ConversationTools.instructions()+plan.prompt()+personaRefresh;FeedbackChatSmokeServer.capture(g.conversation(),prompt);int estimate=prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+json.writeValueAsBytes(f.tools).length+json.writeValueAsBytes(ConversationTools.ALL).length;
        try{ConversationTools.requireTransportSize(estimate);}catch(IllegalArgumentException tooLarge){failGeneration(f,"CONVERSATION_TOOL_TRANSPORT_LIMIT");return;}
        synchronized(f.buffer){f.roundThinking=false;}
        UUID request=UUID.nameUUIDFromBytes((f.op+"|agent-round|"+f.rounds++).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MineAgentRuntimeServices.worker(server).streamConversation(MineAgentRuntimeServices.config(server),"SEMANTIC",prompt,delta->{synchronized(f.buffer){if(!f.permit.get())return;String text=String.valueOf(delta.payload().getOrDefault("delta",""));if("thinking".equals(delta.payload().get("channel"))){if(text.isEmpty())return;if(!f.roundThinking&&f.thinkingLength>0){text="\n\n"+text;}f.roundThinking=true;if(f.thinkingLength+text.length()>1_000_000){f.error="CONVERSATION_THINKING_TOO_LARGE";f.permit.set(false);}else{f.thinkingBuffer.append(text);f.thinkingLength+=text.length();f.thinkingActive=true;f.thinkingDirty=true;}}else{if(f.thinkingActive){f.thinkingActive=false;f.thinkingDirty=true;}if(f.reply.length()+text.length()>131072){f.error="CONVERSATION_RESPONSE_TOO_LARGE";f.permit.set(false);}else{f.buffer.append(text);f.reply.append(text);}}}},f.permit::get,g.preferences(),request,List.copyOf(f.tools),MineAgentRuntimeServices.worldId(server),g.agent().agentId()).whenComplete((response,error)->server.execute(()->{
            if(closed)return;try{
                synchronized(f.buffer){if(f.thinkingActive){f.thinkingActive=false;f.thinkingDirty=true;}}flush(f);if(!store.pending(f.op)){retire(f);return;}if(!f.error.isEmpty())throw new IllegalStateException(f.error);if(!live(f))throw new IllegalStateException("CONVERSATION_CONTEXT_CHANGED");
                if(error!=null||response==null||!response.type().equals("model.stream.result")){for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof java.util.concurrent.TimeoutException)throw new IllegalStateException("CONVERSATION_STREAM_TIMEOUT");String reason=response==null?"":String.valueOf(response.payload().getOrDefault("message",""));throw new IllegalStateException(reason.matches("AGENT_MODEL_[A-Z_]{1,60}")?reason:reason.matches("(?:TOOL_STREAM|STREAM)_[A-Z_]{1,60}")?"CONVERSATION_"+reason:"CONVERSATION_MODEL_FAILED");}
                var raw=json.valueToTree(response.payload().getOrDefault("toolCalls",List.of()));if(!raw.isArray()||raw.size()>ConversationTools.MAX_CALLS_PER_ROUND)throw new IllegalStateException("CONVERSATION_TOOL_RESPONSE");
                if(raw.isEmpty()){String text=f.reply.toString();if(text.isBlank())throw new IllegalStateException("CONVERSATION_EMPTY_RESPONSE");store.finish(f.op,"COMPLETE",text,"",ConversationModelReceipt.from(response.payload()));retire(f);return;}
                f.calls+=raw.size();WorldGeometrySmokeServer.progress(f.rounds,f.calls);var calls=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();var seen=new HashSet<String>();var assistantCalls=new ArrayList<Object>();
                for(var call:raw){String id=call.path("id").asText(),name=call.path("name").asText(),arguments=call.path("arguments").asText();if(id.isBlank()||id.length()>160||!seen.add(id)||!ConversationTools.NAMES.contains(name)||arguments.length()>16384)throw new IllegalStateException("CONVERSATION_TOOL_RESPONSE");calls.add(call);assistantCalls.add(Map.of("id",id,"type","function","function",Map.of("name",name,"arguments",arguments)));}
                var assistantHistory=new LinkedHashMap<String,Object>();assistantHistory.put("role","assistant");assistantHistory.put("content",String.valueOf(response.payload().getOrDefault("text","")));assistantHistory.put("tool_calls",assistantCalls);String reasoning=String.valueOf(response.payload().getOrDefault("reasoningContent",""));if(!reasoning.isEmpty())assistantHistory.put("reasoning_content",reasoning);f.tools.add(assistantHistory);runTools(f,g,plan,calls,0);
            }catch(Exception failure){failGeneration(f,agentError(failure));}
        }));
    }
    private static String agentError(Throwable error){String code=Objects.toString(error.getMessage(),"");return code.matches("(?:CONVERSATION|AGENT)_[A-Z_]{1,64}")?code:"CONVERSATION_AGENT_FAILED";}
    private void runTools(Flight f,Generation g,ConversationContext.Plan plan,List<com.fasterxml.jackson.databind.JsonNode> calls,int index)throws Exception{
        if(!live(f)||!store.pending(f.op)){failGeneration(f,"CONVERSATION_CONTEXT_CHANGED");return;}
        if(index==calls.size()){synchronized(f.buffer){if(!f.reply.isEmpty()&&f.reply.charAt(f.reply.length()-1)!='\n'){f.reply.append('\n');f.buffer.append('\n');}}agentRound(f,g,plan);return;}
        var call=calls.get(index);UUID operation=UUID.nameUUIDFromBytes((f.op+"|tool|"+f.rounds+"|"+call.path("id").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ConversationAgentTools.execute(f.viewer,g.agent().agentId(),operation,call.path("name").asText(),call.path("arguments").asText(),()->live(f)).whenComplete((result,error)->server.execute(()->{
            if(closed)return;try{if(!live(f)||!store.pending(f.op))throw new IllegalStateException("CONVERSATION_CONTEXT_CHANGED");if(error!=null)throw new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN");if(Boolean.getBoolean("mineagent.conversationAgentSmoke"))MineAgentRuntimeServices.audit(server).record(f.viewer.getUUID().toString(),"CONVERSATION_SMOKE_TOOL",operation.toString(),json.writeValueAsString(Map.of("tool",call.path("name").asText(),"arguments",call.path("arguments").asText(),"result",result)));ConversationRecoverySmokeServer.observe(call.path("name").asText());String encoded=json.writeValueAsString(result);if(encoded.length()>24000)throw new IllegalStateException("CONVERSATION_TOOL_CONTEXT_BUDGET");f.tools.add(Map.of("role","tool","tool_call_id",call.path("id").asText(),"content",encoded));runTools(f,g,plan,calls,index+1);}catch(Exception failure){failGeneration(f,agentError(failure));}
        }));
    }
    private dev.mineagent.runtime.api.agent.AgentDefinition requireAgent(UUID id){return MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("CONVERSATION_AGENT_UNAVAILABLE"));}
    private void voiceResult(ServerPlayer viewer,ConversationFocusRegistry.Focus selected,UUID operation,String status,String error){if(closed)return;try{var value=Map.of("conversationId",selected.conversationId(),"contextId",selected.contextId(),"operationId",operation,"status",status,"errorCode",error);String data=json.writeValueAsString(value);MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"CONVERSATION_VOICE_RESULT",operation.toString(),data);if(server.getPlayerList().getPlayer(viewer.getUUID())==viewer)net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer,new dev.mineagent.runtime.neoforge.network.UiPayloads.Event(operation,"conversationVoiceStatus",data));}catch(Exception ignored){/* Consumed TTS is never replayed to repair telemetry. */}}
    private void flush(Flight f)throws Exception{String text,thinking;boolean dirty,active;synchronized(f.buffer){text=f.buffer.toString();f.buffer.setLength(0);thinking=f.thinkingBuffer.toString();f.thinkingBuffer.setLength(0);dirty=f.thinkingDirty;active=f.thinkingActive;f.thinkingDirty=false;}if(dirty&&!store.thinkingDelta(f.op,thinking,active))f.permit.set(false);if(!text.isEmpty()&&!store.delta(f.op,text))f.permit.set(false);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){ServerConversations r;synchronized(ServerConversations.class){r=LIVE.get(event.getServer());}if(r==null||r.closed||event.getServer().getTickCount()%4!=0)return;r.reconcileVoices();for(var f:List.copyOf(r.flights.values()))try{r.flush(f);}catch(Exception e){f.error="CONVERSATION_STORE_WRITE_FAILED";f.permit.set(false);}r.pollNativeReplies();r.pollQueued();}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p)disconnectIfPresent(p.level().getServer(),p);}
    @Override public void close(){if(closed)return;ServerSpeechInput.stop(server);closed=true;focus.clear();for(var v:voices.values()){v.permit.set(false);try{store.voiceOutcome(v.op,"INTERRUPTED","","SERVER_STOPPED");}catch(Exception ignored){}}voices.clear();for(var f:flights.values()){f.permit.set(false);try{flush(f);if(f.summaryJob!=null)summaries.fail(f.summaryJob,"INTERRUPTED","SERVER_STOPPED");store.finish(f.op,"INTERRUPTED",null,"SERVER_STOPPED");}catch(Exception ignored){}}flights.clear();try{summaries.close();store.close();}catch(Exception e){throw new IllegalStateException("CONVERSATION_CLOSE_FAILED",e);}}
}
