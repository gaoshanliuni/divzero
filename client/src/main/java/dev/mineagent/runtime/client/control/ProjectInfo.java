package dev.mineagent.runtime.client.control;
import java.util.Map;
/** Public project identity only. Version comes from the loaded Mod, never server configuration. */
public record ProjectInfo(String version) {
 public static final String NAME="DivZero",URL="https://github.com/gaoshanliuni/divzero",DEVELOPER="gaoshanliuni";
 public ProjectInfo {if(version==null||version.isBlank()||version.length()>256||version.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("PROJECT_VERSION_INVALID");}
 public Map<String,String> fields(){return Map.of("projectName",NAME,"projectUrl",URL,"version",version,"developer",DEVELOPER);}
}
