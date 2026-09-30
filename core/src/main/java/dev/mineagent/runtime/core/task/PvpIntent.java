package dev.mineagent.runtime.core.task;
import java.util.*;
import java.util.regex.Pattern;

/** Narrow recognition of a first-party, explicitly named sparring/attack request. */
public final class PvpIntent {
    private PvpIntent(){}
    public static boolean namesTarget(String input,Collection<String> names,boolean self){
        String text=input.toLowerCase(Locale.ROOT);
        if(Pattern.compile("不要|不许|别打|禁止|停止|不攻击|解释|举例|假如|如果|don't|do not|stop|explain|example|what if").matcher(text).find())return false;
        if(!Pattern.compile("1v1|pvp|对打|决斗|切磋|攻击|打败|迎战|duel|spar|attack|fight").matcher(text).find())return false;
        if(self&&Pattern.compile("(和我|跟我|与我|攻击我|打败我|向我|fight me|attack me|duel me|spar with me)").matcher(text).find())return true;
        for(String name:names)if(name!=null&&!name.isBlank()){
            String lowered=name.toLowerCase(Locale.ROOT);
            if(Pattern.compile("(?<![a-z0-9_])"+Pattern.quote(lowered)+"(?![a-z0-9_])").matcher(text).find())return true;
        }return false;
    }
}
