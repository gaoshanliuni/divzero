package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.content.RuntimeCreatures;
import dev.mineagent.runtime.neoforge.task.ServerPackageCatalog;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** A unified catalog over existing owner-scoped stores, without inventing packages or changing ownership. */
final class ServerCreatedContents {
    private static final ObjectMapper JSON=new ObjectMapper();
    static final List<String> TYPES=List.of("packages","creatures","native_entities","entity_rules","interaction_rules");
    private ServerCreatedContents(){}
    static CompletableFuture<Map<String,Object>> handle(ServerPlayer p,UUID operation,Map<String,String> args,boolean write,BooleanSupplier permit)throws Exception{
        String kind=args.get("kind"),type=args.get("type");
        if(!write&&kind.equals("list")){
            ServerWorkspaceModules.keys(args,"module","kind","type","query","offset");String query=ServerWorkspaceModules.query(args);int offset=ServerWorkspaceModules.offset(args);
            if(type.equals("all")){var sections=new ArrayList<Object>();for(String source:TYPES){var page=page(p,source,query,0);var rows=(List<?>)page.get("items");sections.add(Map.of("type",source,"items",rows.stream().limit(2).toList(),"total",page.get("total")));}return CompletableFuture.completedFuture(Map.of("sections",sections));}
            return CompletableFuture.completedFuture(page(p,type,query,offset));
        }
        if(!TYPES.contains(type)||type.equals("packages"))throw new IllegalArgumentException("CONTENT_KIND");
        UUID id=UUID.fromString(args.get("id"));long expected=Long.parseLong(args.get("revision"));String source=source(p,type,id,expected);
        if(!write){
            ServerWorkspaceModules.keys(args,"module","kind","type","id","revision","offset");if(!kind.equals("source"))throw new IllegalArgumentException("CONTENT_QUERY");int offset=ServerWorkspaceModules.offset(args),length=source.codePointCount(0,source.length());if(offset>length)throw new IllegalArgumentException("CONTENT_SOURCE_OFFSET");int end=Math.min(length,offset+2048);
            return CompletableFuture.completedFuture(Map.of("text",source.substring(source.offsetByCodePoints(0,offset),source.offsetByCodePoints(0,end)),"revision",expected,"nextOffset",end,"more",end<length,"total",length));
        }
        if(!kind.equals("preview"))ServerWorkspaceModules.require(p,dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE);
        if(kind.equals("rename")){
            ServerWorkspaceModules.keys(args,"module","kind","type","id","revision","name");String name=args.get("name").strip();if(name.isBlank()||name.length()>(type.equals("creatures")?48:80))throw new IllegalArgumentException("CONTENT_NAME");
            var document=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(source);document.put("name",name);var changed=new LinkedHashMap<>(args);changed.remove("name");changed.put("kind","save");changed.put("source",document.toString());return handle(p,operation,changed,true,permit);
        }
        if(kind.equals("save")){
            ServerWorkspaceModules.keys(args,"module","kind","type","id","revision","source");var node=JSON.createObjectNode().put("source",args.get("source")).put("expected_revision",expected);
            return switch(type){
                case "creatures"->CompletableFuture.completedFuture(RuntimeCreatures.define(p,operation,node.put("species_id",id.toString())));
                case "native_entities"->NativeEntityTemplates.define(p,operation,node.put("template_id",id.toString()));
                case "entity_rules"->{String ruleKind=ServerEntityInterop.ownedRules(p).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow().kind();yield ServerEntityInterop.set(p,operation,node.put("rule_id",id.toString()),ruleKind,false);}
                case "interaction_rules"->CompletableFuture.completedFuture(ServerInteractionRules.set(p,operation,node.put("rule_id",id.toString()),false));
                default->throw new IllegalArgumentException("CONTENT_TYPE");
            };
        }
        ServerWorkspaceModules.keys(args,"module","kind","type","id","revision");
        if(kind.equals("delete")&&type.equals("entity_rules"))return ServerEntityInterop.set(p,operation,JSON.createObjectNode().put("rule_id",id.toString()).put("expected_revision",expected),"logic",true);
        if(kind.equals("delete")&&type.equals("interaction_rules"))return CompletableFuture.completedFuture(ServerInteractionRules.set(p,operation,JSON.createObjectNode().put("rule_id",id.toString()).put("expected_revision",expected),true));
        if(kind.equals("preview")&&type.equals("creatures"))return ServerPreviews.open(p,null,JSON.createObjectNode().put("kind","creature").put("species_id",id.toString()).put("expected_revision",expected),permit);
        throw new IllegalArgumentException("CONTENT_ACTION");
    }
    private static Map<String,Object> page(ServerPlayer p,String type,String query,int offset)throws Exception{
        if(type.equals("packages"))return JSON.readValue(ServerPackageCatalog.read(p,Map.of("kind","packages","search",query,"offset",Integer.toString(offset))).get("state"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        if(type.equals("creatures"))return RuntimeCreatures.catalog(p,query,offset);
        var rows=new ArrayList<Map<String,Object>>();
        if(type.equals("native_entities"))for(var template:NativeEntityTemplates.ownedTemplates(p)){var definition=template.definition();rows.add(Map.of("id",template.id(),"revision",template.revision(),"name",definition.name(),"target",definition.type(),"state","SAVED"));}
        else if(type.equals("entity_rules"))for(var rule:ServerEntityInterop.ownedRules(p))rows.add(rule(rule.id(),rule.revision(),rule.source(),rule.kind()));
        else if(type.equals("interaction_rules"))for(var rule:ServerInteractionRules.ownedRules(p))rows.add(rule(rule.id(),rule.revision(),rule.source(),"interaction"));
        else throw new IllegalArgumentException("CONTENT_TYPE");
        return ServerWorkspaceModules.page(rows.stream().filter(r->(r.get("name")+" "+r.get("id")+" "+r.get("target")).toLowerCase(Locale.ROOT).contains(query)).sorted(Comparator.comparing(r->r.get("id").toString())).toList(),offset);
    }
    private static Map<String,Object> rule(UUID id,long revision,String source,String kind)throws Exception{var definition=JSON.readTree(source);var selector=definition.has("selector")?definition.path("selector"):definition;return Map.of("id",id,"revision",revision,"name",definition.path("name").asText(id.toString()),"ruleKind",kind,"target",selector.toString().substring(0,Math.min(200,selector.toString().length())),"state","ACTIVE");}
    private static String source(ServerPlayer p,String type,UUID id,long revision)throws Exception{
        String source;long current;
        switch(type){
            case "creatures"->{var value=RuntimeCreatures.get(p.level().getServer()).get(p.getUUID(),id).orElseThrow(()->new SecurityException("CONTENT_NOT_OWNED"));source=value.source();current=value.revision();}
            case "native_entities"->{var value=NativeEntityTemplates.ownedTemplates(p).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new SecurityException("CONTENT_NOT_OWNED"));source=value.source();current=value.revision();}
            case "entity_rules"->{var value=ServerEntityInterop.ownedRules(p).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new SecurityException("CONTENT_NOT_OWNED"));source=value.source();current=value.revision();}
            case "interaction_rules"->{var value=ServerInteractionRules.ownedRules(p).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new SecurityException("CONTENT_NOT_OWNED"));source=value.source();current=value.revision();}
            default->throw new IllegalArgumentException("CONTENT_TYPE");
        }
        if(current!=revision)throw new IllegalStateException("CONTENT_REVISION_CHANGED");return source;
    }
}
