package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.objects.RuntimeMesh;
import dev.mineagent.runtime.core.building.BuildingDocument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Data-only scenes: parsing/serialization off-thread, native block shapes sampled across ticks. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ServerPreviews {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final ExecutorService IO=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"mineagent-preview");t.setDaemon(true);return t;});
 private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
 private record Scene(UUID owner,String json,long expires){}
 private record Data(String title,List<double[]> vertices,List<int[]> faces,String detail,JsonNode nativeSource){Data(String title,List<double[]> vertices,List<int[]> faces,String detail){this(title,vertices,faces,detail,null);}}
 private record Shape(int color,List<AABB> boxes){}
 private interface Job {boolean step();void cancel();}
 private static final class State {final Map<UUID,Scene> scenes=new LinkedHashMap<>();final ArrayDeque<Job> jobs=new ArrayDeque<>();boolean closed;}
 private static State state(MinecraftServer s){return STATES.computeIfAbsent(s,k->new State());}
 public static Map<String,String> read(ServerPlayer p,Map<String,String> args){
  UUID id=UUID.fromString(args.get("previewId"));int offset=Integer.parseInt(args.getOrDefault("offset","0"));var scene=state(p.level().getServer()).scenes.get(id);
  if(scene==null||!scene.owner.equals(p.getUUID())||scene.expires<System.currentTimeMillis()||offset<0||offset>scene.json.length())throw new IllegalArgumentException("PREVIEW_EXPIRED_OR_SCOPE");
  int end=Math.min(offset+24000,scene.json.length());return Map.of("chunk",scene.json.substring(offset,end),"nextOffset",end<scene.json.length()?Integer.toString(end):"-1");
 }
 private static Data mesh(String title,RuntimeMesh mesh){return new Data(title,mesh.vertices().stream().map(v->new double[]{v.x(),v.y(),v.z()}).toList(),mesh.triangles().stream().map(t->new int[]{t.a(),t.b(),t.c(),t.color()}).toList(),"模型几何 / 颜色");}
 private static void current(ServerPlayer p,UUID agent,Object level,State state,BooleanSupplier permit){if(state.closed||!permit.getAsBoolean()||p.level()!=level||p.level().getServer().getPlayerList().getPlayer(p.getUUID())!=p||(agent!=null&&!dev.mineagent.runtime.neoforge.task.ServerTaskStart.allowed(p,agent)))throw new IllegalStateException("PREVIEW_CONTEXT_CHANGED");}
 public static CompletableFuture<Map<String,Object>> open(ServerPlayer p,UUID agent,JsonNode args,BooleanSupplier permit){return open(p,agent,args,permit,null);}
 public static CompletableFuture<Map<String,Object>> open(ServerPlayer p,UUID agent,JsonNode args,BooleanSupplier permit,UUID conversation){
  var server=p.level().getServer();var level=p.level();var state=state(server);
  try{
   current(p,agent,level,state,permit);String display=args.path("display").asText("open");if(!Set.of("open","chat").contains(display))throw new IllegalArgumentException("PREVIEW_DISPLAY");String kind=args.path("kind").asText("building");CompletableFuture<Data> generated;
   if(kind.equals("recipe")){
    var stack=NativeMenuData.preview(p,args.path("recipe_id").asText());var encoded=net.minecraft.world.item.ItemStack.CODEC.encodeStart(p.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),stack).getOrThrow();var nativeSource=JSON.createObjectNode().put("kind","item");nativeSource.set("stack",JSON.readTree(encoded.toString()));generated=CompletableFuture.completedFuture(new Data(stack.getHoverName().getString(),List.of(),List.of(),"药水配方产物预览",nativeSource));
   }else if(kind.equals("entity")){
    var entity=level.getEntity(UUID.fromString(args.path("entity_id").asText()));if(entity==null||entity.distanceToSqr(p)>128*128)throw new IllegalArgumentException("PREVIEW_ENTITY_NOT_NEARBY");
    var nativeSource=JSON.createObjectNode().put("kind","entity").put("entityId",entity.getUUID().toString()).put("entityType",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    generated=CompletableFuture.completedFuture(new Data(entity.getName().getString(),List.of(),List.of(),"原生实体预览",nativeSource));
   }else if(kind.equals("creature")){var species=dev.mineagent.runtime.neoforge.content.RuntimeCreatures.get(server).get(p.getUUID(),UUID.fromString(args.path("species_id").asText())).orElseThrow(()->new SecurityException("PREVIEW_SPECIES_NOT_OWNED"));if(args.has("expected_revision")&&species.revision()!=args.path("expected_revision").asLong())throw new IllegalStateException("PREVIEW_STALE");var definition=species.definition();generated=CompletableFuture.supplyAsync(()->mesh(definition.name(),RuntimeMesh.parse(definition.model())),IO);
   }else if(kind.equals("package_model")){var runtime=ServerPackageRuntime.get(server);var pack=runtime.ownedPackage(p.getUUID(),UUID.fromString(args.path("package_id").asText()),args.path("expected_revision").asLong()).orElseThrow(()->new SecurityException("PREVIEW_PACKAGE_NOT_OWNED_OR_STALE"));var definition=pack.definitions().get(UUID.fromString(args.path("definition_id").asText()));if(definition==null)throw new IllegalArgumentException("PREVIEW_DEFINITION");String path=args.path("model_path").asText();var contentStore=runtime.worldContent();generated=CompletableFuture.supplyAsync(()->{try{return mesh(definition.name(),dev.mineagent.runtime.core.objects.RuntimeModelBundle.load(pack,definition,path,contentStore).mesh());}catch(Exception e){throw new CompletionException(e);}},IO);
   }else if(kind.equals("item")){
    int slot=args.has("slot")?args.path("slot").asInt(-1):p.getInventory().getSelectedSlot();if(slot<0||slot>=p.getInventory().getContainerSize())throw new IllegalArgumentException("PREVIEW_ITEM_SLOT");
    var stack=p.getInventory().getItem(slot);if(stack.isEmpty())throw new IllegalArgumentException("PREVIEW_ITEM_EMPTY");var binding=dev.mineagent.runtime.neoforge.content.RuntimeItem.binding(stack);String title=stack.getHoverName().getString();
    if(binding!=null)generated=CompletableFuture.supplyAsync(()->mesh(title,binding.mesh()),IO);
    else {var encoded=net.minecraft.world.item.ItemStack.CODEC.encodeStart(p.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),stack).getOrThrow();var nativeSource=JSON.createObjectNode().put("kind","item");nativeSource.set("stack",JSON.readTree(encoded.toString()));generated=CompletableFuture.completedFuture(new Data(title,List.of(),List.of(),"原生物品预览",nativeSource));}
   }else if(kind.equals("model")){
    if(!args.path("source").isTextual())throw new IllegalArgumentException("PREVIEW_MODEL_SOURCE");String source=args.path("source").asText();generated=CompletableFuture.supplyAsync(()->mesh("模型预览",RuntimeMesh.parse(source)),IO);
   }else if(kind.equals("building")&&!args.has("file_id")){
    generated=worldPreview(p,agent,args,state,permit);
   }else if(kind.equals("building")){
    generated=ServerBuildingFiles.previewSource(p,agent,args).thenCompose(source->{var result=new CompletableFuture<Data>();server.execute(()->{
     if(state.closed){result.completeExceptionally(new IllegalStateException("PREVIEW_CONTEXT_CHANGED"));return;}
     state.jobs.add(new Job(){
      final BuildingDocument.Bounds bounds=(BuildingDocument.Bounds)source.get("bounds");
      @SuppressWarnings("unchecked") final List<BuildingDocument.Block> cells=(List<BuildingDocument.Block>)source.get("cells");
      final List<double[]> vertices=new ArrayList<>();final List<int[]> faces=new ArrayList<>();final Map<String,Shape> shapes=new HashMap<>();int index,missing;
      public void cancel(){result.completeExceptionally(new IllegalStateException("PREVIEW_CONTEXT_CHANGED"));}
      public boolean step(){try{
       current(p,agent,level,state,permit);boolean simple=(Boolean)source.get("simplified");
       if(simple)addBox(vertices,faces,0,0,0,bounds.size().get(0),bounds.size().get(1),bounds.size().get(2),0x556bb9cd);
       else {long deadline=System.nanoTime()+1500000;for(int n=0;n<128&&index<cells.size();n++,index++){
        var cell=cells.get(index);double x=cell.pos().x()-bounds.min().x(),y=cell.pos().y()-bounds.min().y(),z=cell.pos().z()-bounds.min().z();
        Shape shape=shapes.get(cell.state());if(shape==null){try{var block=BlockStateParser.parseForBlock(p.registryAccess().lookupOrThrow(Registries.BLOCK),cell.state(),false).blockState();int color=0xff000000|block.getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO).col;var boxes=block.getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO).toAabbs();shape=new Shape(color==0xff000000&&boxes.isEmpty()?0x8869c8ec:color,boxes.isEmpty()?List.of(new AABB(0,0,0,1,1,1)):boxes);}catch(Exception e){shape=new Shape(0xffde4bce,List.of(new AABB(0,0,0,1,1,1)));missing++;}shapes.put(cell.state(),shape);}
        for(var b:shape.boxes)addBox(vertices,faces,x+b.minX,y+b.minY,z+b.minZ,x+b.maxX,y+b.maxY,z+b.maxZ,shape.color);
        if(System.nanoTime()>=deadline){index++;break;}
       }}
       if(!simple&&index<cells.size())return false;
       result.complete(new Data(source.get("title").toString(),vertices,faces,simple?"范围预览：选区过大，请选择较小范围查看细节":"结构预览 / 近似材质"+(missing>0?" / 紫色表示未解析状态":"")));return true;
      }catch(Exception e){result.completeExceptionally(e);return true;}}
     });
    });return result;});
   }else throw new IllegalArgumentException("PREVIEW_KIND");
   return generated.thenApplyAsync(data->{try{var sceneData=new LinkedHashMap<String,Object>();sceneData.put("title",data.title);sceneData.put("vertices",data.vertices);sceneData.put("faces",data.faces);sceneData.put("detail",data.detail);if(data.nativeSource!=null)sceneData.put("nativeSource",data.nativeSource);String json=JSON.writeValueAsString(sceneData);if(json.length()>8*1024*1024)throw new IllegalArgumentException("PREVIEW_SCENE_SIZE");return Map.entry(data,json);}catch(Exception e){throw new CompletionException(e);}},IO).thenCompose(pair->{var result=new CompletableFuture<Map<String,Object>>();server.execute(()->{try{
    current(p,agent,level,state,permit);var table=state.scenes;table.values().removeIf(s->s.expires<System.currentTimeMillis());while(table.values().stream().filter(s->s.owner.equals(p.getUUID())).count()>=4){UUID oldest=table.entrySet().stream().filter(e->e.getValue().owner.equals(p.getUUID())).findFirst().orElseThrow().getKey();table.remove(oldest);}
    UUID id=UUID.randomUUID();table.put(id,new Scene(p.getUUID(),pair.getValue(),System.currentTimeMillis()+1800000));
    if(display.equals("chat")){var offer=JSON.createObjectNode().put("text","预览："+pair.getKey().title);offer.putArray("buttons").addObject().put("label","打开预览").put("action","preview").put("value",id.toString());ServerConversations.get(server).richMessage(p,agent,conversation,UUID.randomUUID(),offer);}else show(p,id);
    result.complete(Map.of("status",display.equals("chat")?"PREVIEW_OFFERED":"PREVIEW_OPENED","previewId",id,"vertices",pair.getKey().vertices.size(),"triangles",pair.getKey().faces.size(),"worldChanged",false,"renderConfirmation","CLIENT_REQUIRED"));
   }catch(Exception e){result.completeExceptionally(e);}});return result;});
  }catch(Exception e){return CompletableFuture.failedFuture(e);}
 }
 private static CompletableFuture<Data> worldPreview(ServerPlayer player,UUID agent,JsonNode args,State state,BooleanSupplier permit){
  var result=new CompletableFuture<Data>();var level=player.level();if(!args.path("dimension").asText(level.dimension().identifier().toString()).equals(level.dimension().identifier().toString()))throw new IllegalArgumentException("PREVIEW_DIMENSION");
  var min=point(args.path("min"));var max=point(args.path("max"));for(int axis=0;axis<3;axis++)if(max.get(axis)<min.get(axis)||(long)max.get(axis)-min.get(axis)>=2048)throw new IllegalArgumentException("PREVIEW_BOUNDS");
  if(!level.isInWorldBounds(new BlockPos(min.get(0),min.get(1),min.get(2)))||!level.isInWorldBounds(new BlockPos(max.get(0),max.get(1),max.get(2))))throw new IllegalArgumentException("PREVIEW_WORLD_BOUNDS");
  int sx=max.get(0)-min.get(0)+1,sy=max.get(1)-min.get(1)+1,sz=max.get(2)-min.get(2)+1;long volume=(long)sx*sy*sz;
  if(volume>2_000_000){var vertices=new ArrayList<double[]>();var faces=new ArrayList<int[]>();addBox(vertices,faces,0,0,0,sx,sy,sz,0x556bb9cd);return CompletableFuture.completedFuture(new Data("现场建筑范围",vertices,faces,"选区过大，仅显示范围；请选择较小区域查看材质细节"));}
  state.jobs.add(new Job(){long cursor;final com.fasterxml.jackson.databind.node.ArrayNode cells=JSON.createArrayNode();
   public void cancel(){result.completeExceptionally(new IllegalStateException("PREVIEW_CONTEXT_CHANGED"));}
   public boolean step(){try{current(player,agent,level,state,permit);long deadline=System.nanoTime()+1500000;for(int count=0;count<512&&cursor<volume;count++,cursor++){
    int x=(int)(cursor%sx),y=(int)(cursor/sx%sy),z=(int)(cursor/(sx*(long)sy));var pos=new BlockPos(min.get(0)+x,min.get(1)+y,min.get(2)+z);if(!level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4))throw new IllegalArgumentException("PREVIEW_CHUNK_UNLOADED");var block=level.getBlockState(pos);
    if(!block.isAir()){if(cells.size()>=8192)throw new IllegalArgumentException("PREVIEW_DETAIL_LIMIT");var cell=cells.addObject().put("state",BlockStateParser.serialize(block));cell.putArray("position").add(x).add(y).add(z);}if(System.nanoTime()>=deadline){cursor++;break;}
   }if(cursor<volume)return false;var source=JSON.createObjectNode().put("kind","blocks");source.set("blocks",cells);source.putArray("size").add(sx).add(sy).add(sz);result.complete(new Data("现场建筑预览",List.of(),List.of(),cells.isEmpty()?"选区没有非空气方块":"现场结构快照 / 原生材质 / 不复制容器内容",source));return true;}catch(Exception error){result.completeExceptionally(error);return true;}}
  });return result;
 }
 private static List<Integer> point(JsonNode node){if(!node.isArray()||node.size()!=3)throw new IllegalArgumentException("PREVIEW_BOUNDS");var result=new ArrayList<Integer>();for(var value:node){if(!value.isIntegralNumber()||!value.canConvertToInt()||Math.abs(value.longValue())>30_000_000)throw new IllegalArgumentException("PREVIEW_BOUNDS");result.add(value.intValue());}return result;}
 public static int show(ServerPlayer player,UUID id){
  read(player,Map.of("previewId",id.toString(),"offset","0"));
  net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new dev.mineagent.runtime.neoforge.network.UiPayloads.Event(UUID.randomUUID(),"previewOpen","{\"previewId\":\""+id+"\"}"));return 1;
 }
 @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){var state=STATES.get(event.getServer());if(state==null)return;long deadline=System.nanoTime()+4000000;int count=state.jobs.size();while(count-->0&&!state.jobs.isEmpty()){var job=state.jobs.removeFirst();if(!job.step())state.jobs.addLast(job);if(System.nanoTime()>=deadline)break;}}
 @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent event){var state=STATES.remove(event.getServer());if(state!=null){state.closed=true;for(var job:state.jobs)job.cancel();state.jobs.clear();state.scenes.clear();}}
 private static void addBox(List<double[]> v,List<int[]> f,double x0,double y0,double z0,double x1,double y1,double z1,int color){if(v.size()>200000)throw new IllegalArgumentException("PREVIEW_DETAIL_LIMIT");int b=v.size();v.addAll(List.of(new double[]{x0,y0,z0},new double[]{x1,y0,z0},new double[]{x1,y1,z0},new double[]{x0,y1,z0},new double[]{x0,y0,z1},new double[]{x1,y0,z1},new double[]{x1,y1,z1},new double[]{x0,y1,z1}));int[][] sides={{0,3,2,1},{4,5,6,7},{0,4,7,3},{1,2,6,5},{3,7,6,2},{0,1,5,4}};for(var q:sides){f.add(new int[]{b+q[0],b+q[1],b+q[2],color});f.add(new int[]{b+q[0],b+q[2],b+q[3],color});}}
 private ServerPreviews(){}
}
