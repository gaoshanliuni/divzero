package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.NativePackageCompatibility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Unified Web/legacy Code Studio source bridge. Publication is not execution or runtime-state migration. */
public final class ServerJavaStudio {
    private ServerJavaStudio(){}
    public static void authorize(ServerPlayer player){var server=player.level().getServer();if(!server.isSameThread()||player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||server.getPlayerList().getPlayer(player.getUUID())!=player)throw new SecurityException("JAVA_STUDIO_IDENTITY");for(var action:List.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES))if(!MineAgentRuntimeServices.permissions(server).allowed(player.getUUID(),player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),action))throw new SecurityException("JAVA_STUDIO_PERMISSION");}
    private static boolean javaPath(String path){return path.toLowerCase(Locale.ROOT).endsWith(".java");}
    private static boolean supported(String path){return javaPath(path)||RuntimeStudioScriptPlan.path(path);}
    private static boolean clientPath(String path){return path!=null&&path.startsWith("client/");}
    private record StudioSource(String entry,boolean java,boolean client,Map<String,CodeDraft.SourceRef> refs){public StudioSource{refs=Map.copyOf(refs);}}
    private static StudioSource source(RuntimePackage pkg,boolean client,boolean java){if(client){if(java){var plan=ClientJavaPlan.inspect(pkg);var refs=new TreeMap<String,CodeDraft.SourceRef>();plan.sources().forEach((path,ref)->refs.put(path,new CodeDraft.SourceRef(ref.sha256(),Math.toIntExact(ref.size()))));return new StudioSource(plan.entrypoint(),true,true,refs);}var plan=ClientScriptPlan.inspect(pkg);var refs=new TreeMap<String,CodeDraft.SourceRef>();plan.modules().forEach((path,ref)->refs.put(path,new CodeDraft.SourceRef(ref.sha256(),Math.toIntExact(ref.size()))));return new StudioSource(plan.entrypoint(),false,true,refs);}var ref=RuntimeStudioPlan.source(pkg);if(RuntimeStudioPlan.java(pkg)!=java)throw new IllegalStateException("STUDIO_LANGUAGE_CHANGED");return new StudioSource(ref.path(),java,false,RuntimeStudioPlan.refs(pkg));}
    private static Map<String,String> readAll(RuntimePackage pkg,StudioSource source,ContentAddressedStore content)throws Exception{if(!source.client())return RuntimeStudioPlan.readAll(pkg,content);var result=new TreeMap<String,String>();for(var entry:source.refs().entrySet())result.put(entry.getKey(),CodeDraftSources.read(content,entry.getValue()));return Collections.unmodifiableMap(result);}
    private static CodeDraft draft(ServerPlayer viewer,UUID id,long revision){var d=MineAgentRuntimeServices.codeDrafts(viewer.level().getServer()).get(id).orElseThrow(()->new IllegalStateException("JAVA_STUDIO_DRAFT_MISSING"));if(!d.ownerPlayerId().equals(viewer.getUUID())||!d.worldId().equals(MineAgentRuntimeServices.worldId(viewer.level().getServer()))||d.revision()!=revision||!supported(d.path()))throw new IllegalStateException("JAVA_STUDIO_DRAFT_CHANGED");return d;}
    public static RuntimePackage publishSource(ServerPlayer viewer,UUID operation,CodeDraft source,long expectedPackage,String name)throws Exception {
        authorize(viewer);var d=draft(viewer,source.draftId(),source.revision());var server=viewer.level().getServer();var runtime=ServerPackageRuntime.get(server);var library=runtime.worldLibrary();var current=library.get(d.packageId()).orElse(null);
        boolean javaSource=javaPath(d.path()),client=clientPath(d.path());StudioSource currentSource=null;if(current!=null){if(runtime.ownedPackage(viewer.getUUID(),current.packageId(),current.revision()).isEmpty()||current.revision()!=expectedPackage)throw new IllegalStateException("JAVA_STUDIO_PACKAGE_CHANGED");currentSource=source(current,client,javaSource);}else if(expectedPackage!=0)throw new IllegalStateException("JAVA_STUDIO_PACKAGE_CHANGED");
        if(current!=null&&!current.dependencies().isEmpty()&&!d.dependencyDeclaration())throw new IllegalStateException("STUDIO_DEPENDENCY_BASE_NOT_LOADED");
        if(name==null||name.isBlank()||name.length()>128)throw new IllegalArgumentException("JAVA_STUDIO_NAME");
        var sourceFiles=MineAgentRuntimeServices.codeDrafts(server).sources(d);if(sourceFiles.keySet().stream().anyMatch(path->clientPath(path)!=client))throw new IllegalStateException("STUDIO_WORKSPACE_SIDE");
        if(currentSource!=null&&currentSource.refs().size()>1&&!d.workspace())throw new IllegalStateException("STUDIO_WORKSPACE_BASE_NOT_LOADED");
        if(javaSource)dev.mineagent.runtime.worker.compile.JavaSourceCompiler.inferClassName(d.source());else for(String text:sourceFiles.values())if(!new dev.mineagent.runtime.scripting.preflight.ScriptPreflight().inspect(text).accepted())throw new IllegalStateException("STUDIO_SCRIPT_PREFLIGHT");
        NativeCompatibility contract;if(current!=null)contract=current.nativeCompatibility();else if(client)contract=new NativeCompatibility(1,Map.of("CLIENT",new NativeCompatibility.Target("26.1.2","neoforge","26.1.2.106","official",25,Map.of())));else{var environment=NativePackageCompatibility.observe();var mods=new TreeMap<>(environment.mods());mods.remove("minecraft");mods.remove("neoforge");if(mods.size()>64)throw new IllegalStateException("JAVA_STUDIO_ENVIRONMENT_LIMIT");contract=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target(environment.minecraft(),environment.loader(),environment.loaderVersion(),environment.namespace(),environment.javaFeature(),mods)));}
        String path=client?d.path():d.workspace()?d.path():currentSource!=null?currentSource.entry():javaSource?"server/extension.java":d.path().toLowerCase(Locale.ROOT).endsWith(".mjs")?"server/studio.mjs":"server/studio.js";
        var resources=new LinkedHashMap<String,RuntimeResourceRef>();var entries=new LinkedHashMap<String,RuntimeEntrypoint>();if(current!=null){resources.putAll(current.resources());entries.putAll(current.entrypoints());for(String old:currentSource.refs().keySet())resources.remove(old);entries.remove(client?(javaSource?"client_java":"client"):javaSource?"java":"studio_script");}
        for(var file:sourceFiles.entrySet()){
            if(file.getValue().isBlank())throw new IllegalStateException("STUDIO_WORKSPACE_EMPTY_SOURCE");
            byte[] bytes=CodeDraftSources.utf8(file.getValue());String target=file.getKey().equals(d.path())?path:file.getKey(),stored=runtime.worldContent().put(bytes).sha256();
            if(resources.containsKey(target))throw new IllegalStateException("STUDIO_WORKSPACE_PATH_COLLISION");
            resources.put(target,new RuntimeResourceRef(target,stored,client?RuntimeResourceSide.CLIENT:RuntimeResourceSide.SERVER,javaSource?"text/x-java-source":"application/javascript",bytes.length));
        }
        String hash=CodeDraftSources.fingerprint(d);var ref=resources.get(path);entries.put(client?(javaSource?"client_java":"client"):javaSource?"java":"studio_script",new RuntimeEntrypoint(path,client?RuntimeResourceSide.CLIENT:RuntimeResourceSide.SERVER,ref.sha256()));
        var coderSource=MineAgentRuntimeServices.codeDrafts(server).coder().sourceJob(d);if(coderSource.isPresent()&&!coderSource.get().state().equals("ADOPTED"))throw new IllegalStateException("STUDIO_CODER_ADOPTION_NOT_RECORDED");
        var unsigned=new RuntimePackage(d.packageId(),current==null?RuntimePackageType.EXTENSION:current.type(),name,current==null?"1.0.0":current.version(),ActivationMode.HOT_RUNTIME,d.dependencies(),current==null?(client?Set.of():Set.of("RUN_CODE")):current.permissions(),entries,current==null?Map.of():client?current.definitions():Map.of(),resources,current==null?(coderSource.isPresent()?PackageOrigin.GENERATED:PackageOrigin.LOCAL_STUDIO):current.origin(),false,expectedPackage+1,"0".repeat(64),"",System.currentTimeMillis(),contract);
        String canonical=RuntimePackageCanonicalizer.sha256(unsigned);if(current!=null&&current.canonicalSha256().equals(canonical))return current;
        var signer=MineAgentRuntimeServices.identity(server);var candidate=new RuntimePackage(unsigned.packageId(),unsigned.type(),unsigned.name(),unsigned.version(),unsigned.activationMode(),unsigned.dependencies(),unsigned.permissions(),unsigned.entrypoints(),unsigned.definitions(),unsigned.resources(),unsigned.origin(),false,unsigned.revision(),canonical,Base64.getEncoder().encodeToString(signer.sign(canonical.getBytes(StandardCharsets.US_ASCII))),unsigned.updatedAtEpochMillis(),contract);
        if(client){if(javaSource)ClientJavaPlan.inspect(candidate);else ClientScriptPlan.inspect(candidate);}else if(javaSource)RuntimeJavaPlan.source(candidate);else RuntimeStudioScriptPlan.source(candidate);
        library.publishStudio(new JavaStudioMetadata.Input(operation,d.worldId(),viewer.getUUID(),d.draftId(),d.revision(),d.packageId(),expectedPackage,hash,name),candidate);return library.get(candidate.packageId()).orElseThrow();
    }
    public static RuntimePackage ensureSource(ServerPlayer viewer,CodeDraft draft)throws Exception {
        var library=ServerPackageRuntime.get(viewer.level().getServer()).worldLibrary();var current=library.get(draft.packageId()).orElse(null);String name=current==null?(javaPath(draft.path())?"Java · ":"Rhino · ")+draft.path():current.name();
        UUID operation=UUID.nameUUIDFromBytes(("java-studio-source|"+draft.worldId()+"|"+draft.draftId()+"|"+draft.revision()+"|"+(current==null?0:current.revision())).getBytes(StandardCharsets.UTF_8));return publishSource(viewer,operation,draft,current==null?0:current.revision(),name);
    }
    private static List<Map<String,Object>> publications(ServerPlayer viewer,CodeDraft d){
        var server=viewer.level().getServer();var drafts=MineAgentRuntimeServices.codeDrafts(server);var all=new ArrayList<Map<String,Object>>();
        for(var r:drafts.javaPublications().forPackage(viewer.getUUID(),d.packageId()))all.add(Map.ofEntries(
                Map.entry("id",r.id()),Map.entry("draft",r.draft()),Map.entry("revision",r.revision()),Map.entry("state",r.state()),Map.entry("error",r.error()),Map.entry("uncertain",r.uncertain()),
                Map.entry("loaded",MineAgentRuntimeServices.javaExtensions(server).isLoaded(r.id())),Map.entry("artifact",r.artifact()),Map.entry("nativeClasspath",r.nativeClasspath()),
                Map.entry("dependencyHash",r.dependencies()==null?"":r.dependencies().receiptHash()),Map.entry("dependencyCount",r.dependencies()==null?0:r.dependencies().nodes().size()),Map.entry("consumerCount",MineAgentRuntimeServices.javaExtensions(server).consumers(r.id()).size()),Map.entry("packageHash",r.runtimePackageHash()),Map.entry("language","JAVA"),Map.entry("updatedAt",r.updatedAt())));
        for(var r:drafts.scriptPublications().forPackage(viewer.getUUID(),d.packageId())){
            var status=StudioScriptRuntime.status(server,r.id());all.add(Map.ofEntries(
                    Map.entry("id",r.id()),Map.entry("draft",r.draft()),Map.entry("revision",r.revision()),Map.entry("state",r.state()),Map.entry("error",r.error()),Map.entry("uncertain",r.uncertain()),
                    Map.entry("loaded",status.get("loaded")),Map.entry("suspended",status.get("suspended")),Map.entry("metadataPending",status.get("metadataPending")),
                    Map.entry("packageHash",r.packageHash()),Map.entry("packageRevision",r.packageRevision()),Map.entry("sourceHash",r.sourceHash()),
                    Map.entry("dependencyHash",r.dependencies()==null?"":r.dependencies().receiptHash()),Map.entry("dependencyCount",r.dependencies()==null?0:r.dependencies().nodes().size()),Map.entry("consumerCount",StudioScriptRuntime.consumers(server,r.id()).size()),Map.entry("language","RHINO"),Map.entry("legacyStop",r.legacyStop()),Map.entry("source",r.source()),Map.entry("line",r.line()),Map.entry("column",r.column()),Map.entry("updatedAt",r.updatedAt())));
        }
        return all.stream().sorted(Comparator.<Map<String,Object>>comparingLong(r->((Number)r.get("updatedAt")).longValue()).reversed().thenComparing(r->r.get("id").toString())).toList();
    }
    public static Map<String,Object> read(ServerPlayer viewer,Map<String,String> args)throws Exception {
        authorize(viewer);var server=viewer.level().getServer();var drafts=MineAgentRuntimeServices.codeDrafts(server);String kind=args.get("kind");
        if(Set.of("coderList","coderGet","coderText","coderFiles","coderFile").contains(kind))return ServerStudioCoder.get(server).read(viewer,args);
        if(kind.equals("dependencies"))return dependencyRead(viewer,args);
        if(kind.equals("dependencyCandidates"))return dependencyCandidates(viewer,args);
        if(Set.of("workspaceFiles","workspaceHistory","workspaceSource").contains(kind))return workspaceRead(viewer,args);
        if(kind.equals("scriptCheck")){
            if(!args.keySet().equals(Set.of("kind","source"))||args.get("source")==null||args.get("source").isBlank()||args.get("source").length()>16000)throw new IllegalArgumentException("STUDIO_SCRIPT_SOURCE_LIMIT");
            var result=new dev.mineagent.runtime.scripting.preflight.ScriptPreflight().inspect(args.get("source"));
            return Map.of("accepted",result.accepted(),"diagnostics",result.diagnostics().stream().limit(8).map(d->Map.of("code",d.code(),"line",d.line(),"message",d.message().substring(0,Math.min(400,d.message().length())))).toList(),"more",result.diagnostics().size()>8);
        }
        if(kind.equals("draftHistory")){
            if(!args.keySet().equals(Set.of("kind","draftId","revision","offset")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");
            var d=draft(viewer,UUID.fromString(args.get("draftId")),Long.parseLong(args.get("revision")));int offset=Integer.parseInt(args.get("offset"));
            if(offset<0||offset>d.history().size())throw new IllegalArgumentException("STUDIO_HISTORY_PAGE");var items=new ArrayList<Map<String,Object>>();
            for(int i=offset;i<Math.min(d.history().size(),offset+8);i++){String old=d.history().get(i);items.add(Map.of("index",i,"hash",RuntimePackageCanonicalizer.sha256(old),"characters",old.length()));}
            return Map.of("draftId",d.draftId(),"revision",d.revision(),"history",items,"total",d.history().size(),"nextOffset",offset+items.size(),"more",offset+items.size()<d.history().size());
        }
        if(kind.equals("historySource")){
            if(!args.keySet().equals(Set.of("kind","draftId","revision","entryIndex","hash","offset")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");
            var d=draft(viewer,UUID.fromString(args.get("draftId")),Long.parseLong(args.get("revision")));int index=Integer.parseInt(args.get("entryIndex"));
            if(index<0||index>=d.history().size())throw new IllegalStateException("STUDIO_HISTORY_CHANGED");String source=d.history().get(index),hash=RuntimePackageCanonicalizer.sha256(source);
            if(!hash.equals(args.get("hash")))throw new IllegalStateException("STUDIO_HISTORY_CHANGED");if(source.length()>16000)throw new IllegalStateException("STUDIO_HISTORY_SOURCE_LIMIT");
            int start=Integer.parseInt(args.get("offset")),length=source.codePointCount(0,source.length());if(start<0||start>length)throw new IllegalArgumentException("STUDIO_HISTORY_PAGE");int end=Math.min(length,start+1024);
            return Map.of("text",source.substring(source.offsetByCodePoints(0,start),source.offsetByCodePoints(0,end)),"hash",hash,"nextOffset",end,"more",end<length);
        }
        if(kind.equals("list")){if(!args.keySet().equals(Set.of("kind","offset")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");int offset=Integer.parseInt(args.get("offset"));if(offset<0||offset>1000000)throw new IllegalArgumentException("JAVA_STUDIO_PAGE");var all=drafts.allFor(viewer.getUUID(),false).stream().filter(d->supported(d.path())).toList();var page=all.stream().skip(offset).limit(8).map(d->Map.of("id",d.draftId(),"revision",d.revision(),"path",d.path(),"targetSide",clientPath(d.path())?"CLIENT":"SERVER","status",d.status(),"packageId",d.packageId(),"taskId",d.taskId())).toList();return Map.of("drafts",page,"nextOffset",offset+page.size(),"more",offset+page.size()<all.size());}
        if(kind.equals("scriptDiag")){
            if(!args.keySet().equals(Set.of("kind","publicationId","publicationRevision","offset")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");
            var r=drafts.scriptPublications().get(UUID.fromString(args.get("publicationId")));if(!r.owner().equals(viewer.getUUID())||r.revision()!=Long.parseLong(args.get("publicationRevision"))||Integer.parseInt(args.get("offset"))!=0)throw new IllegalStateException("STUDIO_SCRIPT_RECORD_CHANGED");
            return Map.of("text",r.error()+(r.source().isEmpty()?"":"\n"+r.source()+":"+r.line()+":"+r.column()),"nextOffset",0,"more",false);
        }
        if(kind.equals("diagnostics")){if(!args.keySet().equals(Set.of("kind","publicationId","publicationRevision","offset")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");var r=drafts.javaPublications().get(UUID.fromString(args.get("publicationId")));if(!r.owner().equals(viewer.getUUID())||r.revision()!=Long.parseLong(args.get("publicationRevision")))throw new IllegalStateException("JAVA_STUDIO_PUBLICATION_CHANGED");String text=String.join("\n",r.diagnostics());int offset=Integer.parseInt(args.get("offset")),length=text.codePointCount(0,text.length());if(offset<0||offset>length)throw new IllegalArgumentException("JAVA_STUDIO_PAGE");int end=Math.min(length,offset+4096);return Map.of("text",text.substring(text.offsetByCodePoints(0,offset),text.offsetByCodePoints(0,end)),"nextOffset",end,"more",end<length);}
        if(kind.equals("package")){var legacy=args.keySet().equals(Set.of("kind","packageId","packageRevision","offset"));if(!legacy&&!args.keySet().equals(Set.of("kind","packageId","packageRevision","targetSide","language","offset")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");var p=ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision"))).orElseThrow(()->new SecurityException("JAVA_STUDIO_NOT_OWNED"));String side=legacy?"SERVER":args.get("targetSide"),language=legacy?(RuntimeStudioPlan.java(p)?"JAVA":"RHINO"):args.get("language");if(!Set.of("SERVER","CLIENT").contains(side)||!Set.of("RHINO","JAVA").contains(language))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");var selected=source(p,side.equals("CLIENT"),language.equals("JAVA"));String text=readAll(p,selected,ServerPackageRuntime.get(server).worldContent()).get(selected.entry());int start=Integer.parseInt(args.get("offset")),length=text.codePointCount(0,text.length());if(start<0||start>length)throw new IllegalArgumentException("JAVA_STUDIO_PAGE");int end=Math.min(length,start+4096);return Map.of("packageId",p.packageId(),"revision",p.revision(),"name",p.name(),"source",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)),"nextOffset",end,"more",end<length,"hash",p.canonicalSha256(),"path",selected.entry(),"targetSide",selected.client()?"CLIENT":"SERVER","language",selected.java()?"JAVA":"RHINO");}
        if(!args.keySet().equals(Set.of("kind","draftId","revision","offset")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");UUID draftId=UUID.fromString(args.get("draftId"));long requested=Long.parseLong(args.get("revision"));if(kind.equals("get")&&requested==0)requested=drafts.get(draftId).filter(v->v.ownerPlayerId().equals(viewer.getUUID())).orElseThrow().revision();var d=draft(viewer,draftId,requested);int offset=Integer.parseInt(args.get("offset"));int length=d.source().codePointCount(0,d.source().length());if(offset<0||kind.equals("source")&&offset>length||kind.equals("get")&&offset>8192)throw new IllegalArgumentException("JAVA_STUDIO_PAGE");
        if(kind.equals("source")){int end=Math.min(length,offset+4096);return Map.of("text",d.source().substring(d.source().offsetByCodePoints(0,offset),d.source().offsetByCodePoints(0,end)),"nextOffset",end,"more",end<length,"hash",RuntimePackageCanonicalizer.sha256(d.source()));}
        if(!kind.equals("get"))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");
        var p=ServerPackageRuntime.get(server).worldLibrary().get(d.packageId()).orElse(null);
        var all=clientPath(d.path())?List.<Map<String,Object>>of():publications(viewer,d);var page=all.stream().skip(offset).limit(8).toList();
        var legacy=MineAgentRuntimeServices.packages(server).active(d.packageId()).orElse(null);
        var coderSource=drafts.coder().sourceJob(d).orElse(null);var nativeSource=drafts.coder().nativePublicationSource(d).orElse(null);int nativeTypes=coderSource==null||coderSource.input().nativeSelection()==null?0:coderSource.input().nativeSelection().types().size(),nativeOverlays=coderSource==null||coderSource.input().nativeSelection()==null?0:coderSource.input().nativeSelection().overlays().size();
        return Map.ofEntries(Map.entry("id",d.draftId()),Map.entry("revision",d.revision()),Map.entry("status",d.status()),Map.entry("path",d.path()),Map.entry("targetSide",clientPath(d.path())?"CLIENT":"SERVER"),
                Map.entry("packageId",d.packageId()),Map.entry("packageRevision",p==null?0:p.revision()),Map.entry("packageCanonical",p==null?"":p.canonicalSha256()),Map.entry("packageName",p==null?(clientPath(d.path())?"CLIENT ":"")+(javaPath(d.path())?"Java · ":"Rhino · ")+d.path():p.name()),
                Map.entry("taskId",d.taskId()),Map.entry("publications",page),Map.entry("nextPublicationOffset",offset+page.size()),Map.entry("morePublications",offset+page.size()<all.size()),
                Map.entry("sourceHash",RuntimePackageCanonicalizer.sha256(d.source())),Map.entry("language",javaPath(d.path())?"JAVA":"RHINO"),Map.entry("historyCount",d.history().size()),Map.entry("workspaceHash",CodeDraftSources.fingerprint(d)),Map.entry("fileCount",d.additionalSources().size()+1),Map.entry("workspace",d.workspace()),Map.entry("dependencyCount",d.dependencies().size()),Map.entry("agentId",MineAgentRuntimeServices.tasks(server).get(d.taskId()).map(t->t.agentId().toString()).orElse("")),
                Map.entry("nativeTypeCount",nativeTypes),Map.entry("nativeOverlayCount",nativeOverlays),Map.entry("nativePublicationContext",nativeSource!=null),Map.entry("nativeContextInvalidated",coderSource!=null&&coderSource.input().nativeSelection()!=null&&nativeSource==null),
                Map.entry("legacyActive",!clientPath(d.path())&&legacy!=null),Map.entry("legacyLoaded",!clientPath(d.path())&&MineAgentRuntimeServices.packages(server).isLoaded(d.packageId())),
                Map.entry("legacyCanStop",!clientPath(d.path())&&legacy!=null&&StudioScriptRuntime.legacyMatches(d,legacy)));
    }
    public static Map<String,Object> write(ServerPlayer viewer,UUID operation,Map<String,String> args)throws Exception {
        authorize(viewer);var server=viewer.level().getServer();var drafts=MineAgentRuntimeServices.codeDrafts(server);String action=args.get("action");if(!"true".equals(args.get("confirmed")))throw new IllegalArgumentException("JAVA_STUDIO_CONFIRM_REQUIRED");
        if(action.equals("coderSubmit"))return ServerStudioCoder.get(server).submit(viewer,operation,args);
        if(action.equals("dependency")){
            String change=args.get("change");var expected=change.equals("put")?Set.of("action","draftId","revision","change","packageId","packageRevision","version","confirmed"):Set.of("action","draftId","revision","change","packageId","version","confirmed");
            if(!args.keySet().equals(expected))throw new IllegalArgumentException("STUDIO_DEPENDENCY_ARGUMENTS");
            var d=draft(viewer,UUID.fromString(args.get("draftId")),Long.parseLong(args.get("revision")));var values=new LinkedHashMap<>(d.dependencies());UUID dependency=UUID.fromString(args.get("packageId"));
            if(change.equals("put")){if(!args.get("version").matches("[0-9A-Za-z_.+-]{1,64}"))throw new IllegalArgumentException("STUDIO_DEPENDENCY_VERSION");requireDependencyCandidate(viewer,d,dependency,Long.parseLong(args.get("packageRevision")),args.get("version"));values.put(dependency,args.get("version"));}
            else if(change.equals("remove")){if(values.remove(dependency)==null)throw new IllegalStateException("STUDIO_DEPENDENCY_MISSING");}else throw new IllegalArgumentException("STUDIO_DEPENDENCY_ACTION");
            var result=drafts.dependencies(d.draftId(),viewer.getUUID(),d.revision(),values);if(!result.accepted())throw new IllegalStateException("STUDIO_DEPENDENCY_"+result.errorCode());
            return Map.of("draftId",d.draftId(),"revision",result.draft().revision(),"state","DRAFT_NOT_PUBLISHED","workspaceHash",CodeDraftSources.fingerprint(result.draft()));
        }
        if(action.equals("workspaceFile")){
            if(!args.keySet().equals(Set.of("action","draftId","revision","change","path","target","source","confirmed")))throw new IllegalArgumentException("STUDIO_WORKSPACE_ARGUMENTS");
            var d=draft(viewer,UUID.fromString(args.get("draftId")),Long.parseLong(args.get("revision")));var result=drafts.file(d.draftId(),viewer.getUUID(),d.revision(),args.get("change"),args.get("path"),args.get("target"),args.get("source"));
            if(!result.accepted())throw new IllegalStateException("STUDIO_WORKSPACE_"+result.errorCode());return Map.of("draftId",d.draftId(),"revision",result.draft().revision(),"entry",result.draft().path(),"workspaceHash",CodeDraftSources.fingerprint(result.draft()),"state","DRAFT_NOT_PUBLISHED");
        }
        if(action.equals("coderRepair"))return ServerStudioCoder.get(server).repair(viewer,operation,args);
        if(action.equals("coderCancel")){if(!args.keySet().equals(Set.of("action","confirmed","jobId","revision")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");return ServerStudioCoder.get(server).cancel(viewer,UUID.fromString(args.get("jobId")),Long.parseLong(args.get("revision")));}
        if(action.equals("coderAdopt")){if(!args.keySet().equals(Set.of("action","confirmed","jobId","revision","sourceHash")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");return ServerStudioCoder.get(server).adopt(viewer,UUID.fromString(args.get("jobId")),Long.parseLong(args.get("revision")),args.get("sourceHash"));}
        if(action.equals("create")){
            var legacyCreate=args.keySet().equals(Set.of("action","path","source","agentId","packageId","packageRevision","confirmed"));if(!legacyCreate&&!args.keySet().equals(Set.of("action","path","source","agentId","packageId","packageRevision","targetSide","confirmed")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");String targetSide=args.getOrDefault("targetSide","SERVER");if(!Set.of("SERVER","CLIENT").contains(targetSide))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");UUID agent=UUID.fromString(args.get("agentId"));if(!ServerPackageRuntime.get(server).mayGenerate(viewer.getUUID(),agent))throw new SecurityException("JAVA_STUDIO_AGENT_OWNER");
            if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.START_TASK))throw new SecurityException("JAVA_STUDIO_TASK_PERMISSION");
            String path=args.get("path"),text=args.get("source");boolean client=targetSide.equals("CLIENT");if(clientPath(path)!=client||!path.matches("[A-Za-z0-9_./-]{1,128}")||path.contains("..")||!supported(path)||text==null||text.isBlank()||text.length()>16000)throw new IllegalArgumentException("JAVA_STUDIO_SOURCE_INPUT");UUID pkg=args.get("packageId").isEmpty()?UUID.nameUUIDFromBytes(("web-java-package|"+MineAgentRuntimeServices.worldId(server)+"|"+viewer.getUUID()+"|"+operation).getBytes(StandardCharsets.UTF_8)):UUID.fromString(args.get("packageId"));long revision=Long.parseLong(args.get("packageRevision"));StudioSource originalSource=null;if(!args.get("packageId").isEmpty()){var p=ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,revision).orElseThrow(()->new SecurityException("JAVA_STUDIO_NOT_OWNED"));originalSource=source(p,client,javaPath(path));}
            if(RuntimeStudioScriptPlan.path(path)&&!new dev.mineagent.runtime.scripting.preflight.ScriptPreflight().inspect(text).accepted())throw new IllegalArgumentException("STUDIO_SCRIPT_PREFLIGHT");var task=MineAgentRuntimeServices.tasks(server).createIdempotent(operation,agent,viewer.getUUID(),"Code Studio "+targetSide+": "+path,50,List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("publish",Set.of())));
            var additional=new LinkedHashMap<String,CodeDraft.SourceRef>();Map<UUID,String> dependencies=Map.of();
            if(!args.get("packageId").isEmpty()){
                var original=ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,revision).orElseThrow();readAll(original,originalSource,ServerPackageRuntime.get(server).worldContent());
                dependencies=original.dependencies();additional.putAll(originalSource.refs());additional.remove(originalSource.entry());if(additional.containsKey(path))throw new IllegalStateException("STUDIO_WORKSPACE_PATH_COLLISION");
            }
            var d=drafts.createIdempotent(operation,viewer.getUUID(),task.taskId(),pkg,task.revision(),Math.max(1,revision),path,args.get("source"),additional,!additional.isEmpty()||!dependencies.isEmpty(),dependencies);return Map.of("draftId",d.draftId(),"revision",d.revision(),"packageId",pkg,"state","DRAFT");
        }
        UUID id=UUID.fromString(args.get("draftId"));long expected=Long.parseLong(args.get("revision"));var d=draft(viewer,id,expected);
        if(action.equals("save")){if(!args.keySet().equals(Set.of("action","draftId","revision","source","confirmed")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");var result=drafts.update(id,viewer.getUUID(),expected,args.get("source"));if(!result.accepted())throw new IllegalStateException("JAVA_STUDIO_"+result.errorCode());return Map.of("draftId",id,"revision",result.draft().revision(),"state",result.draft().status());}
        if(action.equals("publishSource")){if(!args.keySet().equals(Set.of("action","draftId","revision","packageRevision","name","confirmed")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");var p=publishSource(viewer,operation,d,Long.parseLong(args.get("packageRevision")),args.get("name"));return Map.of("packageId",p.packageId(),"revision",p.revision(),"canonical",p.canonicalSha256(),"state","SOURCE_PUBLISHED_NOT_EXECUTED");}
        if(action.equals("run")){if(!args.keySet().equals(Set.of("action","draftId","revision","confirmed")))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");if(clientPath(d.path()))throw new IllegalStateException("STUDIO_CLIENT_LOCAL_EXECUTION_REQUIRED");if(javaPath(d.path()))dev.mineagent.runtime.neoforge.network.MineAgentNetwork.runJavaStudio(viewer,id,expected);else StudioScriptRuntime.get(server).run(viewer,d,expected);return Map.of("state","OBSERVE_PUBLICATION_JOURNAL");}
        if(action.equals("stopLegacyScript")){
            if(!args.keySet().equals(Set.of("action","draftId","revision","confirmed"))||!RuntimeStudioScriptPlan.path(d.path()))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");
            var record=StudioScriptRuntime.get(server).stopLegacy(viewer,d);return Map.of("state",record.state(),"publicationId",record.id());
        }
        if(action.equals("stop")){if(!args.keySet().equals(Set.of("action","draftId","revision","publicationId","publicationRevision","confirmed"))||clientPath(d.path()))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");if(javaPath(d.path()))dev.mineagent.runtime.neoforge.network.MineAgentNetwork.stopJavaStudio(viewer,id,expected,args);else StudioScriptRuntime.get(server).stop(viewer,d,UUID.fromString(args.get("publicationId")),Long.parseLong(args.get("publicationRevision")));return Map.of("state","OBSERVE_PUBLICATION_JOURNAL");}
        throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");
    }
    public static String error(Throwable failure){for(int i=0;failure!=null&&i<16;i++,failure=failure.getCause()){String code=Objects.toString(failure.getMessage(),"");if(code.matches("(?:JAVA|NATIVE_(?:CLASSPATH|CODER|LIVE)|STUDIO_SCRIPT|STUDIO)_[A-Z_]{1,80}"))return code;}return "JAVA_STUDIO_FAILED";}
    private static Map<String,Object> dependencyRead(ServerPlayer player,Map<String,String> args)throws Exception {
        if(!args.keySet().equals(Set.of("kind","draftId","revision","version","offset")))throw new IllegalArgumentException("STUDIO_DEPENDENCY_ARGUMENTS");
        var d=draft(player,UUID.fromString(args.get("draftId")),Long.parseLong(args.get("revision")));long version=Long.parseLong(args.get("version"));int offset=Integer.parseInt(args.get("offset"));
        if(version<0||offset<0||offset>32)throw new IllegalArgumentException("STUDIO_DEPENDENCY_PAGE");
        var required=version==0?d.dependencies():d.workspaceHistory().stream().filter(v->v.revision()==version).findFirst().orElseThrow(()->new IllegalStateException("STUDIO_WORKSPACE_HISTORY_MISSING")).dependencies();
        var server=player.level().getServer();var page=new ArrayList<Map<String,Object>>();boolean java=javaPath(d.path()),client=clientPath(d.path());String graphHash="",resolution=version==0?(client?"CLIENT_LOCAL_RUNTIME_REQUIRED":java?"RESOLVED_NOT_COMPILED":"RESOLVED_NOT_EXECUTED"):"HISTORICAL_DECLARATION";
        if(version==0&&!client)try{graphHash=java?ServerJavaDependencies.resolve(server,player.getUUID(),d.packageId(),required).receiptHash():ServerScriptDependencies.resolve(server,player.getUUID(),d.packageId(),required).receiptHash();}catch(Exception failure){resolution=error(failure);}
        for(var edge:required.entrySet().stream().sorted(Map.Entry.comparingByKey()).skip(offset).limit(4).toList()){
            var value=new LinkedHashMap<String,Object>();value.put("packageId",edge.getKey());value.put("version",edge.getValue());value.put("state",version==0?"NOT_RESOLVED":"HISTORICAL_DECLARATION");
            if(version==0&&client){value.put("state","DECLARED_CLIENT_LOCAL_RUNTIME_REQUIRED");value.put("consumerCount",0);value.put("consumers",List.of());}
            else if(version==0)try{
                List<UUID> consumers;
                if(java){var graph=ServerJavaDependencies.resolve(server,player.getUUID(),d.packageId(),Map.of(edge.getKey(),edge.getValue()));var node=graph.node(edge.getKey());
                    value.put("className",node.className());value.put("publicationId",node.publication());value.put("packageRevision",node.packageRevision());value.put("artifact",node.artifact());value.put("transitiveCount",graph.nodes().size()-1);consumers=MineAgentRuntimeServices.javaExtensions(server).consumers(node.publication());
                }else{var graph=ServerScriptDependencies.resolve(server,player.getUUID(),d.packageId(),Map.of(edge.getKey(),edge.getValue()));var node=graph.node(edge.getKey());
                    value.put("className","Rhino · "+node.entry());value.put("publicationId",node.publication());value.put("packageRevision",node.packageRevision());value.put("sourceHash",node.sourceHash());value.put("transitiveCount",graph.nodes().size()-1);consumers=StudioScriptRuntime.consumers(server,node.publication());
                }
                value.put("state","PUBLISHED_AND_LOADED");value.put("consumerCount",consumers.size());value.put("consumers",consumers.stream().limit(8).toList());
            }catch(Exception failure){value.put("state",error(failure));}
            page.add(value);
        }
        return Map.ofEntries(
                Map.entry("dependencies",page),
                Map.entry("revision",d.revision()),
                Map.entry("version",version),
                Map.entry("total",required.size()),
                Map.entry("nextOffset",offset+page.size()),
                Map.entry("more",offset+page.size()<required.size()),
                Map.entry("resolution",resolution),
                Map.entry("graphHash",graphHash),
                Map.entry("java",java),
                Map.entry("targetSide",client?"CLIENT":"SERVER"),
                Map.entry("supported",true));
    }
    private static Map<String,Object> dependencyCandidates(ServerPlayer player,Map<String,String> args)throws Exception {
        if(!args.keySet().equals(Set.of("kind","draftId","revision","offset")))throw new IllegalArgumentException("STUDIO_DEPENDENCY_ARGUMENTS");
        var draft=draft(player,UUID.fromString(args.get("draftId")),Long.parseLong(args.get("revision")));int offset=Integer.parseInt(args.get("offset"));if(offset<0||offset>1000000)throw new IllegalArgumentException("STUDIO_DEPENDENCY_PAGE");
        boolean client=clientPath(draft.path()),java=javaPath(draft.path());var source=ServerPackageRuntime.get(player.level().getServer()).ownedHeads(player.getUUID(),offset,8);var items=new ArrayList<Map<String,Object>>();
        for(var pkg:source.items()){
            if(pkg.packageId().equals(draft.packageId())||pkg.activationMode()!=ActivationMode.HOT_RUNTIME)continue;
            if(client){if(pkg.entrypoints().containsKey("client"))items.add(Map.of("packageId",pkg.packageId(),"name",pkg.name(),"version",pkg.version(),"revision",pkg.revision(),"targetSide","CLIENT","language","RHINO"));if(pkg.entrypoints().containsKey("client_java"))items.add(Map.of("packageId",pkg.packageId(),"name",pkg.name(),"version",pkg.version(),"revision",pkg.revision(),"targetSide","CLIENT","language","JAVA"));}
            else if(java&&pkg.entrypoints().containsKey("java"))items.add(Map.of("packageId",pkg.packageId(),"name",pkg.name(),"version",pkg.version(),"revision",pkg.revision(),"targetSide","SERVER","language","JAVA"));
            else if(!java&&pkg.entrypoints().containsKey("studio_script"))items.add(Map.of("packageId",pkg.packageId(),"name",pkg.name(),"version",pkg.version(),"revision",pkg.revision(),"targetSide","SERVER","language","RHINO"));
        }
        return Map.of("items",items,"offset",offset,"nextOffset",source.nextOffset(),"more",source.more(),"catalogTotal",source.total(),"targetSide",client?"CLIENT":"SERVER","language",java?"JAVA":"RHINO");
    }
    private static void requireDependencyCandidate(ServerPlayer player,CodeDraft draft,UUID packageId,long packageRevision,String version){
        if(packageRevision<1||packageId.equals(draft.packageId()))throw new IllegalStateException("STUDIO_DEPENDENCY_CHANGED");var runtime=ServerPackageRuntime.get(player.level().getServer());var pkg=runtime.ownedPackage(player.getUUID(),packageId,packageRevision).orElseThrow(()->new SecurityException("STUDIO_DEPENDENCY_OWNER"));
        boolean client=clientPath(draft.path()),java=javaPath(draft.path()),compatible=client&&(pkg.entrypoints().containsKey("client")||pkg.entrypoints().containsKey("client_java"))||!client&&java&&pkg.entrypoints().containsKey("java")||!client&&!java&&pkg.entrypoints().containsKey("studio_script");
        if(pkg.revision()!=packageRevision||!pkg.version().equals(version)||pkg.activationMode()!=ActivationMode.HOT_RUNTIME||!compatible)throw new IllegalStateException("STUDIO_DEPENDENCY_CHANGED");
    }
    private static Map<String,Object> workspaceRead(ServerPlayer player,Map<String,String> args)throws Exception {
        String kind=args.get("kind");var expected=kind.equals("workspaceSource")?Set.of("kind","draftId","revision","version","path","hash","offset"):Set.of("kind","draftId","revision","version","offset");
        if(!args.keySet().equals(expected))throw new IllegalArgumentException("STUDIO_WORKSPACE_ARGUMENTS");
        var draft=draft(player,UUID.fromString(args.get("draftId")),Long.parseLong(args.get("revision")));int offset=Integer.parseInt(args.get("offset"));long version=Long.parseLong(args.get("version"));
        if(offset<0||version<0)throw new IllegalArgumentException("STUDIO_WORKSPACE_PAGE");
        if(kind.equals("workspaceHistory")){if(version!=0||offset>20)throw new IllegalArgumentException("STUDIO_WORKSPACE_PAGE");var history=draft.workspaceHistory();var page=history.stream().skip(offset).limit(4).map(v->Map.of("revision",v.revision(),"entry",v.entry(),"files",v.files().size(),"dependencyCount",v.dependencies().size())).toList();return Map.of("history",page,"nextOffset",offset+page.size(),"more",offset+page.size()<history.size());}
        CodeDraft.WorkspaceVersion saved=version==0?null:draft.workspaceHistory().stream().filter(v->v.revision()==version).findFirst().orElseThrow(()->new IllegalStateException("STUDIO_WORKSPACE_HISTORY_MISSING"));
        var refs=saved==null?CodeDraftSources.refs(draft):saved.files();String entry=saved==null?draft.path():saved.entry();
        if(kind.equals("workspaceFiles")){if(offset>64)throw new IllegalArgumentException("STUDIO_WORKSPACE_PAGE");var page=refs.entrySet().stream().sorted(Map.Entry.comparingByKey()).skip(offset).limit(8).map(e->Map.of("path",e.getKey(),"hash",e.getValue().sha256(),"bytes",e.getValue().bytes(),"entry",e.getKey().equals(entry))).toList();return Map.of("files",page,"entry",entry,"workspaceHash",CodeDraftSources.studioFingerprint(entry,refs,saved==null?draft.dependencies():saved.dependencies()),"revision",draft.revision(),"version",version,"nextOffset",offset+page.size(),"more",offset+page.size()<refs.size());}
        var ref=refs.get(args.get("path"));if(ref==null||!ref.sha256().equals(args.get("hash")))throw new IllegalStateException("STUDIO_WORKSPACE_FILE_CHANGED");
        var service=MineAgentRuntimeServices.codeDrafts(player.level().getServer());String source=saved==null?service.sourceFile(draft,args.get("path")):service.historyFile(saved,args.get("path"));
        int length=source.codePointCount(0,source.length());if(offset>length)throw new IllegalArgumentException("STUDIO_WORKSPACE_PAGE");int end=Math.min(length,offset+1024);
        return Map.of("text",source.substring(source.offsetByCodePoints(0,offset),source.offsetByCodePoints(0,end)),"hash",ref.sha256(),"workspaceHash",CodeDraftSources.studioFingerprint(entry,refs,saved==null?draft.dependencies():saved.dependencies()),"nextOffset",end,"more",end<length);
    }
}
