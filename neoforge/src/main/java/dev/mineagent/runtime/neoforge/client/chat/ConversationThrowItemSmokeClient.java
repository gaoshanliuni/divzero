package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.ui.*;
import dev.mineagent.runtime.neoforge.client.objects.RuntimeThrownItemRenderer;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;
final class ConversationThrowItemSmokeClient {
    private static int ticks,wait,seen,holding;private static boolean sent,done;private static boolean captured,previewOpened,previewDone;private static int previewTicks;private static volatile boolean captureBusy;
    private static void capture(Path path){captureBusy=true;net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(path);}catch(Exception e){ConversationRuntimeItemSmokeServer.failure=e.toString();}finally{captureBusy=false;}});}
    static void tick()throws Exception{
        if(done)return;var mc=Minecraft.getInstance();var root=Files.createDirectories(mc.gameDirectory.toPath().resolve("runtime-item-smoke"));ticks++;
        try{
            if(!ConversationRuntimeItemSmokeServer.failure.isEmpty())throw new IllegalStateException(ConversationRuntimeItemSmokeServer.failure);if(ticks>14000)throw new IllegalStateException("THROW_CLIENT_TIMEOUT");if(mc.player==null||!ConversationRuntimeItemSmokeServer.ready)return;
            if(!ConversationRuntimeItemSmokeServer.saved()&&!sent&&++wait>=15){sent=true;String prompt=ConversationRuntimeItemSmokeServer.spherical()?"@工具助手 先查看可用建模能力。我要非常接近真球的橙色篮球练习物品，直径约0.4格，圆润轮廓和平滑光照，带贴合球面的细黑色圆弧接缝。使用新开放的参数化球体/圆环，不要堆盒子或手写海量三角面；自动选择足够高的精度。保留40Tick蓄力、短长力度不同、松手投出原件、碰撞反弹与本人拾回。请真实生成独立HOT包；生成时不执行，待我批准启用后在instance.create实际发一件（不要仅标记ready），恢复不重发。本次先做好高质量球体，不做球框计分或电脑操作。":"@工具助手 重新生成独立HOT练习球包。前版X/Z宽0.4却Y高0.7，成了长椭球且漏了深色接缝，未通过球形审查。本次必须X/Y/Z外包尺寸都约0.4，所有顶点y在0至0.4；用少量分层boxes构成上下左右对称的圆球轮廓，橙色，局部深色接缝，无尖刺或额外换色版。长按40Tick满蓄力，8与36Tick力度不同，松手投出原件，可碰撞反弹并拾回，不复制或用命令模拟。源码模型由你生成，不用旧示例。待我审查启用后首次创建才发一件，恢复不重发。不做球框计分或电脑操作。";if(prompt.length()>256)throw new IllegalStateException("THROW_PROMPT_EXCEEDS_NATIVE_CHAT");Files.writeString(root.resolve("prompt.txt"),prompt);mc.player.connection.sendChat(prompt);}
            if(ConversationRuntimeItemSmokeServer.spherical()&&ConversationRuntimeItemSmokeServer.itemReady&&!previewDone){
                if(!previewOpened){if(mc.player.getMainHandItem().isEmpty())return;previewOpened=true;mc.setScreen(new SphereModelPreviewScreen(mc.player.getMainHandItem()));}
                if(++previewTicks==35)capture(root.resolve("sphere-preview.png"));if(previewTicks<60||captureBusy)return;previewDone=true;mc.setScreen(null);
            }
            if(ConversationRuntimeItemSmokeServer.itemReady&&mc.screen!=null)mc.setScreen(null);
            int request=ConversationThrowItemSmokeServer.request;
            if(request>=1&&request<=3&&request!=seen&&!mc.player.getMainHandItem().isEmpty()){
                if(++wait<20)return;seen=request;holding=1;mc.options.keyUse.setDown(true);mc.gameMode.useItem(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND);wait=0;
            }
            if(holding>0){mc.options.keyUse.setDown(true);holding++;int target=seen==1?8:seen==2?36:80;if(seen<3?ConversationThrowItemSmokeServer.observedUseTicks>=target:holding>=target){mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(mc.player);holding=0;}}
            if(!captured&&RuntimeThrownItemRenderer.rendered>3){captured=true;capture(root.resolve("flight.png"));}
            if(ConversationRuntimeItemSmokeServer.verified){mc.options.keyUse.setDown(false);if(RuntimeThrownItemRenderer.rendered<1)throw new IllegalStateException("THROW_CLIENT_FLIGHT_NOT_DRAWN");Files.writeString(root.resolve("client.json"),new com.google.gson.Gson().toJson(Map.of("flightRenderSubmissions",RuntimeThrownItemRenderer.rendered,"nativeUseAndRelease",true,"systemInputInjected",false,"keyMappingReleased",!mc.options.keyUse.isDown())));done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");mc.stop();}
        }catch(Exception failure){mc.options.keyUse.setDown(false);done=true;Files.writeString(root.resolve("client-failure.json"),new com.google.gson.Gson().toJson(Map.of("error",failure.toString(),"request",seen,"holding",holding)));mc.stop();}
    }
}
