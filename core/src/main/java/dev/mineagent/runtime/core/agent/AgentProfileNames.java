package dev.mineagent.runtime.core.agent;

/** PLAYER_NAME's native wire limit is 16 UTF-16 units; Unicode is supported by the profile codec. */
public final class AgentProfileNames {
 public static String require(String value){
  if(value==null||value.isBlank())throw new IllegalArgumentException("AI 玩家名称不能为空");
  String name=value.strip();
  if(name.length()>16)throw new IllegalArgumentException("AI 玩家名称不能超过原版资料名的 16 个字符");
  if(name.codePoints().anyMatch(Character::isWhitespace)||name.indexOf('"')>=0||name.indexOf(39)>=0||name.startsWith("@"))throw new IllegalArgumentException("AI 玩家资料名不能包含空格、引号或选择器前缀");
  if(name.codePoints().anyMatch(c->Character.isISOControl(c)||c==0xA7||c>=0x202A&&c<=0x202E||c>=0x2066&&c<=0x2069))throw new IllegalArgumentException("AI 玩家名称包含无效字符");
  return name;
 }
 public static boolean supported(String value){try{require(value);return true;}catch(IllegalArgumentException invalid){return false;}}
 private AgentProfileNames(){}
}
