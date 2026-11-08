package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.config.PermissionConfig;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.LevelSettings;
import java.util.*;

/** Isolated native fixture: no model calls, no production config or world. */
public final class CapabilityAccessSmoke {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static void grant(ServerPlayer player,Set<PermissionAction> actions){
        var server=player.level().getServer();var config=MineAgentRuntimeServices.config(server);
        require(PermissionConfig.apply(config,MineAgentRuntimeServices.permissions(server),config.revision(),player.getUUID(),actions,true).accepted(),"FIXTURE_GRANT_FAILED");
    }
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private static void denied(Checked action)throws Exception{try{action.run();}catch(SecurityException expected){return;}throw new IllegalStateException("MISSING_PERMISSION_DENIAL");}
    public static Map<String,Object> run(ServerPlayer player,UUID agent)throws Exception{
        require(Boolean.getBoolean("mineagent.skillSmoke")&&"capability_access".equals(System.getProperty("mineagent.skillSmokeMode")),"FIXTURE_DISABLED");
        var server=player.level().getServer();var data=server.getWorldData();var field=data.getClass().getDeclaredField("settings");field.setAccessible(true);var old=(LevelSettings)field.get(data);
        field.set(data,new LevelSettings(old.levelName(),old.gameType(),old.difficultySettings(),false,old.dataConfiguration(),old.lifecycle()));server.getPlayerList().deop(player.nameAndId());
        require(!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),"FIXTURE_STILL_OPERATOR");grant(player,Set.of());
        var pigs=new ArrayList<net.minecraft.world.entity.Mob>();
        for(int i=0;i<75;i++){
            var pig=net.minecraft.world.entity.EntityType.PIG.create(player.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);require(pig!=null,"PIG_CREATE");pig.setNoAi(true);pig.setNoGravity(true);pig.setPos(player.getX()+2+i%10,player.getY(),player.getZ()+2+i/10);require(player.level().addFreshEntity(pig),"PIG_SPAWN");pigs.add(pig);
        }
        String entitySource="{\"entity_id\":\""+pigs.getFirst().getUUID()+"\",\"events\":[\"entity_use\"]}";
        var entityArgs=JSON.createObjectNode().put("source",entitySource);var blockArgs=JSON.createObjectNode().put("source","{\"position\":[5,101,6],\"events\":[\"block_use\"]}");
        denied(()->ServerInteractionRules.set(player,UUID.randomUUID(),entityArgs,false));
        grant(player,Set.of(PermissionAction.EDIT_ENTITIES));
        ServerInteractionRules.set(player,UUID.randomUUID(),entityArgs,false);
        require(ServerInteractionRules.denied(player,null,pigs.getFirst(),"entity_use",false),"ENTITY_RULE_NOT_ACTIVE");
        denied(()->ServerInteractionRules.set(player,UUID.randomUUID(),blockArgs,false));
        var ability=ServerEventAbilities.define(player,UUID.randomUUID(),JSON.createObjectNode().put("source","{\"events\":[\"living_jump\"],\"decision\":\"continue\"}"));
        ServerEventAbilities.bind(player,UUID.randomUUID(),JSON.createObjectNode().put("ability_id",ability.get("abilityId").toString()).put("ability_revision",1).put("entity_id",pigs.get(1).getUUID().toString()));
        grant(player,Set.of(PermissionAction.EDIT_WORLD));
        require(!ServerInteractionRules.denied(player,null,pigs.getFirst(),"entity_use",false),"REVOKED_ENTITY_RULE_ACTIVE");
        ServerInteractionRules.set(player,UUID.randomUUID(),blockArgs,false);
        var at=new net.minecraft.core.BlockPos(5,101,6);require(ServerInteractionRules.denied(player,at,null,"block_use",false),"WORLD_RULE_NOT_ACTIVE");
        var mixed=JSON.createObjectNode().put("source","{\"position\":[6,101,6],\"events\":[\"block_use\"],\"on_allowed\":[{\"type\":\"velocity\",\"y\":0.2,\"target\":\"actor\"}]}");
        denied(()->ServerInteractionRules.set(player,UUID.randomUUID(),mixed,false));
        grant(player,Set.of(PermissionAction.EDIT_WORLD,PermissionAction.EDIT_ENTITIES));ServerInteractionRules.set(player,UUID.randomUUID(),mixed,false);
        grant(player,Set.of());require(!ServerInteractionRules.denied(player,at,null,"block_use",false),"REVOKED_WORLD_RULE_ACTIVE");
        var expectedEntities=player.level().getEntities(player,player.getBoundingBox().inflate(32),e->e.getType()==net.minecraft.world.entity.EntityType.PIG).stream().map(e->e.getUUID().toString()).collect(java.util.stream.Collectors.toSet());require(expectedEntities.containsAll(pigs.stream().map(e->e.getUUID().toString()).toList()),"FIXTURE_PIGS_NOT_OBSERVED");
        var seen=new HashSet<String>();int offset=0,pages=0;
        do{
            var args=JSON.createObjectNode().put("section","view").put("entity_type","minecraft:pig").put("radius",32).put("limit",13).put("offset",offset).put("sort","distance");
            var result=ConversationAgentTools.execute(player,agent,UUID.randomUUID(),"inspect_player",args.toString(),()->true).getNow(null);require(result!=null,"READ_NOT_IMMEDIATE");var page=JSON.valueToTree(result);
            require(page.path("total").asInt()==expectedEntities.size(),"DENSE_ENTITY_TOTAL_"+page);for(var entity:page.path("entities"))require(seen.add(entity.path("entityId").asText()),"DUPLICATE_ENTITY_PAGE");offset=page.path("nextOffset").asInt();pages++;
        }while(offset>=0);
        require(seen.equals(expectedEntities)&&pages==(expectedEntities.size()+12)/13,"DENSE_ENTITY_PAGES_INCOMPLETE");
        return Map.of("operator",false,"narrowEntityGrant",true,"narrowWorldGrant",true,"mixedActionsRequireBoth",true,"revocationStopsNativeCallbacks",true,"templateBinding",true,"nativeEntities",seen.size(),"pages",pages,"modelCalls",0);
    }
    private CapabilityAccessSmoke(){}
}
