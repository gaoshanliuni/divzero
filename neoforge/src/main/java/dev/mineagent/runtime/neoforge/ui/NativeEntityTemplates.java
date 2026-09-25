package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.mineagent.runtime.core.creature.NativeEntityDefinition;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.integration.TwilightBossInterop;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;

/** Template ownership is separate from the original registered type. Native saving keeps live boss state. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class NativeEntityTemplates {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String NAMESPACE="native_entity_templates_v1", OWNER="mineagent_native_owner", TEMPLATE="mineagent_native_template", REVISION="mineagent_native_revision", GROUP="mineagent_native_group";
    private static final Map<MinecraftServer,State> LIVE=new IdentityHashMap<>();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    public record Template(UUID id, UUID owner, long revision, String source) {public NativeEntityDefinition definition(){return NativeEntityDefinition.parse(source);}}
    private static final class State implements AutoCloseable {
        final UUID world;final SqliteRuntimeRepository db;final Map<UUID,Template> rows=new LinkedHashMap<>();final Set<UUID> writing=new HashSet<>();boolean closed;
        State(MinecraftServer s)throws Exception {
            world=MineAgentRuntimeServices.worldId(s);db=new SqliteRuntimeRepository(s.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"));
            for(var row:db.list(world,NAMESPACE)){var t=JSON.readValue(row.payload(),Template.class);t.definition();rows.put(t.id(),new Template(t.id(),t.owner(),row.revision(),t.source()));}
        }
        public void close()throws Exception{closed=true;db.close();}
    }
    private static State state(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("NATIVE_ENTITY_SERVER_THREAD");return LIVE.computeIfAbsent(server,s->{try{return new State(s);}catch(Exception e){throw new IllegalStateException("NATIVE_ENTITY_STORE",e);}});}
    private static void authority(ServerPlayer p){if(!p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("NATIVE_ENTITY_GAMEMASTER_REQUIRED");}
    private static EntityType<?> type(String id){var key=Identifier.parse(id);if(!BuiltInRegistries.ENTITY_TYPE.containsKey(key))throw new IllegalArgumentException("NATIVE_ENTITY_MOD_NOT_INSTALLED");var type=BuiltInRegistries.ENTITY_TYPE.getValue(key);if(!type.canSummon()||!type.canSerialize()||id.equals("minecraft:player"))throw new IllegalArgumentException("NATIVE_ENTITY_TYPE_NOT_PERSISTENT_SUMMONABLE");return type;}
    private static int offset(JsonNode n){if(!n.has("offset"))return 0;if(!n.get("offset").isIntegralNumber()||!n.get("offset").canConvertToInt()||n.get("offset").intValue()<0)throw new IllegalArgumentException("NATIVE_ENTITY_OFFSET");return n.get("offset").intValue();}
    public static Map<String,Object> inspect(ServerPlayer p,JsonNode a){
        int offset=offset(a);String query=a.path("query").asText("").toLowerCase(Locale.ROOT);var s=state(p.level().getServer());
        var types=BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(Object::toString).filter(k->k.contains(query)).sorted().toList();
        var templates=s.rows.values().stream().filter(t->t.owner().equals(p.getUUID())&&(t.definition().name().toLowerCase(Locale.ROOT).contains(query)||t.definition().type().contains(query))).toList();
        var instances=new ArrayList<Map<String,Object>>();for(var e:p.level().getAllEntities())if(owned(p,e))instances.add(observe(e));
        var out=new LinkedHashMap<String,Object>();out.put("contract",NativeEntityDefinition.CONTRACT);out.put("twilightBosses",TwilightBossInterop.catalog());
        if(a.has("template_id")){var t=ownedTemplate(p,UUID.fromString(a.path("template_id").asText()));out.put("template",t);}
        out.put("types",types.stream().skip(offset).limit(24).map(k->{var t=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(k));return Map.of("type",k,"summonable",t.canSummon(),"persistent",t.canSerialize());}).toList());out.put("nextTypeOffset",offset+24<types.size()?offset+24:-1);
        out.put("templates",templates.stream().skip(offset).limit(8).map(t->Map.of("id",t.id(),"name",t.definition().name(),"type",t.definition().type(),"revision",t.revision())).toList());out.put("nextTemplateOffset",offset+8<templates.size()?offset+8:-1);
        out.put("instances",instances.stream().skip(offset).limit(16).toList());out.put("nextInstanceOffset",offset+16<instances.size()?offset+16:-1);out.put("instanceScope","CURRENT_DIMENSION_LOADED");return out;
    }
    public static Map<String,Object> derive(ServerPlayer p,JsonNode a)throws Exception {
        Entity e=ServerEntityInterop.target(p,a.path("entity_id").asText());if(!(e instanceof Mob mob))throw new IllegalArgumentException("NATIVE_ENTITY_MOB_REQUIRED");type(TwilightBossInterop.id(e));
        var n=JSON.createObjectNode().put("name",a.path("name").asText("派生"+e.getName().getString())).put("type",TwilightBossInterop.id(e));
        var attributes=n.putObject("attributes");for(var h:BuiltInRegistries.ATTRIBUTE.listElements().toList()){var i=mob.getAttribute(h);if(i!=null)attributes.put(h.key().identifier().toString(),i.getBaseValue());}
        var equipment=n.putObject("equipment");for(var slot:EquipmentSlot.values()){var item=mob.getItemBySlot(slot);if(!item.isEmpty())equipment.set(slot.getName(),JSON.readTree(ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,p.registryAccess()),item).getOrThrow().toString()));}
        var def=NativeEntityDefinition.parse(n.toString());return Map.of("source",def.source(),"sourceEntity",e.getUUID(),"mode","FRESH_NATIVE_BEHAVIOR","notCopied",List.of("UUID/world references","current phase/targets/passengers","private live state"),"next","define_native_entity then control_native_entity spawn","contract",NativeEntityDefinition.CONTRACT);
    }
    private record Patch(Map<AttributeInstance,Double> attributes, Map<EquipmentSlot,ItemStack> equipment){}
    private static Patch validate(ServerPlayer p,Mob mob,NativeEntityDefinition def){
        var attrs=new LinkedHashMap<AttributeInstance,Double>();for(var f:def.attributes().entrySet()){
            var id=Identifier.parse(f.getKey());if(!BuiltInRegistries.ATTRIBUTE.containsKey(id))throw new IllegalArgumentException("NATIVE_ENTITY_ATTRIBUTE_UNKNOWN");var h=BuiltInRegistries.ATTRIBUTE.wrapAsHolder(BuiltInRegistries.ATTRIBUTE.getValue(id));var instance=mob.getAttribute(h);
            if(instance==null||h.value().sanitizeValue(f.getValue())!=f.getValue())throw new IllegalArgumentException("NATIVE_ENTITY_ATTRIBUTE_RANGE");attrs.put(instance,f.getValue());
        }
        var equipment=new EnumMap<EquipmentSlot,ItemStack>(EquipmentSlot.class);for(var f:def.equipment().entrySet())equipment.put(EquipmentSlot.byName(f.getKey()),ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,p.registryAccess()),JsonParser.parseString(f.getValue())).getOrThrow());return new Patch(attrs,equipment);
    }
    private static void apply(Mob mob,NativeEntityDefinition def,Patch patch,boolean fresh){
        patch.attributes().forEach(AttributeInstance::setBaseValue);patch.equipment().forEach(mob::setItemSlot);mob.setCustomName(Component.literal(def.name()));mob.setNoAi(def.noAi());mob.setPersistenceRequired();mob.setHealth(fresh?mob.getMaxHealth():Math.min(mob.getHealth(),mob.getMaxHealth()));
    }
    public static CompletableFuture<Map<String,Object>> define(ServerPlayer p,UUID operation,JsonNode a)throws Exception{
        authority(p);var def=NativeEntityDefinition.parse(a.path("source").asText());type(def.type());var server=p.level().getServer();var s=state(server);
        UUID id=a.has("template_id")?UUID.fromString(a.path("template_id").asText()):operation;var old=s.rows.get(id);long expected=a.path("expected_revision").asLong(0);
        if(a.has("expected_revision")&&(!a.get("expected_revision").isIntegralNumber()||!a.get("expected_revision").canConvertToLong()||expected<0||expected==Long.MAX_VALUE))throw new IllegalArgumentException("NATIVE_ENTITY_REVISION");
        if(old!=null&&!old.owner().equals(p.getUUID()))throw new SecurityException("NATIVE_ENTITY_NOT_OWNED");if(expected!=(old==null?0:old.revision()))throw new IllegalStateException("NATIVE_ENTITY_STALE");
        if(old!=null&&!old.definition().type().equals(def.type()))throw new IllegalArgumentException("NATIVE_ENTITY_TYPE_IMMUTABLE");
        if(!s.writing.add(id))throw new IllegalStateException("NATIVE_ENTITY_UPDATE_PENDING");
        var next=new Template(id,p.getUUID(),expected+1,def.source());var result=new CompletableFuture<Map<String,Object>>();
        CompletableFuture.supplyAsync(()->{try{return s.db.compareAndSet(s.world,NAMESPACE,id.toString(),expected,JSON.writeValueAsString(next),System.currentTimeMillis());}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((saved,error)->server.execute(()->{
            s.writing.remove(id);if(error!=null||s.closed){result.complete(Map.of("status","UNKNOWN","error","NATIVE_ENTITY_TEMPLATE_WRITE_UNKNOWN","replayAllowed",false));return;}
            if(!saved.accepted()){result.complete(Map.of("status","REJECTED","error","NATIVE_ENTITY_STALE"));return;}s.rows.put(id,next);result.complete(Map.of("status","APPLIED","template",next,"spawned",false,"existingInstancesChanged",false,"validation","Registry and schema checked; native attributes/equipment validated before spawn/apply"));
        }));return result;
    }
    private static Template ownedTemplate(ServerPlayer p,UUID id){var t=state(p.level().getServer()).rows.get(id);if(t==null||!t.owner().equals(p.getUUID()))throw new SecurityException("NATIVE_ENTITY_TEMPLATE_NOT_OWNED");return t;}
    private static boolean owned(ServerPlayer p,Entity e){return e.getPersistentData().getStringOr(OWNER,"").equals(p.getUUID().toString())&&!e.getPersistentData().getStringOr(TEMPLATE,"").isEmpty();}
    private static Vec3 position(ServerPlayer p,JsonNode n){
        if(!n.isArray()||n.size()!=3)throw new IllegalArgumentException("NATIVE_ENTITY_POSITION");for(var v:n)if(!v.isNumber()||!Double.isFinite(v.asDouble()))throw new IllegalArgumentException("NATIVE_ENTITY_POSITION");
        var pos=new Vec3(n.get(0).asDouble(),n.get(1).asDouble(),n.get(2).asDouble());var b=BlockPos.containing(pos);if(p.distanceToSqr(pos)>4096*4096||!p.level().hasChunkAt(b)||!p.level().getWorldBorder().isWithinBounds(b)||b.getY()<p.level().getMinY()||b.getY()>p.level().getMaxY())throw new IllegalArgumentException("NATIVE_ENTITY_POSITION_UNLOADED");return pos;
    }
    public static Map<String,Object> control(ServerPlayer p,UUID operation,JsonNode a)throws Exception {
        authority(p);String action=a.path("action").asText();
        if(action.equals("spawn"))return spawn(p,operation,a);
        Entity target=ServerEntityInterop.root(ServerEntityInterop.target(p,a.path("entity_id").asText()));if(!(target instanceof Mob mob)||!owned(p,mob))throw new SecurityException("NATIVE_ENTITY_NOT_OWNED");
        if(action.equals("remove")){mob.discard();return Map.of("status","APPLIED","removed",mob.getUUID(),"summonedChildrenRemoved",false);}
        if(!action.equals("apply_template"))throw new IllegalArgumentException("NATIVE_ENTITY_ACTION");
        var template=ownedTemplate(p,UUID.fromString(mob.getPersistentData().getStringOr(TEMPLATE,"")));if(!a.path("expected_revision").isIntegralNumber()||a.path("expected_revision").asLong(-1)!=template.revision())throw new IllegalStateException("NATIVE_ENTITY_STALE");
        if(!TwilightBossInterop.id(mob).equals(template.definition().type()))throw new IllegalStateException("NATIVE_ENTITY_TYPE_CHANGED");
        apply(mob,template.definition(),validate(p,mob,template.definition()),false);mob.getPersistentData().putLong(REVISION,template.revision());return Map.of("status","APPLIED","entity",observe(mob),"instanceRecreated",false);
    }
    private static Map<String,Object> spawn(ServerPlayer p,UUID operation,JsonNode a)throws Exception {
        var t=ownedTemplate(p,UUID.fromString(a.path("template_id").asText()));var d=t.definition();var type=type(d.type());var at=position(p,a.path("position"));
        if(a.has("encounter")&&!a.get("encounter").isBoolean())throw new IllegalArgumentException("NATIVE_ENTITY_ENCOUNTER");
        if(!type.isAllowedInPeaceful()&&p.level().getDifficulty()==net.minecraft.world.Difficulty.PEACEFUL)throw new IllegalArgumentException("NATIVE_ENTITY_PEACEFUL");
        int count=a.path("encounter").asBoolean(true)?TwilightBossInterop.boss(d.type()).map(TwilightBossInterop.Boss::groupSize).orElse(1):1;
        if(d.type().equals("twilightforest:knight_phantom"))for(var e:p.level().getAllEntities())if(TwilightBossInterop.id(e).equals(d.type())&&e.isAlive()&&e.distanceToSqr(at)<160*160)throw new IllegalStateException("NATIVE_ENTITY_KNIGHT_GROUP_NEARBY");
        var prepared=new ArrayList<Mob>();var added=new ArrayList<Mob>();
        try {
            for(int i=0;i<count;i++){
                var pos=count==1?at:at.add(Math.cos(i*2*Math.PI/count)*4,0,Math.sin(i*2*Math.PI/count)*4);position(p,JSON.valueToTree(List.of(pos.x,pos.y,pos.z)));
                Entity e=type.create(p.level(),EntitySpawnReason.COMMAND);if(!(e instanceof Mob mob)){if(e!=null)e.discard();throw new IllegalArgumentException("NATIVE_ENTITY_MOB_REQUIRED");}prepared.add(mob);mob.snapTo(pos.x,pos.y,pos.z,p.getYRot(),0);
                var patch=validate(p,mob,d);mob.finalizeSpawn(p.level(),p.level().getCurrentDifficultyAt(mob.blockPosition()),EntitySpawnReason.COMMAND,null);
                if(mob.isSpawnCancelled()||mob.isRemoved())throw new IllegalStateException("NATIVE_ENTITY_SPAWN_CANCELLED");
                TwilightBossInterop.initialize(mob,BlockPos.containing(at),i);apply(mob,d,patch,true);
                var data=mob.getPersistentData();data.putString(OWNER,p.getUUID().toString());data.putString(TEMPLATE,t.id().toString());data.putLong(REVISION,t.revision());data.putString(GROUP,operation.toString());
                if(!p.level().noCollision(mob))throw new IllegalStateException("NATIVE_ENTITY_COLLISION");
            }
            for(var mob:prepared){
                if(!p.level().tryAddFreshEntityWithPassengers(mob))throw new IllegalStateException("NATIVE_ENTITY_ADD_FAILED");
                // tryAddFreshEntityWithPassengers only checks duplicate IDs. A NeoForge join
                // listener may reject a root/passenger even when that helper returns true.
                if(mob.getSelfAndPassengers().anyMatch(e->p.level().getEntity(e.getUUID())!=e||e.isRemoved()))throw new IllegalStateException("NATIVE_ENTITY_JOIN_REJECTED");
                added.add(mob);
            }
            return Map.of("status","APPLIED","templateId",t.id(),"nativeType",d.type(),"entities",added.stream().map(NativeEntityTemplates::observe).toList(),"group",operation,"behavior","ORIGINAL_NATIVE_CLASS","fullCombatVerified",false);
        } catch(Exception error){
            // finalizeSpawn / third-party join hooks can have side effects. Never report a safe replay.
            var observed=new ArrayList<Map<String,Object>>();for(var mob:prepared)if(p.level().getEntity(mob.getUUID())==mob)observed.add(observe(mob));else mob.discard();
            return Map.of("status",observed.isEmpty()?"UNKNOWN":"PARTIAL","error",Objects.toString(error.getMessage(),"NATIVE_ENTITY_SPAWN_FAILED"),"entities",observed,"replayAllowed",false);
        }
    }
    public static Map<String,Object> observe(Entity e){
        var out=new LinkedHashMap<String,Object>();out.put("entity_id",e.getUUID());out.put("type",TwilightBossInterop.id(e));out.put("class",e.getClass().getName());out.put("name",e.getName().getString());out.put("position",List.of(e.getX(),e.getY(),e.getZ()));out.put("parts",e.getParts()==null?0:e.getParts().length);
        var data=e.getPersistentData();out.put("owner",data.getStringOr(OWNER,""));out.put("template_id",data.getStringOr(TEMPLATE,""));out.put("template_revision",data.getLongOr(REVISION,0));out.put("group",data.getStringOr(GROUP,""));out.put("adapter",TwilightBossInterop.inspect(e));
        if(e instanceof Mob m){out.put("no_ai",m.isNoAi());out.put("health",m.getHealth());out.put("maxHealth",m.getMaxHealth());out.put("tickCount",m.tickCount);}return out;
    }
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e)throws Exception{var s=LIVE.remove(e.getServer());if(s!=null)s.close();}
    private NativeEntityTemplates(){}
}
