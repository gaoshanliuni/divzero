package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.neoforge.ui.WorldBoardSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldBoardSmokeClient {
    private static final Gson JSON=new Gson();private static int ticks,phase,after;private static boolean opened,backup,busy,ended;
    private static String failure,hud;private static JsonObject probe;
    private static String deferredCapture;private static int deferredNext;
    public static void accept(JsonObject p){probe=p;hud=p.has("hud")?p.get("hud").getAsString():hud;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldBoardSmoke")||ended)return;
        var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened&&!WorldBoardSmokeServer.restore()){opened=true;host.open();}
        String id=WorldBoardSmokeServer.viewId;
        if(phase==0&&host.ready()&&UiClientSessions.current()!=null&&ticks%10==0&&!WorldBoardSmokeServer.restore())main("""
            document.querySelector('#open-generation').click();const card=[...document.querySelectorAll('.generation-job:not(.patch-job)')].find(c=>c.dataset.packageId===PACKAGE);if(!card)return;
            const source=card.querySelector('select[aria-label="已有计分目标"]');
            if(!window.__wbBound&&source&&[...source.options].some(o=>o.value===SOURCE)){window.__wbBound=true;source.value=SOURCE;source.dispatchEvent(new Event('change',{bubbles:true}));[...card.querySelectorAll('button')].find(b=>b.textContent==='创建独立绑定视图').click();}
            if(!TARGET)return;
            const hud=card.querySelector('[data-hud-view-id="'+TARGET+'"]');if(hud&&!window.__wbHud){window.__wbHud=true;hud.click();}
            const panel=card.querySelector('[data-world-view-id="'+TARGET+'"]');if(panel&&!window.__wbFront&&HUD_READY){window.__wbFront=true;if(!panel.open)panel.querySelector('summary').click();panel.querySelector('[data-world-action=worldFront]').scrollIntoView({block:'center'});panel.querySelector('[data-world-action=worldFront]').click();}
            """.replace("PACKAGE",q(WorldBoardSmokeServer.packageId().toString())).replace("SOURCE",q(WorldBoardSmokeServer.sourceId)).replace("TARGET",q(id)).replace("HUD_READY",Boolean.toString(hud!=null&&!hud.isBlank()&&PackageContentClient.session(hud)!=null)));
        if(host.ready()&&ticks%10==0)main("const f=document.querySelector('[data-mode=PASSIVE_HUD] iframe');window.mineagentQuery({request:JSON.stringify({channel:'worldBoardProbe',hud:f?.name??'',status:document.querySelector('#status').textContent}),persistent:false,onSuccess(){},onFailure(){}});");
        var entity=id==null?null:WorldBoardClient.entity(UUID.fromString(id));String text=entity==null||entity.textRenderState()==null?"":entity.textRenderState().text().getString();
        if(phase==0&&entity!=null&&!busy&&text.contains("Cedar  "+(WorldBoardSmokeServer.restore()?31:7))){
            if(WorldBoardSmokeServer.restore()&&host.browser()!=null)throw new IllegalStateException("WORLD_BOARD_RESTART_REQUIRED_BROWSER");
            if(!WorldBoardSmokeServer.restore()&&mc.screen instanceof WebGuiInteractionScreen s)s.onClose();
            deferredCapture=WorldBoardSmokeServer.restore()?"restart-native":"initial-native";deferredNext=WorldBoardSmokeServer.restore()?20:1;phase=90;after=ticks+20;
        }
        if(phase==90&&ticks>=after&&!busy)capture(deferredCapture,deferredNext);
        if(phase==1&&!busy){WorldBoardSmokeServer.externalRequested=true;phase=2;}
        if(phase==2&&WorldBoardSmokeServer.externalChanged&&text.contains("Cedar  23")&&!busy){
            busy=true;PackagePageAgent.inspectManagedView(hud).whenComplete((value,error)->mc.execute(()->{try{
                if(error!=null)throw new IllegalStateException(error);if(!JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString().matches("(?s).*Cedar.*23.*")){busy=false;return;}
                Files.writeString(root().resolve("hud-external-dom.json"),value);phase=3;busy=false;host.open();
            }catch(Exception e){failure=e.toString();}}));
        }
        if(phase==3&&ticks%10==0&&host.ready()){
            main("document.querySelector('#open-generation').click();const p=document.querySelector('[data-world-view-id=\""+id+"\"]');if(!p||window.__wbMoved||!p.querySelector('summary').textContent.endsWith('r2'))return;window.__wbMoved=true;if(!p.open)p.querySelector('summary').click();for(const [key,value] of Object.entries({x:30.5,y:162,z:5.5,yaw:150,scale:2.5,viewDistance:96})){const input=p.querySelector('[data-world-field='+key+']');input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}));}p.querySelector('[data-world-action=worldMove]').scrollIntoView({block:'center'});p.querySelector('[data-world-action=worldMove]').click();");
            var data=WorldBoardClient.snapshot().get(UUID.fromString(id));
            if(data!=null&&data.revision()==3&&entity!=null&&Math.abs(entity.getX()-30.5)<0.01&&Math.abs(entity.getYRot()-150)<0.01){if(mc.screen instanceof WebGuiInteractionScreen s)s.onClose();phase=4;after=ticks+20;}
        }
        if(phase==4&&ticks>=after&&!busy)capture("transformed-native",5);
        if(phase==5&&!busy){host.close();WorldBoardSmokeServer.closedBrowserRequested=true;phase=6;after=ticks+30;}
        if(phase==6&&ticks>=after&&WorldBoardSmokeServer.closedBrowserChanged&&text.contains("Cedar  31")&&!busy){if(host.browser()!=null)throw new IllegalStateException("BROWSER_STILL_OPEN");capture("browser-closed-native",7);}
        if(phase==7&&!busy){WorldBoardSmokeServer.finishRequested=true;phase=30;}
        if(phase==20&&!busy){host.open();phase=21;}
        if(phase==21&&host.ready()&&UiClientSessions.current()!=null&&ticks%10==0){
            main("document.querySelector('#open-generation').click();const p=document.querySelector('[data-world-view-id=\""+id+"\"]');if(!p||window.__wbDetached)return;window.__wbDetached=true;if(!p.open)p.querySelector('summary').click();p.querySelector('[data-world-action=worldDetach]').scrollIntoView({block:'center'});p.querySelector('[data-world-action=worldDetach]').click();");
            if(entity==null){phase=22;after=ticks+30;}
        }
        if(phase==22&&ticks>=after&&host.ready()&&ticks%10==0){
            main("const p=document.querySelector('[data-world-view-id=\""+id+"\"]');if(!p||window.__wbDeleted)return;window.__wbDeleted=true;if(!p.open)p.querySelector('summary').click();p.querySelector('[data-world-action=worldDelete]').scrollIntoView({block:'center'});p.querySelector('[data-world-action=worldDelete]').click();");
            WorldBoardSmokeServer.finishRequested=true;phase=30;
        }
        if(phase==30&&WorldBoardSmokeServer.verified&&!ended){
            Files.writeString(root().resolve("client-result.json"),JSON.toJson(Map.of("driver","TRUSTED_GUI_FIXTURE_NOT_AI_PLANNER","nativeRenderer","VANILLA_TEXT_DISPLAY_CLIENT_ONLY_PER_VIEWER","restore",WorldBoardSmokeServer.restore(),"viewId",id,"browserIndependent",true,"fullV1",false)));
            ended=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_BOARD_CLIENT_OK run={} restore={}",WorldBoardSmokeServer.RUN,WorldBoardSmokeServer.restore());mc.stop();
        }
        if(failure!=null||ticks>3600){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"error",failure==null?"TIMEOUT":failure,"probe",probe==null?new JsonObject():probe,"native",WorldBoardClient.snapshot())));ended=true;host.close();mc.stop();throw new IllegalStateException("WORLD_BOARD_SMOKE_FAILED "+failure);}
    }
    private static void capture(String name,int next)throws Exception{
        var mc=Minecraft.getInstance();busy=true;Files.createDirectories(root());var id=UUID.fromString(WorldBoardSmokeServer.viewId);var e=WorldBoardClient.entity(id);
        var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,mc.level.registryAccess());e.saveWithoutId(out);var tag=out.buildResult();
        var transform=tag.read("transformation",com.mojang.math.Transformation.EXTENDED_CODEC).orElseThrow();
        float scale=transform.scale().x();if(name.equals("transformed-native")&&Math.abs(scale-2.5)>0.01)throw new IllegalStateException("NATIVE_SCALE_NOT_APPLIED");
        Files.writeString(root().resolve(name+".json"),JSON.toJson(Map.of("entityId",e.getId(),"text",e.textRenderState().text().getString(),"x",e.getX(),"y",e.getY(),"z",e.getZ(),"yaw",e.getYRot(),"scale",scale,"data",WorldBoardClient.snapshot().get(id),"browserPresent",WebGuiHostAdapter.INSTANCE.browser()!=null)));
        Files.writeString(root().resolve(name+".snbt"),tag.toString());
        net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(name+".png"));phase=next;busy=false;}catch(Exception ex){failure=ex.toString();}});
    }
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldBoardSmokeServer.directory());}
    private static String q(String s){return JSON.toJson(s);}
    private static void main(String script){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);}
}
