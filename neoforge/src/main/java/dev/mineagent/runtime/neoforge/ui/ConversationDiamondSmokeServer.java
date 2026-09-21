package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;
/** Places only a test ore; the model chooses all scan ranges and produces the coordinates. */
public final class ConversationDiamondSmokeServer {
    public static volatile boolean ready,verified;public static volatile String failure="";public static volatile UUID agent;
    private static final List<Map<String,Object>> scans=new ArrayList<>();private static BlockPos ore;
    public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentReal")&&System.getProperty("mineagent.conversationAgentScenario","").equals("diamond");}
    public static void observe(Map<String,Object> r){if(active())scans.add(Map.copyOf(r));}
    private static void save(MinecraftServer s,String name,Object data)throws Exception{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("diamond-smoke")).resolve(name+".json"),new ObjectMapper().writeValueAsString(data));}
    public static void tick(MinecraftServer s){
        if(verified||!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;
        try{
            if(!ready){p.connection.teleport(p.getX(),-48,p.getZ(),0,0);var center=p.blockPosition();for(var pos:BlockPos.betweenClosed(center.offset(-3,-1,-3),center.offset(3,3,3)))p.level().setBlockAndUpdate(pos,pos.getY()<center.getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());ore=center.offset(23,-5,0);for(var pos:BlockPos.betweenClosed(center.offset(-32,-16,-32),center.offset(32,47,32)))if(p.level().getBlockState(pos).is(Blocks.DIAMOND_ORE)||p.level().getBlockState(pos).is(Blocks.DEEPSLATE_DIAMOND_ORE))p.level().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());p.level().setBlockAndUpdate(ore,Blocks.DIAMOND_ORE.defaultBlockState());agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("工具助手",p.getUUID(),p.level(),p.position().add(3,0,0)).agentId();save(s,"fixture",Map.of("ore",List.of(ore.getX(),ore.getY(),ore.getZ()),"center",List.of(center.getX(),center.getY(),center.getZ()),"modelToldCoordinates",false));ready=true;return;}
            var store=ServerConversations.get(s).store();var list=store.list(p.getUUID(),agent,"ALL","",0,20).conversations();if(list.isEmpty())return;var c=list.getFirst();if(c.messageCount()<2||!c.activeOperation().isEmpty())return;var context=store.context(p.getUUID(),agent,c.conversationId(),null).orElseThrow();save(s,"conversation",context);save(s,"scans",scans);
            if(!context.requestState().equals("COMPLETE")||context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash"))throw new IllegalStateException("DIAMOND_REAL_CHAT_FAILED");
            if(scans.size()<2||!((List<?>)scans.getFirst().get("found")).isEmpty())throw new IllegalStateException("DIAMOND_NO_EMPTY_INITIAL_SCAN");int first=((Number)scans.getFirst().get("radius")).intValue();
            boolean found=scans.stream().anyMatch(scan->((Number)scan.get("radius")).intValue()>first&&((List<?>)scan.get("found")).stream().anyMatch(o->((Map<?,?>)o).get("position").equals(List.of(ore.getX(),ore.getY(),ore.getZ()))));if(!found||!p.level().getBlockState(ore).is(Blocks.DIAMOND_ORE))throw new IllegalStateException("DIAMOND_NOT_FOUND_AFTER_EXPANSION");
            var m=store.messages(p.getUUID(),agent,c.conversationId(),0,20).messages().stream().filter(v->v.role().equals("ASSISTANT")).reduce((a,b)->b).orElseThrow();StringBuilder reply=new StringBuilder();for(int offset=0;offset<m.textLength();){var part=store.chunk(p.getUUID(),agent,c.conversationId(),m.messageId(),m.revision(),offset,4096);reply.append(part.text());offset+=part.text().length();}for(int coordinate:new int[]{ore.getX(),ore.getY(),ore.getZ()})if(!reply.toString().contains(Integer.toString(coordinate)))throw new IllegalStateException("DIAMOND_REPLY_COORDINATE_MISSING");
            save(s,"result",Map.of("status","DEEPSEEK_EXPANDING_DIAMOND_SCAN_VERIFIED","scans",scans,"reply",reply.toString(),"oreUnchanged",true,"loadedOnly",true));verified=true;
        }catch(Exception e){failure=e.toString();try{save(s,"failure",Map.of("error",failure,"scans",scans));}catch(Exception ignored){}}
    }
}
