package dev.mineagent.runtime.core.conversation;

import java.util.*;
import java.util.regex.Pattern;

/** Request/task-local disclosure. It has no reference to skills, bodies or permissions. */
public final class CapabilitySession {
    private final LinkedHashSet<String> groups=new LinkedHashSet<>(),tools=new LinkedHashSet<>(CapabilityCatalog.RESIDENT);
    public synchronized Map<String,Object> load(String name){
        var group=CapabilityCatalog.require(name);boolean added=groups.add(name);for(String tool:group.tools())tools.add(CapabilityCatalog.canonical(tool));
        return Map.of("status",added?"LOADED":"ALREADY_LOADED","name",name,"description",group.description(),"tools",group.tools(),"contextOnly",true,"executionAuthorized",false);
    }
    public synchronized List<String> groups(){return List.copyOf(groups);}
    public synchronized List<String> tools(){return List.copyOf(tools);}
    public synchronized List<ConversationTools.Definition> definitions(){return CapabilityCatalog.selected(tools);}
    public synchronized void used(String tool){
        if(!ConversationTools.NAMES.contains(tool))return;
        String canonical=CapabilityCatalog.canonical(tool);if(tools.contains(canonical))return;
        var candidates=CapabilityCatalog.groupsFor(canonical);if(candidates.size()==1)load(candidates.getFirst());else tools.add(canonical);
    }
    /** Explicit task verbs preload relevant domains. Discovery remains available for all other wording. */
    public synchronized void preload(String input,Collection<String> continuation){
        String text=input.toLowerCase(Locale.ROOT);
        boolean continues=matches(text,"^(继续|接着|continue|resume)[。.!！\\s]*$|刚才|上次那个|继续之前|继续上次|same task|continue that");
        if(continues&&continuation!=null)for(String group:continuation)load(group);
        boolean act=matches(text,"创建|生成|建造|搭建|制作|修改|更改|添加|增加|删除|设置|替换|帮我|请你|我要|给我|打开|检查|查看|查询|读取|导入|修复|设计|扩大|缩小|预览|create|build|make|modify|change|add|remove|set |open |inspect|import|fix|show|preview");
        var chosen=new LinkedHashSet<String>();
        if(matches(text,"记住|记忆|我喜欢|偏好|喜欢的|回家|remember|preference|my home|go home"))chosen.add("memory");
        if(matches(text,"跟着|跟随|巡逻|漫步|接管|托管|回家|follow|patrol|take over|autopilot"))chosen.add("movement");
        if(matches(text,"种田|种地|收割|补种|钓鱼|耕田|farm|fishing|harvest|replant"))chosen.add("farming");
        if(matches(text,"战斗|跑打|对打|警戒|守住|格挡|反击|jump.?tap|w.?tap|s.?tap|pvp|combat|fight|guard|kite"))chosen.add("combat");
        if(matches(text,"(建|盖|造).{0,8}(房|屋|楼|桥)"))chosen.add("building");
        if(matches(text,"(创建|做|建).{0,8}商店")){chosen.add("ui");chosen.add("items");}
        if(act&&matches(text,"建筑|房子|房屋|屋顶|地基|楼梯|阳台|窗户|蓝图|搭桥|几何|house|building|roof|foundation|balcony|blueprint|bridge"))chosen.add("building");
        if(act&&matches(text,"界面|菜单|工作区|悬浮|血条|hud|\\bui\\b|gui|panel|desktop|overlay"))chosen.add("ui");
        if(act&&matches(text,"物品|装备|胸甲|裤子|护腿|武器|附魔|药水|剑|斧|钻石|item|armor|equipment|sword|enchant"))chosen.add("items");
        if(act&&matches(text,"模型|建模|内容包|模组|脚本|model|mesh|package|script"))chosen.add("content");
        if(act&&matches(text,"生物|实体|动画|羊|僵尸|骷髅|entity|creature|animation|mob|boss"))chosen.add("entities");
        if(act&&matches(text,"皮肤|外观|人设|性格|skin|appearance|persona|ysm"))chosen.add("appearance");
        if(act&&matches(text,"文件|上传|下载|file|upload|download"))chosen.add("files");
        if(matches(text,"网上|上网|联网|搜索|网页|图片|search|website|web page"))chosen.add("web");
        if(matches(text,"本机|电脑|python|安装.*库|打开.*网易云|打开.*记事本|terminal|operating system"))chosen.add("host");
        if(act&&matches(text,"推土机|贴图|纹理|回调|上锁|bulldozer|texture|callback|lock"))chosen.add("rules");
        if(act&&matches(text,"聊天|消息|思考|默认响应|chat|message|thinking"))chosen.add("chat");
        if(act&&matches(text,"命令|游戏规则|容器|箱子|方块|坐标|世界状态|command|gamerule|container|chest|block|coordinates"))chosen.add("world");
        // Stable catalog order for initial preloads; later discoveries append without reshuffling existing tools.
        for(var group:CapabilityCatalog.GROUPS)if(chosen.contains(group.name()))load(group.name());
    }
    private static boolean matches(String input,String expression){return Pattern.compile(expression,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(input).find();}
}
