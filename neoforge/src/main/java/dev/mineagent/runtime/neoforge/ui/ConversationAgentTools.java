package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.mineagent.runtime.core.conversation.*;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual self-player tools used by ordinary conversation. No model text is executed as Java or an OS command. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ConversationAgentTools {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{var t=new Thread(r,"mineagent-conversation-tool-journal");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<MinecraftServer,List<Work>> WORK=new IdentityHashMap<>();
    private interface Work{boolean tick()throws Exception;void cancel();}
    private ConversationAgentTools(){}
    private static boolean current(ServerPlayer p,BooleanSupplier permit){return permit.getAsBoolean()&&p.level().getServer().getPlayerList().getPlayer(p.getUUID())==p;}
    private static boolean itemPermission(ServerPlayer p){return MineAgentRuntimeServices.permissions(p.level().getServer()).allowed(p.getUUID(),p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE);}
    private static void keys(JsonNode n,String... allowed){if(!n.isObject()||!Set.of(allowed).containsAll(n.properties().stream().map(Map.Entry::getKey).toList()))throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS");}
    private static String text(JsonNode n,String key,int max){if(!n.path(key).isTextual()||n.path(key).asText().length()>max||n.path(key).asText().isBlank())throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS");return n.path(key).asText();}
    private static int number(JsonNode n,String key,int min,int max){var v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<min||v.intValue()>max)throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS");return v.intValue();}
    private static String code(Throwable e){String v=Objects.toString(e.getMessage(),"");return v.matches("[A-Z][A-Z0-9_]{1,80}")?v:"AGENT_TOOL_FAILED";}
    public static CompletableFuture<Map<String,Object>> execute(ServerPlayer p,UUID agent,UUID operation,String tool,String arguments,BooleanSupplier permit){
        var s=p.level().getServer();try{
            if(!s.isSameThread()||!current(p,permit)||!ConversationTools.NAMES.contains(tool)||arguments.length()>16384)throw new IllegalArgumentException("AGENT_TOOL_CONTEXT");
            JsonNode args=JSON.readTree(arguments);if(args==null||!args.isObject())throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS");
            if(!ConversationTools.mutation(tool)){if(tool.equals("inspect_container"))return CompletableFuture.completedFuture(ConversationNativeInteractions.inspect(p,agent,args));return read(p,tool,args,permit);}
            if(!ServerTaskStart.allowed(p,agent))throw new SecurityException("AGENT_TOOL_PERMISSION");
            if(Set.of("give_item","modify_item").contains(tool)&&!itemPermission(p))throw new SecurityException("AGENT_ITEM_PERMISSION");
            var world=MineAgentRuntimeServices.worldId(s);var level=p.level();long permission=MineAgentRuntimeServices.permissions(s).actionRevision(p.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE);
            var db=s.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");var result=new CompletableFuture<Map<String,Object>>();String intent=JSON.writeValueAsString(Map.of("state","DISPATCHING","owner",p.getUUID(),"agent",agent,"tool",tool,"arguments",args));
            CompletableFuture.runAsync(()->{try{ConversationToolJournal.save(db,world,operation,0,intent);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((v,error)->s.execute(()->{
                if(error!=null){result.completeExceptionally(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN"));return;}
                CompletableFuture<Map<String,Object>> action;
                try{if(!current(p,permit)||p.level()!=level||!ServerTaskStart.allowed(p,agent)||permission!=MineAgentRuntimeServices.permissions(s).actionRevision(p.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE))throw new IllegalStateException("AGENT_TOOL_CONTEXT_CHANGED");if(Set.of("give_item","modify_item").contains(tool)&&!itemPermission(p))throw new SecurityException("AGENT_ITEM_PERMISSION");action=mutate(p,agent,operation,tool,args,permit);}
                catch(Exception rejected){action=CompletableFuture.completedFuture(Map.of("status","REJECTED","error",code(rejected)));}
                action.whenComplete((receipt,failure)->s.execute(()->{
                    var value=failure==null?receipt:Map.<String,Object>of("status","UNKNOWN","error","AGENT_TOOL_OUTCOME_UNKNOWN");
                    ConversationInteractionSmokeServer.observe(tool,args,value);
                    ConversationRuntimeItemSmokeServer.observe(tool,value);
                    try{String encoded=JSON.writeValueAsString(Map.of("owner",p.getUUID(),"agent",agent,"tool",tool,"arguments",args,"receipt",value));CompletableFuture.runAsync(()->{try{ConversationToolJournal.save(db,world,operation,1,encoded);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((written,writeError)->s.execute(()->{if(writeError!=null||failure!=null)result.completeExceptionally(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN"));else result.complete(value);}));}
                    catch(Exception writeError){result.completeExceptionally(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN"));}
                }));
            }));return result;
        }catch(Exception invalid){return CompletableFuture.completedFuture(Map.of("status","REJECTED","error",code(invalid)));}
    }
    private static CompletableFuture<Map<String,Object>> read(ServerPlayer p,String tool,JsonNode args,BooleanSupplier permit)throws Exception{
        if(tool.equals("scan_blocks"))return scan(p,args,permit);
        if(tool.equals("web_search")||tool.equals("read_web_page"))return web(p,tool,args,permit);
        if(tool.equals("inspect_blocks"))return inspectBlocks(p,args,permit);
        if(tool.equals("inspect_blueprints"))return inspectBlueprints(p,args,permit);
        if(tool.equals("inspect_commands")){keys(args,"prefix");if(!args.path("prefix").isTextual()||args.path("prefix").asText().length()>2048)throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS");return inspectCommands(p,args.path("prefix").asText(),permit);}
        Map<String,Object> value=switch(tool){
            case "inspect_capabilities"->{keys(args);yield Map.of("readyTools",ConversationTools.NAMES,"canModifyItems",itemPermission(p),"webEnabled",Boolean.parseBoolean(MineAgentRuntimeServices.config(p.level().getServer()).snapshot().values().getOrDefault("web.enabled","true")),"pending",Map.of("create_blueprint","Read-only exported NBT adapter available for local singleplayer owner; Create runtime and schematicannon not verified","host_command","Separate local approval/execution adapter pending","dynamic_item_registry","Signed HOT package item carrier supports generated colored geometry and item.use/restyle; charging/throwing and hot FML registry IDs are not implemented","seed_map","Only actual local world inspection currently available","settings_and_music","Client settings and background audio tool adapter pending","chat_memory_write","Preference/persona UI exists; natural-language write adapter pending","memory_expiry","Realtime memory TTL and automatic expiry adapter pending"));}
            case "inspect_player"->{keys(args,"section","offset");yield player(p,text(args,"section",32),args.has("offset")?number(args,"offset",0,100000):0);}
            case "inspect_registry"->{keys(args,"kind","query","offset");yield registry(p,text(args,"kind",24),args.path("query").asText(""),args.has("offset")?number(args,"offset",0,100000):0);}
            case "inspect_world"->{keys(args);var rules=p.level().getGameRules();var result=new LinkedHashMap<String,Object>();result.put("mods",net.neoforged.fml.ModList.get().getMods().stream().limit(128).map(m->Map.of("id",m.getModId(),"version",m.getVersion().toString())).toList());result.put("minecraftVersion",net.minecraft.SharedConstants.getCurrentVersion().name());result.put("dimension",p.level().dimension().identifier().toString());result.put("gameTime",p.level().getGameTime());result.put("keep_inventory",rules.get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY));result.put("pvp",rules.get(net.minecraft.world.level.gamerules.GameRules.PVP));result.put("position",List.of(p.getX(),p.getY(),p.getZ()));result.put("biome",p.level().getBiome(p.blockPosition()).unwrapKey().map(k->k.identifier().toString()).orElse("unknown"));result.put("seed",p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)?p.level().getSeed():"PERMISSION_REQUIRED");result.put("mapScope","Current observed position only; not a generated world map");result.put("availableCommandRoots",p.level().getServer().getCommands().getDispatcher().getRoot().getChildren().stream().filter(n->n.canUse(p.createCommandSourceStack())).map(n->n.getName()).filter(n->!n.equals("ai")).sorted().toList());yield result;}

            default->throw new IllegalArgumentException("AGENT_TOOL_UNKNOWN");
        };return CompletableFuture.completedFuture(value);
    }
    private static ObjectNode encoded(ServerPlayer p,ItemStack stack)throws Exception{return (ObjectNode)JSON.readTree(ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,p.registryAccess()),stack).getOrThrow().toString());}
    private static Map<String,Object> stack(ServerPlayer p,int slot,ItemStack value)throws Exception{if(value.isEmpty())return Map.of("slot",slot,"empty",true,"stackHash","EMPTY");var encoded=encoded(p,value);String raw=encoded.toString();var result=new LinkedHashMap<String,Object>();result.put("slot",slot);result.put("item",BuiltInRegistries.ITEM.getKey(value.getItem()).toString());result.put("name",value.getHoverName().getString());result.put("count",value.getCount());result.put("stackHash",RuntimePackageCanonicalizer.sha256(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));result.put("components",raw.length()<=4096?encoded.path("components"):"TOO_LARGE_FOR_SNAPSHOT");return result;}
    static String hash(ServerPlayer p,ItemStack stack)throws Exception{return String.valueOf(stack(p,0,stack).getOrDefault("stackHash",""));}
    private static Map<String,Object> player(ServerPlayer p,String section,int offset)throws Exception{
        var result=new LinkedHashMap<String,Object>();result.put("player",p.getUUID());result.put("observedGameTick",p.level().getGameTime());
        if(section.equals("summary")){result.put("name",p.getGameProfile().name());result.put("health",p.getHealth());result.put("maxHealth",p.getMaxHealth());result.put("absorption",p.getAbsorptionAmount());result.put("food",p.getFoodData().getFoodLevel());result.put("saturation",p.getFoodData().getSaturationLevel());result.put("experience",Map.of("level",p.experienceLevel,"progress",p.experienceProgress,"total",p.totalExperience));result.put("position",List.of(p.getX(),p.getY(),p.getZ()));result.put("view",Map.of("yaw",p.getYRot(),"pitch",p.getXRot()));result.put("dimension",p.level().dimension().identifier().toString());result.put("gameMode",p.gameMode.getGameModeForPlayer().getName());result.put("sneaking",p.isShiftKeyDown());result.put("blockInteractionRange",p.blockInteractionRange());result.put("mainHand",stack(p,p.getInventory().getSelectedSlot(),p.getMainHandItem()));result.put("offHand",stack(p,40,p.getOffhandItem()));var spawn=p.getRespawnConfig();result.put("spawn",spawn==null?Map.of("kind","WORLD_DEFAULT","position",p.level().getRespawnData().pos().toShortString()):Map.of("dimension",spawn.respawnData().dimension().identifier().toString(),"position",spawn.respawnData().pos().toShortString(),"yaw",spawn.respawnData().yaw(),"pitch",spawn.respawnData().pitch(),"forced",spawn.forced()));result.put("lastDeath",p.getLastDeathLocation().map(v->Map.of("dimension",v.dimension().identifier().toString(),"position",v.pos().toShortString())).orElse(Map.of()));}
        else if(section.equals("inventory")){var items=new ArrayList<Object>();int total=p.getInventory().getContainerSize();for(int slot=offset;slot<Math.min(total,offset+16);slot++)items.add(stack(p,slot,p.getInventory().getItem(slot)));result.put("slots",items);result.put("totalSlots",total);result.put("nextOffset",offset+16<total?offset+16:-1);result.put("selectedSlot",p.getInventory().getSelectedSlot());}
        else if(section.equals("advancements")){var all=p.level().getServer().getAdvancements().getAllAdvancements().stream().sorted(Comparator.comparing(a->a.id().toString())).toList();var list=new ArrayList<Object>();for(var a:all.stream().skip(offset).limit(16).toList()){var progress=p.getAdvancements().getOrStartProgress(a);list.add(Map.of("id",a.id().toString(),"done",progress.isDone(),"completedCriteria",progress.getCompletedCriteria()));}result.put("advancements",list);result.put("nextOffset",offset+16<all.size()?offset+16:-1);}
        else if(section.equals("view")){var entities=new ArrayList<Object>();for(var e:p.level().getEntities(p,p.getBoundingBox().inflate(16)).stream().limit(32).toList()){var row=new LinkedHashMap<String,Object>();row.put("entityId",e.getUUID());row.put("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());row.put("position",List.of(e.getX(),e.getY(),e.getZ()));row.put("distance",p.distanceTo(e));var delta=e.getEyePosition().subtract(p.getEyePosition());row.put("estimatedInView",p.getLookAngle().dot(delta.normalize())>.5&&p.hasLineOfSight(e));if(e instanceof ItemEntity item)row.put("item",stack(p,-1,item.getItem()));entities.add(row);}result.put("entities",entities);result.put("viewSource","SERVER_ESTIMATED_CONE_NOT_CLIENT_RENDER");var hit=p.pick(p.blockInteractionRange(),1,false);var crosshair=new LinkedHashMap<String,Object>();crosshair.put("type",hit.getType().name());crosshair.put("position",List.of(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z));if(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit&&hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK){var pos=blockHit.getBlockPos();String state=net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(p.level().getBlockState(pos));crosshair.put("blockPosition",List.of(pos.getX(),pos.getY(),pos.getZ()));crosshair.put("face",blockHit.getDirection().name().toLowerCase(Locale.ROOT));crosshair.put("blockState",state);crosshair.put("blockHash",NativeContainerSnapshot.sha(state));}result.put("crosshair",crosshair);}
        else throw new IllegalArgumentException("AGENT_PLAYER_SECTION");return result;
    }
    private static Map<String,Object> registry(ServerPlayer p,String kind,String query,int offset){if(query.length()>80)throw new IllegalArgumentException("AGENT_REGISTRY_QUERY");String q=query.toLowerCase(Locale.ROOT);var all=new ArrayList<Map<String,Object>>();if(kind.equals("items")){for(var item:BuiltInRegistries.ITEM){String id=BuiltInRegistries.ITEM.getKey(item).toString(),name=new ItemStack(item).getHoverName().getString();if(ConversationTools.matches(id,name,q))all.add(Map.of("id",id,"name",name));}}else if(kind.equals("blocks")){for(var b:BuiltInRegistries.BLOCK){String id=BuiltInRegistries.BLOCK.getKey(b).toString();if(ConversationTools.matches(id,b.getName().getString(),q))all.add(Map.of("id",id,"name",b.getName().getString(),"defaultState",b.defaultBlockState().toString()));}}else if(kind.equals("enchantments")){var r=p.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);r.listElements().forEach(h->{String id=h.key().identifier().toString();if(ConversationTools.matches(id,h.value().description().getString(),q))all.add(Map.of("id",id,"maxLevel",h.value().getMaxLevel(),"name",h.value().description().getString()));});}else throw new IllegalArgumentException("AGENT_REGISTRY_KIND");all.sort(Comparator.comparing(x->x.get("id").toString()));return Map.of("entries",all.stream().skip(offset).limit(16).toList(),"nextOffset",offset+16<all.size()?offset+16:-1);}
    private static ItemStack patch(ServerPlayer p,ItemStack source,JsonNode args)throws Exception{
        var root=encoded(p,source);if(args.has("components")){if(!args.path("components").isObject()||args.path("components").size()>32)throw new IllegalArgumentException("AGENT_COMPONENTS");var components=root.withObject("components");for(var entry:args.path("components").properties())components.set(entry.getKey(),entry.getValue());}
        var result=ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,p.registryAccess()),JsonParser.parseString(root.toString())).getOrThrow();
        if(args.has("name"))result.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal(text(args,"name",128)));
        if(args.has("enchantments")){if(!args.path("enchantments").isArray()||args.path("enchantments").size()>16)throw new IllegalArgumentException("AGENT_ENCHANTMENTS");var changes=new LinkedHashMap<Holder<Enchantment>,Integer>();for(var entry:args.path("enchantments")){keys(entry,"id","level");var key=ResourceKey.create(Registries.ENCHANTMENT,Identifier.parse(text(entry,"id",128)));changes.put(p.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key),number(entry,"level",0,255));}EnchantmentHelper.updateEnchantments(result,m->changes.forEach(m::set));}
        if(result.isEmpty()||result.getCount()>result.getMaxStackSize())throw new IllegalArgumentException("AGENT_ITEM_COUNT");return result;
    }
    private static CompletableFuture<Map<String,Object>> mutate(ServerPlayer p,UUID agent,UUID operation,String tool,JsonNode a,BooleanSupplier permit)throws Exception{
        Map<String,Object> result;
        switch(tool){
            case "modify_item"->{keys(a,"slot","expected_hash","name","enchantments","components");int slot=number(a,"slot",0,p.getInventory().getContainerSize()-1);var before=p.getInventory().getItem(slot);if(before.isEmpty()||!hash(p,before).equals(text(a,"expected_hash",64)))throw new IllegalStateException("AGENT_ITEM_CHANGED");var after=patch(p,before,a);p.getInventory().setItem(slot,after);p.inventoryMenu.broadcastChanges();p.containerMenu.broadcastChanges();result=Map.of("status","APPLIED","item",stack(p,slot,p.getInventory().getItem(slot)));}
            case "give_item"->{keys(a,"item","count","name","enchantments","components");var id=Identifier.parse(text(a,"item",128));if(!BuiltInRegistries.ITEM.containsKey(id))throw new IllegalArgumentException("AGENT_UNKNOWN_ITEM");int count=number(a,"count",1,64);var gift=patch(p,new ItemStack(BuiltInRegistries.ITEM.getValue(id),count),a);var description=stack(p,-1,gift.copy());p.getInventory().add(gift);p.inventoryMenu.broadcastChanges();result=Map.of("status",gift.isEmpty()?"APPLIED":"PARTIAL","inserted",count-gift.getCount(),"remaining",gift.getCount(),"item",description);}
            case "drop_item"->{keys(a,"expected_hash","whole_stack");if(!a.path("whole_stack").isBoolean()||!hash(p,p.getMainHandItem()).equals(text(a,"expected_hash",64)))throw new IllegalArgumentException("AGENT_ITEM_CHANGED");int before=p.getMainHandItem().getCount();p.drop(a.path("whole_stack").booleanValue());p.inventoryMenu.broadcastChanges();result=Map.of("status","OBSERVED","beforeCount",before,"afterMainHand",stack(p,p.getInventory().getSelectedSlot(),p.getMainHandItem()));}
            case "pickup_item"->{keys(a,"entity_id");var e=p.level().getEntity(UUID.fromString(text(a,"entity_id",36)));if(!(e instanceof ItemEntity item)||!p.getBoundingBox().inflate(.5).intersects(item.getBoundingBox()))throw new IllegalArgumentException("AGENT_PICKUP_OUT_OF_REACH");int before=item.getItem().getCount();item.playerTouch(p);result=Map.of("status","OBSERVED","picked",before-(item.isRemoved()?0:item.getItem().getCount()),"entityRemoved",item.isRemoved());}
            case "interact_block"->{return ConversationNativeInteractions.useBlock(p,agent,a);}
            case "quick_move_container"->{return ConversationNativeInteractions.quickMove(p,agent,operation,a);}
            case "close_container"->{return ConversationNativeInteractions.closeMenu(p,agent,a);}
            case "run_game_command"->{keys(a,"command");return command(p,text(a,"command",2048),permit);}
            case "generate_content_package"->{keys(a,"prompt");return generateContent(p,agent,operation,text(a,"prompt",4096),permit);}
            case "start_world_task"->{keys(a,"prompt");var task=ServerTaskStart.start(p,operation,agent,text(a,"prompt",4096),0,false);result=Map.of("status","TASK_STARTED_NOT_COMPLETED","taskId",task.taskId(),"state",task.status());}
            case "request_player_control"->{keys(a,"prompt");dev.mineagent.runtime.neoforge.task.PlayerBodyAgent.submit(p,agent,text(a,"prompt",4096));result=Map.of("status","PLAN_REQUESTED_NOT_EXECUTED","localApprovalRequired",true);}
            default->throw new IllegalArgumentException("AGENT_TOOL_UNKNOWN");
        }return CompletableFuture.completedFuture(result);
    }
    private static CompletableFuture<Map<String,Object>> generateContent(ServerPlayer p,UUID agent,UUID operation,String prompt,BooleanSupplier permit){
        var server=p.level().getServer();var runtime=ServerPackageRuntime.get(server);if(!runtime.mayGenerate(p.getUUID(),agent))throw new SecurityException("AGENT_PACKAGE_PERMISSION");
        var result=new CompletableFuture<Map<String,Object>>();var level=p.level();
        try{
            runtime.submit(p,agent,operation,prompt,"WORLD_CONTENT");
            WORK.computeIfAbsent(server,k->new ArrayList<>()).add(new Work(){final long deadline=System.nanoTime()+java.time.Duration.ofMinutes(10).toNanos();
                public void cancel(){try{if(runtime.generation(p.getUUID(),operation).filter(j->j.state().equals("GENERATING")).isPresent())runtime.cancel(p.getUUID(),operation);}catch(Exception ignored){}result.completeExceptionally(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN"));}
                public boolean tick(){
                    if(!current(p,permit)||p.level()!=level||!runtime.mayGenerate(p.getUUID(),agent)||System.nanoTime()>deadline){cancel();return true;}
                    var job=runtime.generation(p.getUUID(),operation).orElseThrow();if(job.state().equals("GENERATING"))return false;
                    if(!job.state().equals("PUBLISHED")){result.completeExceptionally(new IllegalStateException("AGENT_PACKAGE_GENERATION_"+job.state()));return true;}
                    var pack=runtime.ownedPackage(p.getUUID(),job.packageId(),job.packageRevision()).orElseThrow();
                    result.complete(Map.of("status","PUBLISHED_AWAITING_PLAYER_APPROVAL","packageId",pack.packageId(),"name",pack.name(),"revision",pack.revision(),"activationMode",pack.activationMode(),"executed",false,"nextStep","F2包目录选择该包，审查后明确启用；需要重载/重启则遵循所示生命周期"));return true;
                }
            });
        }catch(Exception unknown){result.completeExceptionally(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN",unknown));}
        return result;
    }
    private static CompletableFuture<Map<String,Object>> inspectCommands(ServerPlayer p,String value,BooleanSupplier permit){
        String prefix=value.startsWith("/")?value.substring(1):value;
        if(prefix.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("AGENT_COMMAND_ARGUMENTS");
        var source=p.createCommandSourceStack();var dispatcher=p.level().getServer().getCommands().getDispatcher();var parse=dispatcher.parse(prefix,source);
        var context=parse.getContext();var node=context.getLastChild().getNodes().isEmpty()?dispatcher.getRoot():context.getLastChild().getNodes().getLast().getNode();
        var usage=dispatcher.getSmartUsage(node,source).entrySet().stream().filter(e->!e.getKey().getName().equals("ai")).map(Map.Entry::getValue).sorted().limit(32).toList();
        var future=new CompletableFuture<Map<String,Object>>();var server=p.level().getServer();var level=p.level();
        dispatcher.getCompletionSuggestions(parse).orTimeout(5,TimeUnit.SECONDS).whenComplete((suggestions,error)->server.execute(()->{
            if(!current(p,permit)||p.level()!=level){future.complete(Map.of("status","REJECTED","error","AGENT_TOOL_CONTEXT_CHANGED"));return;}
            if(error!=null){future.complete(Map.of("status","REJECTED","error","AGENT_COMMAND_SUGGESTIONS_UNAVAILABLE"));return;}
            var options=suggestions.getList().stream().filter(x->!x.getText().equals("ai")).limit(32).map(x->Map.of("text",x.getText(),"replaceStart",x.getRange().getStart(),"replaceEnd",x.getRange().getEnd())).toList();
            future.complete(Map.of("status","OBSERVED","prefix",prefix,"usage",usage,"suggestions",options,"suggestionsTruncated",suggestions.getList().size()>32,"minecraftVersion",net.minecraft.SharedConstants.getCurrentVersion().name()));
        }));return future;
    }
    private static CompletableFuture<Map<String,Object>> command(ServerPlayer p,String value,BooleanSupplier permit)throws Exception{
        String command=value.startsWith("/")?value.substring(1):value;if(command.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("AGENT_COMMAND_ARGUMENTS");var server=p.level().getServer();var parse=server.getCommands().getDispatcher().parse(command,p.createCommandSourceStack());try{net.minecraft.commands.Commands.validateParseResults(parse);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid){String message=invalid.getRawMessage().getString();return CompletableFuture.completedFuture(Map.of("status","COMMAND_REJECTED","error","AGENT_COMMAND_SYNTAX","message",message.substring(0,Math.min(512,message.length())),"cursor",invalid.getCursor(),"command",command,"nextStep","Use inspect_commands with the command prefix to discover current syntax; do not repeat unchanged."));}if(parse.getContext().getLastChild().getCommand()==null)return CompletableFuture.completedFuture(Map.of("status","COMMAND_REJECTED","error","AGENT_COMMAND_INCOMPLETE","command",command,"nextStep","Use inspect_commands to discover remaining arguments."));for(var c=parse.getContext();c!=null;c=c.getChild())for(var n:c.getNodes())if(n.getNode().getName().equals("ai"))throw new IllegalArgumentException("AGENT_COMMAND_RECURSION");
        var feedback=new ConversationCommandFeedback(p.commandSource());var future=new CompletableFuture<Map<String,Object>>();var level=p.level();WORK.computeIfAbsent(server,k->new ArrayList<>()).add(new Work(){int at=-1;boolean observed,success=true;long count;public void cancel(){future.completeExceptionally(new IllegalStateException("AGENT_TOOL_OUTCOME_UNKNOWN"));}public boolean tick(){if(!current(p,permit)||p.level()!=level){cancel();return true;}if(at<0){at=server.getTickCount();server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withSource(feedback).withCallback((ok,n)->{observed=true;success&=ok;count+=n;}),command);return false;}if(server.getTickCount()-at<2)return false;if(observed){future.complete(Map.of("status",success?"APPLIED":"COMMAND_REJECTED","success",success,"result",count,"command",command,"output",feedback.messages(),"outputTruncated",feedback.truncated()));return true;}if(server.getTickCount()-at>200){cancel();return true;}return false;}});return future;
    }
    private static CompletableFuture<Map<String,Object>> web(ServerPlayer p,String tool,JsonNode args,BooleanSupplier permit){
        String key=tool.equals("web_search")?"query":"url";keys(args,key,"scope");String value=text(args,key,key.equals("query")?256:2048);var server=p.level().getServer();var level=p.level();var future=new CompletableFuture<Map<String,Object>>();
        MineAgentRuntimeServices.worker(server).webResearch(MineAgentRuntimeServices.config(server),key.equals("query")?"web.search":"web.read",value,args.path("scope").asText("minecraft"),permit).whenComplete((reply,error)->server.execute(()->{
            if(!current(p,permit)||p.level()!=level){future.complete(Map.of("status","REJECTED","error","AGENT_TOOL_CONTEXT_CHANGED"));return;}
            if(error!=null||reply==null){future.complete(Map.of("status","REJECTED","error","WEB_REQUEST_FAILED"));return;}
            if(!reply.type().equals("web.result")){String code=String.valueOf(reply.payload().getOrDefault("code","WEB_REQUEST_FAILED"));future.complete(Map.of("status","REJECTED","error",code.matches("(?:WEB|SERVICE_BUDGET)_[A-Z0-9_]{1,80}")?code:"WEB_REQUEST_FAILED"));return;}
            ConversationBuildSmokeServer.observeWeb(tool,reply.payload());future.complete(reply.payload());
        }));return future;
    }
    private static CompletableFuture<Map<String,Object>> inspectBlueprints(ServerPlayer p,JsonNode a,BooleanSupplier permit){
        keys(a,"action","blueprint_id","expected_hash","offset","variant");var server=p.level().getServer();
        if(!server.isSingleplayerOwner(p.nameAndId()))throw new SecurityException("BLUEPRINT_LOCAL_OWNER_REQUIRED");
        String action=text(a,"action",16);int offset=a.has("offset")?number(a,"offset",0,131072):0,variant=a.has("variant")?number(a,"variant",0,63):0;
        String id=action.equals("read")?text(a,"blueprint_id",36):"",hash=a.has("expected_hash")&&!a.path("expected_hash").asText().isEmpty()?text(a,"expected_hash",64):"";var level=p.level();var game=server.getServerDirectory();var future=new CompletableFuture<Map<String,Object>>();
        CompletableFuture.supplyAsync(()->{try{return action.equals("list")?CreateBlueprintReader.list(game,offset):action.equals("read")?CreateBlueprintReader.read(game,id,hash,offset,variant):Map.<String,Object>of("status","REJECTED","error","BLUEPRINT_ACTION");}catch(Exception e){return Map.<String,Object>of("status","REJECTED","error",code(e));}},IO).whenComplete((value,error)->server.execute(()->{
            if(!current(p,permit)||p.level()!=level||!server.isSingleplayerOwner(p.nameAndId()))future.complete(Map.of("status","REJECTED","error","AGENT_TOOL_CONTEXT_CHANGED"));
            else if(error!=null)future.complete(Map.of("status","REJECTED","error","BLUEPRINT_READ_FAILED"));
            else{var result=new LinkedHashMap<String,Object>(value);result.put("createInstalled",net.neoforged.fml.ModList.get().isLoaded("create"));ConversationBuildSmokeServer.observeBlueprint(result);future.complete(result);}
        }));return future;
    }
    private static int[] coordinates(JsonNode a,String key){
        var n=a.path(key);if(!n.isArray()||n.size()!=3)throw new IllegalArgumentException("AGENT_BLOCK_COORDINATES");
        int[] result=new int[3];for(int i=0;i<3;i++){if(!n.get(i).isIntegralNumber()||!n.get(i).canConvertToInt())throw new IllegalArgumentException("AGENT_BLOCK_COORDINATES");result[i]=n.get(i).asInt();}return result;
    }
    private static CompletableFuture<Map<String,Object>> inspectBlocks(ServerPlayer p,JsonNode a,BooleanSupplier permit){
        keys(a,"min","max","after_ticks");var lo=coordinates(a,"min");var hi=coordinates(a,"max");
        var region=new BlockObservationRegion(lo[0],lo[1],lo[2],hi[0],hi[1],hi[2],a.has("after_ticks")?number(a,"after_ticks",0,200):0);region.requireNear(p.blockPosition().getX(),p.blockPosition().getY(),p.blockPosition().getZ());
        var level=p.level();long at=level.getGameTime()+region.afterTicks(),deadline=System.currentTimeMillis()+30000;var future=new CompletableFuture<Map<String,Object>>();
        WORK.computeIfAbsent(level.getServer(),k->new ArrayList<>()).add(new Work(){
            public void cancel(){future.completeExceptionally(new IllegalStateException("AGENT_BLOCK_OBSERVATION_INTERRUPTED"));}
            public boolean tick()throws Exception{
                if(!current(p,permit)||p.level()!=level||System.currentTimeMillis()>deadline){cancel();return true;}if(level.getGameTime()<at)return false;
                region.requireNear(p.blockPosition().getX(),p.blockPosition().getY(),p.blockPosition().getZ());
                var palette=new ArrayList<Map<String,Object>>();var indices=new HashMap<String,Integer>();var runs=new ArrayList<List<Integer>>();int represented=0,unknown=0;boolean truncated=false;
                outer:for(int y=region.minY();y<=region.maxY();y++)for(int z=region.minZ();z<=region.maxZ();z++)for(int x=region.minX();x<=region.maxX();x++){
                    var pos=new BlockPos(x,y,z);int index;
                    if(!level.isInWorldBounds(pos))index=-2;
                    else if(!level.getChunkSource().hasChunk(x>>4,z>>4))index=-1;
                    else{
                        var block=level.getBlockState(pos);String state=net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(block);
                        index=indices.getOrDefault(state,-3);
                        if(index==-3&&palette.size()<32){index=palette.size();indices.put(state,index);var fluid=block.getFluidState();palette.add(Map.of("state",state,"stateHash",RuntimePackageCanonicalizer.sha256(state.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"fluid",BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(),"fluidSource",fluid.isSource()));}
                    }
                    if(!runs.isEmpty()&&runs.getLast().getFirst()==index){var old=runs.removeLast();runs.add(List.of(index,old.get(1)+1));}
                    else{if(runs.size()>=256){truncated=true;break outer;}runs.add(List.of(index,1));}
                    represented++;if(index<0)unknown++;
                }
                var result=new LinkedHashMap<String,Object>();result.put("status","OBSERVED");result.put("min",List.of(lo[0],lo[1],lo[2]));result.put("max",List.of(hi[0],hi[1],hi[2]));result.put("order","X_Z_Y");result.put("palette",palette);result.put("runs",runs);result.put("totalCells",region.volume());result.put("representedCells",represented);result.put("unknownCells",unknown);result.put("truncated",truncated||unknown>0);result.put("observedGameTick",level.getGameTime());result.put("waitedTicks",region.afterTicks());result.put("scope","LOADED_BLOCKS_ONLY_NO_BLOCK_ENTITY_NBT");future.complete(result);return true;
            }
        });return future;
    }
    private static CompletableFuture<Map<String,Object>> scan(ServerPlayer p,JsonNode a,BooleanSupplier permit){
        keys(a,"blocks","radius","y_min","y_max");int radius=number(a,"radius",2,32),lo=number(a,"y_min",-2048,2048),hi=number(a,"y_max",-2048,2048);if(hi<lo||hi-lo>=64||!a.path("blocks").isArray()||a.path("blocks").size()<1||a.path("blocks").size()>8)throw new IllegalArgumentException("AGENT_SCAN_BOUNDS");var wanted=new HashSet<net.minecraft.world.level.block.Block>();for(var v:a.path("blocks")){var id=Identifier.parse(v.asText());if(!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("AGENT_UNKNOWN_BLOCK");wanted.add(BuiltInRegistries.BLOCK.getValue(id));}
        var center=p.blockPosition();var level=p.level();var positions=BlockPos.betweenClosed(center.getX()-radius,lo,center.getZ()-radius,center.getX()+radius,hi,center.getZ()+radius).iterator();var future=new CompletableFuture<Map<String,Object>>();WORK.computeIfAbsent(level.getServer(),k->new ArrayList<>()).add(new Work(){int checked,unloaded;boolean truncated;final List<Object> found=new ArrayList<>();final long deadline=System.currentTimeMillis()+30000;public void cancel(){future.completeExceptionally(new IllegalStateException("AGENT_SCAN_INTERRUPTED"));}public boolean tick(){if(!current(p,permit)||p.level()!=level||System.currentTimeMillis()>deadline){cancel();return true;}for(int i=0;i<4096&&positions.hasNext();i++){var pos=positions.next();if(!level.isInWorldBounds(pos)||!level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4)){unloaded++;continue;}checked++;var state=level.getBlockState(pos);if(wanted.contains(state.getBlock())){if(found.size()<64)found.add(Map.of("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),"position",List.of(pos.getX(),pos.getY(),pos.getZ())));else truncated=true;}}if(positions.hasNext())return false;future.complete(Map.of("found",found,"checked",checked,"unloadedOrOutOfWorld",unloaded,"resultsTruncated",truncated,"radius",radius,"yMin",lo,"yMax",hi,"center",List.of(center.getX(),center.getY(),center.getZ()),"scope","LOADED_BLOCKS_ONLY"));return true;}});return future;
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var jobs=WORK.get(e.getServer());if(jobs==null)return;for(var work:List.copyOf(jobs)){try{if(work.tick())jobs.remove(work);}catch(Exception failed){work.cancel();jobs.remove(work);}}}
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e){var jobs=WORK.remove(e.getServer());if(jobs!=null)jobs.forEach(Work::cancel);}
}
