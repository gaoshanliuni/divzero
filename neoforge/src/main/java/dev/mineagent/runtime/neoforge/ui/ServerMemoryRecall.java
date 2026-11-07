package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.core.memory.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Versioned semantic recall. Indexing is independent from the response and never holds the server thread. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime")
public final class ServerMemoryRecall {
 private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
 private static final Semaphore INDEX_SLOTS=new Semaphore(2);
 private record Key(MinecraftServer server,UUID world,UUID owner,UUID agent,String base,String model){}
 private record Query(Key scope,String text){}
 private record Scope(Key key,Path db,BooleanSupplier permit){
  String model(){return key.base()+"|"+key.model();}
  boolean current(){var server=key.server();if(server.isSameThread())return valid();try{return server.submit(this::valid).get(5,TimeUnit.SECONDS);}catch(Exception e){return false;}}
  private boolean valid(){var p=key.server().getPlayerList().getPlayer(key.owner());var values=MineAgentRuntimeServices.config(key.server()).snapshot().values();return p!=null&&permit.getAsBoolean()&&ServerChatAccess.canContinue(p,key.agent())&&MineAgentRuntimeServices.worldId(key.server()).equals(key.world())&&values.getOrDefault("provider.openai.baseUrl","").equals(key.base())&&values.getOrDefault("provider.openai.role.embedding","").strip().equals(key.model());}
 }
 private static final Set<Key> INDEXING=ConcurrentHashMap.newKeySet();
 private static final Map<Key,Long> RETRY_AFTER=new ConcurrentHashMap<>();
 private static final Map<Query,double[]> QUERIES=Collections.synchronizedMap(new LinkedHashMap<>());
 @net.neoforged.bus.api.SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){INDEXING.removeIf(key->key.server()==event.getServer());RETRY_AFTER.keySet().removeIf(key->key.server()==event.getServer());synchronized(QUERIES){QUERIES.keySet().removeIf(query->query.scope().server()==event.getServer());}}
 private static Scope scope(MinecraftServer server,UUID owner,UUID agent,BooleanSupplier permit){var values=MineAgentRuntimeServices.config(server).snapshot().values();return new Scope(new Key(server,MineAgentRuntimeServices.worldId(server),owner,agent,values.getOrDefault("provider.openai.baseUrl",""),values.getOrDefault("provider.openai.role.embedding","").strip()),server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),permit);}
 private static List<SemanticMemoryIndex.Document> documents(Scope s)throws Exception{
  var docs=new ArrayList<SemanticMemoryIndex.Document>();var k=s.key();
  try(var memory=new DialogueMemoryStore(s.db(),k.world(),k.owner(),k.agent(),Clock.systemUTC())){for(var e:memory.activeEntries())docs.add(new SemanticMemoryIndex.Document("dialogue",e.id(),e.revision(),e.kind()+" "+e.topic()+" "+e.value()+" "+String.join(" ",e.aliases()),e.updatedAt(),e.source()));}
  for(var e:MineAgentRuntimeServices.memories(k.server()).visibleTo(k.owner(),false))docs.add(new SemanticMemoryIndex.Document("shared",e.memoryId(),e.revision(),e.kind()+" "+e.key()+" "+e.value(),e.updatedAtEpochMillis(),"world_note"));return docs;
 }
 private static SemanticMemoryIndex indexStore(Scope s)throws Exception{return new SemanticMemoryIndex(s.db(),s.key().world(),s.key().owner(),s.key().agent());}
 public static void changed(MinecraftServer server,UUID owner,UUID agent){var player=server.getPlayerList().getPlayer(owner);if(player==null)return;var s=scope(server,owner,agent,()->server.getPlayerList().getPlayer(owner)==player);if(!s.key().model().isBlank())index(s);}
 public static CompletableFuture<String> recall(MinecraftServer server,UUID owner,UUID agent,String query,int budget,BooleanSupplier permit){
  var s=scope(server,owner,agent,permit);if(s.key().model().isBlank())return context(s,query,budget,Map.of(),"LEXICAL_ONLY");
  index(scope(server,owner,agent,()->server.getPlayerList().getPlayer(owner)!=null));var key=new Query(s.key(),query);double[] cached=QUERIES.get(key);
  CompletableFuture<double[]> embedding=cached!=null?CompletableFuture.completedFuture(cached):embed(s,List.of(query)).thenApply(vectors->{if(vectors.size()!=1)throw new IllegalStateException("MEMORY_QUERY_VECTOR_COUNT");var vector=vectors.getFirst();if(s.current())synchronized(QUERIES){QUERIES.put(key,vector);while(QUERIES.size()>128)QUERIES.remove(QUERIES.keySet().iterator().next());}return vector;});
  // Timeout applies to this dependent future; the original response may still populate the scoped cache.
  return embedding.thenApply(vector->vector).completeOnTimeout(null,1500,TimeUnit.MILLISECONDS).exceptionally(error->null).thenCompose(vector->{
   if(vector==null)return context(s,query,budget,Map.of(),"LEXICAL_FALLBACK_VECTOR_UNAVAILABLE");
   return CompletableFuture.supplyAsync(()->{try(var index=indexStore(s)){if(!s.current())throw new IllegalStateException("MEMORY_CONTEXT_CHANGED");var docs=documents(s);return SemanticMemoryIndex.similarities(docs,index.vectors(docs,s.model()),vector);}catch(Exception e){throw new CompletionException(e);}},IO).thenCompose(scores->context(s,query,budget,scores,scores.isEmpty()?"LEXICAL_WHILE_INDEXING":"HYBRID_KEYWORD_SEMANTIC"));
  });
 }
 private static CompletableFuture<String> context(Scope s,String query,int budget,Map<String,Double> scores,String mode){return CompletableFuture.supplyAsync(()->{var k=s.key();try(var memory=new DialogueMemoryStore(s.db(),k.world(),k.owner(),k.agent(),Clock.systemUTC())){if(!s.current())throw new IllegalStateException("MEMORY_CONTEXT_CHANGED");String body=memory.context(query,budget,scores)+MineAgentRuntimeServices.memories(k.server()).context(k.owner(),query,2000,scores);return body.isEmpty()?"":"[记忆检索方式："+mode+"；向量仅用于排序，不是事实证明。]\n"+body;}catch(Exception e){throw new CompletionException(e);}},IO);}
 private static CompletableFuture<List<double[]>> embed(Scope s,List<String> inputs){
  return CompletableFuture.supplyAsync(()->{if(!s.current())throw new IllegalStateException("MEMORY_CONTEXT_CHANGED");return true;},IO).thenCompose(ignored->MineAgentRuntimeServices.worker(s.key().server()).embedMemory(MineAgentRuntimeServices.config(s.key().server()),s.key().world(),s.key().agent(),inputs,s::current)).thenApply(reply->{if(!s.current()||!s.key().model().equals(reply.payload().get("requestedModel")))throw new IllegalStateException("MEMORY_EMBEDDING_MODEL_CHANGED");return vectors(reply);});
 }
 private static void index(Scope s){
  var key=s.key();if(key.model().isBlank()||System.currentTimeMillis()<RETRY_AFTER.getOrDefault(key,0L)||!INDEXING.add(key))return;
  CompletableFuture.runAsync(()->{boolean acquired=false;try{
   INDEX_SLOTS.acquire();acquired=true;
   while(s.current()){
    List<SemanticMemoryIndex.Document> missing;try(var index=indexStore(s)){var docs=documents(s);var existing=index.vectors(docs,s.model());missing=docs.stream().filter(d->!existing.containsKey(d.origin()+"_"+d.id())).sorted(Comparator.comparingLong(SemanticMemoryIndex.Document::updatedAt).reversed()).limit(8).toList();}
    if(missing.isEmpty())break;var values=embed(s,missing.stream().map(SemanticMemoryIndex.Document::text).toList()).get(90,TimeUnit.SECONDS);if(values.size()!=missing.size())throw new IllegalStateException("MEMORY_VECTOR_COUNT");if(!s.current())break;
    var fresh=documents(s);try(var index=indexStore(s)){for(int i=0;i<missing.size();i++){var d=missing.get(i);if(fresh.stream().anyMatch(v->v.id().equals(d.id())&&v.origin().equals(d.origin())&&v.revision()==d.revision()))index.save(d,s.model(),values.get(i));}}
   }
   RETRY_AFTER.remove(key);
  }catch(Exception e){RETRY_AFTER.put(key,System.currentTimeMillis()+30000);if(e instanceof InterruptedException)Thread.currentThread().interrupt();}finally{if(acquired)INDEX_SLOTS.release();INDEXING.remove(key);}},IO);
 }
 private static List<double[]> vectors(dev.mineagent.runtime.api.worker.WorkerEnvelope reply){if(!reply.type().equals("embedding.result")||!(reply.payload().get("vectors") instanceof List<?> rows))throw new IllegalStateException("MEMORY_EMBEDDING_UNAVAILABLE");var out=new ArrayList<double[]>();for(var row:rows){if(!(row instanceof List<?> values))throw new IllegalArgumentException("MEMORY_VECTOR_FORMAT");var vector=new double[values.size()];for(int i=0;i<vector.length;i++){if(!(values.get(i) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("MEMORY_VECTOR_VALUE");vector[i]=n.doubleValue();}out.add(vector);}return out;}
 private ServerMemoryRecall(){}
}
