package dev.mineagent.runtime.core.creature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** A fresh native instance recipe, never an opaque copy of entity identity or world references. */
public record NativeEntityDefinition(String name, String type, Map<String, Double> attributes,
                                     boolean noAi, Map<String, String> equipment, String source,
                                     CopyPolicy copyPolicy,String copyAdapter,String adapterVersion,Map<String,Object> copyState) {
    public enum CopyPolicy { BASIC, SAFE_STATE, MOD_ADAPTER, CUSTOM }
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static NativeEntityDefinition parse(String source) {
        try {
            if (source == null || source.length() > 14000) throw new IllegalArgumentException();
            JsonNode n = JSON.readTree(source);
            if (!n.isObject()) throw new IllegalArgumentException();
            for (var f : n.properties()) if (!Set.of("name", "type", "attributes", "no_ai", "equipment","copy_policy","copy_adapter","adapter_version","copy_state").contains(f.getKey())) throw new IllegalArgumentException();
            String name = n.path("name").asText(), type = n.path("type").asText();
            if (!n.path("name").isTextual() || name.isBlank() || name.length() > 64
                || name.codePoints().anyMatch(Character::isISOControl)
                || !type.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || type.equals("minecraft:player")) throw new IllegalArgumentException();
            if (n.has("no_ai") && !n.get("no_ai").isBoolean()) throw new IllegalArgumentException();
            var attributes = new TreeMap<String, Double>();
            if (n.has("attributes")) {
                if (!n.get("attributes").isObject()) throw new IllegalArgumentException();
                for (var f : n.get("attributes").properties()) {
                    if (!f.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !f.getValue().isNumber()
                        || !Double.isFinite(f.getValue().doubleValue())) throw new IllegalArgumentException();
                    attributes.put(f.getKey(), f.getValue().doubleValue());
                }
            }
            var equipment = new TreeMap<String, String>();
            if (n.has("equipment")) {
                if (!n.get("equipment").isObject()) throw new IllegalArgumentException();
                for (var f : n.get("equipment").properties()) {
                    if (!Set.of("mainhand", "offhand", "head", "chest", "legs", "feet", "body", "saddle").contains(f.getKey())
                        || !f.getValue().isObject()) throw new IllegalArgumentException();
                    equipment.put(f.getKey(), f.getValue().toString());
                }
            }
            var policy=CopyPolicy.valueOf(n.path("copy_policy").asText("BASIC"));String adapter=n.path("copy_adapter").asText(""),adapterVersion=n.path("adapter_version").asText("");
            if((policy==CopyPolicy.MOD_ADAPTER||policy==CopyPolicy.CUSTOM)&&(!adapter.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!adapterVersion.matches("[A-Za-z0-9_.+-]{1,64}")))throw new IllegalArgumentException("ENTITY_COPY_ADAPTER_REQUIRED");
            if(n.has("copy_state")&&!n.get("copy_state").isObject())throw new IllegalArgumentException("ENTITY_COPY_STATE");var state=new TreeMap<String,Object>();
            for(var field:n.path("copy_state").properties()){
                if(!field.getKey().matches("[a-z][a-zA-Z0-9_]{0,63}")||!field.getValue().isValueNode()||field.getValue().isNull()||field.getValue().isNumber()&&!Double.isFinite(field.getValue().asDouble()))throw new IllegalArgumentException("ENTITY_COPY_STATE_FIELD");
                state.put(field.getKey(),JSON.convertValue(field.getValue(),Object.class));
            }
            if(policy==CopyPolicy.BASIC&&!state.isEmpty()||policy==CopyPolicy.SAFE_STATE&&!Set.of("silent","nameVisible","age").containsAll(state.keySet()))throw new IllegalArgumentException("ENTITY_COPY_STATE_FIELD");
            return new NativeEntityDefinition(name, type, Map.copyOf(attributes), n.path("no_ai").asBoolean(false), Map.copyOf(equipment), n.toString(),policy,adapter,adapterVersion,Map.copyOf(state));
        } catch (Exception e) { throw new IllegalArgumentException("NATIVE_ENTITY_SOURCE", e); }
    }

    public static final String CONTRACT = """
        原生派生模板：source={name,type,attributes?:{属性ID:基础值},equipment?:{mainhand:{id:'minecraft:iron_sword',count:1}},no_ai?:false}。
        inspect_native_entities先读当前已安装注册表/模板/实例和暮色BOSS目录；derive_native_entity从实际生物读取属性与装备生成草稿；define_native_entity持久保存模板；control_native_entity(action=spawn,template_id,position:[x,y,z])生成。
        这条路径创建原注册类型的真实独立实例，保留其Java类、原生AI/技能/多部件/Renderer，不是RuntimeCreature头部代理，不是热注册新EntityType。源Mod及其资源必须保留安装。
        新实例从原生finalizeSpawn初始状态开始，不复制原UUID、仇恨、乘客、Home、战斗阶段、私有内存或任意NBT链接；copy_policy默认为BASIC（属性/装备/no_ai）；SAFE_STATE额外复制公开的silent/nameVisible和AgeableMob的age；不复制健康损失/仇恨/召唤关系。MOD_ADAPTER使用已安装且匹配类型的copy_adapter与adapter_version；CUSTOM通过copy_fields仅提取该适配器声明的字段，source中copy_state只允许标量并由适配器逐字段验证。无适配器时明确拒绝，不回退到任意NBT。Home重新定位到生成点。不会将源BOSS的伙伴/召唤物挂到新实例。
        暮色幻影骑士spawn默认整组六只；可encounter=false只生成一只。附近已有幻影骑士时拒绝整组生成，避免原生按距离组队造成混组；后续移动仍遵循原Mod的近邻规则。
        原生BOSS可破坏方块、召唤、掉落和改变原Mod进度；必须匹配玩家需求与场地。可用set_entity_state/rule/animation按生成回执entity_id修改；规则不等于完整私有Java改写。
        control_native_entity(action=apply_template,entity_id,expected_revision)只对自己的实例应用当前模板属性/装备/no_ai，不重建实体或重置战斗阶段；模板更新本身不改变已有实例。remove只移除指定自有主实体，不自动杀掉原生召唤物。
        模板和所有权按世界/玩家隔离；实例元数据随原生存档保存。分页不截断总列表。生成成功/Tick正常不代表全技能、全阶段、死亡进度和所有第三方Mod兼容已验收。
        """;
}
