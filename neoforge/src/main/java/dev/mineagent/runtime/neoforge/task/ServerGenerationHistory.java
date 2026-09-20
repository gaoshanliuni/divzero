package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.packages.PackageGenerationJob;
import dev.mineagent.runtime.core.packages.PackageUiPatchJob;
import dev.mineagent.runtime.core.task.AgentUiTaskLinks;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Invoked only after the existing trusted PLAYER task-history gate. Never grants package execution or replay. */
public final class ServerGenerationHistory {
    private static final ObjectMapper JSON=new ObjectMapper();private ServerGenerationHistory(){}
    public static Map<String,String> read(ServerPlayer viewer,Map<String,String> args)throws Exception{
        String kind=args.get("kind"),category=args.get("category");
        if(!Set.of("GENERATION","UI_PATCH","WORLD_PATCH","LINK").contains(category))throw new IllegalArgumentException("PACKAGE_HISTORY_CATEGORY");
        var server=viewer.level().getServer();var runtime=ServerPackageRuntime.get(server);Object result;
        if(kind.equals("jobs")){
            require(args,Set.of("kind","category","state","archive","offset"));int offset=offset(args);
            var page=runtime.history(viewer.getUUID(),category,args.get("state"),args.get("archive"),offset);
            result=Map.of("items",page.items().stream().map(ServerGenerationHistory::summary).toList(),"offset",offset,"nextOffset",page.nextOffset(),"total",page.total(),"more",page.more(),"usage",page.usage());
        }else{
            require(args,kind.equals("job")?Set.of("kind","category","operation","revision"):Set.of("kind","category","operation","revision","offset"));
            if(!Set.of("job","jobPrompt","jobRaw").contains(kind))throw new IllegalArgumentException("PACKAGE_HISTORY_KIND");
            var record=runtime.historyRecord(viewer.getUUID(),category,UUID.fromString(args.get("operation")));var meta=summary(record);
            if(!(kind.equals("job")&&args.get("revision").equals("0"))&&((Number)meta.get("revision")).longValue()!=Long.parseLong(args.get("revision")))throw new IllegalStateException("PACKAGE_HISTORY_STALE");
            if(kind.equals("job"))result=meta;
            else if(kind.equals("jobPrompt"))result=slice(prompt(record),offset(args));
            else{
                String hash=String.valueOf(meta.get("rawHash"));if(!hash.matches("[a-f0-9]{64}"))throw new IllegalStateException("PACKAGE_HISTORY_RAW_NOT_RECORDED");
                var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));
                byte[] bytes;try(var in=java.nio.file.Files.newInputStream(content.pathFor(hash))){bytes=in.readNBytes(24*1024*1024+1);}
                if(bytes.length>24*1024*1024)throw new IllegalStateException("PACKAGE_HISTORY_RAW_LIMIT");
                if(!hash.equals(dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes)))throw new IllegalStateException("PACKAGE_HISTORY_RAW_INTEGRITY");
                var decoder=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                result=slice(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(),offset(args));
            }
        }
        String encoded=JSON.writeValueAsString(result);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24000)throw new IllegalStateException("PACKAGE_HISTORY_RESPONSE_BUDGET");
        return Map.of("state",encoded,"executionMode","OWNER_GENERATION_HISTORY_READ_ONLY_NO_MODEL");
    }
    private static Map<String,Object> summary(Object record){
        var result=new LinkedHashMap<String,Object>();String state;
        if(record instanceof PackageGenerationJob j){
            result.put("operationId",j.operationId());result.put("taskId",j.taskId());result.put("agentId",j.agentId());result.put("revision",j.revision());result.put("updatedAt",j.updatedAtEpochMillis());result.put("packageId",j.packageId());result.put("packageRevision",j.packageRevision());result.put("intent",j.taskIntentRevision());state=j.state();
            result.put("purpose",j.purpose());result.put("error",safe(j.errorCode()));result.put("provider",safe(j.providerId()));result.put("rawHash",safe(j.rawOutputSha256()));result.put("canonicalHash",safe(j.canonicalSha256()));result.put("archived",!state.equals("GENERATING"));
            if(j.repairSource()!=null){result.put("repairOf",j.repairSource().operationId());result.put("repairSourceHash",j.repairSource().rawOutputSha256());}
        }else if(record instanceof PackageUiPatchJob j){
            result.put("operationId",j.operationId());result.put("taskId",j.taskId());result.put("agentId",j.agentId());result.put("revision",j.revision());result.put("updatedAt",j.updatedAtEpochMillis());result.put("packageId",j.base().packageId());result.put("packageRevision",j.base().revision());result.put("intent",j.taskIntentRevision());state=j.state();
            result.put("error",safe(j.errorCode()));result.put("provider",safe(j.providerId()));result.put("rawHash",safe(j.rawOutputSha256()));result.put("baseHash",j.base().canonicalSha256());result.put("candidateHash",j.candidate()==null?"":j.candidate().canonicalSha256());result.put("headRevision",j.headRevision());result.put("candidateFiles",j.candidate()==null?0:j.candidate().resources().size());result.put("archived",!Set.of("PENDING","READY","APPLYING","ROLLING_BACK").contains(state));
        }else if(record instanceof AgentUiTaskLinks.Link j){
            result.put("operationId",j.operationId());result.put("taskId",j.parentTaskId());result.put("agentId",j.agent());result.put("revision",j.revision());result.put("updatedAt",j.updatedAt());result.put("packageId",safe(j.packageId()));result.put("packageRevision",j.packageRevision());result.put("intent",j.parentIntent());state=j.state();
            result.put("childTaskId",safe(j.childTaskId()));result.put("tool",j.request().tool());result.put("error",safe(j.error()));result.put("rawHash","");result.put("archived",Set.of("FAILED","STALE","COMPLETED").contains(state));
        }else throw new IllegalArgumentException("PACKAGE_HISTORY_RECORD");
        result.put("state",state);result.put("promptPreview",prefix(prompt(record),160));return Map.copyOf(result);
    }
    private static String prompt(Object r){return r instanceof PackageGenerationJob j?j.prompt():r instanceof PackageUiPatchJob j?j.prompt():((AgentUiTaskLinks.Link)r).request().prompt();}
    private static String safe(Object v){return Objects.toString(v,"");}
    private static String prefix(String text,int max){return text.substring(0,text.offsetByCodePoints(0,Math.min(max,text.codePointCount(0,text.length()))));}
    private static Map<String,Object> slice(String text,int offset){int length=text.codePointCount(0,text.length());if(offset>length)throw new IllegalArgumentException("PACKAGE_HISTORY_OFFSET");int next=Math.min(length,offset+1024);return Map.of("text",text.substring(text.offsetByCodePoints(0,offset),text.offsetByCodePoints(0,next)),"offset",offset,"nextOffset",next,"more",next<length,"length",length);}
    private static int offset(Map<String,String> args){int offset=Integer.parseInt(args.get("offset"));if(offset<0||offset>24*1024*1024)throw new IllegalArgumentException("PACKAGE_HISTORY_OFFSET");return offset;}
    private static void require(Map<String,String> args,Set<String> keys){if(!args.keySet().equals(keys))throw new IllegalArgumentException("PACKAGE_HISTORY_ARGUMENTS");}
}
