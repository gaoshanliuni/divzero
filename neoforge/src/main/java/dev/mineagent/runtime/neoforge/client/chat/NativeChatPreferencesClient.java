package dev.mineagent.runtime.neoforge.client.chat;
import com.google.gson.JsonObject;import dev.mineagent.runtime.client.control.NativeChatPreferences;import net.minecraft.client.Minecraft;import net.minecraft.network.chat.Component;import net.minecraft.network.chat.contents.TranslatableContents;import net.neoforged.bus.api.*;import net.neoforged.fml.common.EventBusSubscriber;import java.util.*;import java.util.concurrent.*;
@EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class NativeChatPreferencesClient {
 public static long hiddenMessages;
 public static final String THINKING_KEY="mineagent.chat.thinking";
 private static NativeChatPreferences preferences;private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"mineagent-chat-preferences");t.setDaemon(true);return t;});
 private static synchronized NativeChatPreferences prefs(){if(preferences==null)try{preferences=new NativeChatPreferences(Minecraft.getInstance().gameDirectory.toPath().resolve("config/mineagent-native-chat.properties"));}catch(Exception e){throw new IllegalStateException("NATIVE_CHAT_SETTINGS_UNAVAILABLE",e);}return preferences;}
 public static boolean thinking(Component message){return message.getContents() instanceof TranslatableContents t&&t.getKey().equals(THINKING_KEY);}
 public static boolean showThinking(){try{return prefs().state().showThinking();}catch(Exception e){return true;}}
 public static Map<String,Object> view(){try{var s=prefs().state();return Map.of("showThinking",s.showThinking(),"revision",s.revision());}catch(Exception e){return Map.of("showThinking",true,"revision",0L,"error","NATIVE_CHAT_SETTINGS_UNAVAILABLE");}}
 public static CompletableFuture<Map<String,Object>> save(boolean value,long expected){return CompletableFuture.supplyAsync(()->{try{prefs().save(expected,value);return view();}catch(Exception e){throw new CompletionException(e);}},IO).thenApply(result->{Minecraft.getInstance().execute(()->{if(!value){var chat=Minecraft.getInstance().gui.getChat();((dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess)chat).mineagent$messages().removeIf(m->thinking(m.content()));chat.rescaleChat();}});return result;});}
 public static CompletableFuture<Map<String,Object>> handle(JsonObject a){String kind=a.get("kind").getAsString();if(kind.equals("read"))return CompletableFuture.completedFuture(view());if(!kind.equals("set")||!a.has("showThinking")||!a.get("showThinking").isJsonPrimitive()||!a.getAsJsonPrimitive("showThinking").isBoolean()||!a.has("expectedRevision"))return CompletableFuture.failedFuture(new IllegalArgumentException("NATIVE_CHAT_SETTINGS_ARGUMENTS"));return save(a.get("showThinking").getAsBoolean(),a.get("expectedRevision").getAsLong());}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void chat(net.neoforged.neoforge.client.event.ClientChatReceivedEvent e){if(e.isSystem()&&thinking(e.getMessage())&&!showThinking()){hiddenMessages++;e.setCanceled(true);}}
 private NativeChatPreferencesClient(){}
}
