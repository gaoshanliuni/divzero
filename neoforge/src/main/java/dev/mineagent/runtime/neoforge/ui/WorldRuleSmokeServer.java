package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.shared.SharedStateTarget;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.content.WorldContentRuntime;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** An explicit non-model RULE fixture alongside feedback regression, not a repair of any generated application. */
public final class WorldRuleSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static UUID agent,task,definition;private static SharedStateTarget target;private static int phase,started;
    public static boolean finished;
    private static final String SCRIPT="""
        on('instance.create',function(){
          var schema={version:1,fields:{count:{type:'INTEGER',scope:'SHARED'},privateValue:{type:'STRING',scope:'SHARED',read:'OWNER',write:'OWNER'}}};
          var defined=JSON.parse(String(content.sharedDefine('state','schema',0,JSON.stringify(schema))));
          if(defined.status!=='APPLIED'||defined.revision!==1)throw new Error('RULE_SCHEMA_REVISION');
          var stale=JSON.parse(String(content.sharedTransact('state','known-stale',JSON.stringify({schema_version:1,expected_revision:0,conditions:[],writes:[{key:'count',op:'PUT',value:99}]}))));
          if(stale.status!=='CONFLICT'||stale.revision!==1)throw new Error('RULE_STALE_INITIALIZER_NOT_REJECTED');
          var initialized=JSON.parse(String(content.sharedTransact('state','initialize',JSON.stringify({schema_version:1,expected_revision:defined.revision,conditions:[{key:'count',test:'ABSENT'}],writes:[{key:'count',op:'PUT_IF_ABSENT',value:0},{key:'privateValue',op:'PUT',value:'OWNER_ONLY'}]}))));
          if(initialized.status!=='APPLIED')throw new Error('RULE_INITIALIZATION_FAILED');
        });
        on('instance.restore',function(){content.sharedRead('state');});
        """;
    public static boolean enabled(){return DeliverySmokeServer.dataMode()&&!DeliveryGoalSmokeServer.enabled()&&!FeedbackRestartSmokeServer.enabled()&&!FeedbackApplicationSmokeServer.enabled();}
    private static ManagedTask task(MinecraftServer s){return MineAgentRuntimeServices.tasks(s).get(task).orElseThrow();}
    private static void require(boolean b,String code){if(!b)throw new IllegalStateException(code);}
    private static Map<String,Object> rule(){var result=new LinkedHashMap<>(target.wire());result.put("kind","world_rule");result.put("definition_id",definition);return result;}
    private static Map<String,Object> data(String key,Object value){var result=new LinkedHashMap<>(target.wire());result.putAll(Map.of("kind","shared_state","schema_version",1,"key",key,"expected_value",value));return result;}
    private static String checks(Object... values)throws Exception{return JSON.writeValueAsString(Map.of("checks",List.of(values)));}
    private static void denied(MinecraftServer s,String checks,String code)throws Exception{
        boolean rejected=false;try{MineAgentRuntimeServices.taskExecutor(s).worldActions().finishTask(task(s),checks);}
        catch(IllegalArgumentException|IllegalStateException|SecurityException expected){rejected=true;}
        require(rejected&&task(s).status()==TaskStatus.RUNNING,code);
    }
    public static void tick(MinecraftServer s,ServerPlayer owner)throws Exception{
        if(!enabled()||finished)return;var world=WorldContentRuntime.get(s);var actions=MineAgentRuntimeServices.taskExecutor(s).worldActions();
        if(phase==0){
            started=s.getTickCount();byte[] bytes=SCRIPT.getBytes(StandardCharsets.UTF_8);String hash=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes);
            var file=new GeneratedFile("server/main.js",RuntimeResourceSide.SERVER,"application/javascript",hash,bytes);var entry=new RuntimeEntrypoint(file.path(),file.side(),file.sha256());
            var d=new RuntimeDefinition(UUID.randomUUID(),"Pure RULE proof fixture",RuntimeDefinitionKind.RULE,"server",Set.of(),Map.of(),1);definition=d.definitionId();
            var p=ServerPackageRuntime.get(s).importOwned(owner,UUID.randomUUID(),new ParsedRuntimePackage("Pure rule fixture, not generated","1",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE","state.shared"),Map.of("server",entry,"server.restore",entry),List.of(d),List.of(file)));
            var activation=world.activate(owner,UUID.randomUUID(),p.packageId(),p.revision(),definition,new RuntimeInstanceLocation("minecraft:overworld",owner.getX()+12,owner.getY(),owner.getZ()+12,0,0),true,false);
            require(activation.state().equals("ACTIVE"),"RULE_NATIVE_ACTIVATION");p=ServerPackageRuntime.get(s).worldLibrary().get(p.packageId()).orElseThrow();
            target=new SharedStateTarget(p.packageId(),activation.instanceId(),p.revision(),p.canonicalSha256(),"state");
            agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("Rule proof Actor",owner.getUUID(),s.overworld(),owner.position().add(4,0,0)).agentId();phase=1;
        }
        if(s.getTickCount()-started>400)throw new IllegalStateException("RULE_PROOF_TIMEOUT_"+phase);
        if(phase==1&&MineAgentRuntimeServices.bodies(s).body(agent).isPresent()){
            task=MineAgentRuntimeServices.tasks(s).create(agent,owner.getUUID(),"Explicit no-model rule verification",1,List.of(new TaskStepSpec("execute",Set.of()))).taskId();
            actions.submit(task(s),List.of(Map.of("id","wait-rule","name","await_world_activation","arguments",JSON.writeValueAsString(Map.of("package_id",target.packageId(),"canonical_sha256",target.canonicalSha256(),"definition_id",definition)))));phase=2;
        }
        if(phase==2){var batch=actions.list(owner.getUUID()).stream().filter(b->b.taskId().equals(task)).findFirst().orElseThrow();
            if(!batch.state().equals("COMPLETED"))return;
            require(batch.receipts().getFirst().verified()&&batch.receipts().getFirst().after().get("businessVerified").equals("false"),"RULE_WAIT_NOT_BUSINESS");
            denied(s,checks(rule()),"RULE_ONLY_FINISH_ACCEPTED");
            denied(s,checks(rule(),data("count",1)),"RULE_WRONG_VALUE_ACCEPTED");
            denied(s,checks(rule(),data("privateValue","OWNER_ONLY")),"RULE_OWNER_FIELD_ACCEPTED");
            var wrong=rule();wrong.put("definition_id",UUID.randomUUID());denied(s,checks(wrong,data("count",0)),"RULE_WRONG_DEFINITION_ACCEPTED");
            actions.finishTask(task(s),checks(rule(),data("count",0)));
            require(task(s).status()==TaskStatus.COMPLETED&&world.verifiedBlocks(target.instanceId())==0&&world.verifiedObjects(target.instanceId())==0,"RULE_FINAL_PROOF");
            Path root=s.getServerDirectory().resolve("world-rule-evidence");Files.createDirectories(root);
            Files.writeString(root.resolve("result.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("status","NATIVE_RULE_AND_AUTHORIZED_DATA_VERIFIED","target",target,"definitionId",definition,"task",task(s),"wait",batch,"verifiedBlocks",0,"verifiedObjects",0,"denials",List.of("RULE_ONLY","WRONG_VALUE","OWNER_FIELD","WRONG_DEFINITION"),"realModelCalls",0,"systemInputInjected",false)));
            finished=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_RULE_PROOF_OK instance={} task={}",target.instanceId(),task);
        }
    }
}
