package dev.mineagent.runtime.neoforge.content;

import dev.mineagent.runtime.core.objects.*;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import java.util.UUID;

/** Actual transferred stack, not a copied visual projectile. Gameplay/scoring remains in package scripts. */
public final class RuntimeThrownItemEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> ITEM=SynchedEntityData.defineId(RuntimeThrownItemEntity.class,EntityDataSerializers.ITEM_STACK);
    private final InterpolationHandler interpolation=new InterpolationHandler(this,3);
    private RuntimeItemBinding binding;private RuntimeMesh mesh;private UUID thrower;private String transfer="PREPARING";
    private int pickupDelay=40,collisions,usedTicks;private double launchSpeed,previousX,previousY,previousZ;
    public RuntimeThrownItemEntity(EntityType<? extends RuntimeThrownItemEntity> type,Level level){super(type,level);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){builder.define(ITEM,ItemStack.EMPTY);}
    public ItemStack item(){return entityData.get(ITEM).copy();}
    public RuntimeItemBinding binding(){return binding;}public String transferState(){return transfer;}public UUID thrower(){return thrower;}
    public int collisions(){return collisions;}public int usedTicks(){return usedTicks;}public double launchSpeed(){return launchSpeed;}
    public double previousX(){return previousX;}public double previousY(){return previousY;}public double previousZ(){return previousZ;}
    void prepare(ItemStack stack,UUID actor,int used,ObjectPhysics.Motion motion){if(stack.getCount()!=1)throw new IllegalArgumentException("ITEM_TRANSFER_COUNT");entityData.set(ITEM,stack.copy());refresh();if(binding==null||!mesh.physics().dynamic()||mesh.collision().width()>1||mesh.collision().height()>1||mesh.collision().depth()>1)throw new IllegalArgumentException("ITEM_THROW_MODEL");thrower=actor;usedTicks=used;launchSpeed=Math.sqrt(motion.x()*motion.x()+motion.y()*motion.y()+motion.z()*motion.z());setDeltaMovement(motion.x(),motion.y(),motion.z());}
    void transferred(){transfer="ACTIVE";needsSync=true;}
    private void refresh(){binding=RuntimeItem.binding(entityData.get(ITEM));mesh=binding==null?null:binding.mesh();refreshDimensions();bounds();}
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(key.equals(ITEM))refresh();}
    @Override public EntityDimensions getDimensions(Pose pose){return mesh==null?super.getDimensions(pose):EntityDimensions.scalable((float)Math.max(mesh.collision().width(),mesh.collision().depth()),(float)mesh.collision().height());}
    @Override public void setPos(double x,double y,double z){super.setPos(x,y,z);bounds();}
    private void bounds(){if(mesh!=null){var b=mesh.collision();setBoundingBox(new AABB(getX()-b.width()/2,getY(),getZ()-b.depth()/2,getX()+b.width()/2,getY()+b.height(),getZ()+b.depth()/2));}}
    @Override public InterpolationHandler getInterpolation(){return interpolation;}
    @Override public boolean hurtServer(ServerLevel level,net.minecraft.world.damagesource.DamageSource source,float amount){return false;}
    @Override public void tick(){
        super.tick();if(level().isClientSide()){interpolation.interpolate();bounds();return;}
        if(entityData.get(ITEM).isEmpty()){discard();return;}if(pickupDelay>0)pickupDelay--;
        previousX=getX();previousY=getY();previousZ=getZ();
        if(!transfer.equals("ACTIVE")||binding==null||!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(level().getServer())||!WorldContentRuntime.get(level().getServer()).flightActive(this)){if(getDeltaMovement().lengthSqr()>0){setDeltaMovement(Vec3.ZERO);needsSync=true;}return;}
        if(!level().getChunkSource().hasChunk(blockPosition().getX()>>4,blockPosition().getZ()>>4))return;
        var p=mesh.physics();var velocity=getDeltaMovement();if(onGround()&&velocity.lengthSqr()<1e-10&&!level().noCollision(this,getBoundingBox().move(0,-.01,0)))return;
        var motion=ObjectPhysics.gravity(new ObjectPhysics.Motion(velocity.x,velocity.y,velocity.z),p);
        int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(motion.x()),Math.max(Math.abs(motion.y()),Math.abs(motion.z())))/.5));
        for(int i=0;i<steps;i++){
            Vec3 wanted=new Vec3(motion.x()/steps,motion.y()/steps,motion.z()/steps),before=position();move(MoverType.SELF,wanted);var actual=position().subtract(before);
            boolean x=Math.abs(wanted.x-actual.x)>1e-6,y=Math.abs(wanted.y-actual.y)>1e-6,z=Math.abs(wanted.z-actual.z)>1e-6;
            if(x||y||z){collisions++;double incomingY=motion.y();motion=ObjectPhysics.bounce(motion,x,y,z,new RuntimeMesh.Physics(true,p.mass(),p.gravity(),p.restitution(),1));motion=new ObjectPhysics.Motion(motion.x(),ObjectPhysics.settleVertical(motion.y(),incomingY,y,p),motion.z());}
        }
        motion=ObjectPhysics.bounce(motion,false,false,false,p);setDeltaMovement(motion.x(),motion.y(),motion.z());needsSync=true;bounds();
    }
    @Override public void playerTouch(Player player){
        if(!(player instanceof ServerPlayer p)||!level().getServer().isSameThread()||level().getServer().getPlayerList().getPlayer(p.getUUID())!=p||!transfer.equals("ACTIVE")||pickupDelay>0||thrower==null||!thrower.equals(p.getUUID())||!p.isAlive()||p.isSpectator()||p.level()!=level()||!p.getBoundingBox().inflate(.1).intersects(getBoundingBox()))return;
        var stack=entityData.get(ITEM);if(stack.getCount()!=1)return;transfer="PICKING";
        try{p.getInventory().add(stack);if(stack.isEmpty()){transfer="COLLECTED";p.take(this,1);discard();}else transfer="ACTIVE";p.inventoryMenu.broadcastFullState();}
        catch(Exception unknown){transfer="PICKUP_UNKNOWN";dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Runtime item pickup outcome unknown entity={}",getUUID());}
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){out.store("Item",ItemStack.CODEC,entityData.get(ITEM));out.putString("Transfer",transfer);out.putString("Thrower",thrower==null?"":thrower.toString());out.putInt("PickupDelay",pickupDelay);out.putInt("UsedTicks",usedTicks);out.putDouble("LaunchSpeed",launchSpeed);out.putInt("Collisions",collisions);}
    @Override protected void readAdditionalSaveData(ValueInput in){entityData.set(ITEM,in.read("Item",ItemStack.CODEC).orElse(ItemStack.EMPTY));refresh();String state=in.getStringOr("Transfer","UNKNOWN");transfer=state.equals("ACTIVE")?"ACTIVE":"UNKNOWN";try{thrower=UUID.fromString(in.getStringOr("Thrower",""));}catch(Exception invalid){transfer="UNKNOWN";}pickupDelay=Math.max(0,Math.min(40,in.getIntOr("PickupDelay",40)));usedTicks=in.getIntOr("UsedTicks",0);launchSpeed=in.getDoubleOr("LaunchSpeed",0);collisions=in.getIntOr("Collisions",0);}
}
