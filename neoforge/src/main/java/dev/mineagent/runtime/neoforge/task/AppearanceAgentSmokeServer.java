package dev.mineagent.runtime.neoforge.task;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.api.decision.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Explicit new live-model acceptance. No preset planner response, no fixture fallback to pass the task. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class AppearanceAgentSmokeServer {
    public static final String PROMPT="先读取你自己的当前外观和实际可用模型。请给我一个外观选择卡让我决定，选项使用你真实读到的模型；不要在我回答之前改外观。我回答后请理解选项和完整的自然语言补充，更换你自己的外观，并通过实际状态检查完成任务。不要创建物件或网页，不执行移动、挖掘、放置等世界动作，不改观察玩家外观。";
    public static final String ANSWER="不使用这个选项中的模型，请改用 default 模型、蓝色的 blue 贴图，播放 idle 动画。只修改你自己的外观，保留我和你的背包。";
    public static volatile String agentId,decisionId,failure;
    public static volatile boolean answerAllowed,completed;
    private static UUID agent,taskId,negativeTask;private static int waitingAt=-1;private static boolean saved;private static Map<String,Integer> viewerInventory,actorInventory;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public static boolean enabled(){return Boolean.getBoolean("mineagent.appearanceAgentSmoke");}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||completed||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var root=server.getServerDirectory().resolve("appearance-agent-evidence");Files.createDirectories(root);
        var bodies=MineAgentRuntimeServices.bodies(server);var tasks=MineAgentRuntimeServices.tasks(server);var decisions=MineAgentRuntimeServices.decisions(server);var actions=MineAgentRuntimeServices.taskExecutor(server).worldActions();
        try{
            if(agent==null){
                if(MineAgentRuntimeServices.config(server).secretValue("provider.openai.apiKey").isEmpty())throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
                var definition=bodies.create("Appearance Agent Live",viewer);agent=definition.agentId();agentId=agent.toString();var body=bodies.body(agent).orElseThrow();var pos=body.blockPosition();
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)body.level().setBlockAndUpdate(pos.offset(x,-1,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(body.level(),body.getX()+2,body.getY()+1,body.getZ()+4,Set.of(),156,10,true);
                viewerInventory=inventory(viewer);actorInventory=NativeCraftingAction.inventory(body);
                Files.writeString(root.resolve("fixture.json"),JSON.writeValueAsString(Map.of("agent",definition,"viewer",viewer.getUUID(),"prompt",PROMPT,"answerDriver",ANSWER,"mode","LIVE_PROVIDER_NOT_FIXED_PLAN")));
            }
            var body=bodies.body(agent).orElseThrow();
            if(taskId==null){var task=tasks.all().stream().filter(t->t.agentId().equals(agent)&&t.title().equals(PROMPT)).findFirst().orElse(null);if(task==null)return;taskId=task.taskId();}
            var task=tasks.get(taskId).orElseThrow();if(task.status()==TaskStatus.PAUSED||task.status()==TaskStatus.FAILED)throw new IllegalStateException("LIVE_APPEARANCE_TASK_"+task.status());
            if(decisionId==null){var q=decisions.allFor(viewer.getUUID()).stream().filter(d->decisions.taskLink(d.decisionId()).filter(l->l.taskId().equals(taskId)).isPresent()).findFirst().orElse(null);if(q!=null){decisionId=q.decisionId().toString();waitingAt=body.tickCount;Files.writeString(root.resolve("model-question.json"),JSON.writeValueAsString(q));}}
            if(decisionId!=null&&decisions.get(UUID.fromString(decisionId)).orElseThrow().status()==DecisionStatus.OPEN){
                var state=MineAgentNetwork.readAppearanceFromUi(viewer,agent);if(((Number)state.get("revision")).longValue()!=0)throw new IllegalStateException("APPEARANCE_CHANGED_BEFORE_ANSWER");
                if(body.tickCount-waitingAt>=80){answerAllowed=true;Files.writeString(root.resolve("waiting.json"),JSON.writeValueAsString(Map.of("bodyTicks",body.tickCount-waitingAt,"task",task,"appearance",state,"planningAttempts",actions.planningAttempts(task))));}
            }
            if(task.status()!=TaskStatus.COMPLETED)return;
            if(!saved){
                var state=MineAgentNetwork.readAppearanceFromUi(viewer,agent);var answer=decisions.acceptedAnswer(UUID.fromString(decisionId)).orElseThrow();var history=actions.list(viewer.getUUID()).stream().filter(b->b.taskId().equals(taskId)).toList();
                var tools=history.stream().flatMap(b->b.actions().stream()).map(WorldActionSpec::tool).collect(java.util.stream.Collectors.toSet());
                if(!tools.containsAll(Set.of("inspect_appearance","set_appearance"))||tools.stream().anyMatch(t->!Set.of("inspect_appearance","set_appearance","say").contains(t))||!answer.customText().equals(ANSWER)||answer.source()!=AnswerSource.UI||!viewerInventory.equals(inventory(viewer))||!actorInventory.equals(NativeCraftingAction.inventory(body)))throw new IllegalStateException("LIVE_TASK_PROVENANCE_OR_INVENTORY");
                if(!state.get("model").equals("default")||!state.get("texture").equals("blue")||!state.get("animation").equals("idle")||((Number)state.get("revision")).longValue()!=1)throw new IllegalStateException("LIVE_APPEARANCE_NOT_MATCHED");
                Files.writeString(root.resolve("live.json"),JSON.writeValueAsString(Map.of("task",task,"question",decisions.get(UUID.fromString(decisionId)).orElseThrow(),"answer",answer,"history",history,"appearance",state,"planningAttempts",actions.planningAttempts(task),"viewerInventoryUnchanged",true,"actorInventoryUnchanged",true)));
                saved=true;var negative=tasks.create(agent,viewer.getUUID(),"显式无模型 stale 外观回归",1,List.of(new TaskStepSpec("fixture_plan",Set.of()),new TaskStepSpec("execute",Set.of("fixture_plan"))));negative=tasks.completeStep(negative.taskId(),negative.revision(),"fixture_plan").task();negativeTask=negative.taskId();
                actions.submit(negative,List.of(Map.of("id","stale","name","set_appearance","arguments","{\"model\":\"default\",\"texture\":\"default\",\"animation\":\"idle\",\"expected_revision\":0}")));return;
            }
            var negative=tasks.get(negativeTask).orElseThrow();var batch=actions.list(viewer.getUUID()).stream().filter(b->b.taskId().equals(negativeTask)).findFirst().orElseThrow();if(!Set.of("FAILED","INTERRUPTED").contains(batch.state()))return;
            if(!batch.error().equals("STALE_REVISION"))throw new IllegalStateException("STALE_TASK_TOOL_NOT_REJECTED");
            var state=MineAgentNetwork.readAppearanceFromUi(viewer,agent);if(((Number)state.get("revision")).longValue()!=1||!state.get("texture").equals("blue"))throw new IllegalStateException("STALE_TOOL_CHANGED_NATIVE");
            Files.writeString(root.resolve("stale-tool.json"),JSON.writeValueAsString(Map.of("batch",batch,"task",negative,"appearance",state,"providerCalls",0)));
            completed=true;
        }catch(Exception e){failure=e.getMessage()!=null&&e.getMessage().matches("[A-Z0-9_]{1,100}")?e.getMessage():"APPEARANCE_AGENT_FIXTURE_FAILED";Files.writeString(root.resolve("failure.json"),JSON.writeValueAsString(Map.of("error",failure,"taskId",taskId==null?"":taskId.toString(),"batches",actions.list(viewer.getUUID()))));}
    }
    private static Map<String,Integer> inventory(net.minecraft.server.level.ServerPlayer player){
        var result=new LinkedHashMap<String,Integer>();for(int i=0;i<player.getInventory().getContainerSize();i++){var stack=player.getInventory().getItem(i);if(!stack.isEmpty())result.put(i+"/"+net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())+"/"+stack.getDamageValue(),stack.getCount());}return Map.copyOf(result);
    }
}
