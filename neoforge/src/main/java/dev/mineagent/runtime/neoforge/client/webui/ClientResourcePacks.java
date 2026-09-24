package dev.mineagent.runtime.neoforge.client.webui;

import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.PackagePreviewTransfer;
import dev.mineagent.runtime.client.resources.LocalResourcePackStore;
import dev.mineagent.runtime.client.trust.ServerTrustStore;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.content.ManagedClientResourcePackGuard;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Client-only download and explicit local-machine resource activation. Never accepts an enable command from the server. */
public final class ClientResourcePacks {
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private static final Set<Download> downloads=ConcurrentHashMap.newKeySet();private static volatile Download download;private static String downloadOutcome="";private static long downloadGeneration;private static LocalResourcePackStore.Job active;private static AutoCloseable permit;private static Pending finish;private static boolean recoveryHook;
    private record Pending(LocalResourcePackStore.Job job,boolean success,String code){}
    private static final class Download {
        final Session session;final Object connection;final UUID id;final long revision;final String canonical,server,fingerprint;final CompletableFuture<Map<String,Object>> future=new CompletableFuture<>();final long expires=System.currentTimeMillis()+120000;
        String transfer;PackagePreviewTransfer.Assembler bytes;
        Download(Session s,UUID id,long revision,String hash)throws Exception{session=s;connection=Minecraft.getInstance().getConnection();this.id=id;this.revision=revision;canonical=hash;server=serverId();fingerprint=trust().trustedFingerprint(server);if(!fingerprint.equalsIgnoreCase(PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","")))throw new IllegalStateException("RESOURCE_PACK_TRUST_CHANGED");}
    }
    private ClientResourcePacks(){}
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static LocalResourcePackStore store()throws Exception{return LocalResourcePackStore.get(mc().gameDirectory.toPath());}
    private static ServerTrustStore trust()throws Exception{return new ServerTrustStore(mc().gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties"));}
    private static String serverId(){return mc().getCurrentServer()==null?"local-integrated":mc().getCurrentServer().ip;}
    private static void thread(){if(!mc().isSameThread())throw new IllegalStateException("RESOURCE_PACK_CLIENT_THREAD");}
    public static void recoveryHookReady(){recoveryHook=true;}
    public static void recoveryStarted(){thread();if(active!=null&&active.phase().equals("DISPATCHING"))complete(active,false,"RESOURCE_PACK_VANILLA_RECOVERY");}
    public static BooleanSupplier webScope(){Session s=UiClientSessions.current();Object c=mc().getConnection();if(s==null||s.binding().actorKind()!=ActorKind.PLAYER||!s.binding().viewId().equals("runtime-shell")||!s.binding().entryPath().equals("trusted/shell")||!s.binding().ownerPackageId().equals(dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.TRUSTED_SHELL_PACKAGE)||!s.binding().actorId().equals(s.binding().viewerPlayerId())||s.binding().preview()||s.binding().taskId()!=null)throw new SecurityException("RESOURCE_PACK_TRUSTED_SHELL_REQUIRED");return ()->UiClientSessions.current()==s&&mc().getConnection()==c&&WebGuiHostAdapter.INSTANCE.ready();}
    public static Map<String,Object> startDownload(UUID id,long revision,String canonical){
        thread();long generation=++downloadGeneration;downloadOutcome="DOWNLOADING";var result=download(id,revision,canonical);result.whenComplete((value,error)->mc().execute(()->{if(error!=null)dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Client resource-pack download could not start code={} type={}",code(error),error.getClass().getSimpleName(),error);if(generation==downloadGeneration)downloadOutcome=error==null?Objects.toString(value.get("code"),"RESOURCE_PACK_FAILED"):code(error);}));
        if(result.isDone())return result.getNow(Map.of("code","RESOURCE_PACK_FAILED"));return Map.of("code","ACCEPTED","state","DOWNLOADING");
    }
    public static CompletableFuture<Map<String,Object>> download(UUID id,long revision,String canonical){
        thread();webScope();
        try{var d=new Download(UiClientSessions.current(),id,revision,canonical);downloads.add(d);download=d;
            UiClientSessions.command("package.resourcePrepare",Map.of("packageId",id.toString(),"packageRevision",Long.toString(revision),"canonical",canonical),UUID.randomUUID()).whenComplete((r,error)->{
                if(!current(d))return;try{if(error!=null||r.code()!=Code.ACCEPTED)throw new IllegalStateException("RESOURCE_PACK_DOWNLOAD_FAILED");var v=r.values();if(!id.toString().equals(v.get("packageId"))||!Long.toString(revision).equals(v.get("packageRevision"))||!canonical.equals(v.get("canonical")))throw new IllegalStateException("RESOURCE_PACK_SOURCE_CHANGED");d.transfer=UUID.fromString(v.get("transferId")).toString();d.bytes=new PackagePreviewTransfer.Assembler(Integer.parseInt(v.get("size")),v.get("sha256"),ResourcePackPlan.MAX_BUNDLE);next(d);}catch(Exception e){fail(d,e);}
            });return d.future;
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
    }
    private static void removed(Download d){downloads.remove(d);if(download==d)download=downloads.stream().findFirst().orElse(null);}
    private static boolean current(Download d){if(!downloads.contains(d))return false;if(d.session!=UiClientSessions.current()||d.connection!=mc().getConnection()||System.currentTimeMillis()>=d.expires||!WebGuiHostAdapter.INSTANCE.ready()){fail(d,"RESOURCE_PACK_DOWNLOAD_CONTEXT_CHANGED");return false;}return true;}
    private static void next(Download d){
        if(!current(d))return;
        if(d.bytes.offset()==d.bytes.size()){
            try{CompletableFuture.supplyAsync(()->{try{var t=trust();if(!t.trustedFingerprint(d.server).equals(d.fingerprint))throw new IllegalStateException("RESOURCE_PACK_TRUST_CHANGED");return ResourcePackPlan.decode(d.bytes.finish(),d.id,d.revision,d.canonical,(value,sig)->t.verify(d.server,value,sig));}catch(Exception failure){throw new CompletionException(failure);}},IO).whenComplete((resolved,error)->mc().execute(()->{
                if(!current(d))return;if(error!=null){fail(d,error);return;}
                try{var local=store();CompletableFuture.supplyAsync(()->{try{if(!downloads.contains(d))throw new IllegalStateException("RESOURCE_PACK_DOWNLOAD_CONTEXT_CHANGED");return local.downloaded(d.server,d.fingerprint,resolved);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((asset,failure)->mc().execute(()->{
                    if(!current(d))return;if(failure!=null){fail(d,failure);return;}release(d);removed(d);d.future.complete(Map.of("code","DOWNLOADED_NOT_APPROVED","filename",asset.filename(),"revision",asset.revision()));
                }));}catch(Exception failure){fail(d,failure);}
            }));}catch(Exception error){fail(d,error);}return;
        }
        int offset=d.bytes.offset();UiClientSessions.command("package.resourceChunk",Map.of("transferId",d.transfer,"offset",Integer.toString(offset)),UUID.randomUUID()).whenComplete((r,error)->{
            if(!current(d))return;try{if(error!=null||r.code()!=Code.OBSERVED||!Integer.toString(offset).equals(r.values().get("offset")))throw new IllegalStateException("RESOURCE_PACK_DOWNLOAD_FAILED");d.bytes.append(offset,Base64.getDecoder().decode(r.values().get("bytes")));next(d);}catch(Exception failure){fail(d,failure);}
        });
    }
    private static void release(Download d){if(d.transfer!=null&&UiClientSessions.current()==d.session)UiClientSessions.command("package.resourceRelease",Map.of("transferId",d.transfer),UUID.randomUUID());}
    private static void fail(Download d,Throwable failure){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Client resource-pack download failed code={} type={}",code(failure),failure.getClass().getSimpleName(),failure);fail(d,code(failure));}
    private static void fail(Download d,String code){if(!downloads.contains(d))return;release(d);removed(d);d.future.complete(Map.of("code",code));}
    public static void cancelDownload(){thread();var d=download;if(d!=null)fail(d,"RESOURCE_PACK_DOWNLOAD_CANCELLED");}
    private static List<String> selected(){return mc().getResourceManager().listPacks().map(PackResources::packId).toList();}
    private static String selection()throws Exception{return RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(Map.of("selected",selected())));}
    private static boolean busy(){return active!=null||download!=null||mc().getOverlay()!=null||((dev.mineagent.runtime.neoforge.mixin.client.ResourceReloadAccess)mc()).mineagent$pendingReload()!=null;}
    public static Map<String,Object> read(int offset)throws Exception{
        thread();if(offset<0||offset>4096)throw new IllegalArgumentException("RESOURCE_PACK_PAGE");var local=store();var all=local.list();var chosen=selected();String env=ManagedClientResourcePackGuard.environment().fingerprint();var values=new ArrayList<Object>();
        for(var a:all.stream().skip(offset).limit(8).toList()){
            String error="";try{ManagedClientResourcePackGuard.trusted(a);}catch(Exception failure){error=code(failure);}boolean loaded=chosen.contains("file/"+a.filename());var job=local.latest(a.filename());
            values.add(Map.ofEntries(Map.entry("filename",a.filename()),Map.entry("name",a.manifest().name()),Map.entry("packageId",a.manifest().packageId()),Map.entry("canonical",a.manifest().canonicalSha256()),Map.entry("fingerprint",a.fingerprint()),Map.entry("state",a.state()),Map.entry("revision",a.revision()),Map.entry("loadedNow",loaded),Map.entry("error",error),Map.entry("canEnable",!busy()&&recoveryHook&&ManagedClientResourcePackGuard.ready()&&!loaded&&error.isEmpty()),Map.entry("canDisable",!busy()&&recoveryHook&&loaded),Map.entry("job",job.<Object>map(j->Map.of("operation",j.input().operation(),"phase",j.phase(),"code",j.code(),"before",j.before().stream().limit(8).toList(),"after",j.after().stream().limit(8).toList(),"beforeCount",j.before().size(),"afterCount",j.after().size())).orElse(Map.of()))));
        }
        return Map.ofEntries(Map.entry("items",values),Map.entry("offset",offset),Map.entry("nextOffset",offset+values.size()),Map.entry("more",offset+values.size()<all.size()),Map.entry("selection",selection()),Map.entry("environment",env),Map.entry("client",Map.of("minecraft",ManagedClientResourcePackGuard.environment().minecraft(),"loaderVersion",ManagedClientResourcePackGuard.environment().loaderVersion(),"javaFeature",ManagedClientResourcePackGuard.environment().javaFeature())),Map.entry("busy",busy()),Map.entry("persistingResult",finish!=null),Map.entry("downloadOutcome",downloadOutcome),Map.entry("download",download==null?Map.of():Map.of("packageId",download.id,"received",download.bytes==null?0:download.bytes.offset(),"total",download.bytes==null?0:download.bytes.size())));
    }
    public static Map<String,Object> change(UUID operation,String filename,String action,long expected,String expectedSelection,String environment,boolean confirmed,String actor,BooleanSupplier scope)throws Exception{
        thread();if(!confirmed||!scope.getAsBoolean())throw new SecurityException("RESOURCE_PACK_LOCAL_CONSENT_REQUIRED");var local=store();var input=new LocalResourcePackStore.Input(operation,filename,action,expected,expectedSelection,environment,actor,mc().getResourcePackDirectory().toAbsolutePath().normalize().toString());var old=local.job(operation);if(old.isPresent()){if(!old.get().input().equals(input))throw new IllegalStateException("RESOURCE_PACK_OPERATION_REUSED");return Map.of("code","ACCEPTED","job",old.get());}
        if(busy()||!recoveryHook||!ManagedClientResourcePackGuard.ready())throw new IllegalStateException("RESOURCE_PACK_RELOAD_BUSY");if(!selection().equals(expectedSelection)||!environment.equals(ManagedClientResourcePackGuard.environment().fingerprint()))throw new IllegalStateException("RESOURCE_PACK_CONTEXT_CHANGED");var a=local.get(filename);if(a.revision()!=expected)throw new IllegalStateException("RESOURCE_PACK_STALE");
        var before=selected();var after=new ArrayList<>(before);var own=local.list().stream().filter(v->v.serverId().equals(a.serverId())&&v.fingerprint().equals(a.fingerprint())&&v.manifest().packageId().equals(a.manifest().packageId())).map(v->"file/"+v.filename()).collect(java.util.stream.Collectors.toSet());after.removeIf(own::contains);
        if(action.equals("ENABLE")){ManagedClientResourcePackGuard.trusted(a);if(before.contains("file/"+filename))throw new IllegalStateException("RESOURCE_PACK_ALREADY_LOADED");after.add("file/"+filename);}else if(before.stream().noneMatch(own::contains))throw new IllegalStateException("RESOURCE_PACK_NOT_LOADED");
        var job=local.begin(input,before,after);active=job;
        try{CompletableFuture.supplyAsync(()->{try{
            if(!action.equals("ENABLE"))return new byte[0];byte[] bytes=ResourcePackPlan.readArchive(local.cacheDirectory().resolve(filename));if(!RuntimePackageCanonicalizer.sha256(bytes).equals(a.archiveHash()))throw new IllegalStateException("RESOURCE_PACK_ARCHIVE_HASH");ResourcePackPlan.inspect(a.manifest()).snapshot(bytes);ResourcePackPlan.publish(Path.of(input.directory()),filename,bytes,a.archiveHash());return bytes;
        }catch(Exception failure){throw new CompletionException(failure);}},IO).whenComplete((bytes,error)->mc().execute(()->{if(active!=job)return;if(error!=null){complete(job,false,code(error));return;}dispatch(job,a,bytes,scope);}));}
        catch(Exception failure){complete(job,false,code(failure));}
        return Map.of("code","ACCEPTED","job",job);
    }
    private static void dispatch(LocalResourcePackStore.Job job,LocalResourcePackStore.Asset asset,byte[] bytes,BooleanSupplier scope){
        var request=job;try{
            if(!scope.getAsBoolean()||mc().getOverlay()!=null||!selection().equals(job.input().selection())||!job.input().environment().equals(ManagedClientResourcePackGuard.environment().fingerprint()))throw new IllegalStateException("RESOURCE_PACK_CONTEXT_CHANGED");
            if(job.input().action().equals("ENABLE")){ManagedClientResourcePackGuard.trusted(asset);var meta=ManagedClientResourcePackGuard.metadata(asset,bytes);if(meta==null||!meta.getCompatibility().isCompatible())throw new IllegalStateException("RESOURCE_PACK_FORMAT_INCOMPATIBLE");}
            request=store().dispatch(job);active=request;if(job.input().action().equals("ENABLE"))permit=ManagedClientResourcePackGuard.permit(asset.filename(),job.input().operation());
            var repository=mc().getResourcePackRepository();repository.reload();for(String id:job.after())if(!repository.isAvailable(id))throw new IllegalStateException("RESOURCE_PACK_OTHER_SOURCE_MISSING");repository.setSelected(job.after());
            mc().options.resourcePacks.clear();mc().options.incompatibleResourcePacks.clear();for(var pack:repository.getSelectedPacks())if(!pack.isFixedPosition()&&!pack.isHidden()){mc().options.resourcePacks.add(pack.getId());if(!pack.getCompatibility().isCompatible())mc().options.incompatibleResourcePacks.add(pack.getId());}
            mc().options.save();var expectedOptions=List.copyOf(mc().options.resourcePacks);var expectedIncompatible=List.copyOf(mc().options.incompatibleResourcePacks);var ticket=request;
            mc().reloadResourcePacks().whenComplete((ignored,error)->mc().execute(()->{if(active!=ticket)return;if(error!=null){complete(ticket,false,"RESOURCE_PACK_RELOAD_FAILED");return;}try{
                if(!selected().equals(ticket.after())||!ticket.input().environment().equals(ManagedClientResourcePackGuard.environment().fingerprint()))throw new IllegalStateException("RESOURCE_PACK_RESULT_CHANGED");
                if(ticket.input().action().equals("ENABLE")){ManagedClientResourcePackGuard.trusted(asset);verifyResources(asset);}
                savedOptions(expectedOptions,expectedIncompatible);complete(ticket,true,"RESOURCE_PACK_RELOADED_AND_OPTIONS_OBSERVED");
            }catch(Exception failure){complete(ticket,false,code(failure));}}));
        }catch(Exception failure){complete(request,false,code(failure));}
    }
    private static void verifyResources(LocalResourcePackStore.Asset asset)throws Exception{
        for(var e:ResourcePackPlan.inspect(asset.manifest()).files().entrySet())if(e.getKey().startsWith("assets/")){int split=e.getKey().indexOf('/',7);var id=net.minecraft.resources.Identifier.fromNamespaceAndPath(e.getKey().substring(7,split),e.getKey().substring(split+1));var r=mc().getResourceManager().getResource(id).orElseThrow(()->new IllegalStateException("RESOURCE_PACK_RESOURCE_MISSING"));if(!r.sourcePackId().equals("file/"+asset.filename()))throw new IllegalStateException("RESOURCE_PACK_RESOURCE_OVERRIDDEN");byte[] bytes;try(var in=r.open()){bytes=in.readNBytes(4*1024*1024+1);}if(bytes.length!=e.getValue().size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(e.getValue().sha256()))throw new IllegalStateException("RESOURCE_PACK_RESOURCE_HASH");}
    }
    private static void savedOptions(List<String> selected,List<String> incompatible)throws Exception{
        Path file=mc().gameDirectory.toPath().resolve("options.txt");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IllegalStateException("RESOURCE_PACK_OPTIONS_UNVERIFIED");byte[] bytes;try(var in=Files.newInputStream(file)){bytes=in.readNBytes(2*1024*1024+1);}if(bytes.length>2*1024*1024)throw new IllegalStateException("RESOURCE_PACK_OPTIONS_UNVERIFIED");String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();var json=new com.fasterxml.jackson.databind.ObjectMapper();var values=new HashMap<String,String>();for(String line:text.split("\\R"))for(String key:List.of("resourcePacks","incompatibleResourcePacks"))if(line.startsWith(key+":")){if(values.put(key,line.substring(key.length()+1))!=null)throw new IllegalStateException("RESOURCE_PACK_OPTIONS_UNVERIFIED");}
        if(!json.valueToTree(selected).equals(json.readTree(values.getOrDefault("resourcePacks","null")))||!json.valueToTree(incompatible).equals(json.readTree(values.getOrDefault("incompatibleResourcePacks","null"))))throw new IllegalStateException("RESOURCE_PACK_OPTIONS_UNVERIFIED");
    }
    private static void complete(LocalResourcePackStore.Job job,boolean success,String code){try{store().finish(job,success,code);active=null;finish=null;}catch(Exception failure){active=job;finish=new Pending(job,success,code);}finally{if(permit!=null){try{permit.close();}catch(Exception ignored){}permit=null;}}}
    public static void tick(){thread();for(var d:List.copyOf(downloads))current(d);if(finish!=null){var p=finish;complete(p.job(),p.success(),p.code());}}
    public static String code(Throwable failure){while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();String code=Objects.toString(failure.getMessage(),"");if(code.equals("SERVER_IDENTITY_NOT_TRUSTED"))return "RESOURCE_PACK_TRUST_REQUIRED";return code.matches("(?:RESOURCE_PACK|NATIVE_COMPATIBILITY|NATIVE_ENVIRONMENT)_[A-Z0-9_]{1,64}")?code:"RESOURCE_PACK_FAILED";}
}
