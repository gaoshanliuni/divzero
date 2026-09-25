package dev.mineagent.runtime.core.interaction;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Donor geometry is attached at the target's animated pivot; no entity AI or hitboxes change. */
public record EntityPartReplacement(String sourceType, String sourcePart, List<Double> translation,
                                    List<Double> rotation, List<Double> scale) {
    public static boolean path(String value) {
        return value != null && value.length() <= 180 && value.matches("root(?:/[^/\\p{Cntrl}]+)*");
    }
    public static Map<String,EntityPartReplacement> parse(JsonNode n) {
        if(n==null)return Map.of();
        if(!n.isObject()||n.size()>64)throw new IllegalArgumentException("ENTITY_REPLACEMENT_PARTS");
        var result=new LinkedHashMap<String,EntityPartReplacement>();
        for(var entry:n.properties()){
            String target=entry.getKey();var value=entry.getValue();
            if(!path(target))throw new IllegalArgumentException("ENTITY_REPLACEMENT_TARGET_PATH");
            // Parent and descendant replacement together is ambiguous (which pivot survives?).
            for(String other:result.keySet())if(target.startsWith(other+"/")||other.startsWith(target+"/"))throw new IllegalArgumentException("ENTITY_REPLACEMENT_OVERLAP");
            EntityLogicSpec.keys(value,"source_type","source_part","translation","rotation","scale");
            String type=value.path("source_type").asText(),part=value.path("source_part").asText();
            if(!value.path("source_type").isTextual()||!EntitySelector.id(type)||type.equals("minecraft:player")||!path(part))throw new IllegalArgumentException("ENTITY_REPLACEMENT_SOURCE");
            result.put(target,new EntityPartReplacement(type,part,vector(value,"translation",List.of(0d,0d,0d),-256,256),vector(value,"rotation",List.of(0d,0d,0d),-3600,3600),vector(value,"scale",List.of(1d,1d,1d),.01,16)));
        }
        return Collections.unmodifiableMap(result);
    }
    private static List<Double> vector(JsonNode n,String name,List<Double> fallback,double min,double max){
        if(!n.has(name))return fallback;var v=n.get(name);if(!v.isArray()||v.size()!=3)throw new IllegalArgumentException("ENTITY_REPLACEMENT_VECTOR");var values=new ArrayList<Double>();
        for(var number:v){double d=number.asDouble();if(!number.isNumber()||!Double.isFinite(d)||d<min||d>max)throw new IllegalArgumentException("ENTITY_REPLACEMENT_VECTOR");values.add(d);}return List.copyOf(values);
    }
    public static final String CONTRACT="""
        局部模型替换：inspect_entity_animation(entity_id)读取目标实际ModelPart路径；inspect_entity_model(entity_type)读取源实体客户端模型路径和原纹理，无需真的生成源生物。
        replace_entity_part选择entity_id或entity_type，target_part为目标路径，source_type为源实体类型，source_part为源路径。羊头通常root/head；凋零中央头为root/center_head（不同资源/Mod模型先读目录）。
        translation=[x,y,z]为目标锚点局部像素偏移，rotation为度，scale为局部倍率；默认0/0/1。源部件根pivot被重定位到目标部件的原生动画pivot；保留源子部件的相对布局，跟随目标转头/进食动作，不导入源生物AI。
        仅换几何和源纹理，羊的UUID/种类/身体/羊毛/行为/命中箱不变。对应羊毛等ModelPart覆盖层的同名路径也隐藏，防止羊头羊毛盖住新头；其它身体部件不隐藏。不会生成真实凋零。
        多部件使用原有part_index；模型须走LivingEntityRenderer与ModelPart。专有渲染器返回UNAVAILABLE，不能冒称兼容。源Mod/资源须安装；当前读取默认客户端模型，不克隆任意实体变体。
        返回ruleId/revision后，修改需rule_id/expected_revision；delete_entity_rule恢复原部件。APPLIED仅是持久化规则，必须再次inspect_entity_animation查看replacementDraws与hiddenParts确认实际渲染。可用set_entity_animation的replacements:{目标路径:{source_type,source_part,translation,rotation,scale}}与动画同一规则组合。
        """;
}
