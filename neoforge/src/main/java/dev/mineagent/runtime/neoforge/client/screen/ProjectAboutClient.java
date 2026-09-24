package dev.mineagent.runtime.neoforge.client.screen;
import dev.mineagent.runtime.client.control.ProjectInfo;
import net.neoforged.fml.ModList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import java.util.*;
public final class ProjectAboutClient {
 public static ProjectInfo info(){return new ProjectInfo(ModList.get().getModContainerById("mineagent_runtime").orElseThrow().getModInfo().getVersion().toString());}
 public static Map<String,?> handle(String action){var mc=Minecraft.getInstance();switch(action){
  case "read"->{var out=new LinkedHashMap<String,Object>(info().fields());try(var in=ProjectAboutClient.class.getResourceAsStream("/assets/mineagent_runtime/icon.png")){if(in==null)throw new IllegalStateException("PROJECT_ICON_MISSING");out.put("icon", "data:image/png;base64,"+Base64.getEncoder().encodeToString(in.readAllBytes()));}catch(java.io.IOException e){throw new IllegalStateException("PROJECT_ICON_UNREADABLE",e);}return out;}
  case "copy_url"->{mc.keyboardHandler.setClipboard(ProjectInfo.URL);return Map.of("status","COPIED");}
  case "open_url"->{ConfirmLinkScreen.confirmLinkNow(mc.screen,ProjectInfo.URL,true);return Map.of("status","LINK_CONFIRMATION_OPENED");}
  default->throw new IllegalArgumentException("PROJECT_ABOUT_ACTION");
 }}
 private ProjectAboutClient(){}
}
