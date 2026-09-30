package dev.mineagent.runtime.neoforge.client.body;

import com.fasterxml.jackson.databind.JsonNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Runs only options explicitly carried by the current server-authorized takeover frame. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeBoostInput {
    private static Object session,level;private static LocalPlayer body;
    private static String operation="";private static int hopAt=-1;private static double floor;
    public static void reset(){session=level=body=null;operation="";hopAt=-1;}
    public static void prepare(JsonNode frame,LocalPlayer player){
        if(!AutonomousBodyClient.active()||!player.isAlive()||player.isPassenger()||player.isInWater())return;
        var mc=Minecraft.getInstance();var current=AutonomousBodyClient.cameraSession();
        if(session!=current||body!=player||level!=mc.level){reset();session=current;body=player;level=mc.level;}
        String id=frame.path("operation").asText();if(id.equals(operation))return;operation=id;
        if(frame.path("boostMicroHop").asBoolean()&&player.onGround()&&player.level().noCollision(player,player.getBoundingBox().expandTowards(0,.25,0))){
            floor=player.getY();hopAt=player.tickCount;player.setPos(player.getX(),player.getY()+.25,player.getZ());player.setOnGround(false);
        }
        if(frame.path("boostCritical").asBoolean()&&player.level().noCollision(player,player.getBoundingBox().expandTowards(0,.0625,0))){
            player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(),player.getY()+.0625,player.getZ(),false,player.horizontalCollision));
            player.connection.send(new ServerboundMovePlayerPacket.Pos(player.getX(),player.getY(),player.getZ(),false,player.horizontalCollision));
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Pre event){
        var mc=Minecraft.getInstance();
        if(body==null)return;if(!AutonomousBodyClient.active()||session!=AutonomousBodyClient.cameraSession()||mc.player!=body||mc.level!=level||!body.isAlive()){reset();return;}
        if(hopAt>=0&&body.tickCount-hopAt>=2){double dy=floor-body.getY();var box=body.getBoundingBox().move(0,dy,0);
            if(Math.abs(dy)<.6&&body.level().noCollision(body,box)&&!body.level().noCollision(body,box.move(0,-.05,0)))body.setPos(body.getX(),floor,body.getZ());hopAt=-1;
        }
    }
    private NativeBoostInput(){}
}
