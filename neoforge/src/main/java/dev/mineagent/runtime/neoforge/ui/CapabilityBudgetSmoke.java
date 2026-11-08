package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.WorldContentRuntime;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Opt-in fixed candidate; verifies the real publication path without making a model call. */
public final class CapabilityBudgetSmoke {
    private static UUID operation,instance;
    private static final Set<String> observedItems=new HashSet<>();
    private static final String MODEL="""
        {"version":1,"boxes":[{"from":[-0.1,0,-0.1],"to":[0.1,0.2,0.1],"color":"#72bcd4"}],"collision":[-0.1,0,-0.1,0.1,0.2,0.1]}
        """;
    private static final String SCRIPT="""
        var index=0;
        function step(){
          var part=Math.floor(index/2);
          if(index%2===0)content.giveItem('item'+part,'models/tiny.json',1,'budget fixture '+part);
          else content.createObject('object'+part,'models/object.json',(part%12)*1.5,1,Math.floor(part/12)*1.5);
          index++;
          if(index<280)schedule(2,step);else content.state('completed','true');
        }
        on('instance.create',function(){content.state('automatic','true');schedule(2,step);});
        """;
    private static void guard(){if(!Boolean.getBoolean("mineagent.skillSmoke")||!System.getProperty("mineagent.skillSmokeMode","").equals("capability_foundation"))throw new SecurityException("FIXTURE_DISABLED");}
    public static Map<String,Object> publish(ServerPlayer player,UUID agent)throws Exception {
        guard();var server=player.level().getServer();var runtime=ServerPackageRuntime.get(server);
        var f=ServerPackageRuntime.class.getDeclaredField("jobs");f.setAccessible(true);var jobs=(PackageGenerationService)f.get(runtime);
        operation=UUID.randomUUID();var job=jobs.submit(player.getUUID(),agent,operation,"FIXTURE_ONLY: 140 distinct real items and objects",true,"WORLD_CONTENT").job();
        var context=ServerPackageRuntime.class.getDeclaredField("generatedActivation");context.setAccessible(true);((GeneratedContentActivation)context.get(runtime)).capture(player,job);
        var code=new GeneratedFile("server/main.js",RuntimeResourceSide.SERVER,"application/javascript",ResourceIds.create(),SCRIPT.getBytes(StandardCharsets.UTF_8));
        var model=new GeneratedFile("models/tiny.json",RuntimeResourceSide.COMMON,"application/json",ResourceIds.create(),MODEL.replace("\"version\":1","\"version\":1,\"texture\":\"models/checker.png\"").getBytes(StandardCharsets.UTF_8));
        var objectModel=new GeneratedFile("models/object.json",RuntimeResourceSide.COMMON,"application/json",ResourceIds.create(),MODEL.getBytes(StandardCharsets.UTF_8));
        byte[] png;try(var input=CapabilityBudgetSmoke.class.getResourceAsStream("/assets/mineagent_runtime/icon.png")){if(input==null)throw new IllegalStateException("FIXTURE_TEXTURE_MISSING");png=input.readAllBytes();}
        var texture=new GeneratedFile("models/checker.png",RuntimeResourceSide.COMMON,"image/png",ResourceIds.create(),png);
        var definition=new RuntimeDefinition(UUID.randomUUID(),"Budget fixture",RuntimeDefinitionKind.ENTITY,"server",Set.of(model.path(),objectModel.path(),texture.path()),Map.of(),1);
        var environment=dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe();
        var compatibility=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target(environment.minecraft(),environment.loader(),environment.loaderVersion(),environment.namespace(),environment.javaFeature(),Map.of())));
        var parsed=new ParsedRuntimePackage("Budget fixture","1.0",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE"),
                Map.of("server",new RuntimeEntrypoint(code.path(),code.side(),code.sha256())),List.of(definition),List.of(code,model,objectModel,texture),compatibility);
        for(var file:parsed.files())runtime.worldContent().put(file.sha256(),file.content());
        var candidate=RuntimePackagePublisher.prepare(parsed,job.packageId(),MineAgentRuntimeServices.identity(server));
        var publish=ServerPackageRuntime.class.getDeclaredMethod("publishGenerated",PackageGenerationJob.class,RuntimePackage.class,String.class,String.class);publish.setAccessible(true);
        String raw=runtime.worldContent().put("FIXTURE_ONLY_CANDIDATE".getBytes(StandardCharsets.UTF_8)).sha256();publish.invoke(runtime,job,candidate,"fixture",raw);
        var published=runtime.generation(player.getUUID(),operation).orElseThrow();var receipt=runtime.generatedActivation(player,published);
        if(!"PUBLISHED_AND_ACTIVE".equals(receipt.get("status")))throw new IllegalStateException("AUTO_ACTIVATION_FAILED_"+receipt);
        var activated=WorldContentRuntime.get(server).list(player.getUUID()).stream().filter(a->a.packageId().equals(job.packageId())).toList();
        if(activated.size()!=1)throw new IllegalStateException("AUTO_ACTIVATION_COUNT");instance=activated.getFirst().instanceId();
        // A duplicated commit callback must observe the same receipt and must not create another instance.
        publish.invoke(runtime,published,candidate,"fixture",raw);
        if(WorldContentRuntime.get(server).list(player.getUUID()).stream().filter(a->a.packageId().equals(job.packageId())).count()!=1)throw new IllegalStateException("AUTO_ACTIVATION_REPLAYED");
        return Map.of("providerCalls",0,"fixtureOnly",true,"operation",operation,"instance",instance,"receipt",receipt,"duplicateDidNotCreateInstance",true);
    }
    public static Map<String,Object> observe(ServerPlayer player)throws Exception {
        guard();var runtime=WorldContentRuntime.get(player.level().getServer());
        for(int slot=0;slot<player.getInventory().getContainerSize();slot++) {
            var stack=player.getInventory().getItem(slot);var binding=dev.mineagent.runtime.neoforge.content.RuntimeItem.binding(stack);
            if(binding!=null&&binding.instance().equals(instance)) {observedItems.add(binding.part());if(!binding.part().equals("item139"))player.getInventory().setItem(slot,net.minecraft.world.item.ItemStack.EMPTY);else if(slot<9)player.getInventory().setSelectedSlot(slot);}
        }
        var stored=runtime.instance(instance).orElseThrow();var active=runtime.list(player.getUUID()).stream().filter(a->a.instanceId().equals(instance)).findFirst().orElseThrow();
        if(!active.state().equals("ACTIVE"))throw new IllegalStateException("CONTENT_FIXTURE_STOPPED_"+active);
        long items=stored.state().keySet().stream().filter(k->k.startsWith("_item.")).count();
        int objects=runtime.verifiedObjects(instance);boolean complete=stored.state().getOrDefault("completed","").equals("true");
        if(complete&&(items!=140||objects!=140||observedItems.size()!=140))throw new IllegalStateException("CONTENT_COUNT_NOT_VERIFIED_"+items+"_"+objects+"_"+observedItems.size());
        return Map.of("complete",complete,"itemDefinitions",items,"actualItemsObserved",observedItems.size(),"actualObjects",objects,"resources",runtime.resourceObservation());
    }
    private CapabilityBudgetSmoke(){}
}
