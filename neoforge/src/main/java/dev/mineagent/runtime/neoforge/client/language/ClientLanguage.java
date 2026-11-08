package dev.mineagent.runtime.neoforge.client.language;
import dev.mineagent.runtime.client.control.*;import net.minecraft.client.Minecraft;import net.minecraft.network.chat.Component;import net.neoforged.bus.api.SubscribeEvent;import net.neoforged.fml.common.EventBusSubscriber;import java.util.*;import java.util.concurrent.*;import com.google.gson.JsonObject;
@EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ClientLanguage {
 private static UiLanguagePreferences preferences;private static int reopenIn;private static Object reopenConnection,reopenScreen;
 private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"mineagent-ui-language");t.setDaemon(true);return t;});
 private static synchronized UiLanguagePreferences prefs(){if(preferences==null)try{preferences=new UiLanguagePreferences(Minecraft.getInstance().gameDirectory.toPath().resolve("config/mineagent-ui-language.properties"));}catch(Exception e){throw new IllegalStateException("UI_LANGUAGE_UNAVAILABLE",e);}return preferences;}
 public static String gameLanguage(){var mc=Minecraft.getInstance();return mc==null||mc.options==null?"en_us":mc.options.languageCode;}
 public static String language(){try{return UiLanguagePreferences.resolve(prefs().state().language(),gameLanguage());}catch(Exception e){return UiLanguagePreferences.resolve("auto",gameLanguage());}}
 public static String t(String source){return UiLanguageCatalog.text(language(),source);}
 public static String permissionName(dev.mineagent.runtime.api.permission.PermissionAction action){return t(switch(action){
  case CHAT->"聊天";
  case START_TASK->"启动任务";
  case CREATE_AGENT->"创建 AI 玩家";
  case MODIFY_AGENT->"管理 AI 玩家";
  case CANCEL_AGENT_TASK->"取消 AI 任务";
  case RUN_CODE->"运行代码";
  case MANAGE_PACKAGES->"管理内容包";
  case MANAGE_PROVIDERS->"管理模型服务";
  case MANAGE_PERMISSIONS->"管理权限组";
  case CONTROL_PUBLIC_MEDIA->"控制公共媒体";
  case MANAGE_SCOREBOARD->"管理计分板";
  case DISCOVER_OBJECTS->"发现世界对象";
  case SUBSCRIBE_EVENTS->"订阅事件";
  case SCHEDULE_TASKS->"安排定时任务";
  case ACCESS_SHARED_STATE->"访问共享状态";
  case OFFER_CONTENT->"提供内容";
  case RECEIVE_UI_FEEDBACK->"接收界面反馈";
  case RESTORE_BACKUP->"恢复备份";
  case VIEW_DIAGNOSTICS->"查看诊断";
  case EDIT_WORLD->"编辑世界与方块";
  case EDIT_ENTITIES->"编辑实体与状态";
 });}
 public static Component component(String source){return Component.literal(t(source));}
 public static Map<String,Object> view(){try{var s=prefs().state();return Map.of("language",s.language(),"effectiveLanguage",UiLanguagePreferences.resolve(s.language(),gameLanguage()),"revision",s.revision(),"defaultLanguage","auto");}catch(Exception e){return Map.of("language","auto","effectiveLanguage",UiLanguagePreferences.resolve("auto",gameLanguage()),"revision",0L,"defaultLanguage","auto","error","UI_LANGUAGE_UNAVAILABLE");}}
 public static CompletableFuture<Map<String,Object>> save(String language,long revision){return CompletableFuture.supplyAsync(()->{try{prefs().save(revision,language);return view();}catch(Exception e){throw new CompletionException(e);}},IO);}
 public static CompletableFuture<Map<String,Object>> handle(JsonObject a){String action=a.get("action").getAsString();if(action.equals("read"))return CompletableFuture.completedFuture(view());if(action.equals("set")){if(!a.has("language")||!a.has("expectedRevision")||!a.get("expectedRevision").getAsString().matches("0|[1-9][0-9]{0,18}"))return CompletableFuture.failedFuture(new IllegalArgumentException("UI_LANGUAGE_ARGUMENTS"));return save(a.get("language").getAsString(),a.get("expectedRevision").getAsLong());}if(action.equals("reopen")){reopenConnection=Minecraft.getInstance().getConnection();reopenScreen=Minecraft.getInstance().screen;reopenIn=3;return CompletableFuture.completedFuture(Map.of("status","REOPEN_SCHEDULED"));}return CompletableFuture.failedFuture(new IllegalArgumentException("UI_LANGUAGE_ACTION"));}
 @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e){if(reopenIn>0&&--reopenIn==0){var h=dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE;var mc=Minecraft.getInstance();if(mc.getConnection()==reopenConnection&&mc.level!=null&&mc.screen==reopenScreen&&mc.screen instanceof dev.mineagent.runtime.neoforge.client.nativeui.NativeWorkspaceScreen){((dev.mineagent.runtime.neoforge.client.nativeui.NativeWorkspaceScreen)mc.screen).onClose();h.open();}reopenConnection=reopenScreen=null;}}
 private ClientLanguage(){}
}
