package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.api.ui.ContainerProtocol.*;
import dev.mineagent.runtime.core.ui.ContainerTransaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Native self-player interaction; never writes inventory counts or block state to fake an action. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ConversationNativeInteractions {
    private static final Map<MinecraftServer,Map<UUID,Lease>> LEASES=new IdentityHashMap<>();
    private static final class Lease implements ContainerTransaction.Port {
        final UUID id=UUID.randomUUID(),agent;final ServerPlayer player;final net.minecraft.server.level.ServerLevel level;final AbstractContainerMenu menu;final ContainerTransaction tx;
        long expires;boolean live=true;
        Lease(ServerPlayer p,UUID agent){this.player=p;this.agent=agent;level=p.level();menu=p.containerMenu;tx=new ContainerTransaction(this,64);touch();}
        void touch(){expires=System.nanoTime()+java.time.Duration.ofMinutes(10).toNanos();}
        public boolean valid(){return live&&System.nanoTime()<expires&&player.isAlive()&&!player.isSpectator()&&player.level()==level&&level.getServer().getPlayerList().getPlayer(player.getUUID())==player&&player.containerMenu==menu&&menu!=player.inventoryMenu&&menu.slots.size()<=128&&menu.stillValid(player);}
        public Raw capture(){return NativeContainerSnapshot.capture(player,menu);}
        public void execute(Action action){menu.clicked(action.slot(),0,ContainerInput.QUICK_MOVE,player);menu.broadcastChanges();player.inventoryMenu.broadcastFullState();}
        public void close(){live=false;} // Invalidation never silently closes a player's screen or drops its cursor.
    }
    private static void keys(JsonNode n,String... allowed){if(!n.isObject()||!Set.of(allowed).containsAll(n.properties().stream().map(Map.Entry::getKey).toList()))throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS");}
    private static String text(JsonNode n,String key){if(!n.path(key).isTextual()||n.path(key).asText().isBlank())throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS");return n.path(key).asText();}
    private static long number(JsonNode n,String key,long min,long max){var v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<min||v.longValue()>max)throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS");return v.longValue();}
    private static void playerReady(ServerPlayer p){if(!p.level().getServer().isSameThread()||!p.isAlive()||p.isSpectator())throw new IllegalStateException("AGENT_PLAYER_UNAVAILABLE");}
    private static Map<UUID,Lease> leases(ServerPlayer p){return LEASES.computeIfAbsent(p.level().getServer(),s->new HashMap<>());}
    private static Lease observe(ServerPlayer p,UUID agent){
        playerReady(p);if(p.containerMenu==p.inventoryMenu)return null;if(p.containerMenu.slots.size()>128||!p.containerMenu.stillValid(p))throw new IllegalStateException("AGENT_CONTAINER_UNSUPPORTED");
        var map=leases(p);var lease=map.get(p.getUUID());if(lease==null||!lease.agent.equals(agent)||!lease.valid()){if(lease!=null)lease.close();lease=new Lease(p,agent);map.put(p.getUUID(),lease);}lease.touch();return lease;
    }
    private static Lease require(ServerPlayer p,UUID agent,JsonNode args){playerReady(p);var lease=leases(p).get(p.getUUID());if(lease==null||!lease.agent.equals(agent)||!lease.id.toString().equals(text(args,"session_id"))||!lease.valid())throw new IllegalStateException("AGENT_CONTAINER_SESSION_CHANGED");lease.touch();return lease;}
    private static Map<String,Object> view(Lease lease,State state,int offset){
        var result=new LinkedHashMap<String,Object>();result.put("status","OBSERVED");result.put("session_id",lease.id);result.put("revision",state.revision());result.put("menuType",state.menuType());result.put("menuId",state.menuId());result.put("nativeStateId",state.nativeStateId());result.put("cursor",state.carried());
        result.put("slots",state.slots().stream().skip(offset).limit(16).toList());result.put("totalSlots",state.slots().size());result.put("nextOffset",offset+16<state.slots().size()?offset+16:-1);return result;
    }
    public static Map<String,Object> inspect(ServerPlayer p,UUID agent,JsonNode args){keys(args,"offset");int offset=args.has("offset")?(int)number(args,"offset",0,128):0;var lease=observe(p,agent);return lease==null?Map.of("status","NO_OPEN_CONTAINER"):view(lease,lease.tx.read(),offset);}
    public static CompletableFuture<Map<String,Object>> useBlock(ServerPlayer p,UUID agent,JsonNode args)throws Exception{
        keys(args,"position","face","hand","expected_block_hash","expected_hand_hash");playerReady(p);
        if(p.containerMenu!=p.inventoryMenu||!p.inventoryMenu.getCarried().isEmpty())throw new IllegalStateException("AGENT_PLAYER_MENU_BUSY");
        var coordinates=args.path("position");if(!coordinates.isArray()||coordinates.size()!=3)throw new IllegalArgumentException("AGENT_BLOCK_COORDINATES");int[] c=new int[3];for(int i=0;i<3;i++){if(!coordinates.get(i).isIntegralNumber()||!coordinates.get(i).canConvertToInt())throw new IllegalArgumentException("AGENT_BLOCK_COORDINATES");c[i]=coordinates.get(i).intValue();}
        var pos=new BlockPos(c[0],c[1],c[2]);var level=p.level();if(!level.isInWorldBounds(pos)||!p.isWithinBlockInteractionRange(pos,0)||!level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4)||!level.mayInteract(p,pos))throw new IllegalStateException("AGENT_BLOCK_OUT_OF_REACH_OR_PROTECTED");
        String faceName=args.path("face").asText("auto");Direction face=faceName.equals("auto")?null:Direction.valueOf(faceName.toUpperCase(Locale.ROOT));InteractionHand hand=switch(text(args,"hand")){case "main"->InteractionHand.MAIN_HAND;case "off"->InteractionHand.OFF_HAND;default->throw new IllegalArgumentException("AGENT_HAND");};
        String before=net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(pos));if(!NativeContainerSnapshot.sha(before).equals(text(args,"expected_block_hash")))throw new IllegalStateException("AGENT_BLOCK_CHANGED");
        if(!ConversationAgentTools.hash(p,p.getItemInHand(hand)).equals(text(args,"expected_hand_hash")))throw new IllegalStateException("AGENT_HAND_CHANGED");
        var shape=level.getBlockState(pos).getShape(level,pos);if(shape.isEmpty())throw new IllegalStateException("AGENT_BLOCK_NO_OUTLINE");
        var bounds=shape.toAabbs().stream().map(box->box.move(pos)).min(Comparator.comparingDouble(box->box.getCenter().distanceToSqr(p.getEyePosition()))).orElseThrow();
        Vec3 aim=bounds.getCenter();if(face!=null)aim=new Vec3(face.getStepX()<0?bounds.minX+.0001:face.getStepX()>0?bounds.maxX-.0001:aim.x,face.getStepY()<0?bounds.minY+.0001:face.getStepY()>0?bounds.maxY-.0001:aim.y,face.getStepZ()<0?bounds.minZ+.0001:face.getStepZ()>0?bounds.maxZ-.0001:aim.z);var hit=level.clip(new ClipContext(p.getEyePosition(),aim,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));
        if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos))throw new IllegalStateException("AGENT_BLOCK_OCCLUDED");
        if(face!=null&&hit.getDirection()!=face)return CompletableFuture.completedFuture(Map.of("status","REJECTED","error","AGENT_BLOCK_FACE_NOT_VISIBLE","visibleFace",hit.getDirection().name().toLowerCase(Locale.ROOT)));
        try{
            // Preserve vanilla/Mod callbacks and the real client screen-open notification.
            var interaction=p.gameMode.useItemOn(p,level,p.getItemInHand(hand),hand,hit);if(interaction.consumesAction())p.swing(hand,true);p.inventoryMenu.broadcastFullState();p.containerMenu.broadcastChanges();
            String after=net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(pos));var result=new LinkedHashMap<String,Object>();result.put("status","OBSERVED");result.put("interactionConsumed",interaction.consumesAction());result.put("hitFace",hit.getDirection().name().toLowerCase(Locale.ROOT));result.put("before",before);result.put("after",after);result.put("afterBlockHash",NativeContainerSnapshot.sha(after));result.put("menuOpened",p.containerMenu!=p.inventoryMenu);
            if(p.containerMenu!=p.inventoryMenu){if(p.containerMenu.slots.size()>128)result.put("container","OPEN_BUT_UNSUPPORTED");else result.put("container",inspect(p,agent,new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));}
            return CompletableFuture.completedFuture(result);
        }catch(Exception unknown){return CompletableFuture.failedFuture(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN",unknown));}
    }
    public static CompletableFuture<Map<String,Object>> quickMove(ServerPlayer p,UUID agent,UUID operation,JsonNode args){
        keys(args,"session_id","expected_revision","slot");var lease=require(p,agent,args);long expected=number(args,"expected_revision",1,Long.MAX_VALUE);int index=(int)number(args,"slot",0,lease.menu.slots.size()-1);
        var before=lease.tx.read();if(before.revision()!=expected)throw new IllegalStateException("AGENT_CONTAINER_STATE_CHANGED");if(!lease.menu.getCarried().isEmpty())throw new IllegalStateException("AGENT_CONTAINER_CURSOR_BUSY");var slot=lease.menu.getSlot(index);if(!slot.isActive()||!slot.mayPickup(p)||slot.getItem().isEmpty())throw new IllegalStateException("AGENT_CONTAINER_SLOT_UNAVAILABLE");
        try{
            var applied=lease.tx.apply(operation,expected,new Action("CLICK",index,0,"QUICK_MOVE",List.of()));var result=new LinkedHashMap<String,Object>(view(lease,applied.state(),0));result.put("changed",applied.changed());result.put("executionMode","NATIVE_MENU_QUICK_MOVE");
            var changed=new ArrayList<Slot>();for(int i=0;i<before.slots().size();i++)if(!before.slots().get(i).item().equals(applied.state().slots().get(i).item()))changed.add(applied.state().slots().get(i));result.put("changedSlots",changed.stream().limit(16).toList());result.put("changedSlotsTruncated",changed.size()>16);return CompletableFuture.completedFuture(result);
        }catch(Exception unknown){lease.close();return CompletableFuture.failedFuture(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN",unknown));}
    }
    public static CompletableFuture<Map<String,Object>> closeMenu(ServerPlayer p,UUID agent,JsonNode args){
        keys(args,"session_id","expected_revision");var lease=require(p,agent,args);if(lease.tx.read().revision()!=number(args,"expected_revision",1,Long.MAX_VALUE))throw new IllegalStateException("AGENT_CONTAINER_STATE_CHANGED");if(!lease.menu.getCarried().isEmpty())throw new IllegalStateException("AGENT_CONTAINER_CURSOR_BUSY");
        try{p.closeContainer();lease.close();return CompletableFuture.completedFuture(Map.of("status",p.containerMenu==p.inventoryMenu?"CLOSED":"CONTAINER_REPLACED","cursorEmpty",p.inventoryMenu.getCarried().isEmpty()));}
        catch(Exception unknown){lease.close();return CompletableFuture.failedFuture(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN",unknown));}
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var map=LEASES.get(e.getServer());if(map!=null)map.values().removeIf(lease->!lease.valid());}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent e){var map=LEASES.remove(e.getServer());if(map!=null)map.values().forEach(Lease::close);}
}
