package dev.mineagent.runtime.neoforge.client;
import dev.mineagent.runtime.neoforge.network.AgentSkinPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.*;
import java.util.*;
/** A complete connection-scoped native skin projection; no downloaded image or executable content. */
public final class AgentSkinClient {
    private static Object wire;private static Map<UUID,AgentSkinPayload.Selection> skins=Map.of();
    private AgentSkinClient(){}
    public static void accept(AgentSkinPayload value,Object connection){var mc=Minecraft.getInstance();if(mc.getConnection()!=null&&mc.getConnection().getConnection()==connection){wire=connection;skins=value.skins();}}
    public static PlayerSkin skin(UUID id){var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=wire){skins=Map.of();wire=null;return null;}var choice=skins.get(id);if(choice==null)return null;if(choice.skin().equals("player")){var info=mc.getConnection().getPlayerInfo(choice.player());return info==null?net.minecraft.client.resources.DefaultPlayerSkin.get(choice.player()):info.getSkin();}var parts=choice.skin().split(":");return new PlayerSkin(new ClientAsset.ResourceTexture(Identifier.withDefaultNamespace("entity/player/"+parts[1]+"/"+parts[0])),null,null,parts[1].equals("slim")?PlayerModelType.SLIM:PlayerModelType.WIDE,true);}
    public static boolean selected(int entityId){var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.getEntity(entityId)!=null&&skin(mc.level.getEntity(entityId).getUUID())!=null;}
}
