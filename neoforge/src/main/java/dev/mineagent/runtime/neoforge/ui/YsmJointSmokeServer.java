package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Production integrated-server fixture; real adapter state is required while the trusted WebGUI performs its own requests. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class YsmJointSmokeServer {
    public static volatile UUID agentId;public static volatile boolean applied,choicesVerified;
    private static boolean positioned;
    private static final Set<UUID> checkedChoices=new HashSet<>();
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.ysmJointSmoke"))return;var server=event.getServer();
        var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var bodies=MineAgentRuntimeServices.bodies(server);var def=bodies.definitions().stream().filter(a->a.ownerPlayerId().equals(viewer.getUUID())&&a.displayName().equals("UI Flow Fixture")).findFirst().orElse(null);if(def==null)return;
        agentId=def.agentId();var body=bodies.body(agentId).orElseThrow();var bridge=new NeoForgeYsmRuntimeBridge(server);
        if(!positioned){positioned=true;var pos=body.blockPosition();for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)server.overworld().setBlockAndUpdate(pos.offset(x,-1,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(body.level(),body.getX()+3,body.getY()+2,body.getZ()+7,Set.of(),156,12,true);
        }
        if(Boolean.getBoolean("mineagent.ysmJointAbsent")){if(bridge.installed())throw new IllegalStateException("YSM_EXPECTED_ABSENT");return;}
        if(!bridge.installed()||!bridge.checksumVerified()||!bridge.runtimeAvailable())throw new IllegalStateException("YSM_PRODUCTION_RUNTIME_UNAVAILABLE");
        if(Boolean.getBoolean("mineagent.appearanceChoiceSmoke")){
            var decisions=MineAgentRuntimeServices.decisions(server);
            for(var question:decisions.allFor(viewer.getUUID())){
                var context=decisions.domainContext(question.decisionId());var effect=decisions.domainEffect(question.decisionId()).orElse(null);
                if(!"appearance".equals(context.get("domain"))||effect==null||effect.state().equals("APPLYING")||checkedChoices.contains(question.decisionId()))continue;
                if(!agentId.toString().equals(context.get("agentId")))throw new IllegalStateException("CHOICE_AGENT_MISMATCH");
                var answer=decisions.acceptedAnswer(question.decisionId()).orElseThrow();
                var before=dev.mineagent.runtime.neoforge.network.MineAgentNetwork.readAppearanceFromUi(viewer,agentId);
                var duplicate=decisions.submitForTask(viewer.getUUID(),MineAgentRuntimeServices.tasks(server),answer);
                if(!duplicate.accepted()||!duplicate.duplicate())throw new IllegalStateException("CHOICE_DUPLICATE_REJECTED");
                dev.mineagent.runtime.neoforge.network.MineAgentNetwork.applyUiDecisionEffects(viewer,answer,question);
                var after=dev.mineagent.runtime.neoforge.network.MineAgentNetwork.readAppearanceFromUi(viewer,agentId);
                if(!before.equals(after))throw new IllegalStateException("CHOICE_DUPLICATE_NATIVE_MUTATION");
                Path root=server.getServerDirectory().resolve("ysm-joint-evidence");Files.createDirectories(root);
                Files.writeString(root.resolve("choice-"+question.decisionId()+".json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("question",question,"context",context,"answer",answer,"effect",effect,"state",after,"native",bridge.currentSelection(agentId).orElseThrow(),"duplicate",duplicate.duplicate(),"duplicateNativeMutation",false)));
                checkedChoices.add(question.decisionId());
                if(checkedChoices.size()==6){
                    Files.writeString(root.resolve("final-native-state.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("state",after,"native",bridge.currentSelection(agentId).orElseThrow(),"checkedDecisions",6,"executionMode","TRUSTED_UI_AND_CHAT_WITH_SERVER_SERVICE_REPLAY_CHECK")));
                    choicesVerified=true;
                }
            }
        }
        var selection=bridge.currentSelection(agentId);if(selection.isPresent()&&selection.get().modelId().equals("default")&&selection.get().textureId().equals("blue")&&!applied){
            applied=true;Path root=server.getServerDirectory().resolve("ysm-joint-evidence");Files.createDirectories(root);var state=dev.mineagent.runtime.neoforge.network.MineAgentNetwork.readAppearanceFromUi(viewer,agentId);
            Files.writeString(root.resolve("native-state.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("state",state,"agent",def,"bodyId",body.getUUID(),"entityId",body.getId(),"health",body.getHealth(),"width",body.getBbWidth(),"height",body.getBbHeight(),"mode",body.gameMode.getGameModeForPlayer().name())));
        }
    }
}
