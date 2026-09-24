package dev.mineagent.runtime.neoforge.client.screen;
import dev.mineagent.runtime.client.control.ProjectInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.*;
/** Local, read-only information; available even without server/operator permissions. */
public final class AboutScreen extends Screen {
 private final Screen parent;private int left,top;public int iconDraws;
 public AboutScreen(Screen parent){super(Component.literal("关于"));this.parent=parent;}
 @Override protected void init(){int w=Math.min(430,width-32);left=(width-w)/2;top=Math.max(12,(height-210)/2);var info=ProjectAboutClient.info();
  addRenderableWidget(new StringWidget(left+62,top+14,w-62,20,Component.literal("关于 "+ProjectInfo.NAME),font));int y=top+64;
  for(String row:java.util.List.of("项目名："+ProjectInfo.NAME,"项目地址："+ProjectInfo.URL,"当前版本："+info.version(),"开发者："+ProjectInfo.DEVELOPER)){
   for(var part:font.split(Component.literal(row),w)){var text=new StringBuilder();part.accept((i,style,c)->{text.appendCodePoint(c);return true;});addRenderableWidget(new StringWidget(left,y,w,14,Component.literal(text.toString()),font));y+=16;}y+=5;
  }
  y=Math.min(y+4,height-52);int half=(w-8)/2;
  addRenderableWidget(Button.builder(Component.literal("打开项目地址"),ConfirmLinkScreen.confirmLink(this,ProjectInfo.URL,true)).bounds(left,y,half,20).build());
  addRenderableWidget(Button.builder(Component.literal("复制项目地址"),b->{ProjectAboutClient.handle("copy_url");b.setMessage(Component.literal("已复制"));}).bounds(left+half+8,y,half,20).build());
  addRenderableWidget(Button.builder(CommonComponents.GUI_BACK,b->onClose()).bounds(left,height-26,w,20).build());
 }
 @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g,int x,int y,float tick){super.extractRenderState(g,x,y,tick);g.blit(net.minecraft.resources.Identifier.fromNamespaceAndPath("mineagent_runtime","icon.png"),left,top,left+48,top+48,0,1,0,1);iconDraws++;}
 @Override public boolean isPauseScreen(){return false;}
 @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
}
