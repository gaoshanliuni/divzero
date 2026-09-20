package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.api.scoreboard.WorldBoardFrame;
import dev.mineagent.runtime.client.webui.WorldBoardInbox;
import dev.mineagent.runtime.neoforge.network.WorldBoardPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import java.util.*;
/** Per-viewer vanilla TextDisplay carriers, never server entities or gameplay state. No global scoreboard broadcast. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldBoardClient {
    private record Owned(Display.TextDisplay entity,WorldBoardFrame.Board data){}
    private static final WorldBoardInbox inbox=new WorldBoardInbox();private static final Map<UUID,Owned> shown=new LinkedHashMap<>();
    private static List<WorldBoardFrame.Board> desired=List.of();private static ClientLevel level;private static Object peer;private static long lastPacket;
    private static int nextId=-2_000_000_000;
    private static final Map<UUID,Long> failedRevisions=new HashMap<>();
    @SubscribeEvent public static void register(RegisterClientPayloadHandlersEvent event){event.register(WorldBoardPayload.TYPE,(packet,context)->{
        var source=context.connection();context.enqueueWork(()->{
            var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=source||mc.player==null||mc.level==null)return;
            ensureContext(mc);if(!inbox.accept(source,mc.player.getUUID(),mc.level.dimension().identifier().toString(),packet.frame()))return;
            desired=packet.frame().boards();lastPacket=System.nanoTime();reconcile();
        });
    });}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();ensureContext(mc);if(level==null)return;
        if(System.nanoTime()-lastPacket>10_000_000_000L)desired=List.of();reconcile();}
    private static void ensureContext(Minecraft mc){
        Object current=mc.getConnection()==null?null:mc.getConnection().getConnection();
        if(peer!=current){clear();peer=current;}
        if(level!=mc.level){removeAll();failedRevisions.clear();desired=List.of();level=mc.level;}
    }
    private static void reconcile(){
        if(level==null)return;var ids=desired.stream().map(WorldBoardFrame.Board::viewId).collect(java.util.stream.Collectors.toSet());
        failedRevisions.keySet().removeIf(id->!ids.contains(id));
        for(var id:List.copyOf(shown.keySet()))if(!ids.contains(id))remove(id);
        for(var board:desired){
            if(Objects.equals(failedRevisions.get(board.viewId()),board.revision()))continue;
            var old=shown.get(board.viewId());
            if(old!=null&&old.data().equals(board)&&level.getEntity(old.entity().getId())==old.entity())continue;
            if(!level.hasChunkAt(net.minecraft.core.BlockPos.containing(board.x(),board.y(),board.z()))){remove(board.viewId());continue;}
            try{
                var entity=old==null||level.getEntity(old.entity().getId())!=old.entity()?new Display.TextDisplay(EntityType.TEXT_DISPLAY,level):old.entity();
                var tag=tag(board);entity.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),tag));entity.setNoGravity(true);
                ((dev.mineagent.runtime.neoforge.mixin.client.WorldBoardTextAccess)entity).mineagent$setText(Component.literal(board.text()).withColor(0xeff2e9));
                if(old==null||entity!=old.entity()){
                    while(level.getEntity(nextId)!=null)nextId--;
                    entity.setId(nextId--);entity.setUUID(UUID.nameUUIDFromBytes(("mineagent-world-board:"+board.viewId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));level.addEntity(entity);
                }
                shown.put(board.viewId(),new Owned(entity,board));
            }catch(RuntimeException invalid){remove(board.viewId());failedRevisions.put(board.viewId(),board.revision());dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("WORLD_BOARD_RENDER_FAILED view={} code={}",board.viewId(),invalid.getClass().getSimpleName());}
        }
    }
    private static CompoundTag tag(WorldBoardFrame.Board b){
        var tag=new CompoundTag();var pos=new ListTag();pos.add(DoubleTag.valueOf(b.x()));pos.add(DoubleTag.valueOf(b.y()));pos.add(DoubleTag.valueOf(b.z()));tag.put("Pos",pos);
        var rotation=new ListTag();rotation.add(FloatTag.valueOf(b.yaw()));rotation.add(FloatTag.valueOf(0));tag.put("Rotation",rotation);
        tag.store("text",ComponentSerialization.CODEC,Component.literal(b.text()).withColor(0xeff2e9));tag.putInt("line_width",400);tag.putByte("text_opacity",(byte)255);
        tag.putInt("background",0xb81b221d);tag.putBoolean("default_background",false);tag.putBoolean("shadow",true);tag.putString("alignment","left");tag.putString("billboard","fixed");
        var transformation=new CompoundTag();transformation.put("translation",floats(0,0,0));transformation.put("scale",floats(b.scale(),b.scale(),b.scale()));transformation.put("left_rotation",floats(0,0,0,1));transformation.put("right_rotation",floats(0,0,0,1));tag.put("transformation",transformation);
        var light=new CompoundTag();light.putInt("block",15);light.putInt("sky",15);tag.put("brightness",light);tag.putFloat("view_range",2);tag.putBoolean("NoGravity",true);
        return tag;
    }
    private static ListTag floats(float...values){var list=new ListTag();for(float v:values)list.add(FloatTag.valueOf(v));return list;}
    private static void remove(UUID id){var old=shown.remove(id);if(old!=null&&level!=null&&level.getEntity(old.entity().getId())==old.entity())level.removeEntity(old.entity().getId(),Entity.RemovalReason.DISCARDED);}
    private static void removeAll(){for(var id:List.copyOf(shown.keySet()))remove(id);}
    public static void clear(){removeAll();failedRevisions.clear();desired=List.of();level=null;peer=null;inbox.clear();lastPacket=0;nextId=-2_000_000_000;}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){clear();}
    public static Map<UUID,WorldBoardFrame.Board> snapshot(){var result=new LinkedHashMap<UUID,WorldBoardFrame.Board>();shown.forEach((k,v)->result.put(k,v.data()));return Map.copyOf(result);}
    public static Display.TextDisplay entity(UUID id){var v=shown.get(id);return v==null?null:v.entity();}
}
