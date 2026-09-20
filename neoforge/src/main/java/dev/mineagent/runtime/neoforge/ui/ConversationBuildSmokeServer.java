package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;

/** Test-bed preparation and independent fluid-tick verification. Never supplies a generator plan. */
public final class ConversationBuildSmokeServer {
    public static volatile boolean ready,verified;
    public static volatile String failure="";
    public static volatile UUID agent;
    private static volatile Map<String,Object> blueprintRead=Map.of();
    private static final List<Map<String,Object>> webEvidence=new ArrayList<>();
    public static boolean web(){return System.getProperty("mineagent.conversationAgentScenario","").equals("web");}
    public static void observeWeb(String tool,Map<String,Object> value){
        if(!active()||!web())return;var evidence=new LinkedHashMap<String,Object>();evidence.put("tool",tool);
        for(String key:List.of("status","kind","provider","url","title","fetchedAt","sha256","query"))if(value.containsKey(key))evidence.put(key,value.get(key));
        evidence.put("textChars",String.valueOf(value.getOrDefault("text","")).length());evidence.put("resultCount",value.get("results") instanceof List<?> list?list.size():0);webEvidence.add(evidence);
    }
    public static boolean blueprint(){return System.getProperty("mineagent.conversationAgentScenario","").equals("blueprint");}
    public static void observeBlueprint(Map<String,Object> value){if(active()&&blueprint()&&value.containsKey("sha256"))blueprintRead=Map.copyOf(value);}
    private static BlockPos center,output;
    private static int phase,deadline,removedAt;
    private static final List<Map<String,Object>> cycles=new ArrayList<>();
    public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentReal")&&Set.of("cobble","blueprint","web").contains(System.getProperty("mineagent.conversationAgentScenario",""));}
    private static void save(MinecraftServer s,String name,Object value)throws Exception{
        Path root=Files.createDirectories(s.getServerDirectory().resolve("conversation-build-smoke"));Files.writeString(root.resolve(name+".json"),new ObjectMapper().writeValueAsString(value));
    }
    public static void tick(MinecraftServer s){
        if(!failure.isEmpty()||verified)return;
        ServerPlayer p=s.getPlayerList().getPlayers().stream().filter(x->!(x instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;
        try{
            if(!ready&&(blueprint()||web())){agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("工具助手",p.getUUID(),p.level(),p.position().add(3,0,0)).agentId();ready=true;return;}
            if(!ready){
                s.getPlayerList().op(p.nameAndId());p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);center=p.blockPosition();
                for(var pos:BlockPos.betweenClosed(center.offset(-12,-3,-12),center.offset(12,8,12)))p.level().setBlockAndUpdate(pos,pos.getY()<center.getY()?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState());
                p.getInventory().clearContent();p.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));p.getInventory().setSelectedSlot(0);p.inventoryMenu.broadcastChanges();
                agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("工具助手",p.getUUID(),p.level(),p.position().add(-8,0,-8)).agentId();
                save(s,"initial",Map.of("center",List.of(center.getX(),center.getY(),center.getZ()),"fixture","Cleared dirt test plot; no water, lava or cobblestone placed by fixture","agent",agent));ready=true;return;
            }
            if(phase==0){
                var store=ServerConversations.get(s).store();var conversations=store.list(p.getUUID(),agent,"ALL","",0,20).conversations();if(conversations.isEmpty())return;
                var c=conversations.getFirst();if(c.messageCount()<2||!c.activeOperation().isEmpty())return;
                var context=store.context(p.getUUID(),agent,c.conversationId(),null).orElseThrow();save(s,"conversation",Map.of("conversation",c,"context",context));
                if(!context.requestState().equals("COMPLETE"))throw new IllegalStateException("BUILD_CONVERSATION_"+context.errorCode());
                if(context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash")||!context.modelReceipt().streamMode().equals("PROVIDER_STREAM"))throw new IllegalStateException("BUILD_REAL_PROVIDER_RECEIPT");
                if(web()){
                    save(s,"web-evidence",webEvidence);var pages=webEvidence.stream().filter(v->"FETCHED_PAGE_TEXT".equals(v.get("kind"))&&((Number)v.get("textChars")).intValue()>80).toList();
                    if(pages.isEmpty()||webEvidence.stream().noneMatch(v->"web_search".equals(v.get("tool"))&&((Number)v.get("resultCount")).intValue()>0))throw new IllegalStateException("WEB_MODEL_MUST_SEARCH_AND_READ");
                    var message=store.messages(p.getUUID(),agent,c.conversationId(),0,20).messages().stream().filter(m->m.role().equals("ASSISTANT")).reduce((a,b)->b).orElseThrow();
                    StringBuilder reply=new StringBuilder();for(int offset=0;offset<message.textLength();){var part=store.chunk(p.getUUID(),agent,c.conversationId(),message.messageId(),message.revision(),offset,4096);reply.append(part.text());offset+=part.text().length();}
                    if(pages.stream().noneMatch(v->reply.toString().contains(String.valueOf(v.get("url")))))throw new IllegalStateException("WEB_REPLY_NO_FETCHED_SOURCE_LINK");
                    save(s,"result",Map.of("status","LIVE_WEB_RESEARCH_NATIVE_VERIFIED","sources",pages,"reply",reply.toString()));verified=true;return;
                }
                if(blueprint()){
                    if(blueprintRead.isEmpty()||!blueprintRead.get("name").equals(System.getProperty("mineagent.conversationAgentBlueprintName")))throw new IllegalStateException("BLUEPRINT_MODEL_DID_NOT_READ_SELECTED_FILE");
                    save(s,"blueprint-readback",blueprintRead);save(s,"result",Map.of("status","REAL_BLUEPRINT_READ_NATIVE_VERIFIED","file",blueprintRead.get("name"),"sha256",blueprintRead.get("sha256"),"size",blueprintRead.get("size"),"totalBlocks",blueprintRead.get("totalBlocks"),"createInstalled",net.neoforged.fml.ModList.get().isLoaded("create"),"placedBlocks",false));verified=true;return;
                }
                phase=1;deadline=s.getTickCount()+160;
            }
            if(phase==1){
                for(var pos:BlockPos.betweenClosed(center.offset(-12,-3,-12),center.offset(12,8,12))){
                    if(!p.level().getBlockState(pos).is(Blocks.COBBLESTONE))continue;boolean water=false,lava=false;
                    for(Direction direction:Direction.Plane.HORIZONTAL){var fluid=p.level().getFluidState(pos.relative(direction));water|=fluid.is(FluidTags.WATER);lava|=fluid.is(FluidTags.LAVA);}
                    if(water&&lava){output=pos.immutable();break;}
                }
                if(output==null){if(s.getTickCount()>deadline)throw new IllegalStateException("BUILD_NO_FLUID_COBBLE_OUTPUT");return;}
                save(s,"generator-before-mining",snapshot(p));removeOutput(s,p);phase=2;
            }
            if(phase==2){
                if(p.level().getBlockState(output).is(Blocks.COBBLESTONE)){
                    cycles.add(Map.of("removedTick",removedAt,"regeneratedTick",s.getTickCount(),"elapsedTicks",s.getTickCount()-removedAt));
                    if(cycles.size()<3){removeOutput(s,p);return;}
                    save(s,"generator-after-mining",snapshot(p));save(s,"result",Map.of("status","NATIVE_COBBLE_GENERATOR_REGENERATED","output",List.of(output.getX(),output.getY(),output.getZ()),"cycles",cycles,"fixturePlacesCobblestone",false,"removal","native destroyBlock; subsequent block production left to fluid ticks"));verified=true;
                }else if(s.getTickCount()>deadline)throw new IllegalStateException("BUILD_COBBLE_DID_NOT_REGENERATE");
            }
        }catch(Exception e){failure=e.toString();try{save(s,"failure",Map.of("error",failure,"phase",phase,"cycles",cycles));}catch(Exception ignored){}}
    }
    private static void removeOutput(MinecraftServer s,ServerPlayer p){
        if(!p.level().getBlockState(output).is(Blocks.COBBLESTONE)||!p.level().destroyBlock(output,true,p))throw new IllegalStateException("BUILD_NATIVE_REMOVAL_FAILED");
        if(p.level().getBlockState(output).is(Blocks.COBBLESTONE))throw new IllegalStateException("BUILD_REMOVAL_NOT_OBSERVED");removedAt=s.getTickCount();deadline=removedAt+160;
    }
    private static List<Map<String,Object>> snapshot(ServerPlayer p){
        var blocks=new ArrayList<Map<String,Object>>();for(var pos:BlockPos.betweenClosed(output.offset(-3,-2,-3),output.offset(3,2,3)))blocks.add(Map.of("position",List.of(pos.getX(),pos.getY(),pos.getZ()),"state",net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(p.level().getBlockState(pos))));return blocks;
    }
}
