package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.ContainerSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ContainerSmokeClient {
    private static final Gson JSON=new Gson();private static boolean opened,backup,busy,ended;private static int ticks,phase,after;private static String view,failure;private static JsonObject probe;
    private static long firstRevision;
    public static void accept(JsonObject p){probe=p;if(p.has("view"))view=p.get("view").getAsString();}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.containerSmoke")||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(phase==0&&host.ready()&&UiClientSessions.current()!=null&&ContainerSmokeServer.agentId!=null&&ticks%10==0)main("""
            if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
            const agent=document.querySelector('#generation-agent');
            if(!RESUME&&!window.__containerGenerated&&[...agent.options].some(o=>o.value===AGENT)){window.__containerGenerated=true;agent.value=AGENT;agent.dispatchEvent(new Event('change',{bubbles:true}));const p=document.querySelector('#generation-prompt');p.value=REQUEST;p.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#generation-submit').click();}
            const card=[...document.querySelectorAll('.generation-job:not(.patch-job)')].find(c=>c.dataset.agentId===AGENT&&c.querySelector('strong').textContent.startsWith('PUBLISHED'));
            if(card&&!window.__containerOpened){const consent=card.querySelector('[data-container-consent]'),button=card.querySelector('[data-container-open]');if(button&&consent){window.__containerOpened=true;consent.scrollIntoView({block:'center'});consent.click();button.click();}}
            """.replace("RESUME",Boolean.toString(ContainerSmokeServer.resume())).replace("AGENT",q(ContainerSmokeServer.agentId)).replace("REQUEST",q(request())));
        if(host.ready()&&ticks%10==0)main("const f=document.querySelector('[data-content-kind=CONTAINER] iframe');window.mineagentQuery({request:JSON.stringify({channel:'containerProbe',view:f?.name??'',status:document.querySelector('#status').textContent}),persistent:false,onSuccess(){},onFailure(){}});");
        if(phase==0&&view!=null&&!view.isBlank()&&PackageContentClient.session(view)!=null&&!busy){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((dom,error)->mc.execute(()->{try{
            if(error!=null)throw new IllegalStateException(error);if(!JsonParser.parseString(dom).getAsJsonObject().get("visibleText").getAsString().contains(ContainerSmokeServer.ITEM_NAME)){busy=false;return;}
            Files.createDirectories(root());Files.writeString(root().resolve("before-dom.json"),dom);Files.writeString(root().resolve("binding.json"),JSON.toJson(PackageContentClient.session(view)));phase=1;after=ticks+20;busy=false;
        }catch(Exception e){failure=e.toString();}}));}
        if(phase==1&&ticks>=after&&!busy){
            if(!(mc.screen instanceof WebGuiInteractionScreen))throw new IllegalStateException("NATIVE_SCREEN_OVERWROTE_WEBGUI");
            busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("before-render.png"));phase=2;busy=false;}catch(Exception e){failure=e.toString();}});
        }
        if(phase==2&&!ContainerSmokeServer.actions()){host.close();ContainerSmokeServer.finish=true;phase=9;}
        if(phase==2&&ContainerSmokeServer.actions()&&!busy)read("initial-native.json",s->{firstRevision=s.get("revision").getAsLong();frame("const b=document.querySelector('[data-slot-index=\"0\"]');b.scrollIntoView({block:'center'});b.click();");phase=3;after=ticks+20;return true;});
        if(phase==3&&ticks>=after&&!busy)read("picked-native.json",s->{if(s.getAsJsonObject("carried").get("count").getAsInt()!=12||count(s,0)!=0)return false;phase=4;after=ticks+20;return true;});
        if(phase==4&&ticks>=after&&!busy){
            if(Boolean.getBoolean("mineagent.containerLongDrag")){
                frame("const a=document.querySelector('[data-slot-index=\"1\"]'),b=document.querySelector('[data-slot-index=\"2\"]'),c=document.querySelector('[data-slot-index=\"3\"]');a.scrollIntoView({block:'center'});const data=new DataTransfer();window.__longContainerDrag={a,b,c,data};a.dispatchEvent(new DragEvent('dragstart',{bubbles:true,cancelable:true,dataTransfer:data}));b.dispatchEvent(new DragEvent('dragenter',{bubbles:true,cancelable:true,dataTransfer:data}));c.dispatchEvent(new DragEvent('dragenter',{bubbles:true,cancelable:true,dataTransfer:data}));");phase=41;after=ticks+70;
            }else{
            frame("const a=document.querySelector('[data-slot-index=\"1\"]'),b=document.querySelector('[data-slot-index=\"2\"]'),c=document.querySelector('[data-slot-index=\"3\"]');a.scrollIntoView({block:'center'});const data=new DataTransfer();a.dispatchEvent(new DragEvent('dragstart',{bubbles:true,cancelable:true,dataTransfer:data}));b.dispatchEvent(new DragEvent('dragenter',{bubbles:true,cancelable:true,dataTransfer:data}));c.dispatchEvent(new DragEvent('dragenter',{bubbles:true,cancelable:true,dataTransfer:data}));c.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:data}));a.dispatchEvent(new DragEvent('dragend',{bubbles:true,cancelable:true,dataTransfer:data}));");phase=5;after=ticks+20;
            }
        }
        if(phase==41&&ticks>=after&&!busy){frame("const {a,b,c,data}=window.__longContainerDrag;const p=document.createElement('p');p.id='gesture-proof';p.textContent=a.isConnected&&b.isConnected&&c.isConnected&&document.querySelector('[data-slot-index=\"3\"]')===c?'LONG_GESTURE_DOM_STABLE':'LONG_GESTURE_DOM_REPLACED';document.body.append(p);c.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:data}));a.dispatchEvent(new DragEvent('dragend',{bubbles:true,cancelable:true,dataTransfer:data}));");phase=5;after=ticks+20;}
        if(phase==5&&ticks>=after&&!busy)read("dragged-native.json",s->{if(s.getAsJsonObject("carried").get("count").getAsInt()!=0||count(s,1)!=4||count(s,2)!=4||count(s,3)!=4)return false;
            frame("document.querySelector('[data-slot-index=\"1\"]').dispatchEvent(new MouseEvent('click',{bubbles:true,shiftKey:true}));");phase=6;after=ticks+20;return true;});
        if(phase==6&&ticks>=after&&!busy)read("quick-move-native.json",s->{if(count(s,1)!=0||playerCount(s)!=4)return false;
            frame("const p=document.createElement('p');p.id='stale-probe';document.body.append(p);mineagentContainer.click("+firstRevision+",2,0,'PICKUP',crypto.randomUUID()).then(()=>p.textContent='STALE_UNEXPECTED_SUCCESS').catch(e=>p.textContent='STALE_REJECTED_'+e.message);");phase=7;after=ticks+20;return true;});
        if(phase==7&&ticks>=after&&!busy){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((dom,error)->mc.execute(()->{try{
            if(error!=null)throw new IllegalStateException(error);if(!JsonParser.parseString(dom).getAsJsonObject().get("visibleText").getAsString().contains("STALE_REJECTED_CONTAINER_STATE_CONFLICT")){busy=false;return;}
            if(Boolean.getBoolean("mineagent.containerLongDrag")&&!dom.contains("LONG_GESTURE_DOM_STABLE"))throw new IllegalStateException("CONTAINER_LONG_GESTURE_REBUILT_DOM");
            Files.writeString(root().resolve("stale-dom.json"),dom);phase=8;after=ticks+20;busy=false;frame("document.querySelector('[data-slot-index=\"2\"]').click();");
        }catch(Exception e){failure=e.toString();}}));}
        if(phase==8&&ticks>=after&&!busy)read("carried-before-close.json",s->{if(s.getAsJsonObject("carried").get("count").getAsInt()!=4||count(s,2)!=0||count(s,3)!=4||playerCount(s)!=4)return false;phase=10;after=ticks+20;return true;});
        if(phase==10&&ticks>=after&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("after-render.png"));phase=11;busy=false;}catch(Exception e){failure=e.toString();}});}
        if(phase==11&&!busy){ContainerSmokeServer.expectedPlayerCount=8;ContainerSmokeServer.expectedChestCount=4;main("document.querySelector('[data-view-id=\""+view+"\"] [aria-label=关闭]').click();");ContainerSmokeServer.finish=true;phase=9;}
        if(phase==9&&ContainerSmokeServer.verified){
            int local=0;for(int i=0;i<mc.player.getInventory().getContainerSize();i++){var stack=mc.player.getInventory().getItem(i);if(stack.getHoverName().getString().equals(ContainerSmokeServer.ITEM_NAME))local+=stack.getCount();}
            if(ContainerSmokeServer.actions()&&local!=8)return;
            Files.writeString(root().resolve("client-result.json"),JSON.toJson(Map.of("stage",ContainerSmokeServer.actions()?"NATIVE_MENU_PICKUP_DRAG_QUICK_MOVE_CLOSE":"NATIVE_MENU_READ","localInventoryNamedCount",local,"driver","TRUSTED_GUI_FIXTURE_NOT_AI_PLANNER","fullV1",false)));
            host.close();if(ContainerSmokeServer.actions()){phase=12;after=ticks+20;}else finish();
        }
        if(phase==12&&ticks>=after){
            if(!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit))throw new IllegalStateException("NORMAL_MENU_TARGET_MISSING");
            mc.gameMode.useItemOn(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);phase=13;after=ticks+20;
        }
        if(phase==13&&ticks>=after&&mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen&&!busy){
            if(mc.player.containerMenu==mc.player.inventoryMenu||mc.player.containerMenu.getSlot(3).getItem().getCount()!=4)throw new IllegalStateException("NORMAL_MENU_REGRESSION");
            Files.writeString(root().resolve("normal-menu.json"),JSON.toJson(Map.of("screen",screen.getClass().getName(),"menuId",mc.player.containerMenu.containerId,"slot3",mc.player.containerMenu.getSlot(3).getItem().getCount(),"ordinaryOpenNotSuppressed",true)));
            busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("normal-menu.png"));phase=14;busy=false;}catch(Exception e){failure=e.toString();}});
        }
        if(phase==14&&!busy){if(mc.screen!=null)mc.screen.onClose();finish();}
        if(failure!=null||ticks>10000){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"error",failure==null?"TIMEOUT":failure,"probe",probe==null?new JsonObject():probe)));ended=true;host.close();mc.stop();throw new IllegalStateException("CONTAINER_SMOKE_FAILED "+failure);}
    }
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(ContainerSmokeServer.directory());}
    private static void finish(){ended=true;WebGuiHostAdapter.INSTANCE.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONTAINER_CLIENT_OK run={}",ContainerSmokeServer.RUN);Minecraft.getInstance().stop();}
    private static String q(String s){return JSON.toJson(s);}
    private static int count(JsonObject state,int slot){for(var e:state.getAsJsonArray("slots"))if(e.getAsJsonObject().get("index").getAsInt()==slot)return e.getAsJsonObject().getAsJsonObject("item").get("count").getAsInt();return -1;}
    private static int playerCount(JsonObject state){int result=0;for(var e:state.getAsJsonArray("slots")){var slot=e.getAsJsonObject();var item=slot.getAsJsonObject("item");if(slot.get("group").getAsString().equals("player")&&item.get("name").getAsString().equals(ContainerSmokeServer.ITEM_NAME))result+=item.get("count").getAsInt();}return result;}
    private static void read(String file,java.util.function.Predicate<JsonObject> next){busy=true;var session=PackageContentClient.session(view);UiClientSessions.contentRequest("command",session,"container.read",Map.of(),UUID.randomUUID()).whenComplete((receipt,error)->{try{
        if(error!=null||receipt.code()!=dev.mineagent.runtime.api.ui.UiProtocol.Code.OBSERVED)throw new IllegalStateException("NATIVE_MENU_READ_FAILED");var state=JsonParser.parseString(receipt.values().get("state")).getAsJsonObject();Files.writeString(root().resolve(file),state.toString());busy=false;next.test(state);
    }catch(Exception e){failure=e.toString();}});}
    private static void frame(String script){var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);for(long id:host.browser().getFrameIdentifiers()){var frame=host.browser().getFrame(id);if(frame!=null&&!frame.isMain()&&url.equals(frame.getURL())){frame.executeJavaScript("(()=>{"+script+"})();",url,0);return;}}throw new IllegalStateException("CONTAINER_FRAME_MISSING");}
    private static void main(String s){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+s+"})();",h.browser().getURL(),0);}
    private static String request(){return "生成全新真实容器操作网页：manifest 除 ui 入口外声明独立 container HTML 入口。用 mineagentContainer SDK 显示实际 slots 和 carried，容器和玩家槽位分组，槽位按钮每个 data-slot-index=实际index；显示实际物品名称和数量，不用假库存/外链图标。click 左击拿起/放下，shift+click QUICK_MOVE；拿起光标堆叠后支持按住 pointerdown 拖过多个槽位再 pointerup，一次调用 drag(revision,槽位列表,0,operationId) 均分；也请提供 HTML5 dragstart/dragenter/drop 的同样功能。真实回执后更新 result.state，冲突可见只刷新不自动重放。初始化只 read，绝不能自动移动。glass-sage 半透明、高对比中文、宽屏9列格子。";}
}
