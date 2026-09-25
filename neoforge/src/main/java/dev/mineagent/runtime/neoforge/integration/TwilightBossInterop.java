package dev.mineagent.runtime.neoforge.integration;

import java.util.*;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/** Optional integration. Method names are fixed here, never supplied by the model. */
public final class TwilightBossInterop {
    public record Boss(String id, String name, int groupSize, String requirements) {}
    public static final List<Boss> BOSSES = List.of(
        new Boss("naga", "娜迦", 1, "宽阔地面；12节身体、冲刺、眩晕和破坏场地由原生AI控制"),
        new Boss("lich", "巫妖", 1, "可传送的地面/空间；反弹破盾、分身、僵尸召唤和近战阶段；初始阶段不是原实例的当前阶段"),
        new Boss("minoshroom", "米诺菇", 1, "可移动地面；冲撞与地面猛击"),
        new Boss("hydra", "九头蛇", 1, "大型空地；原生多头/颈/躯干部件、喷火/迫击炮/咬击和断头再生"),
        new Boss("knight_phantom", "幻影骑士", 6, "默认六只同一Home，独立编号/武器；原生按附近64格组队，避免靠近其它组"),
        new Boss("ur_ghast", "暮色恶魂", 1, "大范围空域；恶魂陷阱/塔楼环境影响路线、暴怒、召唤和天气"),
        new Boss("alpha_yeti", "雪怪首领", 1, "足够高的空间及可破坏环境；投掷、暴走、疲劳"),
        new Boss("snow_queen", "冰雪女王", 1, "飞行空间；冰盾多部件、召唤、俯冲、冰束阶段")
    );
    public static String id(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(); }
    public static Optional<Boss> boss(String id) { return BOSSES.stream().filter(b -> ("twilightforest:"+b.id()).equals(id)).findFirst(); }
    public static List<Map<String,Object>> catalog() {
        var list=new ArrayList<Map<String,Object>>();
        for (var b:BOSSES) list.add(Map.of("type","twilightforest:"+b.id(),"name",b.name(),"groupSize",b.groupSize(),
            "installed",BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse("twilightforest:"+b.id())),"requirements",b.requirements(),"fullCombatVerified",false));
        list.add(Map.of("type","twilightforest:quest_ram","name","谜题羊","classification","QUEST_NOT_COMBAT_BOSS","requirements","可走通用原生派生；收集羊毛/奖励由原生任务逻辑控制","fullCombatVerified",false));
        list.add(Map.of("type","twilightforest:plateau_boss","name","最终高原BOSS占位","classification","UNFINISHED_NO_SAVE_NO_SUMMON","supported",false));
        return list;
    }
    public static void initialize(Entity e, BlockPos home, int member) throws ReflectiveOperationException {
        if (boss(id(e)).isEmpty()) return;
        e.getClass().getMethod("setRestrictionPoint",GlobalPos.class).invoke(e,GlobalPos.of(e.level().dimension(),home));
        if (id(e).equals("twilightforest:knight_phantom")) e.getClass().getMethod("setNumber",int.class).invoke(e,member);
    }
    public static Map<String,Object> inspect(Entity e) {
        if (boss(id(e)).isEmpty()) return Map.of("adapter","GENERIC_NATIVE");
        var out=new LinkedHashMap<String,Object>();out.put("adapter","TWILIGHT_NATIVE_BOSS");out.put("type",id(e));
        try {
            Object home=e.getClass().getMethod("getRestrictionPoint").invoke(e);
            out.put("home",Objects.toString(home,""));
            List<String> methods=switch(id(e)) {
                case "twilightforest:naga" -> List.of("isDazed","isCharging","isStunlessCharging");
                case "twilightforest:lich" -> List.of("getPhase","getShieldStrength","getMinionsToSummon","isShadowClone","countMyClones");
                case "twilightforest:knight_phantom" -> List.of("getNumber","getCurrentFormation");
                case "twilightforest:ur_ghast" -> List.of("isInTantrum");
                case "twilightforest:alpha_yeti" -> List.of("isRampaging","isTired");
                case "twilightforest:snow_queen" -> List.of("getCurrentPhase","countMyMinions");
                default -> List.of();
            };
            for(String method:methods) {Object v=e.getClass().getMethod(method).invoke(e);out.put(method,v instanceof Number||v instanceof Boolean?v:Objects.toString(v));}
        } catch(ReflectiveOperationException error) {out.put("adapterError","TWILIGHT_VERSION_METHOD_UNAVAILABLE");}
        return out;
    }
    private TwilightBossInterop() {}
}
