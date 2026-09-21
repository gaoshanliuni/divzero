package dev.mineagent.runtime.core.agent;
import java.util.*;
/** Real bundled Minecraft skins, plus the requesting player's native skin. No arbitrary URL or path. */
public final class BuiltinAgentSkins {
    private BuiltinAgentSkins(){}
    public static final List<String> NAMES=List.of("alex","ari","efe","kai","makena","noor","steve","sunny","zuri");
    public static List<Map<String,String>> catalog(){var out=new ArrayList<Map<String,String>>();out.add(Map.of("id","player","name","复制聊天玩家当前皮肤"));for(var name:NAMES)for(var shape:List.of("wide","slim"))out.add(Map.of("id",name+":"+shape,"name",name+" / "+(shape.equals("wide")?"标准手臂":"纤细手臂")));return List.copyOf(out);}
    public static boolean valid(String value){if("player".equals(value))return true;if(value==null)return false;String[] parts=value.split(":",-1);return parts.length==2&&NAMES.contains(parts[0])&&Set.of("wide","slim").contains(parts[1]);}
}
