package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Deterministic model fixture, not DeepSeek quality evidence. Actual tool implementations execute in Minecraft. */
final class ConversationAgentControlledProvider implements AutoCloseable {
    private final ObjectMapper json=new ObjectMapper();private final HttpServer http;private final Path game;private final AtomicInteger calls=new AtomicInteger();
    ConversationAgentControlledProvider(Path game)throws Exception{this.game=game;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",e->{try{
        int count=calls.incrementAndGet();var request=json.readTree(e.getRequestBody().readNBytes(300000));if(!request.path("stream").asBoolean()||request.path("tools").size()!=dev.mineagent.runtime.core.conversation.ConversationTools.ALL.size())throw new IllegalArgumentException("CHAT_AGENT_TOOL_DECLARATIONS");
        var messages=request.path("messages");String prompt=messages.get(0).path("content").asText();String latest=prompt.substring(prompt.lastIndexOf("当前用户原文："));var receipts=new ArrayList<JsonNode>();for(var m:messages)if(m.path("role").asText().equals("tool"))receipts.add(json.readTree(m.path("content").asText()));
        int n=receipts.size();var tools=new ArrayList<Map<String,Object>>();String answer="";
        if(latest.contains("手里的剑")){
            if(n==0){answer="我先检查你手里的剑。";tools.add(call("inspect_player",Map.of("section","summary")));}
            else if(n==1){var hand=receipts.getLast().path("mainHand");tools.add(call("modify_item",Map.of("slot",hand.path("slot").asInt(),"expected_hash",hand.path("stackHash").asText(),"name","晨光","enchantments",List.of(Map.of("id","minecraft:sharpness","level",3)))));}
            else{if(!receipts.getLast().path("status").asText().equals("APPLIED"))throw new IllegalStateException("CHAT_AGENT_MODIFY_FAILED:"+receipts.getLast());answer="晨光已附魔锋利 III。\n"+"Long streaming reply verification: abcdefghijklmnopqrstuvwxyz0123456789. ".repeat(80)+"LONG_REPLY_END";}
        }else if(latest.contains("新的钻石剑")){
            if(n==0)tools.add(call("inspect_registry",Map.of("kind","items","query","diamond_sword")));
            else if(n==1)tools.add(call("give_item",Map.of("item",receipts.getLast().path("entries").get(0).path("id").asText(),"count",1,"enchantments",List.of(Map.of("id","minecraft:sharpness","level",5)))));
            else if(n==2)tools.add(call("inspect_player",Map.of("section","inventory")));
            else answer="已给你一把锋利 V 的钻石剑。";
        }else if(latest.contains("死亡不掉落")){
            if(n==0)tools.add(call("inspect_world",Map.of()));
            else if(n==1){tools.add(call("run_game_command",Map.of("command","gamerule keep_inventory true")));tools.add(call("run_game_command",Map.of("command","gamerule pvp false")));}
            else if(n==3)tools.add(call("inspect_world",Map.of()));
            else{var result=receipts.getLast();if(!result.path("keep_inventory").asBoolean()||result.path("pvp").asBoolean(true))throw new IllegalStateException("CHAT_AGENT_RULE_READBACK");answer="已开启死亡不掉落并关闭 PVP。";}
        }else if(latest.contains("找附近的钻石")){
            if(n==0)tools.add(call("inspect_player",Map.of("section","summary")));
            else if(n<=2){int y=receipts.get(0).path("position").get(1).asInt();if(n==2&&!receipts.getLast().path("found").isEmpty())throw new IllegalStateException("CHAT_AGENT_SMALL_RANGE_NOT_EMPTY");tools.add(call("scan_blocks",Map.of("blocks",List.of("minecraft:diamond_ore","minecraft:deepslate_diamond_ore"),"radius",n==1?4:24,"y_min",y-12,"y_max",y-4)));}
            else{if(receipts.getLast().path("found").isEmpty())throw new IllegalStateException("CHAT_AGENT_EXPANSION_EMPTY");answer="扩大范围后找到了钻石矿："+receipts.getLast().path("found").get(0).path("position")+"。";}
        }else throw new IllegalArgumentException("CHAT_AGENT_UNKNOWN_PROMPT");
        e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);
        for(int at=0;at<answer.length();at+=100){emit(e,Map.of("content",answer.substring(at,Math.min(answer.length(),at+100))));Thread.sleep(8);}if(latest.contains("手里的剑")&&n==0)Thread.sleep(700);
        for(int i=0;i<tools.size();i++){var t=tools.get(i);String name=(String)t.get("name"),args=json.writeValueAsString(t.get("args"));int split=Math.max(1,args.length()/2),nameSplit=Math.max(1,name.length()/2);emit(e,Map.of("tool_calls",List.of(Map.of("index",i,"id","call_"+count+"_"+i,"type","function","function",Map.of("name",name.substring(0,nameSplit),"arguments",args.substring(0,split))))));emit(e,Map.of("tool_calls",List.of(Map.of("index",i,"function",Map.of("name",name.substring(nameSplit),"arguments",args.substring(split))))));}
        e.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    }catch(Exception error){Files.writeString(game.resolve("conversation-agent-provider-failure.txt"),error.toString());}finally{e.close();}});http.start();
        try(var cfg=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){if(!cfg.apply(new dev.mineagent.runtime.api.config.ConfigPatch(cfg.snapshot().revision(),Map.of("provider.openai.enabled","true","provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","local-chat-agent","provider.openai.apiKey","fixture-only","runtime.initialized","true","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("CHAT_AGENT_CONFIG");}}
    private Map<String,Object> call(String name,Map<String,Object> args){return Map.of("name",name,"args",args);}
    private void emit(com.sun.net.httpserver.HttpExchange e,Map<String,Object> delta)throws Exception{String line="data: "+json.writeValueAsString(Map.of("model","local-chat-agent","choices",List.of(Map.of("delta",delta))))+"\n\n";e.getResponseBody().write(line.getBytes(StandardCharsets.UTF_8));e.getResponseBody().flush();}
    void verify(){if(calls.get()!=15||Files.exists(game.resolve("conversation-agent-provider-failure.txt")))throw new IllegalStateException("CHAT_AGENT_PROVIDER_COUNT:"+calls.get());}
    public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("conversation-agent-provider.json"),json.writeValueAsString(Map.of("localhostCalls",calls.get(),"paidCalls",0)));}
}
