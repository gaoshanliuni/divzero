package dev.mineagent.runtime.core.conversation;

import java.util.*;
import java.util.regex.Pattern;

/** Game authoring and a request to operate the local computer are different intents. */
public final class GameContentRouting {
    private static boolean has(String text,String pattern){return Pattern.compile(pattern,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(text).find();}
    public static boolean creation(String text){return has(text,"创建|生成|制作|做(?:个|一[个把根支件种套柄座栋])|设计|开发|新增|添加|\\b(create|make|build|craft|design)\\b");}
    public static boolean item(String text){return creation(text)&&has(text,"物品|道具|装备|权杖|法杖|魔杖|召唤杖|武器|剑|斧|胸甲|护甲|盾牌|\\b(item|weapon|wand|staff|scepter|armou?r|sword|shield)\\b");}
    public static boolean nativeContent(String text){return item(text)||creation(text)&&has(text,"建筑|房[子屋]|生物|实体|界面|菜单|内容包|模组|悬浮|\\b(hud|gui|ui|building|house|entity|creature|mod|datapack)\\b");}
    public static boolean hostRequested(String text){
        for(String clause:text.split("[，。；;!?！？\\n]")){
            String host="python|本机|电脑|桌面|终端|操作系统|\\b(pip|terminal|desktop|computer|operating system)\\b|网易云|记事本";
            if(has(clause,"(?:不用|不要|不需要|无需|不必|禁止|别|without|do not|don't|no need).{0,32}(?:"+host+")")||has(clause,"(?:"+host+").{0,12}(?:不需要|不是必需|不必|not required|unnecessary)"))continue;
            if(has(clause,host)&&has(clause,"用|使用|通过|执行|运行|调用|安装|打开|保存|写|处理|生成|创建|计算|\\b(use|using|run|execute|install|open|save|write|process|generate|create|calculate)\\b"))return true;
        }
        return false;
    }
    public static boolean hostTool(String name){return Set.of("inspect_host","read_host_output","python_execute","python_install_packages").contains(name);}
    public static Map<String,Object> nativeWorkflow(){return Map.of("status","REJECTED","error","GAME_CONTENT_TOOL_REQUIRED","executionState","NOT_STARTED","worldModified",false,"preferredSkills",List.of("content","items","entities"),"preferredTools",List.of("generate_content_package","inspect_modeling","validate_model_geometry","inspect_content_candidate","repair_content_package"),"diagnostic","当前请求是游戏内内容创建，没有要求本机操作。新物品（例如召唤权杖）使用generate_content_package及游戏内脚本/原生API；需要编写代码不等于需要Python。加载content及相关能力，读取契约后继续；候选失败返回具体诊断并局部修正。尚未请求本机执行。" );}
    private GameContentRouting(){}
}
