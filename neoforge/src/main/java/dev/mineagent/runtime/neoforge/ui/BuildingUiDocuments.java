package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.ui.UiProtocol.Session;
import dev.mineagent.runtime.client.webui.PackagePreviewTransfer;
import dev.mineagent.runtime.core.building.BuildingDesign;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded UI document transfer; creating or appending an upload never changes a building or the world. */
public final class BuildingUiDocuments {
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Scope(UUID world,UUID viewer,UUID agent,UUID session,long page,long control){static Scope of(Session session,UUID agent){return new Scope(session.binding().worldId(),session.binding().viewerPlayerId(),agent,session.sessionId(),session.pageGeneration(),session.controlEpoch());}}
    private record Upload(Scope scope,String building,long revision,String sha256,long until,PackagePreviewTransfer.Assembler data){}
    public record Plan(String building,long revision,String source){}
    private final Map<UUID,Upload> uploads=new HashMap<>();private final java.util.function.LongSupplier clock;
    public BuildingUiDocuments(){this(System::currentTimeMillis);}
    BuildingUiDocuments(java.util.function.LongSupplier clock){this.clock=clock;}
    private void expire(){uploads.values().removeIf(upload->upload.until<=clock.getAsLong());}
    public Map<String,String> begin(Session session,UUID agent,Map<String,String> args){
        expire();if(uploads.size()>=32||uploads.values().stream().filter(upload->upload.scope.viewer.equals(session.binding().viewerPlayerId())).count()>=4)throw new IllegalStateException("BUILDING_UPLOAD_BUSY");
        String building=args.get("id"),sha=args.get("sha256");long revision=Long.parseLong(args.get("revision"));if(building==null||!building.matches("[A-Za-z][A-Za-z0-9_-]{0,95}")||revision<0)throw new IllegalArgumentException("BUILDING_UPLOAD_ID");
        var data=new PackagePreviewTransfer.Assembler(Integer.parseInt(args.get("size")),sha,524288);UUID id=UUID.randomUUID();uploads.put(id,new Upload(Scope.of(session,agent),building,revision,sha,clock.getAsLong()+120000,data));return Map.of("uploadId",id.toString(),"offset","0");
    }
    private Upload require(Session session,UUID agent,UUID id){expire();var upload=uploads.get(id);if(upload==null||!upload.scope.equals(Scope.of(session,agent)))throw new SecurityException("BUILDING_UPLOAD_CONTEXT");return upload;}
    public Map<String,String> append(Session session,UUID agent,Map<String,String> args){
        UUID id=UUID.fromString(args.get("uploadId"));var upload=require(session,agent,id);String encoded=args.get("bytes");if(encoded==null||encoded.length()>16384)throw new IllegalArgumentException("BUILDING_UPLOAD_CHUNK");byte[] bytes=Base64.getDecoder().decode(encoded);if(bytes.length<1||bytes.length>8192)throw new IllegalArgumentException("BUILDING_UPLOAD_CHUNK");upload.data.append(Integer.parseInt(args.get("offset")),bytes);return Map.of("uploadId",id.toString(),"offset",Integer.toString(upload.data.offset()));
    }
    public Plan finish(Session session,UUID agent,Map<String,String> args)throws Exception{
        UUID id=UUID.fromString(args.get("uploadId"));var upload=require(session,agent,id);byte[] bytes=upload.data.finish();String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        var design=BuildingDesign.parse(source);if(!design.id().equals(upload.building))throw new IllegalArgumentException("BUILDING_UPLOAD_ID_CHANGED");uploads.remove(id);return new Plan(upload.building,upload.revision,source);
    }
    public static Map<String,Object> part(String source,int offset,String expectedHash)throws Exception{
        String hash=RuntimePackageCanonicalizer.sha256(source);if(!expectedHash.isEmpty()&&!expectedHash.equals(hash))throw new IllegalStateException("BUILDING_DOCUMENT_CHANGED");int length=source.codePointCount(0,source.length());if(offset<0||offset>length)throw new IllegalArgumentException("BUILDING_DOCUMENT_OFFSET");int next=Math.min(length,offset+4096);return Map.of("text",source.substring(source.offsetByCodePoints(0,offset),source.offsetByCodePoints(0,next)),"sha256",hash,"offset",offset,"nextOffset",next,"length",length,"more",next<length);
    }
    public static Map<String,Object> summary(Map<String,Object> full,int offset)throws Exception{
        if(!full.containsKey("design"))return full;var result=new LinkedHashMap<>(full);var design=JSON.valueToTree(full.get("design"));var steps=JSON.valueToTree(full.getOrDefault("steps",List.of()));var page=JSON.createArrayNode();for(int i=offset;i<Math.min(steps.size(),offset+16);i++)page.add(steps.get(i));result.put("steps",page);result.put("nextOffset",offset+16);result.put("more",offset+16<steps.size());
        var names=JSON.createArrayNode();for(var component:design.path("components"))for(var step:page)if(component.path("id").asText().equals(step.path("component").asText()))names.add(JSON.createObjectNode().put("id",component.path("id").asText()).put("name",component.path("name").asText(component.path("id").asText())));
        var header=JSON.createObjectNode().put("id",design.path("id").asText()).put("name",design.path("name").asText()).put("dimension",design.path("dimension").asText()).put("componentCount",design.path("components").size());header.set("components",names);result.put("design",header);result.put("designDeferred",true);result.put("designHash",RuntimePackageCanonicalizer.sha256(design.toString()));
        String report=Objects.toString(full.get("report"),"");result.put("reportAvailable",!report.isBlank());if(report.length()>4096){result.put("report","");result.put("reportDeferred",true);}var allHistory=JSON.valueToTree(full.getOrDefault("history",List.of()));var history=JSON.createArrayNode();for(int i=0;i<Math.min(16,allHistory.size());i++)history.add(allHistory.get(i));result.put("more",offset+16<steps.size()||allHistory.size()>16);for(var item:history)if(item.isObject()&&item.path("detail").asText().length()>256){((com.fasterxml.jackson.databind.node.ObjectNode)item).put("detail","");((com.fasterxml.jackson.databind.node.ObjectNode)item).put("detailDeferred",true);}result.put("history",history);return result;
    }
}
