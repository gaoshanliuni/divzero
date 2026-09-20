package dev.mineagent.runtime.neoforge.ui;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.mineagent.runtime.api.ui.ContainerProtocol;
import dev.mineagent.runtime.api.ui.ContainerProtocol.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.core.ui.ContainerTransaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import java.util.*;

/** Actual actor/menu/block identity, native interaction and native slot rules. No client-provided inventory values. */
public final class ServerContainerRuntime implements AutoCloseable {
    private final MinecraftServer server;private final Map<UUID,Lease> leases=new HashMap<>();
    private final class Lease implements ContainerTransaction.Port {
        final UUID id=UUID.randomUUID(),pkg;final long revision;final ServerPlayer viewer,actor;final net.minecraft.server.level.ServerLevel level;final BlockPos pos;final Object blockEntity;final AbstractContainerMenu menu;final ContainerTransaction transaction;UUID session;long expires=System.currentTimeMillis()+120000;
        Lease(ServerPlayer viewer,ServerPlayer actor,UUID pkg,long revision,BlockPos pos){this.viewer=viewer;this.actor=actor;this.level=actor.level();this.pkg=pkg;this.revision=revision;this.pos=pos;this.blockEntity=actor.level().getBlockEntity(pos);this.menu=actor.containerMenu;transaction=new ContainerTransaction(this,2048);}
        public boolean valid(){return server.isSameThread()&&server.isRunning()&&server.getPlayerList().getPlayer(actor.getUUID())==actor&&actor.isAlive()&&!actor.isSpectator()
                &&server.getPlayerList().getPlayer(viewer.getUUID())==viewer&&System.currentTimeMillis()<expires&&actor.level()==level&&actor.containerMenu==menu&&actor.level().getBlockEntity(pos)==blockEntity&&menu.stillValid(actor)&&ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,revision).isPresent();}
        public Raw capture(){
            var slots=new ArrayList<ContainerProtocol.Slot>();var digest=new StringBuilder();int budget=0;
            for(int i=0;i<menu.slots.size();i++){var slot=menu.getSlot(i);var item=item(slot.getItem());digest.append(i).append(':').append(item.fingerprint()).append(';');budget+=item.name().length();
                slots.add(new ContainerProtocol.Slot(i,slot.x,slot.y,slot.container==actor.getInventory()?"player":"container",slot.isActive(),slot.mayPickup(actor),item));}
            var carried=item(menu.getCarried());digest.append("cursor:").append(carried.fingerprint());
            for(int i=0;i<actor.getInventory().getContainerSize();i++)digest.append('|').append(item(actor.getInventory().getItem(i)).fingerprint());
            if(budget>12000)throw new IllegalStateException("CONTAINER_STATE_BUDGET");
            return new Raw(menu.containerId,menu.getStateId(),BuiltInRegistries.MENU.getKey(menu.getType()).toString(),actor.getUUID().toString(),slots,carried,sha(digest.toString()));
        }
        private Item item(ItemStack stack){
            if(stack.isEmpty())return Item.empty();var encoded=ItemStack.CODEC.encodeStart(actor.registryAccess().createSerializationContext(JsonOps.INSTANCE),stack).getOrThrow();
            String canonical=canonical(encoded).toString();if(canonical.length()>65536)throw new IllegalStateException("CONTAINER_ITEM_BUDGET");String name=stack.getHoverName().getString();
            return new Item(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),name.substring(0,Math.min(512,name.length())),stack.getCount(),stack.getMaxStackSize(),sha(canonical));
        }
        public void execute(Action action){
            if(action.kind().equals("CLICK")){
                if(action.slot()!=-999&&(action.slot()<0||action.slot()>=menu.slots.size()))throw new IllegalArgumentException("CONTAINER_SLOT");
                menu.clicked(action.slot(),action.button(),ContainerInput.valueOf(action.clickType()),actor);
            }else{
                if(action.slots().stream().anyMatch(i->i>=menu.slots.size())||!AbstractContainerMenu.isValidQuickcraftType(action.button(),actor))throw new IllegalArgumentException("CONTAINER_DRAG");
                var access=(dev.mineagent.runtime.neoforge.mixin.ContainerQuickCraftAccess)menu;access.mineagent$resetQuickCraft();
                try{menu.clicked(-999,AbstractContainerMenu.getQuickcraftMask(0,action.button()),ContainerInput.QUICK_CRAFT,actor);
                    for(int index:action.slots())menu.clicked(index,AbstractContainerMenu.getQuickcraftMask(1,action.button()),ContainerInput.QUICK_CRAFT,actor);
                    menu.clicked(-999,AbstractContainerMenu.getQuickcraftMask(2,action.button()),ContainerInput.QUICK_CRAFT,actor);
                }finally{access.mineagent$resetQuickCraft();}
            }
            menu.broadcastChanges();actor.inventoryMenu.broadcastFullState();
        }
        public void close(){if(actor.containerMenu==menu){actor.doCloseContainer();actor.inventoryMenu.broadcastFullState();}}
    }
    public ServerContainerRuntime(MinecraftServer server){this.server=server;}
    public UUID open(ServerPlayer actor,UUID pkg,long revision){
        return open(actor,actor,pkg,revision);
    }
    public UUID open(ServerPlayer viewer,ServerPlayer actor,UUID pkg,long revision){
        if(!server.isSameThread()||actor.isSpectator()||!actor.isAlive()||actor.containerMenu!=actor.inventoryMenu||!actor.inventoryMenu.getCarried().isEmpty())throw new IllegalStateException("CONTAINER_ACTOR_BUSY");
        if(leases.values().stream().anyMatch(l->l.actor==actor))throw new IllegalStateException("CONTAINER_ACTOR_BUSY");
        var end=actor.getEyePosition().add(actor.getLookAngle().scale(actor.blockInteractionRange()));
        var hit=actor.level().clip(new net.minecraft.world.level.ClipContext(actor.getEyePosition(),end,net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,actor));
        BlockPos pos=hit.getBlockPos();var state=actor.level().getBlockState(pos);
        if(hit.getType()!=HitResult.Type.BLOCK||!actor.isWithinBlockInteractionRange(pos,0)||!actor.level().mayInteract(actor,pos)||state.getMenuProvider(actor.level(),pos)==null||!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().startsWith("minecraft:"))throw new IllegalStateException("CONTAINER_TARGET_UNAVAILABLE");
        try(var ignored=new ContainerOpenScope(actor)){actor.gameMode.useItemOn(actor,actor.level(),actor.getMainHandItem(),InteractionHand.MAIN_HAND,hit);}
        if(actor.containerMenu==actor.inventoryMenu)throw new IllegalStateException("CONTAINER_NOT_OPENED");
        if(actor.containerMenu.slots.size()>128||!BuiltInRegistries.MENU.getKey(actor.containerMenu.getType()).toString().startsWith("minecraft:")){actor.doCloseContainer();throw new IllegalStateException("CONTAINER_UNSUPPORTED");}
        var lease=new Lease(viewer,actor,pkg,revision,pos);leases.put(lease.id,lease);return lease.id;
    }
    public Code authorize(Binding binding,ServerPlayer viewer){
        if(!ContainerProtocol.bound(binding))return Code.PERMISSION_DENIED;
        Lease lease;try{lease=leases.get(UUID.fromString(binding.targetObjectId()));}catch(IllegalArgumentException invalid){return Code.TARGET_NOT_FOUND;}
        if(lease==null||lease.viewer!=viewer||!binding.actorId().equals(lease.actor.getUUID())||!lease.pkg.equals(binding.ownerPackageId())||lease.revision!=binding.packageRevision())return Code.PERMISSION_DENIED;
        if(binding.actorKind()==ActorKind.PLAYER?(lease.actor!=viewer||binding.taskId()!=null):(binding.taskId()==null||binding.taskRevision()<1||!(lease.actor instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)))return Code.PERMISSION_DENIED;
        return lease.valid()?Code.OK:Code.TARGET_NOT_FOUND;
    }
    public void attach(UUID id,Session session){var lease=leases.get(id);if(lease==null||!session.binding().targetObjectId().equals(id.toString()))throw new SecurityException("CONTAINER_SESSION_CONTEXT");lease.session=session.sessionId();lease.expires=session.expiresAtMillis();}
    public void closeSession(UUID viewer,UUID session){for(var lease:List.copyOf(leases.values()))if(lease.viewer.getUUID().equals(viewer)&&Objects.equals(lease.session,session))close(lease.id);}
    public Raw closeSnapshot(Binding binding){var lease=require(binding);leases.remove(lease.id);lease.transaction.close();return lease.capture();}
    public State read(Binding b){return require(b).transaction.read();}
    public Result act(Binding b,UUID operation,long expected,Action action){return require(b).transaction.apply(operation,expected,action);}
    private Lease require(Binding b){var viewer=server.getPlayerList().getPlayer(b.viewerPlayerId());if(viewer==null||authorize(b,viewer)!=Code.OK)throw new SecurityException("CONTAINER_ACCESS_DENIED");return leases.get(UUID.fromString(b.targetObjectId()));}
    public void close(Binding b){if(ContainerProtocol.bound(b))try{close(UUID.fromString(b.targetObjectId()));}catch(IllegalArgumentException ignored){}}
    public void close(UUID id){var lease=leases.remove(id);if(lease!=null)lease.transaction.close();}
    public void closeViewer(UUID viewer){for(var l:List.copyOf(leases.values()))if(l.viewer.getUUID().equals(viewer))close(l.id);}
    public void closePackage(UUID pkg){for(var l:List.copyOf(leases.values()))if(l.pkg.equals(pkg))close(l.id);}
    public void tick(){for(var l:List.copyOf(leases.values()))if(!l.valid())close(l.id);}
    @Override public void close(){for(var id:List.copyOf(leases.keySet()))close(id);}
    private static String sha(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static JsonElement canonical(JsonElement value){if(value.isJsonObject()){var out=new JsonObject();value.getAsJsonObject().keySet().stream().sorted().forEach(k->out.add(k,canonical(value.getAsJsonObject().get(k))));return out;}if(value.isJsonArray()){var out=new JsonArray();for(var item:value.getAsJsonArray())out.add(canonical(item));return out;}return value;}
}
