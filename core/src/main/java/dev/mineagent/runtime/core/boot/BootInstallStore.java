package dev.mineagent.runtime.core.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import java.nio.file.*;
import java.nio.channels.*;
import java.sql.*;
import java.util.*;

/** Process-global BOOT approvals, distinct from library enabled and world activation. No file or Native action in a DB transaction. */
public final class BootInstallStore implements AutoCloseable {
    public record Build(UUID id,UUID world,UUID owner,RuntimePackage manifest,String publicKey,String environment,
            String nativeClasspath,String artifact,String modId,String phase,String error,List<String> diagnostics,String directory,long revision,long artifactBytes,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String slot,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID replaces) {
        public Build(UUID id,UUID world,UUID owner,RuntimePackage manifest,String publicKey,String environment,String nativeClasspath,String artifact,String modId,String phase,String error,List<String> diagnostics,String directory,long revision,long artifactBytes){this(id,world,owner,manifest,publicKey,environment,nativeClasspath,artifact,modId,phase,error,diagnostics,directory,revision,artifactBytes,"",null);}
        public Build{slot=slot==null?"":slot;if(!slot.isEmpty()&&(manifest==null||!slot.matches("mineagent-boot-"+manifest.packageId()+"-[a-f0-9]{64}\\.jar"))||replaces!=null&&(replaces.equals(id)||slot.isEmpty()))throw new IllegalArgumentException("BOOT_REPLACEMENT_SLOT");diagnostics=List.copyOf(diagnostics);if(id==null||world==null||owner==null||manifest==null||publicKey==null||publicKey.length()>4096||environment==null||!environment.matches("[a-f0-9]{64}")||nativeClasspath==null||!nativeClasspath.matches("(?:[a-f0-9]{64})?")||artifact==null||!artifact.matches("(?:[a-f0-9]{64})?")||modId==null||!modId.matches("(?:[a-z][a-z0-9_]{1,63})?")||!Set.of("BUILDING","CACHING","BUILT","BUILD_FAILED","BUILD_UNKNOWN","INSTALLING","REMOVING","INSTALLED_PENDING_RESTART","REMOVED_PENDING_RESTART","FILE_STATE_UNKNOWN","UPGRADE_PREDECESSOR","UPGRADE_WAIT_OFFLINE","REPLACED").contains(phase)||error==null||!error.matches("[A-Z0-9_]{0,100}")||diagnostics.size()>32||diagnostics.stream().anyMatch(d->d.length()>1500)||directory==null||directory.length()>4096||revision<1||artifactBytes<0||artifactBytes>BootExtensionPlan.MAX_ARCHIVE)throw new IllegalArgumentException("BOOT_STORE_RECORD");}
    }
    public record Change(UUID id,UUID build,UUID world,UUID actor,String action,long expected,String phase,String error){}
    public record Begun(Build value,boolean created){}
    public record Upgrade(BootUpgradePlan input,String phase,String error,long revision){}
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Connection db;private final FileChannel channel;private final FileLock lock;
    private final Map<UUID,Build> builds=new LinkedHashMap<>();private final Path root;
    public BootInstallStore(Path root)throws Exception {
        Files.createDirectories(root);if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_STORE_DIRECTORY");this.root=root.toRealPath();
        Path file=this.root.resolve("boot-installs.db"),lease=this.root.resolve("boot-installs.lock");
        for(var p:List.of(file,lease))if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_STORE_LINK");
        channel=FileChannel.open(lease,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock acquired=null;Connection connection=null;
        try{acquired=channel.tryLock();if(acquired==null)throw new IllegalStateException("BOOT_STORE_IN_USE");connection=DriverManager.getConnection("jdbc:sqlite:"+file);db=connection;lock=acquired;
            try(var s=db.createStatement()){s.execute("PRAGMA busy_timeout=1500");s.execute("PRAGMA journal_mode=WAL");s.execute("CREATE TABLE IF NOT EXISTS boot_builds_v1(id TEXT PRIMARY KEY,payload TEXT NOT NULL,bytes INTEGER NOT NULL,revision INTEGER NOT NULL)");s.execute("CREATE TABLE IF NOT EXISTS boot_changes_v1(id TEXT PRIMARY KEY,payload TEXT NOT NULL,bytes INTEGER NOT NULL)");s.execute("CREATE TABLE IF NOT EXISTS boot_upgrades_v1(id TEXT PRIMARY KEY,previous_build TEXT NOT NULL,next_build TEXT NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL,revision INTEGER NOT NULL)");s.execute("CREATE INDEX IF NOT EXISTS boot_upgrade_next ON boot_upgrades_v1(next_build)");}
            load();for(var b:List.copyOf(builds.values()))if(Set.of("BUILDING","CACHING","INSTALLING","REMOVING").contains(b.phase()))save(copy(b,b.nativeClasspath(),b.artifact(),b.modId(),Set.of("BUILDING","CACHING").contains(b.phase())?"BUILD_UNKNOWN":"FILE_STATE_UNKNOWN","BOOT_RESTART_UNKNOWN",b.diagnostics()));
        }catch(Exception failure){if(connection!=null)connection.close();if(acquired!=null)acquired.release();channel.close();throw failure;}
    }
    public Path root(){return root;}
    private void load()throws Exception {long size=0;try(var s=db.createStatement();var r=s.executeQuery("SELECT payload FROM boot_builds_v1 ORDER BY rowid")){while(r.next()){String raw=r.getString(1);size+=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(builds.size()>=4096||size>64L*1024*1024)throw new IllegalStateException("BOOT_STORE_LIMIT");var b=JSON.readValue(raw,Build.class);if(builds.put(b.id(),b)!=null)throw new IllegalStateException("BOOT_STORE_CORRUPT");}}}
    public synchronized List<Build> all(){
        try(var q=db.createStatement();var r=q.executeQuery("SELECT id,revision FROM boot_builds_v1 ORDER BY rowid")){
            var current=new LinkedHashMap<UUID,Build>();while(r.next()){if(current.size()>=4096)throw new IllegalStateException("BOOT_STORE_LIMIT");UUID id=UUID.fromString(r.getString(1));var b=builds.get(id);if(b==null||b.revision()!=r.getLong(2))b=get(id);current.put(id,b);}
            builds.clear();builds.putAll(current);return List.copyOf(current.values());
        }catch(Exception e){throw new IllegalStateException("BOOT_STORE_READ",e);}
    }
    public synchronized Build get(UUID id){try(var q=db.prepareStatement("SELECT payload FROM boot_builds_v1 WHERE id=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("BOOT_BUILD_MISSING");var value=JSON.readValue(r.getString(1),Build.class);builds.put(id,value);return value;}}catch(Exception e){throw new IllegalStateException("BOOT_STORE_READ",e);}}
    public synchronized Begun begin(UUID id,UUID world,UUID owner,RuntimePackage pkg,String key,String environment,String directory)throws Exception{return begin(id,world,owner,pkg,key,environment,directory,null);}
    public synchronized Begun begin(UUID id,UUID world,UUID owner,RuntimePackage pkg,String key,String environment,String directory,UUID replaces)throws Exception {
        String slot="";if(replaces!=null){var previous=get(replaces);if(!previous.owner().equals(owner)||!previous.manifest().packageId().equals(pkg.packageId())||previous.manifest().canonicalSha256().equals(pkg.canonicalSha256())||!previous.directory().equals(directory))throw new IllegalStateException("BOOT_REPLACEMENT_SOURCE");slot=BootFiles.filename(previous);}
        all();var old=builds.containsKey(id)?get(id):null;if(old!=null){if(!old.world().equals(world)||!old.owner().equals(owner)||!old.manifest().canonicalSha256().equals(pkg.canonicalSha256())||!old.environment().equals(environment)||!old.publicKey().equals(key)||!old.manifest().packageId().equals(pkg.packageId())||old.manifest().revision()!=pkg.revision()||!old.directory().equals(directory)||!Objects.equals(old.replaces(),replaces)||!old.slot().equals(slot))throw new IllegalStateException("BOOT_OPERATION_REUSED");return new Begun(old,false);}
        if(change(id).isPresent()||upgrade(id).isPresent())throw new IllegalStateException("BOOT_OPERATION_REUSED");if(builds.size()>=4096)throw new IllegalStateException("BOOT_BUILD_BUDGET");
        var b=new Build(id,world,owner,pkg,key,environment,"","","","BUILDING","",List.of(),directory,1,0,slot,replaces);writeBuild(b,true);builds.put(id,b);return new Begun(b,true);
    }
    public synchronized Build built(UUID id,String snapshot,String artifact,String modId,List<String> diagnostics,long bytes)throws Exception {
        var old=get(id);if(!old.phase().equals("BUILDING"))throw new IllegalStateException("BOOT_BUILD_STALE");
        if(!snapshot.matches("[a-f0-9]{64}")||!artifact.matches("[a-f0-9]{64}")||!modId.matches("[a-z][a-z0-9_]{1,63}"))throw new IllegalStateException("BOOT_BUILD_RESULT");
        if(bytes<1||bytes>BootExtensionPlan.MAX_ARCHIVE)throw new IllegalStateException("BOOT_ARTIFACT_LIMIT");
        var occupied=new HashMap<String,Long>();try(var q=db.createStatement();var r=q.executeQuery("SELECT payload FROM boot_builds_v1")){while(r.next()){var b=JSON.readValue(r.getString(1),Build.class);if(!b.artifact().isEmpty())occupied.put(b.artifact(),b.artifactBytes());}}
        if(occupied.containsKey(artifact)&&occupied.get(artifact)!=bytes)throw new IllegalStateException("BOOT_ARTIFACT_HASH");occupied.put(artifact,bytes);if(occupied.values().stream().mapToLong(Long::longValue).sum()>512L*1024*1024)throw new IllegalStateException("BOOT_CACHE_BUDGET");
        return save(copy(old,snapshot,artifact,modId,"CACHING","",diagnostics,bytes));
    }
    public synchronized Build cached(UUID id)throws Exception {var b=get(id);if(!b.phase().equals("CACHING"))throw new IllegalStateException("BOOT_BUILD_STALE");return save(copy(b,b.nativeClasspath(),b.artifact(),b.modId(),"BUILT","",b.diagnostics()));}
    public synchronized Build failed(UUID id,String code,List<String> diagnostics)throws Exception {var old=get(id);if(!Set.of("BUILDING","CACHING").contains(old.phase()))return old;return save(copy(old,old.nativeClasspath(),old.artifact(),old.modId(),"BUILD_FAILED",code,diagnostics));}
    /** Readback of the exact .jar.disabled produced by the offline recovery tool; never performs file I/O. */
    public synchronized Build observedOfflineRemoval(UUID id)throws Exception {
        var old=get(id);if(old.phase().equals("REMOVED_PENDING_RESTART")&&old.error().equals("BOOT_OFFLINE_REMOVAL_OBSERVED"))return old;
        if(!Set.of("INSTALLED_PENDING_RESTART","FILE_STATE_UNKNOWN","REMOVING","UPGRADE_PREDECESSOR").contains(old.phase()))throw new IllegalStateException("BOOT_OFFLINE_REMOVAL_STATE");
        return save(copy(old,old.nativeClasspath(),old.artifact(),old.modId(),"REMOVED_PENDING_RESTART","BOOT_OFFLINE_REMOVAL_OBSERVED",old.diagnostics()));
    }
    public synchronized Optional<Change> change(UUID id)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM boot_changes_v1 WHERE id=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){return r.next()?Optional.of(JSON.readValue(r.getString(1),Change.class)):Optional.empty();}}}
    public synchronized boolean beginChange(Change input)throws Exception {
        var existing=change(input.id());if(existing.isPresent()){var old=existing.get();if(!old.build().equals(input.build())||!old.world().equals(input.world())||!old.actor().equals(input.actor())||!old.action().equals(input.action())||old.expected()!=input.expected())throw new IllegalStateException("BOOT_OPERATION_REUSED");return false;}
        if(builds.containsKey(input.id())||upgrade(input.id()).isPresent())throw new IllegalStateException("BOOT_OPERATION_REUSED");var b=get(input.build());if(input.action().equals("INSTALL")&&b.replaces()!=null)throw new IllegalStateException("BOOT_REPLACEMENT_PLAN_REQUIRED");if(b.revision()!=input.expected())throw new IllegalStateException("BOOT_BUILD_STALE");
        if(input.action().equals("INSTALL")?!b.phase().equals("BUILT"):!input.action().equals("REMOVE")||!Set.of("INSTALLED_PENDING_RESTART","FILE_STATE_UNKNOWN","REMOVED_PENDING_RESTART").contains(b.phase()))throw new IllegalStateException("BOOT_CHANGE_STATE");
        if(aggregate("boot_changes_v1","COUNT(*)")>=65536)throw new IllegalStateException("BOOT_CHANGE_BUDGET");
        var next=copy(b,b.nativeClasspath(),b.artifact(),b.modId(),input.action().equals("INSTALL")?"INSTALLING":"REMOVING","",b.diagnostics());
        db.setAutoCommit(false);try{writeChange(input,true);writeBuild(next,false);db.commit();builds.put(b.id(),next);return true;}catch(Exception failure){try{db.rollback();}catch(Exception suppressed){failure.addSuppressed(suppressed);}throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized Build finishChange(UUID id,boolean completed,String code)throws Exception {
        var input=change(id).orElseThrow();var b=get(input.build());if(!Set.of("INSTALLING","REMOVING").contains(b.phase()))return b;
        var next=copy(b,b.nativeClasspath(),b.artifact(),b.modId(),completed?(input.action().equals("INSTALL")?"INSTALLED_PENDING_RESTART":"REMOVED_PENDING_RESTART"):"FILE_STATE_UNKNOWN",code,b.diagnostics());
        var job=new Change(input.id(),input.build(),input.world(),input.actor(),input.action(),input.expected(),completed?"COMPLETED":"UNKNOWN",code);
        db.setAutoCommit(false);try{writeChange(job,false);writeBuild(next,false);db.commit();builds.put(b.id(),next);return next;}catch(Exception failure){try{db.rollback();}catch(Exception ignored){}throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized Optional<Upgrade> upgrade(UUID id)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM boot_upgrades_v1 WHERE id=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){return r.next()?Optional.of(JSON.readValue(r.getString(1),Upgrade.class)):Optional.empty();}}}
    public synchronized List<Upgrade> upgrades()throws Exception {var rows=new ArrayList<Upgrade>();try(var q=db.createStatement();var r=q.executeQuery("SELECT payload FROM boot_upgrades_v1 ORDER BY rowid")){while(r.next()){if(rows.size()>=4096)throw new IllegalStateException("BOOT_UPGRADE_BUDGET");rows.add(JSON.readValue(r.getString(1),Upgrade.class));}}return List.copyOf(rows);}
    public synchronized Upgrade stageUpgrade(BootUpgradePlan plan,long oldRevision,long newRevision)throws Exception {
        var old=upgrade(plan.operation());if(old.isPresent()){if(!old.get().input().equals(plan))throw new IllegalStateException("BOOT_OPERATION_REUSED");return old.get();}
        all();if(builds.containsKey(plan.operation())||change(plan.operation()).isPresent())throw new IllegalStateException("BOOT_OPERATION_REUSED");
        var a=get(plan.previousBuild());var b=get(plan.nextBuild());
        if(!a.directory().equals(plan.directory())||!b.directory().equals(plan.directory())||a.revision()!=oldRevision||b.revision()!=newRevision||!Objects.equals(b.replaces(),a.id())||!a.phase().equals(plan.previousPhase())||!b.phase().equals("BUILT")||!a.owner().equals(plan.owner())||!b.owner().equals(plan.owner())||!a.artifact().equals(plan.previousArtifact())||!b.artifact().equals(plan.nextArtifact())||!a.modId().equals(b.modId())||!a.modId().equals(plan.modId())||!BootFiles.filename(a).equals(plan.filename())||!BootFiles.filename(b).equals(plan.filename()))throw new IllegalStateException("BOOT_UPGRADE_STALE");
        if(upgrades().stream().anyMatch(u->!Set.of("CANCELLED","ROLLED_BACK","APPLIED").contains(u.phase())&&u.input().filename().equals(plan.filename())))throw new IllegalStateException("BOOT_UPGRADE_PENDING");
        var next=new Upgrade(plan,"PREPARING","",1);var before=copy(a,a.nativeClasspath(),a.artifact(),a.modId(),"UPGRADE_PREDECESSOR","",a.diagnostics());var after=copy(b,b.nativeClasspath(),b.artifact(),b.modId(),"UPGRADE_WAIT_OFFLINE","",b.diagnostics());
        db.setAutoCommit(false);try{writeUpgrade(next,true);writeBuild(before,false);writeBuild(after,false);db.commit();builds.put(a.id(),before);builds.put(b.id(),after);return next;}catch(Exception e){try{db.rollback();}catch(Exception ignored){}throw e;}finally{db.setAutoCommit(true);}
    }
    public synchronized Upgrade upgradeStatus(UUID id,String phase,String error)throws Exception{return upgradeStatus(id,phase,error,null);}
    public synchronized Upgrade upgradeStatus(UUID id,String phase,String error,String observedHash)throws Exception {
        if(!Set.of("WAIT_OFFLINE","PLAN_WRITE_FAILED","CANCEL_FAILED","CANCELLED","APPLIED","ROLLED_BACK").contains(phase)||!error.matches("[A-Z0-9_]{0,100}"))throw new IllegalArgumentException("BOOT_UPGRADE_RESULT");
        var old=upgrade(id).orElseThrow();if(old.phase().equals(phase))return old;if(old.phase().equals("CANCELLED")||old.phase().equals("ROLLED_BACK"))throw new IllegalStateException("BOOT_UPGRADE_TERMINAL");
        if(Set.of("WAIT_OFFLINE","PLAN_WRITE_FAILED","CANCEL_FAILED").contains(phase)&&old.phase().equals("APPLIED"))throw new IllegalStateException("BOOT_UPGRADE_ALREADY_APPLIED");
        var next=new Upgrade(old.input(),phase,error,old.revision()+1);Build a=null,b=null;
        if(Set.of("CANCELLED","APPLIED","ROLLED_BACK").contains(phase)){
            var previous=get(old.input().previousBuild());var candidate=get(old.input().nextBuild());
            if(phase.equals("CANCELLED")&&old.phase().equals("APPLIED"))throw new IllegalStateException("BOOT_UPGRADE_ALREADY_APPLIED");
            if(!BootFiles.filename(previous).equals(old.input().filename())||!BootFiles.filename(candidate).equals(old.input().filename()))throw new IllegalStateException("BOOT_UPGRADE_SLOT_CHANGED");
            a=copy(previous,previous.nativeClasspath(),previous.artifact(),previous.modId(),observedHash==null?(phase.equals("APPLIED")?"REPLACED":old.input().previousPhase()):observedHash.equals(previous.artifact())?old.input().previousPhase():"REPLACED","",previous.diagnostics());
            b=copy(candidate,candidate.nativeClasspath(),candidate.artifact(),candidate.modId(),phase.equals("CANCELLED")?"BUILT":observedHash==null?(phase.equals("APPLIED")?"INSTALLED_PENDING_RESTART":"REPLACED"):observedHash.equals(candidate.artifact())?"INSTALLED_PENDING_RESTART":"REPLACED","",candidate.diagnostics());
        }
        db.setAutoCommit(false);try{writeUpgrade(next,false);if(a!=null){writeBuild(a,false);writeBuild(b,false);}db.commit();if(a!=null){builds.put(a.id(),a);builds.put(b.id(),b);}return next;}catch(Exception e){try{db.rollback();}catch(Exception ignored){}throw e;}finally{db.setAutoCommit(true);}
    }
    private void writeUpgrade(Upgrade u,boolean insert)throws Exception {
        String raw=JSON.writeValueAsString(u);int size=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1024;long old=0;
        if(!insert)try(var q=db.prepareStatement("SELECT bytes FROM boot_upgrades_v1 WHERE id=?")){q.setString(1,u.input().operation().toString());try(var r=q.executeQuery()){if(r.next())old=r.getLong(1);}}
        if(size>16384||insert&&aggregate("boot_upgrades_v1","COUNT(*)")>=4096||aggregate("boot_upgrades_v1","COALESCE(SUM(bytes),0)")-old+size>16L*1024*1024)throw new IllegalStateException("BOOT_UPGRADE_BUDGET");
        try(var q=db.prepareStatement(insert?"INSERT INTO boot_upgrades_v1(id,previous_build,next_build,payload,bytes,revision) VALUES(?,?,?,?,?,?)":"UPDATE boot_upgrades_v1 SET payload=?,bytes=?,revision=? WHERE id=? AND revision=?")){
            if(insert){q.setString(1,u.input().operation().toString());q.setString(2,u.input().previousBuild().toString());q.setString(3,u.input().nextBuild().toString());q.setString(4,raw);q.setInt(5,size);q.setLong(6,u.revision());}
            else{q.setString(1,raw);q.setInt(2,size);q.setLong(3,u.revision());q.setString(4,u.input().operation().toString());q.setLong(5,u.revision()-1);}
            if(q.executeUpdate()!=1)throw new IllegalStateException("BOOT_UPGRADE_CAS");
        }
    }
    private Build copy(Build b,String snapshot,String artifact,String modId,String phase,String code,List<String> diagnostics){return copy(b,snapshot,artifact,modId,phase,code,diagnostics,b.artifactBytes());}
    private Build copy(Build b,String snapshot,String artifact,String modId,String phase,String code,List<String> diagnostics,long bytes){if(code==null||!code.matches("[A-Z0-9_]{0,100}")||diagnostics.size()>32||diagnostics.stream().anyMatch(s->s.length()>1500))throw new IllegalArgumentException("BOOT_RECEIPT");return new Build(b.id(),b.world(),b.owner(),b.manifest(),b.publicKey(),b.environment(),snapshot,artifact,modId,phase,code,diagnostics,b.directory(),b.revision()+1,bytes,b.slot(),b.replaces());}
    private Build save(Build b)throws Exception {writeBuild(b,false);builds.put(b.id(),b);return b;}
    private void writeBuild(Build b,boolean insert)throws Exception {
        String raw=JSON.writeValueAsString(b);int size=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int allocated=size+(b.phase().equals("BUILDING")?196608:2048);long old=0;
        if(!insert)try(var q=db.prepareStatement("SELECT bytes FROM boot_builds_v1 WHERE id=?")){q.setString(1,b.id().toString());try(var r=q.executeQuery()){if(r.next())old=r.getLong(1);}}
        if(size>1024*1024||aggregate("boot_builds_v1","COALESCE(SUM(bytes),0)")-old+allocated>64L*1024*1024)throw new IllegalStateException("BOOT_BUILD_BUDGET");
        try(var q=db.prepareStatement(insert?"INSERT INTO boot_builds_v1(id,payload,bytes,revision) VALUES(?,?,?,?)":"UPDATE boot_builds_v1 SET payload=?,bytes=?,revision=? WHERE id=? AND revision=?")){
            if(insert){q.setString(1,b.id().toString());q.setString(2,raw);q.setInt(3,allocated);q.setLong(4,b.revision());}
            else{q.setString(1,raw);q.setInt(2,allocated);q.setLong(3,b.revision());q.setString(4,b.id().toString());q.setLong(5,b.revision()-1);}
            if(q.executeUpdate()!=1)throw new IllegalStateException("BOOT_BUILD_CAS");
        }
    }
    private void writeChange(Change c,boolean insert)throws Exception {
        String raw=JSON.writeValueAsString(c);int size=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;long old=0;
        if(!insert)try(var q=db.prepareStatement("SELECT bytes FROM boot_changes_v1 WHERE id=?")){q.setString(1,c.id().toString());try(var r=q.executeQuery()){if(r.next())old=r.getLong(1);}}
        int allocated=size+(c.phase().equals("PREPARED")?512:256);
        if(size>4096||aggregate("boot_changes_v1","COALESCE(SUM(bytes),0)")-old+allocated>64L*1024*1024)throw new IllegalStateException("BOOT_CHANGE_BUDGET");
        try(var q=db.prepareStatement(insert?"INSERT INTO boot_changes_v1(id,payload,bytes) VALUES(?,?,?)":"UPDATE boot_changes_v1 SET payload=?,bytes=? WHERE id=?")){
            if(insert){q.setString(1,c.id().toString());q.setString(2,raw);q.setInt(3,allocated);}else{q.setString(1,raw);q.setInt(2,allocated);q.setString(3,c.id().toString());}
            if(q.executeUpdate()!=1)throw new IllegalStateException("BOOT_CHANGE_CAS");
        }
    }
    private long aggregate(String table,String expression)throws Exception {try(var s=db.createStatement();var r=s.executeQuery("SELECT "+expression+" FROM "+table)){if(!r.next())throw new IllegalStateException("BOOT_STORE_AGGREGATE");return r.getLong(1);}}
    @Override public synchronized void close()throws Exception {try{db.close();}finally{try{lock.release();}finally{channel.close();}}}
}
