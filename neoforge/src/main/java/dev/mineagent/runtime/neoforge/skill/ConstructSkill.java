package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.building.ConstructionLedger;
import dev.mineagent.runtime.core.task.SkillSpec;
import dev.mineagent.runtime.neoforge.body.*;
import dev.mineagent.runtime.neoforge.ui.ServerBuildings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Material-consuming construction through the same body controller, ledger and real-world verifier. */
public final class ConstructSkill {
    private static final Map<MinecraftServer,Map<UUID,ServerBuildings.NativeLease>> STAGED=new IdentityHashMap<>();
    private final SkillWork w;private ServerBuildings.NativeLease lease;private CompletableFuture<ServerBuildings.NativeLease> opening;
    private CompletableFuture<?> pending;private Runnable afterPending;private List<ConstructionLedger.Cell> cells=List.of();private int index;private long cursor;
    private String phase="OPEN",missing="",failure="";private boolean closed,reserved;private int placementJumpUntil,placementJumpAttempts;
    private final Map<String,Long> materials=new TreeMap<>();
    private final List<ConstructionLedger.Sample> samples=new ArrayList<>();
    private ConstructSkill(SkillWork work){w=work;var staged=STAGED.get(work.runtime.server);lease=staged==null?null:staged.remove(work.token());if(lease!=null)opened();else{
        var owner=owner();opening=ServerBuildings.claimNative(owner,w.session.agent(),w.session.spec().buildingId(),w.session.spec().buildingRevision(),w.token(),w.session.spec().dimension(),()->w.pause("BUILDING_USER_PAUSED"),this::current);waitFor(opening,()->{lease=opening.join();opened();});
    }}
    public static CompletableFuture<Map<String,Object>> start(SkillRuntime runtime,ServerPlayer owner,UUID agent,UUID operation,UUID task,JsonNode args,SkillSpec.Kind alias,BooleanSupplier permit){
        var spec=SkillSpec.parse(args,alias);var stopped=new java.util.concurrent.atomic.AtomicBoolean();
        return ServerBuildings.claimNative(owner,agent,spec.buildingId(),spec.buildingRevision(),operation,spec.dimension(),()->{stopped.set(true);runtime.pauseNative(operation);},()->permit.getAsBoolean()&&!stopped.get()).thenCompose(lease->{
            STAGED.computeIfAbsent(runtime.server,s->new HashMap<>()).put(operation,lease);
            return runtime.startSession(owner,agent,operation,task,args,alias,()->permit.getAsBoolean()&&!stopped.get()).thenApply(value->{var out=new LinkedHashMap<String,Object>(value);out.put("id",spec.buildingId());out.put("revision",spec.buildingRevision());out.put("operation",lease.head.operation());out.put("verification","UNVERIFIED");return (Map<String,Object>)out;}).whenComplete((value,error)->{if(error!=null||!"STARTED".equals(value.get("status"))){var entries=STAGED.get(runtime.server);if(entries!=null)entries.remove(operation);lease.park(false,"NATIVE_START_NOT_ADMITTED").whenComplete((v,e)->runtime.server.execute(lease::release));}});
        });
    }
    static void tick(SkillWork w){if(w.construct==null)w.construct=new ConstructSkill(w);w.construct.advance();}
    private boolean current(){return !closed&&!w.session.terminal()&&w.actor!=null&&w.actor.current();}
    private ServerPlayer owner(){var owner=w.runtime.server.getPlayerList().getPlayer(w.session.owner());if(owner==null)throw new IllegalStateException("BUILDING_OWNER_OFFLINE");return owner;}
    Map<String,Object> view(){
        String stillMissing=missing;
        if(!stillMissing.isEmpty()&&(!phase.equals("WRITE")||!current()||w.count(BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(stillMissing)))>0))stillMissing="";
        return Map.of("building",w.session.spec().buildingId(),"revision",w.session.spec().buildingRevision(),"phase",phase,"remainingMaterials",Map.copyOf(materials),"missingItem",stillMissing,"error",failure);
    }
    private void waitFor(CompletableFuture<?> next,Runnable continuation){pending=next;afterPending=continuation;}
    private void opened(){
        if(!lease.design.dimension().equals(w.session.spec().dimension()))throw new IllegalStateException("BUILDING_DIMENSION_CHANGED");
        if(Set.of("UNVERIFIED","VERIFIED").contains(lease.head.status())){verify();return;}
        phase=lease.head.phase();cursor=lease.head.cursor();if(!Set.of("PREFLIGHT","WRITE","VERIFY").contains(phase))throw new IllegalStateException("BUILDING_NATIVE_PHASE");page();
    }
    private void page(){long at=cursor;var future=lease.update(l->l.page(w.session.spec().buildingRevision(),at,false));waitFor(future,()->{cells=future.join();index=0;});}
    private void advance(){
        if(closed)return;try{
            if(pending!=null){if(!pending.isDone())return;pending.join();var next=afterPending;pending=null;afterPending=null;if(next!=null)next.run();return;}
            if(w.player().hasInfiniteMaterials()){w.waitFor("BUILDING_REQUIRES_FINITE_INVENTORY",100);return;}
            if(cells.isEmpty()){
                if(phase.equals("PREFLIGHT")){phase="WRITE";cursor=0;var next=lease.update(l->{l.prepareNativeWriteOrder(w.token(),w.session.spec().buildingRevision());l.progress("APPLYING","WRITE",0,0);return null;});waitFor(next,this::page);return;}
                if(phase.equals("WRITE")){phase="VERIFY";cursor=0;var next=lease.update(l->{l.progress("APPLYING","VERIFY",0,0);return null;});waitFor(next,this::page);return;}
                if(phase.equals("VERIFY")){long tick=w.player().level().getGameTime();waitFor(lease.update(l->{l.finish(tick);return null;}),this::verify);return;}
                return;
            }
            if(phase.equals("PREFLIGHT")){preflight();return;}
            if(phase.equals("VERIFY")){for(int count=0;index<cells.size()&&count++<32;index++){var cell=cells.get(index);var pos=position(cell);loaded(pos);String actual=state(pos);if(!actual.equals(cell.after()))throw problem("VERIFY_TARGET_CHANGED",cell,cell.after(),actual);cursor=cell.sequence();}if(index==cells.size())page();return;}
            writeCell();
        }catch(Exception error){Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();failure=Objects.toString(cause.getMessage(),cause.getClass().getSimpleName());w.pause("BUILDING_NATIVE_"+failure);}
    }
    private void preflight(){
        for(int count=0;index<cells.size()&&count++<32;index++){
            var cell=cells.get(index);var pos=position(cell);loaded(pos);var actual=w.player().level().getBlockState(pos);var wanted=ServerBuildings.nativeState(w.player(),cell.material(),cell.orientation());String before=BlockStateParser.serialize(actual),after=BlockStateParser.serialize(wanted);
            if(!before.equals(after)){
                if(w.player().level().getBlockEntity(pos)!=null||actual.hasBlockEntity())throw problem("EXISTING_BLOCK_ENTITY_PROTECTED",cell,after,before);
                if(cell.expected()!=null&&!cell.expected().equals(before))throw problem("CONFLICT_POSITION",cell,cell.expected(),before);
                if(!actual.isAir()&&!breakable(pos))throw problem("EXISTING_TERRAIN_PROTECTED",cell,after,before);
                if(!wanted.isAir()){Item item=wanted.getBlock().asItem();if(!(item instanceof BlockItem))throw problem("NO_NATIVE_BLOCK_ITEM",cell,after,before);materials.merge(BuiltInRegistries.ITEM.getKey(item).toString(),1L,Long::sum);}
            }
            samples.add(new ConstructionLedger.Sample(cell.sequence(),before,after,cell.baseline()==null?before:cell.baseline()));cursor=cell.sequence();
        }
        if(index==cells.size()){var batch=List.copyOf(samples);samples.clear();long at=cursor;var future=lease.update(l->{l.samples(batch);l.progress("PREPARING","PREFLIGHT",at,0);return null;});waitFor(future,this::page);}
    }
    private void writeCell(){
        var cell=cells.get(index);var pos=position(cell);loaded(pos);w.block=pos;var actual=w.player().level().getBlockState(pos);var wanted=ServerBuildings.nativeState(w.player(),cell.after(),List.of());
        if(w.operation!=null){perform(cell,pos,wanted);return;}
        if(actual==wanted){advanceCell(cell,false);return;}
        boolean ownBreak=actual.isAir()&&"VERIFIED_NATIVE_BREAK".equals(w.session.receipt().get("state"))&&Long.toString(cell.sequence()).equals(w.session.receipt().get("sequence"))&&w.session.spec().buildingId().equals(w.session.receipt().get("building"));
        if(!BlockStateParser.serialize(actual).equals(cell.before())&&!ownBreak)throw problem("TARGET_CHANGED_DURING_CONSTRUCTION",cell,cell.before(),state(pos));
        if(!w.runtime.reservations.reserve(w.reservation(pos),w.token(),w.tick(),100)){w.waitFor("BUILDING_CELL_RESERVED",10);return;}
        if(!actual.isAir()){
            if(!breakable(pos))throw problem("BREAK_DENIED",cell,cell.before(),state(pos));if(!w.reach(pos,InteractionTargetResolver.Kind.BLOCK))return;int slot=NativeTerrainPolicy.toolSlot(w.player(),pos);if(slot!=w.player().getInventory().getSelectedSlot()){w.actor.select(w.token(),slot);return;}
            w.expectedState=BlockStateParser.serialize(actual);w.nativeBreak=false;w.actor.aimImmediately(w.token(),Vec3.atCenterOf(pos));w.prepare("CONSTRUCT_BREAK",evidence(cell));return;
        }
        if(wanted.isAir()){advanceCell(cell,false);return;}
        Item item=wanted.getBlock().asItem();String id=BuiltInRegistries.ITEM.getKey(item).toString();if(w.count(item)<1){missing=id;w.waitFor("BUILDING_MATERIALS_REQUIRED",20);return;}missing="";
        if(!w.equip(item))return;if(w.tick()>=placementJumpUntil&&!w.reach(pos,InteractionTargetResolver.Kind.PLACE,wanted))return;
        if(!placementSpaceReady(pos,wanted))return;
        w.actor.crouch(w.token(),true);
        if(!w.player().isCrouching())return;
        var hit=NativeTerrainRecovery.placementHit(w.player(),pos,wanted);if(hit==null){if(jumpForPlacement(cell,pos,wanted))return;w.waitFor("BUILDING_SUPPORT_FACE_REQUIRED",40);w.stand=null;w.search=null;return;}
        if(!w.player().mayInteract(w.player().level(),pos)||dev.mineagent.runtime.neoforge.ui.ServerInteractionRules.denied(w.player(),pos,null,"block_place",false))throw problem("PLACE_DENIED",cell,cell.after(),state(pos));
        w.nativeUsePos=hit.getBlockPos();w.beforeCount=w.count(item);w.expectedState=state(pos);w.actor.aimImmediately(w.token(),hit.getLocation());w.prepare("CONSTRUCT_PLACE",evidence(cell));
    }
    private Map<String,String> evidence(ConstructionLedger.Cell cell){return Map.of("building",w.session.spec().buildingId(),"revision",Long.toString(w.session.spec().buildingRevision()),"sequence",Long.toString(cell.sequence()),"block",position(cell).toShortString(),"before",w.expectedState,"desired",cell.after());}
    private void perform(ConstructionLedger.Cell cell,BlockPos pos,BlockState wanted){
        if(!w.saved.isDone())return;if(!reserved){UUID operation=w.operation;String receipt=new com.google.gson.Gson().toJson(w.session.receipt());long at=cursor;waitFor(lease.update(l->{l.nativePrepared(w.token(),operation,cell.sequence(),receipt);l.reserve(at);return null;}),()->reserved=true);return;}
        if(!w.runtime.reservations.reserve(w.reservation(pos),w.token(),w.tick(),100))throw problem("RESERVATION_LOST",cell,cell.before(),state(pos));
        var actual=w.player().level().getBlockState(pos);
        if(w.action.equals("CONSTRUCT_BREAK")&&w.nativeBreak){w.nativeBreak=false;finishAction(cell,false,"VERIFIED_NATIVE_BREAK");return;}
        if(w.action.equals("CONSTRUCT_PLACE")&&actual==wanted&&w.nativeUse&&w.nativeConsumed==1){w.session.add("constructionMaterialsConsumed",1);w.session.add("constructionBlocksPlaced",1);finishAction(cell,true,"VERIFIED_NATIVE_PLACEMENT");return;}
        if(w.action.equals("CONSTRUCT_PLACE")&&w.nativeUse&&w.nativeConsumed==1&&!actual.isAir()&&actual!=wanted){
            String observed=state(pos),diagnostic=problem("PLACED_STATE_MISMATCH",cell,cell.after(),observed).getMessage();UUID operation=w.operation;long prior=cursor,tick=w.player().level().getGameTime();
            w.session.add("constructionMaterialsConsumed",1);w.session.add("constructionBlocksPlaced",1);w.session.add("constructionStateMismatches",1);
            String detail=new com.google.gson.Gson().toJson(Map.of("actual",observed,"expected",cell.after(),"position",pos.toShortString(),"component",cell.component(),"materialsConsumed",1,"executionState","APPLIED_DIFFERENT_STATE"));
            waitFor(lease.update(l->{l.nativeObserved(w.token(),operation,"PLACED_STATE_MISMATCH",detail);l.applied(List.of(cell.sequence()),prior,tick);l.fail("PARTIAL",diagnostic);return null;}),()->{w.confirm("PLACED_STATE_MISMATCH",Map.of("actual",observed,"materialConsumed","1","executionState","APPLIED_DIFFERENT_STATE"));failure=diagnostic;w.pause("BUILDING_NATIVE_"+diagnostic);});return;
        }
        if(w.action.equals("CONSTRUCT_PLACE")&&w.executed&&w.nativeUseObserved&&!w.nativeUse&&w.nativeConsumed==0&&actual.isAir()&&w.count(wanted.getBlock().asItem())==w.beforeCount){
            String diagnostic=problem("PLACE_REJECTED_NO_CHANGE",cell,cell.after(),state(pos)).getMessage();UUID operation=w.operation;
            String detail=new com.google.gson.Gson().toJson(Map.of("executionState","NOT_APPLIED","nativeAccepted",false,"actual",state(pos),"expected",cell.after(),"position",pos.toShortString(),"component",cell.component(),"materialsConsumed",0));
            waitFor(lease.update(l->{l.nativeNotApplied(w.token(),operation,detail,diagnostic);return null;}),()->{w.confirm("NOT_APPLIED_NATIVE_REJECTED",Map.of("actual",state(pos),"materialConsumed","0","executionState","NOT_APPLIED"));failure=diagnostic;w.pause("BUILDING_NATIVE_"+diagnostic);});return;
        }
        if(w.interruptedOperation||w.executed&&w.tick()-w.startedTick>Math.min(2400,Math.max(240L,(long)NativeTerrainPolicy.breakTicks(w.player(),pos)*3+40)))throw problem("ACTION_OUTCOME_REQUIRES_RECONCILIATION",cell,cell.after(),state(pos));
        if(!w.executed&&!state(pos).equals(w.expectedState))throw problem("PREPARED_TARGET_CHANGED",cell,w.expectedState,state(pos));
        if(w.action.equals("CONSTRUCT_BREAK")){if(!w.executed){w.executed=true;w.startedTick=w.tick();}w.actor.breakBlock(w.token(),w.operation,pos);return;}
        if(w.executed)return; // A submitted placement waits for its real receipt; it is never replayed.
        if(!placementSpaceReady(pos,wanted))return;
        w.actor.crouch(w.token(),true);if(!w.player().isCrouching())return;
        var hit=NativeTerrainRecovery.placementHit(w.player(),pos,wanted);
        if(hit==null){if(jumpForPlacement(cell,pos,wanted))return;w.stand=null;w.search=null;w.reach(pos,InteractionTargetResolver.Kind.PLACE,wanted);return;}
        w.nativeUsePos=hit.getBlockPos();w.actor.aimImmediately(w.token(),hit.getLocation());w.executed=true;w.startedTick=w.tick();w.actor.placeBlock(w.token(),w.operation,hit);
    }
    private boolean jumpForPlacement(ConstructionLedger.Cell cell,BlockPos pos,BlockState wanted){
        if(w.tick()<placementJumpUntil)return true;
        if(!w.player().onGround())return true;
        double rise=NativeTerrainRecovery.placementJumpRise(w.player(),w.player().position());
        if(rise<=0||NativeTerrainRecovery.placementHitFrom(w.player(),pos,w.player().getEyePosition().add(0,rise,0),wanted)==null)return false;
        if(placementJumpAttempts++>=3)throw problem("JUMP_PLACEMENT_WINDOW_UNAVAILABLE",cell,cell.after(),state(pos));
        w.actor.haltMotion(w.token());w.actor.crouch(w.token(),true);w.actor.jump(w.token());placementJumpUntil=w.tick()+30;return true;
    }
    private void finishAction(ConstructionLedger.Cell cell,boolean placed,String status){
        UUID operation=w.operation;String observed=state(position(cell));long tick=w.player().level().getGameTime(),prior=cursor;var detail=Map.of("state",observed,"materialsConsumed",placed?1:0,"tick",tick);String encoded=new com.google.gson.Gson().toJson(detail);
        var future=lease.update(l->{l.nativeObserved(w.token(),operation,status,encoded);if(placed)l.applied(List.of(cell.sequence()),cell.sequence(),tick);else l.progress("APPLYING","WRITE",prior,tick);return null;});
        waitFor(future,()->{w.confirm(status,Map.of("actual",observed,"materialConsumed",placed?"1":"0"));reserved=false;w.stand=null;w.search=null;if(placed)advanceCell(cell,true);});
    }
    private void advanceCell(ConstructionLedger.Cell cell,boolean written){
        if(!cell.after().startsWith("minecraft:air")){String item=BuiltInRegistries.ITEM.getKey(ServerBuildings.nativeState(w.player(),cell.after(),List.of()).getBlock().asItem()).toString();materials.computeIfPresent(item,(key,count)->Math.max(0,count-1));}
        missing="";placementJumpUntil=placementJumpAttempts=0;cursor=cell.sequence();index++;w.abandonTarget();if(index>=cells.size())page();
    }
    private void verify(){
        phase="VERIFYING_STRUCTURE";lease.release();var future=ServerBuildings.verifyNative(owner(),w.session.agent(),w.session.spec().buildingId(),w.session.spec().buildingRevision(),w.player().level(),this::current);
        waitFor(future,()->{var result=future.join();if(!"VERIFIED".equals(result.get("verification"))){failure=Objects.toString(result.get("error"),"STRUCTURE_CHECK_FAILED");w.pause("BUILDING_NATIVE_VERIFICATION_FAILED");return;}closed=true;phase="VERIFIED";w.completed("BUILDING_VERIFIED_WITH_NATIVE_MATERIALS");});
    }
    CompletableFuture<Void> stop(){if(closed)return CompletableFuture.completedFuture(null);closed=true;boolean uncertain=w.executed;if(lease==null)return opening==null?CompletableFuture.completedFuture(null):opening.handle((value,error)->value).thenCompose(value->value==null?CompletableFuture.completedFuture(null):park(value,uncertain));return park(lease,uncertain);}
    private CompletableFuture<Void> park(ServerBuildings.NativeLease value,boolean uncertain){return value.park(uncertain,"NATIVE_ACTOR_STOPPED").whenComplete((v,e)->w.runtime.server.execute(value::release));}
    private boolean breakable(BlockPos pos){var p=w.player();return p.mayInteract(p.level(),pos)&&p.level().getBlockEntity(pos)==null&&!p.level().getBlockState(pos).hasBlockEntity()&&p.level().getBlockState(pos).getDestroySpeed(p.level(),pos)>=0&&p.level().getFluidState(pos).isEmpty()&&TerrainProvenance.get(w.runtime.server).available()&&!TerrainProvenance.get(w.runtime.server).knownPlaced(p.level(),pos)&&dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.config(w.runtime.server).flag("autonomy.terrainRecovery.allowUnknownNaturalMaterials",true)&&!dev.mineagent.runtime.neoforge.ui.ServerInteractionRules.denied(p,pos,null,"block_break",false);}
    private boolean placementSpaceReady(BlockPos pos,BlockState wanted){
        var shape=wanted.getCollisionShape(w.player().level(),pos).move(pos.getX(),pos.getY(),pos.getZ());
        if(w.player().level().isUnobstructed(null,shape))return true;
        if(!shape.isEmpty()&&shape.bounds().intersects(w.player().getBoundingBox())){w.stand=null;w.search=null;w.reach(pos,InteractionTargetResolver.Kind.PLACE,wanted);return false;}
        w.waitFor("BUILDING_ENTITY_BLOCKING",10);return false;
    }
    private void loaded(BlockPos pos){if(!NativeTerrainPolicy.loaded(w.player(),pos))throw new IllegalStateException("BUILDING_CHUNK_NOT_LOADED at "+pos.toShortString());}
    private String state(BlockPos pos){return BlockStateParser.serialize(w.player().level().getBlockState(pos));}
    private static BlockPos position(ConstructionLedger.Cell cell){return new BlockPos(cell.x(),cell.y(),cell.z());}
    private static IllegalStateException problem(String reason,ConstructionLedger.Cell cell,String expected,String actual){return new IllegalStateException(reason+" component="+cell.component()+" position=["+cell.x()+","+cell.y()+","+cell.z()+"] expected="+expected+" actual="+actual);}
    static void stopped(MinecraftServer server){var staged=STAGED.remove(server);if(staged!=null)for(var lease:staged.values())lease.park(false,"SERVER_STOPPED");}
}
