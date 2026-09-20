package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;

/** Independent conservation/trajectory checks; never spawns a flight or returns a ball to inventory. */
public final class ConversationThrowItemSmokeServer {
    public static volatile int observedUseTicks;
    public static volatile int request; // 1 short hold, 2 long hold, 3 revoke during charge, 4 done
    private static UUID instance,activation;private static Vec3 origin;private static int phase,tick,fullAt,delay;
    private static RuntimeThrownItemEntity flight;private static double shortSpeed;private static String binding;
    private static final List<Map<String,Object>> throwsSeen=new ArrayList<>();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static void save(MinecraftServer s,String name,Object value)throws Exception{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("runtime-item-smoke")).resolve(name+".json"),JSON.writeValueAsString(value));}
    private static List<RuntimeThrownItemEntity> flights(ServerPlayer p){var values=new ArrayList<RuntimeThrownItemEntity>();for(var e:p.level().getAllEntities())if(e instanceof RuntimeThrownItemEntity f&&!f.isRemoved())values.add(f);return values;}
    private static int inventory(ServerPlayer p){int count=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(MineAgentRegistries.RUNTIME_ITEM.get()))count+=p.getInventory().getItem(i).getCount();return count;}
    public static void begin(MinecraftServer s,ServerPlayer p,UUID selected,UUID approved)throws Exception{
        instance=selected;activation=approved;origin=p.position();var b=RuntimeItem.binding(p.getMainHandItem());if(b==null||b.chargeTicks()!=40||inventory(p)!=1||!flights(p).isEmpty())throw new IllegalStateException("THROW_INITIAL_ITEM");binding=p.getMainHandItem().get(MineAgentRegistries.RUNTIME_ITEM_BINDING.get());p.connection.teleport(origin.x,origin.y,origin.z,0,-10);request=1;tick=s.getTickCount();phase=1;save(s,"throw-initial",Map.of("chargeTicks",b.chargeTicks(),"count",inventory(p),"fixtureCreatesFlights",false));
    }
    public static void tick(MinecraftServer s,ServerPlayer p)throws Exception{
        observedUseTicks=p.isUsingItem()?p.getTicksUsingItem():0;var runtime=WorldContentRuntime.get(s);if(s.getTickCount()%20==0&&flight!=null)save(s,"flight-progress",Map.of("phase",phase,"position",List.of(flight.getX(),flight.getY(),flight.getZ()),"collisions",flight.collisions(),"entityTicks",flight.tickCount,"removed",flight.isRemoved(),"transfer",flight.transferState(),"chunkLoaded",p.level().getChunkSource().hasChunk(flight.blockPosition().getX()>>4,flight.blockPosition().getZ()>>4)));if(s.getTickCount()-tick>1400)throw new IllegalStateException("THROW_NATIVE_TIMEOUT_"+phase);
        if(phase==1||phase==3){
            var list=flights(p);if(list.isEmpty())return;if(list.size()!=1||inventory(p)!=0)throw new IllegalStateException("THROW_DUPLICATED_STACK");flight=list.getFirst();
            if(!flight.transferState().equals("ACTIVE")||!flight.item().get(MineAgentRegistries.RUNTIME_ITEM_BINDING.get()).equals(binding)||flight.item().getCount()!=1)throw new IllegalStateException("THROW_BINDING_CHANGED");
            int held=flight.usedTicks();if(phase==1&&(held<3||held>16)||phase==3&&held<25)throw new IllegalStateException("THROW_SERVER_CHARGE_TIME");
            if(phase==1)shortSpeed=flight.launchSpeed();else if(flight.launchSpeed()<=shortSpeed+.15)throw new IllegalStateException("THROW_CHARGE_DID_NOT_CHANGE_SPEED");
            throwsSeen.add(Map.of("entity",flight.getUUID(),"usedTicks",held,"launchSpeed",flight.launchSpeed(),"handCount",inventory(p),"flightCount",flight.item().getCount()));save(s,"throws",throwsSeen);p.connection.teleport(origin.x+6,origin.y,origin.z-3,60,0);phase=phase==1?2:4;delay=s.getTickCount();return;
        }
        if(phase==2||phase==4){
            if(flight.isRemoved())throw new IllegalStateException("THROW_LOST_BEFORE_PICKUP");if(flight.collisions()==0||s.getTickCount()-delay<50)return;
            if(phase==2&&fullAt==0){for(int i=0;i<36;i++)p.getInventory().setItem(i,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64));p.inventoryMenu.broadcastFullState();fullAt=s.getTickCount();}
            p.connection.teleport(flight.getX(),flight.getY(),flight.getZ(),0,0);
            if(phase==2&&s.getTickCount()-fullAt<8)return;
            if(phase==2){if(flight.isRemoved()||flight.item().getCount()!=1||inventory(p)!=0)throw new IllegalStateException("THROW_FULL_INVENTORY_LOSS");save(s,"full-inventory",Map.of("flightCount",flight.item().getCount(),"inventoryCount",inventory(p),"collisions",flight.collisions()));for(int i=0;i<36;i++)p.getInventory().setItem(i,net.minecraft.world.item.ItemStack.EMPTY);p.inventoryMenu.broadcastFullState();}
            save(s,"collision-"+phase,Map.of("collisions",flight.collisions(),"position",List.of(flight.getX(),flight.getY(),flight.getZ())));phase=phase==2?5:6;return;
        }
        if(phase==5||phase==6){
            if(!flight.isRemoved()){p.connection.teleport(flight.getX(),flight.getY(),flight.getZ(),0,0);return;}
            if(inventory(p)!=1||!flights(p).isEmpty()||!binding.equals(p.getMainHandItem().get(MineAgentRegistries.RUNTIME_ITEM_BINDING.get())))throw new IllegalStateException("THROW_PICKUP_CONSERVATION");
            save(s,"pickup-"+phase,Map.of("inventoryCount",1,"flightCount",0,"bindingPreserved",true,"mode","OWNER_NATIVE_COLLISION_NOT_GIVE"));p.connection.teleport(origin.x,origin.y,origin.z,0,-10);request=phase==5?2:3;phase=phase==5?3:7;delay=s.getTickCount();return;
        }
        if(phase==7){if(!p.isUsingItem()||p.getTicksUsingItem()<8)return;runtime.disable(p,activation);delay=s.getTickCount();phase=8;return;}
        if(phase==8&&s.getTickCount()-delay>20){if(p.isUsingItem()||inventory(p)!=1||!flights(p).isEmpty())throw new IllegalStateException("THROW_REVOKED_CHARGE_EXECUTED");save(s,"result",Map.of("status","REAL_GENERATED_CHARGED_ITEM_NATIVE_VERIFIED","throws",throwsSeen,"collisionVerified",true,"fullInventoryRetained",true,"originalStackReturned",true,"revokedChargeCancelled",true,"systemInputInjected",false,"fixtureMovesPlayerForPickup",true));request=4;ConversationRuntimeItemSmokeServer.verified=true;}
    }
}
