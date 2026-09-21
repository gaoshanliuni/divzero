package dev.mineagent.runtime.neoforge.content;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.objects.*;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** One generic persistent Native carrier. Gameplay belongs to the owning RuntimePackage/instance. */
public final class RuntimeObjectEntity extends Entity {
    private static final EntityDataAccessor<String> HEADER=SynchedEntityData.defineId(RuntimeObjectEntity.class,EntityDataSerializers.STRING);
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Header(UUID instance,String part,String asset,RuntimeMesh.Collision collision,RuntimeMesh.Physics physics){public Header{Objects.requireNonNull(instance);Objects.requireNonNull(collision);Objects.requireNonNull(physics);if(part==null||!part.matches("[A-Za-z0-9_.-]{1,64}")||asset==null||!asset.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("OBJECT_BINDING");}}
    private Header header;private String modelPath="";private Vec3 springAnchor;private double stiffness,damping;private boolean runtimeActive;private int collisions;
    private final InterpolationHandler interpolation=new InterpolationHandler(this,3);
    public RuntimeObjectEntity(EntityType<? extends RuntimeObjectEntity> type,Level level){super(type,level);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){builder.define(HEADER,"");}
    public Header header(){return header;}public String modelPath(){return modelPath;}public boolean runtimeActive(){return runtimeActive;}public int collisions(){return collisions;}
    public void bind(UUID instance,String part,String model,RuntimeModelBundle bundle){
        dev.mineagent.runtime.api.packages.RuntimeEntrypoint.requireRelativePath(model);modelPath=model;
        try{entityData.set(HEADER,JSON.writeValueAsString(new Header(instance,part,bundle.sha256(),bundle.mesh().collision(),bundle.mesh().physics())));}catch(Exception e){throw new IllegalArgumentException("OBJECT_BINDING",e);}
        updateHeader();
    }
    private void updateHeader(){try{String value=entityData.get(HEADER);header=value.isEmpty()?null:JSON.readValue(value,Header.class);if(header!=null){refreshDimensions();bounds();}}catch(Exception e){header=null;}}
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(key.equals(HEADER))updateHeader();}
    @Override public EntityDimensions getDimensions(Pose pose){return header==null?super.getDimensions(pose):EntityDimensions.scalable((float)Math.max(header.collision().width(),header.collision().depth()),(float)header.collision().height());}
    @Override public void setPos(double x,double y,double z){super.setPos(x,y,z);bounds();}
    private void bounds(){if(header!=null){var b=header.collision();setBoundingBox(new AABB(getX()-b.width()/2,getY(),getZ()-b.depth()/2,getX()+b.width()/2,getY()+b.height(),getZ()+b.depth()/2));}}
    @Override public boolean isPickable(){return header!=null&&!header.collision().nonSolid()&&isAlive();}
    @Override public boolean isPushable(){return false;}
    @Override public boolean canBeCollidedWith(Entity other){return header!=null&&!header.collision().nonSolid()&&isAlive();}
    @Override public boolean hurtServer(ServerLevel level,net.minecraft.world.damagesource.DamageSource source,float amount){return false;}
    @Override public InterpolationHandler getInterpolation(){return interpolation;}
    public void velocity(double x,double y,double z){var v=ObjectPhysics.limit(new ObjectPhysics.Motion(x,y,z));setDeltaMovement(v.x(),v.y(),v.z());needsSync=true;}
    public void spring(double x,double y,double z,double force,double drag){ObjectPhysics.spring(new ObjectPhysics.Motion(0,0,0),new ObjectPhysics.Motion(x-getX(),y-getY(),z-getZ()),force,drag,header.physics());springAnchor=new Vec3(x,y,z);stiffness=force;damping=drag;}
    public void clearSpring(){springAnchor=null;}
    void translateManaged(Vec3 delta){if(level().isClientSide()||!Double.isFinite(delta.lengthSqr()))throw new IllegalStateException("INSTANCE_MOVE_NATIVE_CONTEXT");setPos(getX()+delta.x,getY()+delta.y,getZ()+delta.z);if(springAnchor!=null)springAnchor=springAnchor.add(delta);needsSync=true;}
    @Override public void tick(){
        super.tick();if(level().isClientSide()){interpolation.interpolate();bounds();return;}
        runtimeActive=header!=null&&dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(level().getServer())&&WorldContentRuntime.get(level().getServer()).objectActive(this);
        if(!runtimeActive||!header.physics().dynamic())return;
        if(!level().getChunkSource().hasChunk(blockPosition().getX()>>4,blockPosition().getZ()>>4))return;
        var p=header.physics();var v=getDeltaMovement();if(springAnchor==null&&onGround()&&v.lengthSqr()<1e-10&&!level().noCollision(this,getBoundingBox().move(0,-0.01,0)))return;
        var motion=ObjectPhysics.gravity(new ObjectPhysics.Motion(v.x,v.y,v.z),p);
        if(springAnchor!=null)motion=ObjectPhysics.spring(motion,new ObjectPhysics.Motion(springAnchor.x-getX(),springAnchor.y-getY(),springAnchor.z-getZ()),stiffness,damping,p);
        int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(motion.x()),Math.max(Math.abs(motion.y()),Math.abs(motion.z())))/0.5));
        for(int i=0;i<steps;i++){
            Vec3 wanted=new Vec3(motion.x()/steps,motion.y()/steps,motion.z()/steps),before=position();move(MoverType.SELF,wanted);var actual=position().subtract(before);
            boolean x=Math.abs(wanted.x-actual.x)>1e-6,y=Math.abs(wanted.y-actual.y)>1e-6,z=Math.abs(wanted.z-actual.z)>1e-6;
            if(x||y||z){collisions++;double incomingY=motion.y();motion=ObjectPhysics.bounce(motion,x,y,z,new RuntimeMesh.Physics(true,p.mass(),p.gravity(),p.restitution(),1));motion=new ObjectPhysics.Motion(motion.x(),ObjectPhysics.settleVertical(motion.y(),incomingY,y,p),motion.z());}
        }
        motion=ObjectPhysics.bounce(motion,false,false,false,p);setDeltaMovement(motion.x(),motion.y(),motion.z());needsSync=true;bounds();
    }
    @Override public InteractionResult interact(Player player,InteractionHand hand,Vec3 point){
        if(header==null||hand!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;
        if(!level().isClientSide()&&player instanceof ServerPlayer viewer&&hand==InteractionHand.MAIN_HAND&&dev.mineagent.runtime.neoforge.WorldIdentityRuntime.notifyIfPending(viewer))return WorldContentRuntime.get(level().getServer()).interactObject(this,viewer)?InteractionResult.SUCCESS:InteractionResult.PASS;
        return InteractionResult.SUCCESS;
    }
    @Override protected void readAdditionalSaveData(ValueInput input){modelPath=input.getStringOr("modelPath","");entityData.set(HEADER,input.getStringOr("objectBinding",""));updateHeader();if(header!=null&&input.getBooleanOr("spring",false))try{spring(input.getDoubleOr("anchorX",getX()),input.getDoubleOr("anchorY",getY()),input.getDoubleOr("anchorZ",getZ()),input.getDoubleOr("stiffness",0),input.getDoubleOr("damping",0));}catch(IllegalArgumentException invalid){springAnchor=null;}}
    @Override protected void addAdditionalSaveData(ValueOutput output){output.putString("modelPath",modelPath);output.putString("objectBinding",entityData.get(HEADER));output.putBoolean("spring",springAnchor!=null);if(springAnchor!=null){output.putDouble("anchorX",springAnchor.x);output.putDouble("anchorY",springAnchor.y);output.putDouble("anchorZ",springAnchor.z);output.putDouble("stiffness",stiffness);output.putDouble("damping",damping);}}
}
