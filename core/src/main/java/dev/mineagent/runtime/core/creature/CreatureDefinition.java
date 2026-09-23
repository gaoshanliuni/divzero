package dev.mineagent.runtime.core.creature;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.objects.RuntimeMesh;
import java.util.*;

/** Data-only hot species, using a persistent native Animal carrier, never arbitrary host code. */
public record CreatureDefinition(String name,String disposition,double health,double speed,double damage,
        String food,boolean rideable,boolean companion,List<Trade> trades,Trade barter,
        double proximityRadius,String proximityMessage,int proximityCooldown,String model,String source) {
    public record Trade(String input,int inputCount,String output,int outputCount) {}
    private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final String CONTRACT="""
        热更新生物定义：一个持久runtime_creature载体的自定义物种，不是原版生物改名，也不是新的FML Registry ID。
        define_creature(source)的source为JSON：name必填；disposition=friendly/neutral/hostile；health默认20(1..1024)，speed默认0.25(0.05..1)，damage默认3(0..100)。
        food为喂食繁殖的物品ID（默认空/不繁殖）；rideable/companion为布尔。
        trades数组最多16个，右键打开原生交易窗口；每项{input:"minecraft:emerald",inputCount:1,output:"minecraft:apple",outputCount:2}，计数1..64。
        barter为同样的一项交易，附近丢下输入物品即消耗并掉出输出物品；一次一份，20tick冷却，不会自动拾回输出作为输入。
        proximity可选{radius:3,message:"欢迎",cooldown:100}，玩家进入范围触发消息，离开后冷却结束可再触发，不是每tick刷屏。
        hostile自主追击附近生存玩家；neutral受击反击；friendly不主动找玩家。companion可通过control_creature命令follow/stay/attack/patrol；攻击目标必须是当前可见实体，不能攻击主人。
        food优先于交易，空手不潜行右键骑乘（潜行或持有非食物物品右键打开交易）；只有主人可骑/下命令；两只同物种同主人成人喂食后按原生寻路繁殖。
        model可选完整RuntimeMesh JSON对象，支持inspect_modeling的13种primitive，纯顶点颜色（不支持外部texture），必须solid/非dynamic，尺寸最多4×6×4；缺省使用蓝色立方体形体。
        define_creature更新需要species_id与expected_revision，影响该物种已加载实体及下次加载；不会重置已有血量或复制实体。
        control_creature spawn指定species_id、可选position(已加载且距本人64格内)，其余指定entity_id；patrol给2..16个points，attack给target_id。inspect_creatures分页列定义和当前维度已加载实体、血量、命令与实际事件次数；先读回再描述完成。
        """;
    public static CreatureDefinition parse(String source){try{
        if(source==null||source.length()>12000)throw new IllegalArgumentException("CREATURE_DEFINITION_SIZE");JsonNode n=JSON.readTree(source);keys(n,"name","disposition","health","speed","damage","food","rideable","companion","trades","barter","proximity","model");
        String name=text(n,"name","",48);if(name.isBlank()||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("CREATURE_NAME");String disposition=text(n,"disposition","friendly",16);if(!Set.of("friendly","neutral","hostile").contains(disposition))throw new IllegalArgumentException("CREATURE_DISPOSITION");
        String food=item(n,"food",true);var trades=new ArrayList<Trade>();if(n.has("trades")){if(!n.get("trades").isArray()||n.get("trades").size()>16)throw new IllegalArgumentException("CREATURE_TRADES");for(var t:n.get("trades"))trades.add(trade(t));}Trade barter=n.has("barter")?trade(n.get("barter")):null;if(barter!=null&&barter.input().equals(barter.output()))throw new IllegalArgumentException("CREATURE_BARTER_LOOP");
        double radius=0;String message="";int cooldown=100;if(n.has("proximity")){var a=n.get("proximity");keys(a,"radius","message","cooldown");radius=num(a,"radius",3,0.5,16);message=text(a,"message","",256);cooldown=integer(a,"cooldown",100,20,72000);if(message.isBlank())throw new IllegalArgumentException("CREATURE_PROXIMITY_MESSAGE");}
        String model=n.has("model")?JSON.writeValueAsString(n.get("model")):"{\"version\":1,\"boxes\":[{\"from\":[-0.4,0,-0.4],\"to\":[0.4,0.8,0.4],\"color\":\"#72bcd4\"}],\"collision\":[-0.4,0,-0.4,0.4,0.8,0.4]}";
        var mesh=RuntimeMesh.parse(model);if(!mesh.texture().isEmpty()||mesh.physics().dynamic()||mesh.collision().nonSolid()||mesh.collision().width()>4||mesh.collision().depth()>4||mesh.collision().height()>6)throw new IllegalArgumentException("CREATURE_MODEL_BOUNDS");
        return new CreatureDefinition(name,disposition,num(n,"health",20,1,1024),num(n,"speed",.25,.05,1),num(n,"damage",3,0,100),food,bool(n,"rideable"),bool(n,"companion"),List.copyOf(trades),barter,radius,message,cooldown,model,JSON.writeValueAsString(n));
    }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("CREATURE_DEFINITION_INVALID",e);}}
    private static Trade trade(JsonNode n){keys(n,"input","inputCount","output","outputCount");return new Trade(item(n,"input",false),integer(n,"inputCount",1,1,64),item(n,"output",false),integer(n,"outputCount",1,1,64));}
    private static String item(JsonNode n,String k,boolean empty){String s=text(n,k,"",128);if(!(empty&&s.isEmpty())&&!s.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))throw new IllegalArgumentException("CREATURE_ITEM_ID");return s;}
    private static void keys(JsonNode n,String...keys){if(n==null||!n.isObject())throw new IllegalArgumentException("CREATURE_OBJECT");var set=Set.of(keys);n.fieldNames().forEachRemaining(k->{if(!set.contains(k))throw new IllegalArgumentException("CREATURE_UNKNOWN_FIELD:"+k);});}
    private static String text(JsonNode n,String k,String fallback,int max){if(!n.has(k))return fallback;var v=n.get(k);if(!v.isTextual()||v.textValue().length()>max)throw new IllegalArgumentException("CREATURE_TEXT:"+k);return v.textValue();}
    private static boolean bool(JsonNode n,String k){if(!n.has(k))return false;if(!n.get(k).isBoolean())throw new IllegalArgumentException("CREATURE_BOOLEAN");return n.get(k).booleanValue();}
    private static double num(JsonNode n,String k,double fallback,double min,double max){if(!n.has(k))return fallback;var v=n.get(k);if(!v.isNumber()||!Double.isFinite(v.doubleValue())||v.doubleValue()<min||v.doubleValue()>max)throw new IllegalArgumentException("CREATURE_NUMBER:"+k);return v.doubleValue();}
    private static int integer(JsonNode n,String k,int fallback,int min,int max){if(n.has(k)&&(!n.get(k).isIntegralNumber()||!n.get(k).canConvertToInt()))throw new IllegalArgumentException("CREATURE_INTEGER:"+k);return (int)num(n,k,fallback,min,max);}
}
