package dev.mineagent.runtime.neoforge.content;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.mineagent.runtime.core.packages.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.chunk.ChunkGenerator;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads actual post-load registries and ServerLevel generators. It does not create dimensions, teleport players or generate terrain. */
public final class WorldReopenVerifier {
    private WorldReopenVerifier(){}
    public static Set<String> bootPaths(DataPackPlan plan){var result=new HashSet<String>();var registries=net.neoforged.neoforge.registries.DataPackRegistriesHooks.getDataPackRegistriesWithDimensions().toList();for(String path:plan.files().keySet())if(path.startsWith("data/")&&path.endsWith(".json")){int split=path.indexOf('/',5);String resource=path.substring(split+1);for(var data:registries)if(resource.startsWith(data.key().identifier().getPath()+"/"))result.add(path);}return Set.copyOf(result);}
    public static void noRemoval(DataPackPlan next,List<DataPackInstallStore.Artifact> old,List<String> selected){for(var artifact:old)if(selected.contains("file/"+artifact.filename())){var prior=DataPackPlan.inspect(artifact.manifest());if(!prior.reopensWorld())throw new IllegalStateException("WORLD_REOPEN_MIXED_MIGRATION_REQUIRED");if(!bootPaths(next).containsAll(bootPaths(prior)))throw new IllegalStateException("WORLD_REOPEN_REMOVAL_REQUIRES_MIGRATION");}}
    public static Map<String,Integer> verify(MinecraftServer server,DataPackPlan plan,DataPackPlan.Snapshot snapshot)throws Exception{
        var ops=RegistryOps.create(JsonOps.INSTANCE,server.registryAccess());int entries=0,dimensions=0;
        for(String path:bootPaths(plan)){
            int split=path.indexOf('/',5);String namespace=path.substring(5,split),resource=path.substring(split+1);boolean checked=false;
            var json=JsonParser.parseString(new String(snapshot.files().get(path),StandardCharsets.UTF_8));
            for(var data:net.neoforged.neoforge.registries.DataPackRegistriesHooks.getDataPackRegistriesWithDimensions().toList()){
                String prefix=data.key().identifier().getPath()+"/";if(!resource.startsWith(prefix))continue;
                var id=Identifier.fromNamespaceAndPath(namespace,resource.substring(prefix.length(),resource.length()-5));verifyRegistry(server,ops,data,id,json);entries++;checked=true;
                if(data.key().equals(Registries.LEVEL_STEM)){
                    LevelStem wanted=LevelStem.CODEC.parse(ops,json).getOrThrow();var level=server.getLevel(Registries.levelStemToLevel(ResourceKey.create(Registries.LEVEL_STEM,id)));if(level==null)throw new IllegalStateException("WORLD_REOPEN_DIMENSION_MISSING");
                    if(!ChunkGenerator.CODEC.encodeStart(ops,wanted.generator()).getOrThrow().equals(ChunkGenerator.CODEC.encodeStart(ops,level.getChunkSource().getGenerator()).getOrThrow())||!DimensionType.DIRECT_CODEC.encodeStart(ops,wanted.type().value()).getOrThrow().equals(DimensionType.DIRECT_CODEC.encodeStart(ops,level.dimensionType()).getOrThrow()))throw new IllegalStateException("WORLD_REOPEN_DIMENSION_VALUE_MISMATCH");
                    if(wanted.seedOverride().isPresent()&&level.getSeed()!=wanted.seedOverride().getAsLong())throw new IllegalStateException("WORLD_REOPEN_DIMENSION_SEED_MISMATCH");dimensions++;
                }
                break;
            }
            if(!checked)throw new IllegalStateException("WORLD_REOPEN_REGISTRY_UNVERIFIABLE");
        }
        return Map.of("registryFiles",entries,"dimensions",dimensions);
    }
    private static <T> void verifyRegistry(MinecraftServer server,RegistryOps<JsonElement> ops,RegistryDataLoader.RegistryData<T> data,Identifier id,JsonElement json){
        var actual=server.registryAccess().lookupOrThrow(data.key()).getValue(id);if(actual==null)throw new IllegalStateException("WORLD_REOPEN_REGISTRY_MISSING");
        var wanted=data.elementCodec().parse(ops,json).getOrThrow();if(!data.elementCodec().encodeStart(ops,wanted).getOrThrow().equals(data.elementCodec().encodeStart(ops,actual).getOrThrow()))throw new IllegalStateException("WORLD_REOPEN_REGISTRY_VALUE_MISMATCH");
    }
}
