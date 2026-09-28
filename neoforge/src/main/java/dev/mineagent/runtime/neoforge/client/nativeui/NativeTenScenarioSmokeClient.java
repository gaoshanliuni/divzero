package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import dev.mineagent.runtime.neoforge.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual game-side UI, preview, clearing and image checks in an opt-in isolated test instance. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeTenScenarioSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();
    private interface Action {CompletableFuture<?> run()throws Exception;}
    private record Step(String name,int delay,Action action){}
    private static final ArrayDeque<Step> steps=new ArrayDeque<>();private static final List<String> passed=new ArrayList<>();private static final List<Object> receipts=new ArrayList<>();
    private static boolean requested,accepted,built,busy,done;private static int ticks,wait;private static String current="setup";private static UUID agent,cow,previewOffer;
    private static JsonNode recipe;private static String imageUrl;private static long textureRevision;private static double oldZoom,oldYaw;private static Object oldCenter;private static float oldEntityX;private static UIElement hostRoot;
    private static BooleanSupplier waiting;private static CompletableFuture<Void> awaited;private static int waitDeadline;
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void require(boolean yes,String code){if(!yes)throw new IllegalStateException(code);}
    private static Path output()throws Exception{return Files.createDirectories(mc().gameDirectory.toPath().resolve("native-ten-scenarios"));}
    private static void step(String name,int delay,Action action){steps.addLast(new Step(name,delay,action));}
    private static CompletableFuture<Void> now(Runnable action){action.run();return CompletableFuture.completedFuture(null);}
    private static <T> CompletableFuture<T> server(Callable<T> action){var result=new CompletableFuture<T>();mc().getSingleplayerServer().submit(()->{try{return action.call();}catch(Exception e){throw new CompletionException(e);}}).whenComplete((value,error)->mc().execute(()->{if(error!=null)result.completeExceptionally(error);else result.complete(value);}));return result;}
    private static CompletableFuture<JsonNode> tool(String name,ObjectNode args){var owner=mc().player.getUUID();return server(()->ConversationAgentTools.execute(mc().getSingleplayerServer().getPlayerList().getPlayer(owner),agent,UUID.randomUUID(),name,args.toString(),()->true)).thenCompose(future->{var result=new CompletableFuture<JsonNode>();future.whenComplete((value,error)->mc().execute(()->{if(error!=null)result.completeExceptionally(error);else{var data=JSON.valueToTree(value);receipts.add(Map.of("tool",name,"status",data.path("status").asText(),"error",data.path("error").asText()));result.complete(data);}}));return result;});}
    private static CompletableFuture<Void> until(BooleanSupplier condition){waiting=condition;awaited=new CompletableFuture<>();waitDeadline=ticks+600;return awaited;}
    private static CompletableFuture<Void> screenshot(String name)throws Exception{var result=new CompletableFuture<Void>();var path=output().resolve(name+".png");net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(path);result.complete(null);}catch(Exception error){result.completeExceptionally(error);}});return result;}
    private static UIElement find(UIElement node,String id){if(node.getId().equals(id))return node;for(var child:node.getChildren()){var result=find(child,id);if(result!=null)return result;}return null;}
    private static ModularUI ui(){require(mc().screen instanceof ModularUIScreen,"NATIVE_SCREEN_REQUIRED");return ((ModularUIScreen)mc().screen).getModularUI();}
    private static void pointer(UIElement element,int button,boolean drag){
        require(mc().isWindowActive(),"TEST_WINDOW_FOCUS");var ui=ui();float x=element.getPositionX()+element.getSizeWidth()/2,y=element.getPositionY()+element.getSizeHeight()/2;ui.refreshHoveredElementAtScreen(x,y);
        var down=new MouseButtonEvent(x,y,new MouseButtonInfo(button,0));mc().screen.mouseClicked(down,false);
        if(drag){ui.refreshHoveredElementAtScreen(x+20,y+10);mc().screen.mouseDragged(new MouseButtonEvent(x+20,y+10,new MouseButtonInfo(button,0)),20,10);}
        mc().screen.mouseReleased(new MouseButtonEvent(drag?x+20:x,drag?y+10:y,new MouseButtonInfo(button,0)));
    }
    private static ObjectNode definition(String id,String surface){var n=JSON.createObjectNode().put("id",id).put("title",id).put("surface",surface);return n;}
    private static CompletableFuture<JsonNode> define(ObjectNode source){return tool("set_native_ui",JSON.createObjectNode().put("id",source.path("id").asText()).put("expected_revision",0).put("source",source.toString())).thenApply(reply->{require(reply.path("status").asText().equals("APPLIED"),"UI_NOT_APPLIED_"+reply);return reply;});}
    private static CompletableFuture<JsonNode> hide(String id){return tool("control_native_ui",JSON.createObjectNode().put("id",id).put("expected_revision",1).put("action","hide"));}
    private static Map<String,Object> entityRow(){return ((List<Map<String,Object>>)NativeAttachedLayers.smoke("health").get("entities")).stream().filter(row->row.get("id").equals(cow.toString())).findFirst().orElseThrow();}
    private static ObjectNode timer(){var n=definition("furnace_timer","SCREEN_OVERLAY");n.putObject("attachment").put("menu","furnace").put("anchor","top").put("y",-5);n.putObject("sources").putObject("seconds").put("kind","menu").put("field","furnace_remaining_seconds");var root=n.putObject("root").put("id","timer").put("type","label").put("style","width: 150; height: 22; background: #dd202020; color: #ffffff;");root.putObject("bindings").putObject("text").put("op","concat").putArray("args").add("剩余 / Remaining: ").addObject().put("data","seconds");return n;}
    private static void setup(){
        step("world-and-agent",0,()->server(()->{
            var s=mc().getSingleplayerServer();var p=s.getPlayerList().getPlayer(mc().player.getUUID());s.getPlayerList().op(p.nameAndId());p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.teleportTo(p.level(),.5,101,6.5,Set.of(),180,0,true);
            for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)for(int y=100;y<=108;y++)p.level().setBlock(new BlockPos(x,y,z),y==100?Blocks.SMOOTH_STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            for(int x=-1;x<=1;x++)for(int y=101;y<=104;y++)p.level().setBlock(new BlockPos(x,y,-2),Blocks.STONE.defaultBlockState(),2);
            p.level().setBlock(new BlockPos(2,101,0),Blocks.FURNACE.defaultBlockState(),3);var furnace=(net.minecraft.world.level.block.entity.FurnaceBlockEntity)p.level().getBlockEntity(new BlockPos(2,101,0));furnace.setItem(0,new ItemStack(Items.IRON_ORE,64));furnace.setItem(1,new ItemStack(Items.COAL,8));
            p.level().setBlock(new BlockPos(-2,101,0),Blocks.BREWING_STAND.defaultBlockState(),3);
            agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("TenScenarioAI",p.getUUID(),p.level(),p.position().add(3,0,0)).agentId();var entity=net.minecraft.world.entity.EntityType.COW.create(p.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);entity.setPos(.5,101,1.5);entity.setNoAi(true);entity.setPersistenceRequired();p.level().addFreshEntity(entity);cow=entity.getUUID();p.getInventory().setItem(0,new ItemStack(Items.DIAMOND_CHESTPLATE));return true;
        }));
        step("install-furnace-overlay",25,()->define(timer()));
        step("open-real-furnace",10,()->server(()->{var p=mc().getSingleplayerServer().getPlayerList().getPlayer(mc().player.getUUID());p.openMenu((net.minecraft.world.level.block.entity.FurnaceBlockEntity)p.level().getBlockEntity(new BlockPos(2,101,0)));return true;}));
        step("furnace-live-render",30,()->{require(Boolean.TRUE.equals(NativeAttachedLayers.smoke("furnace_timer").get("attached")),"FURNACE_ATTACHMENT_MISSING");require(((Number)NativeAttachedLayers.smoke("furnace_timer").get("painted")).intValue()>0,"FURNACE_NOT_PAINTED");return tool("inspect_native_ui",JSON.createObjectNode().put("id","furnace_timer")).thenAccept(data->{double seconds=data.path("client").path("views").get(0).path("data").path("seconds").asDouble(-1);require(seconds>=0&&seconds<=10,"FURNACE_REAL_TIME");});});
        step("furnace-screenshot",5,()->screenshot("01-furnace-overlay"));
        step("discover-brewing-recipes",0,()->tool("inspect_brewing_recipes",JSON.createObjectNode()).thenAccept(data->{require(data.path("recipes").size()>0,"NO_BREWING_RECIPES");recipe=data.path("recipes").get(0);}));
        step("install-recipe-list",0,()->{var n=definition("recipes","SCREEN_OVERLAY");n.putObject("attachment").put("menu","minecraft:brewing_stand").put("anchor","left").put("x",-7);var root=n.putObject("root").put("id","recipe-list").put("type","column").put("style","width: 145; height: 125; background: #ee333333; padding-all: 7;");var rows=root.putArray("children");rows.addObject().put("id","recipe-heading").put("type","label").put("text","药水配方 / Brewing").put("style","color: #ffffff; height: 20;");var button=rows.addObject().put("id","recipe-preview").put("type","button").put("text",recipe.path("name").asText()).put("style","height: 28;");button.putObject("events").putArray("click").addObject().put("op","emit").put("action","preview");n.putObject("handlers").putObject("preview").put("tool","open_preview").putObject("arguments").put("kind","recipe").put("recipe_id",recipe.path("id").asText());return define(n);});
        step("open-real-brewing-menu",0,()->server(()->{var p=mc().getSingleplayerServer().getPlayerList().getPlayer(mc().player.getUUID());p.openMenu((net.minecraft.world.level.block.entity.BrewingStandBlockEntity)p.level().getBlockEntity(new BlockPos(-2,101,0)));return true;}));
        step("brewing-screenshot",30,()->{require(Boolean.TRUE.equals(NativeAttachedLayers.smoke("recipes").get("attached")),"BREWING_ATTACHMENT_MISSING");require(!Boolean.TRUE.equals(NativeAttachedLayers.smoke("furnace_timer").get("attached")),"OLD_MENU_ATTACHMENT_RETAINED");return screenshot("02-brewing-recipes");});
        step("recipe-click-input",0,()->now(()->{
            var button=NativeInterfacesClient.smokeWidget("recipes","recipe-preview");float x=button.getPositionX()+button.getSizeWidth()/2,y=button.getPositionY()+button.getSizeHeight()/2;var event=new MouseButtonEvent(x,y,new MouseButtonInfo(0,0));
            var down=new net.neoforged.neoforge.client.event.ScreenEvent.MouseButtonPressed.Pre(mc().screen,event,false);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(down);require(down.isCanceled(),"OVERLAY_DID_NOT_OWN_CLICK");var up=new net.neoforged.neoforge.client.event.ScreenEvent.MouseButtonReleased.Pre(mc().screen,event);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(up);require(up.isCanceled(),"OVERLAY_RELEASE_NOT_HANDLED");
        }));
        step("recipe-native-preview",20,()->until(()->Boolean.TRUE.equals(NativePreview.smokeState().get("native"))&&Boolean.TRUE.equals(NativePreview.smokeState().get("painted"))));
        step("preview-rotate",0,()->now(()->{oldYaw=((Number)NativePreview.smokeState().get("yaw")).doubleValue();pointer(NativePreview.smokeCanvas(),0,true);}));
        step("preview-pan",8,()->now(()->{require(((Number)NativePreview.smokeState().get("yaw")).doubleValue()!=oldYaw,"PREVIEW_NO_ROTATION");oldCenter=NativePreview.smokeState().get("center");pointer(NativePreview.smokeCanvas(),1,true);}));
        step("preview-zoom",8,()->now(()->{require(!oldCenter.equals(NativePreview.smokeState().get("center")),"PREVIEW_NO_PAN");oldZoom=((Number)NativePreview.smokeState().get("zoom")).doubleValue();var canvas=NativePreview.smokeCanvas();float x=canvas.getPositionX()+canvas.getSizeWidth()/2,y=canvas.getPositionY()+canvas.getSizeHeight()/2;ui().refreshHoveredElementAtScreen(x,y);mc().screen.mouseScrolled(x,y,0,2);}));
        step("recipe-preview-screenshot",12,()->{require(((Number)NativePreview.smokeState().get("zoom")).doubleValue()!=oldZoom,"PREVIEW_NO_ZOOM");return screenshot("03-recipe-scene");});
        step("native-entity-preview",0,()->tool("open_preview",JSON.createObjectNode().put("kind","entity").put("entity_id",cow.toString())).thenAccept(data->require(data.path("status").asText().equals("PREVIEW_OPENED"),"ENTITY_PREVIEW_OPEN")));
        step("entity-render",25,()->until(()->Boolean.TRUE.equals(NativePreview.smokeState().get("native"))&&Boolean.TRUE.equals(NativePreview.smokeState().get("painted"))));
        step("entity-screenshot",10,()->screenshot("04-native-entity-scene"));
        step("chat-preview-offer",0,()->tool("open_preview",JSON.createObjectNode().put("kind","item").put("slot",0).put("display","chat")).thenAccept(data->{require(data.path("status").asText().equals("PREVIEW_OFFERED"),"PREVIEW_OFFER");previewOffer=UUID.fromString(data.path("previewId").asText());mc().player.connection.sendCommand("ai preview "+previewOffer);}));
        step("offered-item-preview",25,()->until(()->Boolean.TRUE.equals(NativePreview.smokeState().get("native"))&&Boolean.TRUE.equals(NativePreview.smokeState().get("painted"))));
        step("item-screenshot",10,()->screenshot("05-native-item-scene"));
        step("third-party-editor",0,()->now(()->{var editor=new com.lowdragmc.lowdraglib2.test.TestEditor();editor.setId("thirdparty-editor");hostRoot=editor;mc().setScreen(new ModularUIScreen(new ModularUI(UI.of(editor,List.of(NativeUiTheme.mc()),size->size),mc().player),Component.literal("LDLib2 Editor")));}));
        step("inject-third-party-ui",20,()->{var n=definition("editor_skin","SCREEN_OVERLAY");n.putObject("attachment").put("screen_class",ModularUIScreen.class.getName()).put("screen_title","LDLib2 Editor").put("anchor","absolute").put("x",14).put("y",60).put("host_stylesheet","#thirdparty-editor { opacity: 0.82; }");n.putObject("root").put("id","injected").put("type","label").put("text","AI 附加工具 / Injected tools").put("style","width: 245; height: 26; background: #ff174c92; color: #ffffff;");return define(n);});
        step("third-party-style-screenshot",25,()->{require(Math.abs(hostRoot.getStyle().opacity()-.82f)<.01,"HOST_STYLE_NOT_APPLIED");require(Boolean.TRUE.equals(NativeAttachedLayers.smoke("editor_skin").get("attached")),"THIRD_PARTY_ATTACHMENT");return screenshot("06-third-party-skin");});
        step("detach-third-party-style",0,()->hide("editor_skin"));
        step("host-style-restored",15,()->now(()->{require(Math.abs(hostRoot.getStyle().opacity()-1)<.01,"HOST_STYLE_NOT_RESTORED");mc().setScreen(null);}));
        step("desktop",0,()->{var n=definition("desktop","SCREEN");n.put("stylesheet",".divzero-window-title { background: #ff1649a3; } .divzero-desktop-dock { background: #ff173a68; }");var root=n.putObject("root").put("id","desktop-root").put("type","panel").put("style","width: 100%; height: 100%; background: #ff337f9c;");var children=root.putArray("children");for(int i=0;i<2;i++){var window=children.addObject().put("id",i==0?"notes":"files").put("type","window").put("text",i==0?"Notes / 记事本":"Files / 文件").put("style","left: "+(35+i*320)+"; top: "+(35+i*80)+"; width: 290; height: 230;");window.putArray("children").addObject().put("id","content"+i).put("type","label").put("text",i==0?"AI 创建的独立窗口":"可拖动、缩放、最小化和恢复");}return define(n);});
        step("desktop-screenshot",30,()->screenshot("07-ai-desktop"));
        step("desktop-minimize",0,()->now(()->{var window=NativeInterfacesClient.smokeWidget("desktop","notes");var button=window.selfAndAllChildren().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.text.getText().getString().equals("—")).findFirst().orElseThrow();pointer(button,0,false);}));
        step("desktop-restore",12,()->now(()->{require(!NativeInterfacesClient.smokeWidget("desktop","notes").isDisplayed(),"DESKTOP_MINIMIZE");var task=ui().ui.rootElement.selfAndAllChildren().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.hasClass("divzero-window-task")&&b.text.getText().getString().startsWith("Notes")).findFirst().orElseThrow();pointer(task,0,false);}));
        step("desktop-restored",12,()->{require(NativeInterfacesClient.smokeWidget("desktop","notes").isDisplayed(),"DESKTOP_RESTORE");return hide("desktop");});
        step("world-health-bars",10,()->{mc().setScreen(null);var n=definition("health","ENTITY_HUD");n.putObject("attachment").put("range",32);var root=n.putObject("root").put("id","bar").put("type","column").put("style","width: 95; height: 40; background: #bb181818; padding-all: 3;");var children=root.putArray("children");var label=children.addObject().put("id","hp").put("type","label").put("style","height: 12; color: #ffffff;");label.putObject("bindings").putObject("text").put("op","get").putArray("args").addObject().put("data","entity");((com.fasterxml.jackson.databind.node.ArrayNode)label.path("bindings").path("text").path("args")).add("health");var progress=children.addObject().put("id","value").put("type","progress").put("style","height: 10;");var expression=progress.putObject("bindings").putObject("value").put("op","div").putArray("args");for(String key:List.of("health","maxHealth")){var get=expression.addObject().put("op","get").putArray("args");get.addObject().put("data","entity");get.add(key);}return define(n);});
        step("health-visible",25,()->until(()->{try{entityRow();return true;}catch(Exception absent){return false;}}));
        step("health-before-screenshot",0,()->{require(mc().screen==null,"HUD_GRABBED_SCREEN");oldEntityX=((Number)entityRow().get("x")).floatValue();return screenshot("08-entity-health-bars");});
        step("damage-and-move",0,()->server(()->{var entity=(net.minecraft.world.entity.LivingEntity)mc().getSingleplayerServer().overworld().getEntity(cow);entity.setHealth(6);entity.setPos(2.5,101,1.5);return true;}));
        step("health-after-change",30,()->{var row=entityRow();require(row.get("texts").toString().contains("6"),"HUD_HEALTH_NOT_LIVE");require(Math.abs(((Number)row.get("x")).floatValue()-oldEntityX)>3,"HUD_NOT_WORLD_ANCHORED");return screenshot("09-entity-health-updated");});
        step("hide-health",0,()->hide("health"));
        step("bulldozer-fixture",0,()->server(()->{var p=mc().getSingleplayerServer().getPlayerList().getPlayer(mc().player.getUUID());p.teleportTo(p.level(),-5.5,101,3.5,Set.of(),0,0,true);for(int x=-7;x<=-5;x++)for(int y=101;y<=102;y++)for(int z=3;z<=5;z++)p.level().setBlock(new BlockPos(x,y,z),Blocks.DIRT.defaultBlockState(),2);p.level().setBlock(new BlockPos(-6,101,3),Blocks.AIR.defaultBlockState(),2);p.level().setBlock(new BlockPos(-7,101,5),Blocks.CHEST.defaultBlockState(),2);return true;}));
        step("start-bulldozer",10,()->{var args=JSON.createObjectNode().put("id","dozer").put("expected_revision",0).put("target","$viewer").put("dimension","minecraft:overworld").put("width",3).put("height",2).put("depth",3).put("drop_items",false);args.putArray("min").add(-8).add(101).add(2);args.putArray("max").add(-3).add(104).add(7);return tool("set_bulldozer",args).thenAccept(data->require(data.path("status").asText().equals("STARTED"),"DOZER_START"));});
        step("bulldozer-actual-blocks",30,()->server(()->{var level=mc().getSingleplayerServer().overworld();require(level.getBlockState(new BlockPos(-6,102,4)).isAir(),"DOZER_DID_NOT_CLEAR");require(level.getBlockState(new BlockPos(-6,100,4)).is(Blocks.SMOOTH_STONE),"DOZER_REMOVED_FLOOR");require(level.getBlockState(new BlockPos(-7,101,5)).is(Blocks.CHEST),"DOZER_REMOVED_PROTECTED_CONTAINER");return true;}));
        step("pause-bulldozer",0,()->tool("control_bulldozer",JSON.createObjectNode().put("id","dozer").put("expected_revision",1).put("action","pause")));
        step("paused-block-placement",10,()->server(()->{mc().getSingleplayerServer().overworld().setBlock(new BlockPos(-6,102,4),Blocks.DIRT.defaultBlockState(),2);return true;}));
        step("paused-no-clear",20,()->server(()->{require(mc().getSingleplayerServer().overworld().getBlockState(new BlockPos(-6,102,4)).is(Blocks.DIRT),"DOZER_PAUSE_FAILED");return true;}));
        step("resume-bulldozer",0,()->tool("control_bulldozer",JSON.createObjectNode().put("id","dozer").put("expected_revision",2).put("action","resume")));
        step("resumed-clear",25,()->server(()->{require(mc().getSingleplayerServer().overworld().getBlockState(new BlockPos(-6,102,4)).isAir(),"DOZER_RESUME_FAILED");return true;}));
        step("stop-bulldozer",0,()->tool("control_bulldozer",JSON.createObjectNode().put("id","dozer").put("expected_revision",3).put("action","stop")));
        step("image-search",0,()->tool("search_images",JSON.createObjectNode().put("query","洛天依")).thenAccept(data->{var images=data.path("images");for(var entry:images)if(entry.path("kind").asText().equals("ARTICLE_PRIMARY_IMAGE")&&entry.path("filename").asText().contains("Tianyi"))imageUrl=entry.path("url").asText();require(imageUrl!=null,"LUO_CHARACTER_IMAGE_NOT_FOUND");receipts.add(Map.of("imageSource",imageUrl));}));
        step("texture-inspect",0,()->tool("inspect_block_textures",JSON.createObjectNode().put("block_id","minecraft:stone")).thenAccept(data->{require(data.path("status").asText().equals("OBSERVED"),"TEXTURE_INSPECT");textureRevision=data.path("revision").asLong();}));
        step("apply-image-texture",0,()->tool("set_block_texture",JSON.createObjectNode().put("block_id","minecraft:stone").put("image_url",imageUrl).put("expected_revision",textureRevision).put("size",256).put("fit","contain")).thenAccept(data->{require(data.path("status").asText().equals("APPLIED")&&data.path("verifiedSprites").asInt()>0,"TEXTURE_NOT_VERIFIED_"+data);textureRevision=data.path("revision").asLong();receipts.add(Map.of("texture",data));}));
        step("texture-view",30,()->server(()->{var p=mc().getSingleplayerServer().getPlayerList().getPlayer(mc().player.getUUID());p.teleportTo(p.level(),.5,101,5.5,Set.of(),180,0,true);return true;}));
        step("texture-screenshot",45,()->screenshot("10-luo-tianyi-stone"));
        step("restore-texture",0,()->tool("clear_block_texture",JSON.createObjectNode().put("block_id","minecraft:stone").put("expected_revision",textureRevision)).thenAccept(data->require(data.path("status").asText().equals("APPLIED"),"TEXTURE_RESTORE_FAILED_"+data)));
        step("restored-texture-screenshot",40,()->screenshot("11-stone-restored"));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.nativeTenSmoke")||done)return;
        try{
            if(++ticks>18000)throw new IllegalStateException("TEN_SCENARIO_TIMEOUT");if(mc().player==null||mc().level==null)return;
            if(waiting!=null){if(waiting.getAsBoolean()){waiting=null;var future=awaited;awaited=null;future.complete(null);}else if(ticks>=waitDeadline)throw new IllegalStateException("AWAIT_TIMEOUT");}
            if(busy)return;
            if(!requested){requested=true;net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new MineAgentPayloads.PanelRequest());return;}
            if(!accepted){var values=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();var fingerprint=values.getOrDefault("security.identityFingerprint","");if(fingerprint.isEmpty()||!dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.signatureValid())return;new dev.mineagent.runtime.client.trust.ServerTrustStore(mc().gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).confirm("local-integrated",fingerprint,Base64.getDecoder().decode(values.get("security.identityPublicKey")));mc().player.connection.sendCommand("ai accept");accepted=true;GLFW.glfwFocusWindow(mc().getWindow().handle());return;}
            if(!built){if(++wait<40)return;wait=0;built=true;setup();}
            if(steps.isEmpty()){Files.writeString(output().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","modelCalls",0,"checks",passed,"receipts",receipts)));done=true;mc().stop();return;}
            var step=steps.getFirst();if(++wait<step.delay())return;wait=0;steps.removeFirst();current=step.name();busy=true;step.action().run().whenComplete((value,error)->mc().execute(()->{busy=false;if(error!=null)fail(error);else passed.add(current);}));
        }catch(Exception error){fail(error);}
    }
    private static void fail(Throwable error){if(done)return;done=true;try{Files.writeString(output().resolve("failure.json"),JSON.writeValueAsString(Map.of("stage",current,"error",error.toString(),"checks",passed,"receipts",receipts)));}catch(Exception ignored){}mc().stop();}
    private NativeTenScenarioSmokeClient(){}
}
