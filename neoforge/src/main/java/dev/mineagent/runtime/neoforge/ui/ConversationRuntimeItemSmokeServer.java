package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.api.packages.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.util.*;

/** Live generation acceptance. Requires an explicit hash-bound local approval after source inspection. */
public final class ConversationRuntimeItemSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile boolean ready,itemReady,used,clientCaptured,verified,observedModeling;
    public static boolean spherical(){return Set.of("runtime_throw_sphere","runtime_throw_sphere_saved").contains(System.getProperty("mineagent.conversationAgentScenario",""));}
    public static volatile String failure="";
    public static volatile UUID agent;
    private static UUID packId,activation,instance;private static String firstHash,usesKey;
    private static int phase;private static final List<String> mutations=new ArrayList<>();
    public static boolean throwing(){return System.getProperty("mineagent.conversationAgentScenario","").startsWith("runtime_throw");}
    public static boolean saved(){return Set.of("runtime_item_saved","runtime_throw_saved","runtime_throw_sphere_saved").contains(System.getProperty("mineagent.conversationAgentScenario",""));}
    public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentReal")&&(throwing()||saved()||System.getProperty("mineagent.conversationAgentScenario","").equals("runtime_item"));}
    public static void observe(String tool,Map<String,Object> result){if(!active())return;mutations.add(tool);if(tool.equals("generate_content_package")&&result.containsKey("packageId"))packId=UUID.fromString(result.get("packageId").toString());}
    private static Path root(MinecraftServer s)throws Exception{return Files.createDirectories(s.getServerDirectory().resolve("runtime-item-smoke"));}
    private static void save(MinecraftServer s,String name,Object value)throws Exception{Files.writeString(root(s).resolve(name+".json"),JSON.writeValueAsString(value));}
    public static void tick(MinecraftServer s){
        if(!failure.isEmpty()||verified)return;ServerPlayer p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;
        try{
            if(!ready){var center=p.blockPosition();for(var pos:net.minecraft.core.BlockPos.betweenClosed(center.offset(throwing()?-16:-3,-1,throwing()?-16:-3),center.offset(throwing()?16:3,throwing()?10:6,throwing()?16:3)))p.level().setBlockAndUpdate(pos,pos.getY()<center.getY()?net.minecraft.world.level.block.Blocks.STONE.defaultBlockState():net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());if(throwing())for(var pos:net.minecraft.core.BlockPos.betweenClosed(center.offset(-16,0,-16),center.offset(16,11,16)))if(Math.abs(pos.getX()-center.getX())==16||Math.abs(pos.getZ()-center.getZ())==16||pos.getY()==center.getY()+11)p.level().setBlockAndUpdate(pos,net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());s.getPlayerList().op(p.nameAndId());p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.inventoryMenu.broadcastFullState();agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("工具助手",p.getUUID(),p.level(),p.position().add(3,0,0)).agentId();ready=true;return;}
            var runtime=WorldContentRuntime.get(s);var packages=ServerPackageRuntime.get(s);
            if(phase==0){
                if(saved()){String raw=Files.readString(s.getServerDirectory().resolve("runtime-item-source.json"));var parsed=new dev.mineagent.runtime.worker.generation.RuntimePackageOutputParser().parse(dev.mineagent.runtime.worker.generation.GeneratedMetadata.complete(raw));packId=packages.importOwned(p,UUID.randomUUID(),parsed).packageId();save(s,"source-artifact",Map.of("sha256",dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"modelCalls",0,"modified",false));}
                else {var store=ServerConversations.get(s).store();var list=store.list(p.getUUID(),agent,"ALL","",0,20).conversations();if(list.isEmpty())return;var c=list.getFirst();if(c.messageCount()<2||!c.activeOperation().isEmpty())return;
                var context=store.context(p.getUUID(),agent,c.conversationId(),null).orElseThrow();save(s,"conversation",context);
                if(!context.requestState().equals("COMPLETE")||packId==null||mutations.size()!=1||!mutations.getFirst().equals("generate_content_package"))throw new IllegalStateException("RUNTIME_ITEM_CHAT_GENERATION_FAILED");}
                if(spherical()&&!saved()&&!observedModeling)throw new IllegalStateException("MODELING_CAPABILITY_NOT_DISCOVERED");
                var pack=packages.worldLibrary().get(packId).orElseThrow();if(pack.activationMode()!=ActivationMode.HOT_RUNTIME||pack.definitions().size()!=1||pack.definitions().values().iterator().next().kind()!=RuntimeDefinitionKind.ITEM)throw new IllegalStateException("RUNTIME_ITEM_PACKAGE_KIND");
                var beforeInventory=new ArrayList<Object>();for(int slot=0;slot<p.getInventory().getContainerSize();slot++){var stack=p.getInventory().getItem(slot);if(stack.is(MineAgentRegistries.RUNTIME_ITEM.get()))throw new IllegalStateException("RUNTIME_ITEM_EXECUTED_WITHOUT_APPROVAL");if(!stack.isEmpty())beforeInventory.add(Map.of("slot",slot,"item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount()));}
                if(!runtime.list(p.getUUID()).isEmpty())throw new IllegalStateException("RUNTIME_ITEM_EXECUTED_WITHOUT_APPROVAL");
                save(s,"preapproval-inventory",Map.of("naturallyCollectedItems",beforeInventory,"runtimeItems",0,"activations",0));
                {for(var dropped:p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,p.getBoundingBox().inflate(24))){if(dropped.getItem().is(MineAgentRegistries.RUNTIME_ITEM.get()))throw new IllegalStateException("RUNTIME_ITEM_EXECUTED_WITHOUT_APPROVAL");dropped.discard();}p.getInventory().clearContent();p.inventoryMenu.broadcastFullState();}
                save(s,"pending-approval",Map.of("package",pack,"mutations",mutations,"nothingExecuted",true));
                var source=new TreeMap<String,String>();for(var r:pack.resources().values())if(r.path().endsWith(".js")||r.path().endsWith(".json"))source.put(r.path(),new String(packages.worldContent().read(r.sha256()),java.nio.charset.StandardCharsets.UTF_8));save(s,"generated-sources",source);phase=1;
            }
            if(phase==1){
                var pack=packages.worldLibrary().get(packId).orElseThrow();Path approval=root(s).resolve("approve.txt");if(!Files.exists(approval)||!Files.readString(approval).strip().equals(pack.canonicalSha256()))return;
                activation=UUID.randomUUID();var result=runtime.activate(p,activation,packId,pack.revision(),pack.definitions().keySet().iterator().next(),new RuntimeInstanceLocation(p.level().dimension().identifier().toString(),p.getX(),p.getY(),p.getZ(),0,0),true,false);save(s,"activation",result);
                if(!result.state().equals("ACTIVE"))throw new IllegalStateException("RUNTIME_ITEM_ACTIVATION_FAILED");instance=result.instanceId();var stack=p.getMainHandItem();var binding=RuntimeItem.binding(stack);
                if(binding==null||!stack.is(MineAgentRegistries.RUNTIME_ITEM.get())||stack.getCount()!=1||!runtime.itemActive(p,stack))throw new IllegalStateException("RUNTIME_ITEM_NATIVE_STACK_MISSING");if(throwing()){ConversationThrowItemSmokeServer.begin(s,p,instance,activation);itemReady=true;phase=4;return;}firstHash=binding.assetHash();var states=runtime.instance(instance).orElseThrow().state();var keys=states.keySet().stream().filter(k->(k.equals("uses")||k.endsWith("_uses"))&&states.get(k).equals("0")).toList();if(keys.size()!=1)throw new IllegalStateException("RUNTIME_ITEM_COUNTER_NOT_DISCOVERABLE");usesKey=keys.getFirst();save(s,"before-use",Map.of("name",stack.getHoverName().getString(),"binding",binding,"count",stack.getCount(),"state",runtime.instance(instance).orElseThrow().state()));itemReady=true;phase=2;
            }
            if(phase==4){ConversationThrowItemSmokeServer.tick(s,p);return;}
            if(phase==2){
                var stack=p.getMainHandItem();var binding=RuntimeItem.binding(stack);if(binding==null||binding.assetHash().equals(firstHash))return;
                if(stack.getCount()!=1||!stack.getHoverName().getString().equals("星辉钥匙·激活")||!runtime.instance(instance).orElseThrow().state().getOrDefault(usesKey,"").equals("1"))throw new IllegalStateException("RUNTIME_ITEM_USE_NOT_MATCHED");
                var ops=p.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);var encoded=net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops,stack).getOrThrow();var restored=net.minecraft.world.item.ItemStack.CODEC.parse(ops,encoded).getOrThrow();if(!binding.equals(RuntimeItem.binding(restored))||restored.getCount()!=1)throw new IllegalStateException("RUNTIME_ITEM_CODEC_ROUNDTRIP");
                save(s,"after-use",Map.of("name",stack.getHoverName().getString(),"binding",binding,"count",stack.getCount(),"state",runtime.instance(instance).orElseThrow().state(),"codecRoundtrip",true));used=true;phase=3;
            }
            if(phase==3&&clientCaptured){
                var stack=p.getMainHandItem();var b=RuntimeItem.binding(stack);var wrong=stack.copy();wrong.set(MineAgentRegistries.RUNTIME_ITEM_BINDING.get(),dev.mineagent.runtime.core.objects.RuntimeItemBinding.create(UUID.randomUUID(),b.instance(),b.part(),b.packageHash(),b.modelPath(),b.modelSource()).encode());if(runtime.itemActive(p,wrong))throw new IllegalStateException("RUNTIME_ITEM_WRONG_WORLD_ACCEPTED");
                runtime.disable(p,activation);if(runtime.itemActive(p,stack)||runtime.useItem(p,net.minecraft.world.InteractionHand.MAIN_HAND)||!runtime.instance(instance).orElseThrow().state().getOrDefault(usesKey,"").equals("1")||stack.getCount()!=1)throw new IllegalStateException("RUNTIME_ITEM_DISABLED_EXECUTED");
                save(s,"result",Map.of("status","REAL_GENERATED_ITEM_NATIVE_VERIFIED","ordinaryChat",!saved(),"hashBoundManualApproval",true,"nativeItemUse",true,"restyledModel",true,"count",1,"wrongWorldRejected",true,"disabledHandlerRejected",true,"codecRoundtrip",true,"newRegistryIdPerDefinition",false));verified=true;
            }
        }catch(Exception e){failure=e.toString();try{save(s,"failure",Map.of("error",failure,"phase",phase));}catch(Exception ignored){}}
    }
}
