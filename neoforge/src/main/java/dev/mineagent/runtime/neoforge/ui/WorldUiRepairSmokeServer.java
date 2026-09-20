package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.packages.PackageUiPatchJob;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Two genuine scoped Coder edits to an existing rejected sample. No generator replay or manual signed-file edits. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldUiRepairSmokeServer {
    public static final String ORIGINAL_HASH="e7e7e7545ddd2f1acde21b6dcfb553954b71ea5f974de7ed468b86153cbb7619";
    public static final String WORLD_PROMPT="""
        只修复现有青铜风向仪的 server/main.js 与 models/rotor.json，保留 models/base.json、全部 ui、定义/入口/权限/partKey 和其余行为。
        当前 rotor 模型错误地把 collision.minY 写成1.3，RuntimeMesh v1 要求 minY=0。将转头 createObject 的 dy 从0改为1.3；models/rotor.json 中所有 boxes 的 from/to 的Y以及所有vertices的Y都减1.3，collision 改为 [-1,0,-0.15,1,1.2,0.15]，这样世界几何高度保持原样，底座与转头AABB间隙仍为0.15。
        同时修复tick：在 content.object(ROTOR_PART) 前先检查 content.objectCount() 是否等于2；尚未真实加载时直接返回，不在每tick写state。保留旋转 speed/enabled/configChanges、点击打开独立ui、ui.read/ui.action与注册式create/restore。不进行任何UI修改，不自动执行，不新增完成品模板。
        """.strip();
    public static final String UI_PROMPT="""
        仅修复已有 ui/index.html，不修改任何 SERVER/模型/manifest/定义资源，不创建新包。
        原 apply 调用了 state.sdk.action(state.revision,'configure',JSON.stringify(payload),operationId)，SDK会自己编码，这造成payload二次JSON编码。第三个参数改为实际payload对象；回复是{revision,data,executionMode}，data.ok===false时必须显示data.error而非清错假报成功。
        保持原data-ai-id=speed/enabled/apply、数字speed及enabled checkbox、明确应用/只读刷新按钮、speed/enabled/angle/configChanges显示、GUI版式与中文。使用宿主glass-sage变量（--ma-text/--ma-surface/--ma-accent及深色fallback），让文字高对比；不要整页opacity、CDN、浏览器存储或新增Native行为。
        初次read/每秒read/手动read不能重叠，周期read只更新显示与revision，不覆盖人工输入；apply在途时不发周期read，按实际回复推进revision。捕获失败并显示，不能把read错误吞掉后清除提示。显式刷新才允许覆盖草稿。SDK初始化幂等。
        operationId使用crypto.randomUUID；不自动重试写入。保存最近一次提交的operationId、expectedRevision、payload供诊断，不每tick重发。保持当前应用按钮流程，不新增第二个自动提交渠道。
        """.strip();
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile String packageId,worldOperation,uiOperation,worldHash,uiHash,failure;
    public static volatile boolean worldSubmit,uiSubmit,allowWorldApply,allowUiApply,ready;
    public static volatile boolean uiRebuild;public static volatile String uiRaw;
    private static boolean setup,temporaryOperator;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.worldUiRepairSmoke");}
    public static boolean verifyOnly(){return Boolean.getBoolean("mineagent.worldUiRepairVerifyOnly");}
    public static String directory(){return "world-ui-repair-evidence/"+WorldUiModelSmokeServer.RUN;}
    private static boolean approved(Path root,String name,String hash)throws Exception{Path p=root.resolve(name);return hash!=null&&Files.isRegularFile(p)&&Files.readString(p).strip().equals(hash);}
    private static void export(Path root,String kind,PackageUiPatchJob job,ServerPackageRuntime packages)throws Exception{
        Path out=Files.createDirectories(root.resolve(kind));Files.writeString(out.resolve("job.json"),JSON.writeValueAsString(job));
        if(!job.rawOutputSha256().isEmpty()&&!Files.exists(out.resolve("model-output.json")))Files.write(out.resolve("model-output.json"),packages.worldContent().read(job.rawOutputSha256()));
        if(job.candidate()!=null&&!Files.exists(out.resolve("candidate.json"))){
            Files.writeString(out.resolve("candidate.json"),JSON.writeValueAsString(job.candidate()));Path files=out.resolve("candidate").toAbsolutePath().normalize();
            for(var ref:job.candidate().resources().values()){Path target=files.resolve(ref.path()).normalize();if(!target.startsWith(files))throw new IllegalStateException("REPAIR_EVIDENCE_PATH");Files.createDirectories(target.getParent());Files.write(target,packages.worldContent().read(ref.sha256()));}
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||ready||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        Path root=Files.createDirectories(server.getServerDirectory().resolve(directory()));var packages=ServerPackageRuntime.get(server);
        try{
            var original=packages.generation(viewer.getUUID(),UUID.fromString(System.getProperty("mineagent.worldUiRepairGeneration"))).orElseThrow();
            if(!original.state().equals("PUBLISHED")||!original.purpose().equals("WORLD_CONTENT")||!original.canonicalSha256().equals(ORIGINAL_HASH)||!original.worldId().equals(MineAgentRuntimeServices.worldId(server)))throw new IllegalStateException("REPAIR_ORIGINAL_CONTEXT");
            packageId=original.packageId().toString();var pack=packages.worldLibrary().get(original.packageId()).orElseThrow();
            if(!verifyOnly()&&!dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server).list(viewer.getUUID()).stream().filter(a->a.packageId().equals(original.packageId())).toList().isEmpty())throw new IllegalStateException("REPAIR_ALREADY_HAS_NATIVE_ACTIVATION");
            if(!setup){
                if(System.getProperty("mineagent.worldUiRepairRaw","").matches("[a-f0-9]{64}")&&!viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)){
                    // Explicit isolated acceptance profile only; exercise the real Operator gate rather than bypass it.
                    server.getPlayerList().op(viewer.nameAndId());temporaryOperator=true;
                    Files.writeString(root.resolve("operator-fixture.json"),JSON.writeValueAsString(Map.of("viewer",viewer.getUUID(),"operatorBefore",false,"operatorAfter",viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),"scope","EXPLICIT_ISOLATED_ACCEPTANCE_ACCOUNT","productionPolicyChanged",false)));
                }
                Files.writeString(root.resolve("repair-context.json"),JSON.writeValueAsString(Map.of("original",original,"worldPrompt",WORLD_PROMPT,"uiPrompt",UI_PROMPT,"model",MineAgentRuntimeServices.config(server).snapshot().values().get("provider.openai.model"),"generationReplayed",false)));setup=true;
            }
            var worlds=packages.worldPatchJobs(viewer.getUUID()).stream().filter(j->j.base().packageId().equals(original.packageId())&&j.base().canonicalSha256().equals(ORIGINAL_HASH)&&j.prompt().equals(WORLD_PROMPT)).toList();if(worlds.size()>1)throw new IllegalStateException("REPAIR_DUPLICATE_WORLD_JOB");
            var w=worlds.isEmpty()?null:worlds.getFirst();worldSubmit=!verifyOnly()&&w==null&&pack.canonicalSha256().equals(ORIGINAL_HASH);if(w==null){if(!worldSubmit)throw new IllegalStateException("REPAIR_UNEXPECTED_HEAD");return;}
            worldOperation=w.operationId().toString();export(root,"world",w,packages);
            if(Set.of("FAILED","STALE","CANCELLED","INTERRUPTED","ROLLED_BACK").contains(w.state()))throw new IllegalStateException("REPAIR_WORLD_"+w.state()+"_"+w.errorCode());
            worldHash=w.candidate()==null?null:w.candidate().canonicalSha256();allowWorldApply=w.state().equals("READY")&&approved(root,"approve-world.flag",worldHash);if(!w.state().equals("APPLIED"))return;
            var uis=packages.patchJobs(viewer.getUUID()).stream().filter(j->j.base().packageId().equals(original.packageId())&&j.base().canonicalSha256().equals(worldHash)&&j.prompt().equals(UI_PROMPT)).toList();if(uis.size()>1)throw new IllegalStateException("REPAIR_DUPLICATE_UI_JOB");
            var u=uis.isEmpty()?null:uis.getFirst();uiSubmit=!verifyOnly()&&u==null&&pack.canonicalSha256().equals(worldHash);if(u==null){if(!uiSubmit)throw new IllegalStateException("REPAIR_UNEXPECTED_UI_BASE");return;}
            uiOperation=u.operationId().toString();export(root,"ui",u,packages);
            uiRaw=System.getProperty("mineagent.worldUiRepairRaw","");
            if(u.state().equals("FAILED")&&uiRaw.matches("[a-f0-9]{64}")){
                if(!packages.mayRebuildUi(viewer,u.operationId()))throw new IllegalStateException("REPAIR_REBUILD_OPERATOR_REQUIRED");
                uiRebuild=true;return;
            }
            uiRebuild=false;
            if(temporaryOperator){server.getPlayerList().deop(viewer.nameAndId());temporaryOperator=false;Files.writeString(root.resolve("operator-fixture-restored.json"),JSON.writeValueAsString(Map.of("viewer",viewer.getUUID(),"operatorAfter",viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))));}
            var rebuilt=packages.uiRebuildEvidence(viewer.getUUID(),u.operationId());if(rebuilt.isPresent())Files.writeString(root.resolve("ui/rebuild-evidence.json"),JSON.writeValueAsString(rebuilt.get()));
            if(Set.of("FAILED","STALE","CANCELLED","INTERRUPTED","ROLLED_BACK").contains(u.state()))throw new IllegalStateException("REPAIR_UI_"+u.state()+"_"+u.errorCode());
            uiHash=u.candidate()==null?null:u.candidate().canonicalSha256();allowUiApply=u.state().equals("READY")&&approved(root,"approve-ui.flag",uiHash);if(!u.state().equals("APPLIED"))return;
            if(!pack.canonicalSha256().equals(uiHash)||packages.ownedPackage(viewer.getUUID(),pack.packageId(),pack.revision()).isEmpty())throw new IllegalStateException("REPAIR_FINAL_HEAD_NOT_OWNED");
            Files.writeString(root.resolve("repair-ready.json"),JSON.writeValueAsString(Map.of("package",pack,"worldOperation",worldOperation,"uiOperation",uiOperation,"originalGeneration",original.operationId(),"newGenerationCalls",0,"worldRepairJobs",1,"uiRepairJobs",1,"nativeApproved",false)));ready=true;
        }catch(Exception e){if(temporaryOperator){server.getPlayerList().deop(viewer.nameAndId());temporaryOperator=false;}failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve("repair-failure.json"),JSON.writeValueAsString(Map.of("error",failure)));}
    }
}
