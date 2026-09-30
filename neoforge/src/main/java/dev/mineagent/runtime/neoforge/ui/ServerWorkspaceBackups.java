package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.recovery.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Preview-bound restore and bounded tick work. No world reads or writes run on the IO executor. */
final class ServerWorkspaceBackups implements AutoCloseable {
    private record Preview(UUID owner,UUID snapshot,long revision,String dimension,List<BlockChange> changes,long expires){}
    private static final class Job {
        final UUID id,owner;final ServerPlayer viewer;final String dimension,kind;final BooleanSupplier permit;
        final List<BlockSnapshot> targets;final List<BlockChange> steps=new ArrayList<>(),written=new ArrayList<>();
        final List<Map<String,Object>> conflicts=new ArrayList<>();final Map<String,Object> result=new LinkedHashMap<>();
        String status="RUNNING",phase="READ",label="",error="";int cursor;UUID snapshot,history;long revision,finished;boolean undo;
        Job(UUID id,ServerPlayer viewer,String kind,List<BlockSnapshot> targets,BooleanSupplier permit){this.id=id;this.viewer=viewer;owner=viewer.getUUID();dimension=viewer.level().dimension().identifier().toString();this.kind=kind;this.targets=targets;this.permit=permit;}
        Map<String,Object> state(){return Map.of("operationId",id,"status",status,"phase",phase,"processed",cursor,"total",targets.isEmpty()?steps.size():targets.size(),"written",written.size(),"conflicts",conflicts,"error",error,"result",result);}
    }
    private final MinecraftServer server;
    private final Map<UUID,Job> jobs=new LinkedHashMap<>();
    private final Map<UUID,Preview> previews=new HashMap<>();
    private final Set<UUID> lockedHistory=new HashSet<>();
    private final ExecutorService io=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("workspace-backups").factory());
    ServerWorkspaceBackups(MinecraftServer server){this.server=server;}
    Map<String,Object> handle(ServerPlayer viewer,UUID operation,Map<String,String> args,boolean write,BooleanSupplier permit)throws Exception{
        String kind=args.getOrDefault("kind","list");
        if(!write){
            if(kind.equals("job")){ServerWorkspaceModules.keys(args,"module","kind","operationId");var job=jobs.get(UUID.fromString(args.get("operationId")));if(job==null||!job.owner.equals(viewer.getUUID()))throw new IllegalArgumentException("BACKUP_OPERATION_NOT_FOUND");return job.state();}
            ServerWorkspaceModules.keys(args,"module","kind","offset");int offset=ServerWorkspaceModules.offset(args);boolean op=ServerWorkspaceModules.operator(viewer);
            if(kind.equals("list"))return ServerWorkspaceModules.page(MineAgentRuntimeServices.snapshots(server).all().stream().filter(s->op||s.ownerPlayerId().equals(viewer.getUUID())).map(s->Map.of("id",s.snapshotId(),"label",s.label(),"revision",s.revision(),"blocks",s.blocks().size(),"bytes",s.estimatedBytes(),"expiresAt",s.expiresAtEpochMillis())).toList(),offset);
            if(kind.equals("history"))return ServerWorkspaceModules.page(MineAgentRuntimeServices.changeJournal(server).all().stream().filter(e->op||e.actorId().equals(viewer.getUUID())).map(e->Map.of("id",e.changeId(),"action",e.action(),"revision",e.revision(),"blocks",e.changes().size(),"reverted",e.reverted())).toList(),offset);
            throw new IllegalArgumentException("BACKUP_QUERY");
        }
        if(jobs.containsKey(operation))return jobs.get(operation).state();
        Job job;
        if(kind.equals("create")){
            ServerWorkspaceModules.keys(args,"module","kind","label","radius");int radius=Integer.parseInt(args.get("radius"));String label=args.get("label").strip();if(radius<1||radius>16||label.isBlank()||label.length()>256)throw new IllegalArgumentException("BACKUP_CREATE_ARGUMENTS");
            var center=viewer.blockPosition();var targets=new ArrayList<BlockSnapshot>();String dimension=viewer.level().dimension().identifier().toString();for(var pos:BlockPos.betweenClosed(center.offset(-radius,-radius,-radius),center.offset(radius,radius,radius)))targets.add(new BlockSnapshot(dimension,pos.getX(),pos.getY(),pos.getZ(),"minecraft:air",""));
            job=new Job(operation,viewer,kind,targets,permit);job.label=label;
        }else if(kind.equals("preview")){
            ServerWorkspaceModules.keys(args,"module","kind","id","revision");var snapshot=snapshot(viewer,UUID.fromString(args.get("id")),Long.parseLong(args.get("revision")));
            job=new Job(operation,viewer,kind,snapshot.blocks(),permit);job.snapshot=snapshot.snapshotId();job.revision=snapshot.revision();
        }else if(kind.equals("restore")){
            ServerWorkspaceModules.keys(args,"module","kind","previewId");var preview=previews.get(UUID.fromString(args.get("previewId")));
            if(preview==null||!preview.owner.equals(viewer.getUUID())||preview.expires<System.currentTimeMillis()||!preview.dimension.equals(viewer.level().dimension().identifier().toString()))throw new IllegalStateException("BACKUP_PREVIEW_EXPIRED");
            snapshot(viewer,preview.snapshot,preview.revision);previews.remove(UUID.fromString(args.get("previewId")));job=new Job(operation,viewer,kind,List.of(),permit);job.steps.addAll(preview.changes);job.phase="CHECK";
        }else if(kind.equals("undo")||kind.equals("redo")){
            ServerWorkspaceModules.keys(args,"module","kind","id","revision");UUID id=UUID.fromString(args.get("id"));var entry=MineAgentRuntimeServices.changeJournal(server).get(id).orElseThrow();
            if(!ServerWorkspaceModules.operator(viewer)&&!entry.actorId().equals(viewer.getUUID()))throw new SecurityException("WORKSPACE_PERMISSION_DENIED");
            if(entry.revision()!=Long.parseLong(args.get("revision"))||entry.reverted()==kind.equals("undo"))throw new IllegalStateException("BACKUP_REVISION_CHANGED");
            if(!lockedHistory.add(id))throw new IllegalStateException("BACKUP_HISTORY_BUSY");job=new Job(operation,viewer,kind,List.of(),permit);job.phase="CHECK";job.history=id;job.revision=entry.revision();job.undo=kind.equals("undo");
            for(var step:entry.changes())if(!step.before().equals(step.after()))job.steps.add(job.undo?new BlockChange(step.after(),step.before()):step);
        }else throw new IllegalArgumentException("BACKUP_ACTION");
        jobs.put(operation,job);return job.state();
    }
    private LocalSnapshot snapshot(ServerPlayer viewer,UUID id,long revision){var snapshot=MineAgentRuntimeServices.snapshots(server).get(id).orElseThrow();if(!ServerWorkspaceModules.operator(viewer)&&!snapshot.ownerPlayerId().equals(viewer.getUUID()))throw new SecurityException("WORKSPACE_PERMISSION_DENIED");if(snapshot.revision()!=revision||snapshot.expiresAtEpochMillis()<System.currentTimeMillis())throw new IllegalStateException("BACKUP_REVISION_CHANGED");return snapshot;}
    void tick(){
        long now=System.currentTimeMillis();previews.values().removeIf(p->p.expires<now);jobs.values().removeIf(j->j.finished>0&&now-j.finished>600000);
        // Share a time budget across operations; a large region cannot monopolize a server tick.
        long deadline=System.nanoTime()+3_000_000;
        for(var job:List.copyOf(jobs.values())){
            if(!job.status.equals("RUNNING"))continue;
            try{
                if(!job.permit.getAsBoolean()||server.getPlayerList().getPlayer(job.owner)!=job.viewer||!job.viewer.isAlive()||!job.dimension.equals(job.viewer.level().dimension().identifier().toString()))throw new SecurityException("BACKUP_CONTEXT_CHANGED");
                ServerWorkspaceModules.require(job.viewer,dev.mineagent.runtime.api.permission.PermissionAction.RESTORE_BACKUP);
                for(int i=0;i<512&&job.status.equals("RUNNING")&&System.nanoTime()<deadline;i++)step(job);
            }catch(Exception failure){job.error=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());finish(job,job.written.isEmpty()?"REJECTED":"PARTIAL");}
            if(System.nanoTime()>=deadline)break;
        }
    }
    private void step(Job job)throws Exception{
        if(job.phase.equals("READ")){
            if(job.cursor<job.targets.size()){var target=job.targets.get(job.cursor++);var actual=read(target);if(job.kind.equals("create"))job.steps.add(new BlockChange(actual,actual));else if(!actual.equals(target))job.steps.add(new BlockChange(actual,target));return;}
            if(job.kind.equals("preview")){
                UUID token=UUID.randomUUID();previews.put(token,new Preview(job.owner,job.snapshot,job.revision,job.dimension,List.copyOf(job.steps),System.currentTimeMillis()+120000));job.result.put("previewId",token);job.result.put("changedBlocks",job.steps.size());job.result.put("blockEntities",job.steps.stream().filter(c->!c.before().blockEntitySnbt().isBlank()||!c.after().blockEntitySnbt().isBlank()).count());job.result.put("examples",job.steps.stream().limit(12).map(c->Map.of("position",List.of(c.after().x(),c.after().y(),c.after().z()),"dimension",c.after().dimension(),"before",c.before().state(),"after",c.after().state())).toList());complete(job,"PREVIEWED");return;
            }
            job.status="SAVING";var blocks=job.steps.stream().map(BlockChange::before).toList();persist(job,()->{var snapshot=MineAgentRuntimeServices.snapshots(server).create(job.owner,job.label,blocks);return Map.of("snapshotId",snapshot.snapshotId(),"blocks",blocks.size());},"APPLIED");return;
        }
        if(job.phase.equals("CHECK")){
            if(job.cursor<job.steps.size()){var step=job.steps.get(job.cursor++);if(!read(step.before()).equals(step.before()))conflict(job,step.before());return;}
            if(!job.conflicts.isEmpty()){finish(job,"CONFLICT");return;}job.cursor=0;job.phase="WRITE";return;
        }
        if(job.phase.equals("WRITE")){
            if(job.cursor==job.steps.size()){job.cursor=0;job.phase="VERIFY";return;}
            var step=job.steps.get(job.cursor++);if(!read(step.before()).equals(step.before())){conflict(job,step.before());finish(job,job.written.isEmpty()?"CONFLICT":"PARTIAL");return;}
            try{write(step.after());}catch(Exception error){
                try{var actual=read(step.after());if(!actual.equals(step.before()))job.written.add(new BlockChange(step.before(),actual));}catch(Exception unknown){job.error="WRITE_OUTCOME_UNKNOWN";finish(job,"UNKNOWN");return;}
                job.error="BACKUP_NATIVE_WRITE_FAILED";finish(job,job.written.isEmpty()?"REJECTED":"PARTIAL");return;
            }
            final BlockSnapshot actual;try{actual=read(step.after());}catch(Exception unknown){job.error="WRITE_OUTCOME_UNKNOWN";finish(job,"UNKNOWN");return;}
            if(!actual.equals(step.before()))job.written.add(new BlockChange(step.before(),actual));if(!actual.equals(step.after())){conflict(job,actual);finish(job,"PARTIAL");}return;
        }
        if(job.phase.equals("VERIFY")){
            if(job.cursor<job.steps.size()){var step=job.steps.get(job.cursor++);if(!read(step.after()).equals(step.after()))conflict(job,step.after());return;}
            finish(job,job.conflicts.isEmpty()?"APPLIED":"PARTIAL");
        }
    }
    private static void conflict(Job job,BlockSnapshot pos){if(job.conflicts.size()<16)job.conflicts.add(Map.of("dimension",pos.dimension(),"position",List.of(pos.x(),pos.y(),pos.z())));}
    private void finish(Job job,String status){
        if(!job.status.equals("RUNNING"))return;job.status="SAVING";
        persist(job,()->{
            var result=new LinkedHashMap<String,Object>();
            if(job.history!=null&&status.equals("APPLIED")){var journal=MineAgentRuntimeServices.changeJournal(server);var changed=job.undo?journal.markReverted(job.history,job.revision,true):journal.markReapplied(job.history,job.revision,true);if(!changed.accepted())throw new IllegalStateException(changed.errorCode());result.put("historyId",job.history);}
            else if(!job.written.isEmpty()){var entry=MineAgentRuntimeServices.changeJournal(server).record(job.owner,job.kind.equals("restore")?"RESTORE_SNAPSHOT":"PARTIAL_"+job.kind.toUpperCase(Locale.ROOT),List.copyOf(job.written));result.put("changeId",entry.changeId());}
            return result;
        },status);
    }
    @FunctionalInterface private interface Store{Map<String,Object> run()throws Exception;}
    private void persist(Job job,Store action,String status){CompletableFuture.supplyAsync(()->{try{return action.run();}catch(Exception error){throw new CompletionException(error);}},io).whenComplete((value,error)->server.execute(()->{if(error!=null){job.error="BACKUP_PERSISTENCE_OUTCOME_UNKNOWN";complete(job,"UNKNOWN");}else{job.result.putAll(value);complete(job,status);}}));}
    private void complete(Job job,String status){job.status=status;job.finished=System.currentTimeMillis();if(job.history!=null)lockedHistory.remove(job.history);}
    private ServerLevel level(BlockSnapshot target){var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(target.dimension())));var pos=new BlockPos(target.x(),target.y(),target.z());if(level==null||level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos)||!level.hasChunkAt(pos))throw new IllegalStateException("BACKUP_TARGET_UNAVAILABLE");return level;}
    private BlockSnapshot read(BlockSnapshot target){var level=level(target);var pos=new BlockPos(target.x(),target.y(),target.z());var entity=level.getBlockEntity(pos);return new BlockSnapshot(target.dimension(),target.x(),target.y(),target.z(),net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(pos)),entity==null?"":entity.saveWithFullMetadata(level.registryAccess()).toString());}
    private void write(BlockSnapshot target)throws Exception{
        var level=level(target);var pos=new BlockPos(target.x(),target.y(),target.z());var state=net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),target.state(),false).blockState();
        // Parse block-entity payload before any world mutation.
        var restored=target.blockEntitySnbt().isBlank()?null:net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,state,net.minecraft.nbt.TagParser.parseCompoundFully(target.blockEntitySnbt()),level.registryAccess());
        if(!target.blockEntitySnbt().isBlank()&&restored==null)throw new IllegalArgumentException("BACKUP_BLOCK_ENTITY_INVALID");
        level.setBlockAndUpdate(pos,state);if(restored!=null){level.setBlockEntity(restored);restored.setChanged();}
    }
    public void close(){for(var job:jobs.values())if(job.status.equals("RUNNING"))finish(job,job.written.isEmpty()?"CANCELLED":"PARTIAL");io.close();previews.clear();}
}
