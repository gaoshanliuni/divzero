package dev.mineagent.runtime.neoforge.client.objects;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.mineagent.runtime.core.interaction.EntityPartReplacement;
import dev.mineagent.runtime.neoforge.mixin.client.EntityModelPartsAccess;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.*;
import java.util.*;

/** Separate textured submissions, never swaps shared cube lists or changes a server entity. */
public final class EntityPartModels {
    private static final ContextKey<Plan> KEY=new ContextKey<>(Identifier.fromNamespaceAndPath("mineagent_runtime","entity_part_replacements"));
    private record Source(Model<?> model,Identifier texture,Map<String,ModelPart> parts){}
    private record GeometryKey(Model<?> model,String part){}
    private record Attachment(String target,EntityPartReplacement spec,Geometry geometry,Identifier texture){}
    private record Plan(List<Attachment> attachments,List<String> errors){}
    public record Draw(EntityVisualClient.Meta meta,String target,String sourceType,String sourcePart,String texture){}
    private static final class Geometry extends Model<Draw>{Geometry(ModelPart root){super(root,RenderTypes::entityCutout);}public void setupAnim(Draw state){}}
    private static final Map<String,Entity> PROXIES=new HashMap<>();
    private static final Map<GeometryKey,Geometry> GEOMETRY=new HashMap<>();
    private static final Map<String,Model<?>> MODELS=new HashMap<>();
    private static final Map<String,Map<String,Object>> DRAWN=new HashMap<>();
    private static Object level;
    private static void session(){var l=Minecraft.getInstance().level;if(level!=l){PROXIES.clear();GEOMETRY.clear();MODELS.clear();DRAWN.clear();level=l;}}
    @SuppressWarnings({"rawtypes","unchecked"}) private static Source source(String type){
        session();var mc=Minecraft.getInstance();if(mc.level==null)throw new IllegalStateException("ENTITY_MODEL_NO_WORLD");
        Entity proxy=PROXIES.computeIfAbsent(type,k->{var id=Identifier.parse(k);if(!BuiltInRegistries.ENTITY_TYPE.containsKey(id)||k.equals("minecraft:player"))throw new IllegalArgumentException("ENTITY_MODEL_SOURCE_TYPE");var e=BuiltInRegistries.ENTITY_TYPE.getValue(id).create(mc.level,EntitySpawnReason.COMMAND);if(!(e instanceof LivingEntity)||e instanceof net.minecraft.world.entity.player.Player)throw new IllegalArgumentException("ENTITY_MODEL_LIVING_SOURCE_REQUIRED");return e;});
        var renderer=mc.getEntityRenderDispatcher().getRenderer(proxy);if(!(renderer instanceof LivingEntityRenderer living))throw new IllegalArgumentException("ENTITY_MODEL_RENDERER_UNSUPPORTED");
        var state=(LivingEntityRenderState)living.createRenderState(proxy,0);Model<?> model=living.getModel();
        // Resource reload constructs new renderer models. Never retain old baked geometry.
        var old=MODELS.put(type,model);if(old!=null&&old!=model)GEOMETRY.keySet().removeIf(k->k.model()==old);
        return new Source(model,living.getTextureLocation(state),EntityVisualClient.modelParts(model));
    }
    private static ModelPart copy(ModelPart p,boolean root){
        var access=(EntityModelPartsAccess)(Object)p;var children=new LinkedHashMap<String,ModelPart>();access.mineagent$children().forEach((k,v)->children.put(k,copy(v,false)));
        var result=new ModelPart(List.copyOf(access.mineagent$cubes()),children);result.setInitialPose(root?PartPose.ZERO:p.getInitialPose());result.resetPose();return result;
    }
    private static Geometry geometry(Source s,String path){var p=s.parts().get(path);if(p==null)throw new IllegalArgumentException("ENTITY_MODEL_SOURCE_PART_NOT_FOUND");return GEOMETRY.computeIfAbsent(new GeometryKey(s.model(),path),k->new Geometry(copy(p,true)));}
    public static void inspect(UiPayloads.Event packet){
        var out=new LinkedHashMap<String,Object>();try{
            var a=JsonParser.parseString(packet.json()).getAsJsonObject();String type=a.get("entity_type").getAsString();int offset=a.get("offset").getAsInt();if(offset<0)throw new IllegalArgumentException("ENTITY_MODEL_OFFSET");var source=source(type);
            out.put("status","CLIENT_MODEL_REFERENCE");out.put("entity_type",type);out.put("modelClass",source.model().getClass().getName());out.put("texture",source.texture().toString());
            var nodes=source.parts().entrySet().stream().map(e->Map.of("path",e.getKey(),"cubes",((EntityModelPartsAccess)(Object)e.getValue()).mineagent$cubes().size(),"pivot",List.of(e.getValue().getInitialPose().x(),e.getValue().getInitialPose().y(),e.getValue().getInitialPose().z()))).toList();
            out.put("parts",nodes.stream().skip(offset).limit(32).toList());out.put("nextOffset",offset+32<nodes.size()?offset+32:-1);out.put("spawned",false);out.put("contract",EntityPartReplacement.CONTRACT);
        }catch(Exception e){out.put("status","UNAVAILABLE");out.put("error",Objects.toString(e.getMessage(),"ENTITY_MODEL_UNAVAILABLE"));}
        if(Minecraft.getInstance().getConnection()!=null)net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new UiPayloads.Command(packet.requestId(),"entityModelInspectReply",new Gson().toJson(out)));
    }
    public static <S> void submit(SubmitNodeCollector collector,Model<? super S> model,S state,PoseStack poses,RenderType parentType,int light,int overlay,int color,int outline,ModelFeatureRenderer.CrumblingOverlay crumble){
        if(!(state instanceof LivingEntityRenderState living))return;var meta=living.getRenderData(EntityVisualClient.KEY);if(meta==null||meta.type().equals("minecraft:player"))return;
        var attachments=new ArrayList<Attachment>();var errors=new ArrayList<String>();var targetParts=EntityVisualClient.modelParts(model);
        for(var e:EntityVisualClient.replacements(meta).entrySet())try{
            if(!targetParts.containsKey(e.getKey()))throw new IllegalArgumentException("ENTITY_MODEL_TARGET_PART_NOT_FOUND");var donor=source(e.getValue().sourceType());attachments.add(new Attachment(e.getKey(),e.getValue(),geometry(donor,e.getValue().sourcePart()),donor.texture()));
        }catch(Exception ex){errors.add(e.getKey()+":"+Objects.toString(ex.getMessage(),"ENTITY_MODEL_UNAVAILABLE"));}
        living.setRenderData(KEY,new Plan(List.copyOf(attachments),List.copyOf(errors)));if(attachments.isEmpty())return;
        // setupAnim may use shared models also rendered for other entities. Both animation
        // setup and temporary tracks are restored after snapshotting attachment matrices.
        EntityVisualClient.attachmentPose(model,state,poses,()->{
            for(var a:attachments){poses.pushPose();try{
                String prefix="";boolean visible=true;for(String segment:a.target().split("/")){prefix=prefix.isEmpty()?segment:prefix+"/"+segment;var part=targetParts.get(prefix);if(!part.visible){visible=false;break;}part.translateAndRotate(poses);}if(!visible)continue;
                var tr=a.spec().translation();poses.translate(tr.get(0)/16,tr.get(1)/16,tr.get(2)/16);var rot=a.spec().rotation();poses.mulPose(Axis.XP.rotationDegrees(rot.get(0).floatValue()));poses.mulPose(Axis.YP.rotationDegrees(rot.get(1).floatValue()));poses.mulPose(Axis.ZP.rotationDegrees(rot.get(2).floatValue()));var scale=a.spec().scale();poses.scale(scale.get(0).floatValue(),scale.get(1).floatValue(),scale.get(2).floatValue());
                var type=parentType.isOutline()?RenderTypes.outline(a.texture()):parentType.hasBlending()?RenderTypes.entityTranslucentCullItemTarget(a.texture()):RenderTypes.entityCutout(a.texture());
                var draw=new Draw(meta,a.target(),a.spec().sourceType(),a.spec().sourcePart(),a.texture().toString());
                collector.submitModel(a.geometry(),draw,poses,type,light,overlay,(color&0xff000000)|0x00ffffff,null,outline,crumble);
            }finally{poses.popPose();}}
        });
    }
    public static List<String> hide(EntityRenderState state,Map<String,ModelPart> parts){
        var plan=state.getRenderData(KEY);if(plan==null)return List.of();var hidden=new ArrayList<String>();for(var a:plan.attachments()){var p=parts.get(a.target());if(p!=null){p.visible=false;hidden.add(a.target());}}return hidden;
    }
    public static boolean hasPlan(EntityRenderState state){var plan=state.getRenderData(KEY);return plan!=null&&(!plan.attachments().isEmpty()||!plan.errors().isEmpty());}
    public static List<String> errors(EntityRenderState state){var plan=state.getRenderData(KEY);return plan==null?List.of():plan.errors();}
    public static void drawn(Draw d){DRAWN.put(d.meta().entity()+":"+d.meta().part()+":"+d.target(),Map.of("target",d.target(),"sourceType",d.sourceType(),"sourcePart",d.sourcePart(),"texture",d.texture(),"ageInTicks",d.meta().age(),"status","OBSERVED_REPLACEMENT_DRAW"));}
    public static List<Map<String,Object>> draws(EntityRenderState state,EntityVisualClient.Meta meta){var plan=state.getRenderData(KEY);if(plan==null)return List.of();var rows=new ArrayList<Map<String,Object>>();for(var a:plan.attachments()){var row=DRAWN.get(meta.entity()+":"+meta.part()+":"+a.target());if(row!=null&&meta.age()-((Number)row.get("ageInTicks")).doubleValue()<4&&row.get("sourceType").equals(a.spec().sourceType())&&row.get("sourcePart").equals(a.spec().sourcePart()))rows.add(row);}return rows;}
    private EntityPartModels(){}
}
