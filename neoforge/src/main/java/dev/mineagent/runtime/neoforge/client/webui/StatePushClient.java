package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.ui.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Native, document-fenced state delivery. Pages receive only a refresh cue, never the server acknowledgement token. */
public final class StatePushClient {
    private static final Gson JSON=new Gson();private static final Map<String,Entry> entries=new HashMap<>();
    private static final class Entry {final Session session;final long lifecycle;long desired;boolean listening;final LinkedHashMap<String,Long> pending=new LinkedHashMap<>();final Set<String> inFlight=new HashSet<>();final LinkedHashSet<String> delivered=new LinkedHashSet<>();Entry(Session session){this.session=session;lifecycle=PackageContentClient.lifecycle(session.binding().viewId());}}
    public record Read(Map<String,String> arguments,List<String> tokens,String listen,long listenRevision){public Read{arguments=Map.copyOf(arguments);tokens=List.copyOf(tokens);}}
    private StatePushClient(){}
    private static Entry current(Session session,boolean create){String id=session.binding().viewId();var entry=entries.get(id);
        if(entry!=null&&(!ReadOnlyUiLease.sameContext(entry.session,session)||entry.lifecycle!=PackageContentClient.lifecycle(id))){entries.remove(id);entry=null;}
        if(entry==null&&create){if(entries.size()>=32)throw new IllegalStateException("STATE_PUSH_VIEW_BUDGET");entry=new Entry(session);entries.put(id,entry);}return entry;
    }
    public static Read prepare(Session session,Map<String,String> args){
        if(!WorldUiProtocol.bound(session.binding()))throw new SecurityException("STATE_PUSH_WORLD_VIEW_REQUIRED");
        if(args.isEmpty())return new Read(args,List.of(),null,0);
        if(args.keySet().equals(Set.of("listen","listenRevision"))){if(!Set.of("true","false").contains(args.get("listen")))throw new IllegalArgumentException("STATE_PUSH_LISTEN");long revision=Long.parseLong(args.get("listenRevision"));var entry=current(session,true);if(revision<1||revision>9007199254740991L||revision<entry.desired)throw new IllegalArgumentException("STATE_PUSH_LISTEN_REVISION");entry.desired=revision;return new Read(args,List.of(),args.get("listen"),revision);}
        if(!args.equals(Map.of("refresh","true")))throw new SecurityException("STATE_PUSH_RESERVED_ARGUMENTS");var entry=current(session,false);if(entry==null||!entry.listening)throw new SecurityException("STATE_PUSH_NOT_LISTENING");
        entry.pending.entrySet().removeIf(e->e.getValue()<=System.currentTimeMillis());var tokens=entry.pending.keySet().stream().filter(id->!entry.inFlight.contains(id)).limit(16).toList();if(tokens.isEmpty())throw new IllegalStateException("STATE_PUSH_NOT_PENDING");entry.inFlight.addAll(tokens);return new Read(Map.of("pushTokens",JSON.toJson(tokens)),tokens,null,0);
    }
    public static Receipt publicReceipt(Read read,Receipt receipt){
        var values=new LinkedHashMap<>(receipt.values());String proof=values.remove("pushReadTokens");if(receipt.code()==Code.OBSERVED&&!read.tokens().isEmpty()){
            if(proof==null)throw new SecurityException("STATE_PUSH_READ_PROOF_MISSING");var tokens=new ArrayList<String>();for(var token:JsonParser.parseString(proof).getAsJsonArray())tokens.add(StatePushToken.parse(token.getAsString()).wire());if(!tokens.equals(read.tokens()))throw new SecurityException("STATE_PUSH_READ_PROOF_CHANGED");
        }return new Receipt(receipt.operationId(),receipt.code(),values);
    }
    public static void received(Session session,Read read,Receipt receipt){var entry=current(session,read.listen()!=null);if(entry==null)return;
        if(receipt.code()!=Code.OBSERVED){failed(session,read);return;}if(read.listen()!=null&&entry.desired==read.listenRevision()){entry.listening=Boolean.parseBoolean(read.listen());if(!entry.listening){entry.pending.clear();entry.inFlight.clear();}}
        for(var token:read.tokens()){entry.pending.remove(token);entry.inFlight.remove(token);entry.delivered.add(token);while(entry.delivered.size()>64)entry.delivered.removeFirst();acknowledge(session,token);}
    }
    public static void failed(Session session,Read read){if(read==null)return;var entry=current(session,false);if(entry==null)return;for(var token:read.tokens()){entry.pending.remove(token);entry.inFlight.remove(token);}if(read.listen()!=null&&entry.desired==read.listenRevision())entry.listening=false;}
    private static void acknowledge(Session session,String token){if(PackageContentClient.session(session.binding().viewId())==null)return;UiClientSessions.contentRequest("command",session,"worldui.read",Map.of("pushAck",token),StatePushToken.parse(token).id());}
    public static boolean accept(UiPayloads.Event packet){if(!packet.channel().equals("worldUiStatePush"))return false;
        try{var value=JsonParser.parseString(packet.json()).getAsJsonObject();String id=value.get("viewId").getAsString();var session=PackageContentClient.session(id);if(session==null||!WorldUiProtocol.bound(session.binding()))return true;
            if(!session.sessionId().toString().equals(value.get("sessionId").getAsString())||!session.serverInstanceId().toString().equals(value.get("serverInstanceId").getAsString())||session.pageGeneration()!=value.get("pageGeneration").getAsLong()||session.controlEpoch()!=value.get("controlEpoch").getAsLong())return true;
            String token=StatePushToken.parse(value.get("token").getAsString()).wire();var entry=current(session,false);if(entry==null||!entry.listening)return true;if(entry.delivered.contains(token)){acknowledge(session,token);return true;}
            if(!entry.pending.containsKey(token)&&entry.pending.size()>=16)return true;entry.pending.put(token,value.get("expiresAt").getAsLong());
            var host=WebGuiHostAdapter.INSTANCE;var browser=host.browser();if(browser==null)return true;String url=host.packageUrl(id);
            for(long frameId:PackagePageAgent.frameIds(browser.getFrameIdentifiers())){var frame=browser.getFrame(frameId);if(frame!=null&&!frame.isMain()&&Objects.equals(url,frame.getURL())&&PackageContentClient.statePushFrame(browser,frameId,url,session)&&ReadOnlyUiLease.sameContext(session,PackageContentClient.session(id))&&entry.lifecycle==PackageContentClient.lifecycle(id)){frame.executeJavaScript("window.dispatchEvent(new Event('mineagent:world-refresh'));",url,0);break;}}
        }catch(RuntimeException invalid){/* Untrusted or retired hints never create a view, change focus, or carry data. */}return true;
    }
    public static void close(String view){entries.remove(view);}public static void clear(){entries.clear();}
}
