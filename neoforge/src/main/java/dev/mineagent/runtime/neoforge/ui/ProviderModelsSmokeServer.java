package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.api.config.ConfigPatch;
import java.util.*;
/** Explicit isolated profile only; no chat/completions or operating-system actions. */
public final class ProviderModelsSmokeServer {
 public static volatile boolean ready,resetRequested,resetDone;public static volatile String failure="";private static volatile char[] input;
 public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentReal")&&Boolean.getBoolean("mineagent.conversationAgentSmoke")&&System.getProperty("mineagent.conversationAgentScenario","").equals("provider_models");}
 public static char[] consumeInput(){if(!active())throw new IllegalStateException("SMOKE_DISABLED");var value=input;input=null;return value;}
 public static void tick(net.minecraft.server.MinecraftServer s){if(!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;try{
  var config=MineAgentRuntimeServices.config(s);
  if(!ready){s.getPlayerList().op(p.nameAndId());input=config.secretValue("provider.openai.apiKey").orElseThrow().toCharArray();if(!config.apply(new ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.model","select-from-catalog")),true).accepted())throw new IllegalStateException("MODEL_FIXTURE_SETUP");ready=true;}
  if(resetRequested&&!resetDone){var state=ServerProviderModels.view(p,false,0,"");if(!"READY".equals(state.get("status"))||!Integer.valueOf(200).equals(state.get("httpStatus")))throw new IllegalStateException("REAL_MODELS_NOT_OBSERVED");java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(s.getServerDirectory().resolve("provider-models-smoke")).resolve("real-catalog.json"),new com.google.gson.Gson().toJson(state));if(!config.apply(new ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.model","select-from-catalog")),true).accepted())throw new IllegalStateException("MODEL_FIXTURE_RESET");dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendPanelSnapshot(p);input=config.secretValue("provider.openai.apiKey").orElseThrow().toCharArray();resetDone=true;}
 }catch(Exception error){failure="PROVIDER_MODELS_FIXTURE_FAILED";}}
}
