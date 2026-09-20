package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Data-only, bounded real-player input plan. It is not a command/script execution channel. */
public record PlayerControlPlan(List<Step> steps) {
    public record Step(String action,int ticks,double yaw,double pitch,int slot){}
    public PlayerControlPlan { steps=List.copyOf(steps);if(steps.isEmpty()||steps.size()>16)throw new IllegalArgumentException("PLAYER_PLAN_SIZE");int duration=0;for(var s:steps){if(!Set.of("FORWARD","BACK","LEFT","RIGHT","JUMP","SNEAK","SPRINT_FORWARD","ATTACK","USE","WAIT","LOOK","HOTBAR").contains(s.action())||s.ticks()<1||s.ticks()>100||!Double.isFinite(s.yaw())||Math.abs(s.yaw())>180||!Double.isFinite(s.pitch())||Math.abs(s.pitch())>90||s.slot()<0||s.slot()>8)throw new IllegalArgumentException("PLAYER_PLAN_STEP");duration+=s.ticks();}if(duration>600)throw new IllegalArgumentException("PLAYER_PLAN_DURATION"); }
    public static PlayerControlPlan parse(String raw)throws Exception{
        if(raw==null||raw.length()>16000)throw new IllegalArgumentException("PLAYER_PLAN_SIZE");String text=raw.strip();if(text.startsWith("```")){int n=text.indexOf('\n');if(n<0||!text.endsWith("```"))throw new IllegalArgumentException("PLAYER_PLAN_JSON");text=text.substring(n+1,text.length()-3).strip();}
        var json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);var root=json.readTree(text);if(root==null||!root.isObject()||root.size()!=1||!root.path("steps").isArray())throw new IllegalArgumentException("PLAYER_PLAN_JSON");var result=new ArrayList<Step>();for(var n:root.path("steps")){if(!n.isObject()||n.size()!=5||!n.path("action").isTextual()||!n.path("ticks").isIntegralNumber()||!n.path("ticks").canConvertToInt()||!n.path("yaw").isNumber()||!n.path("pitch").isNumber()||!n.path("slot").isIntegralNumber()||!n.path("slot").canConvertToInt())throw new IllegalArgumentException("PLAYER_PLAN_FIELDS");result.add(new Step(n.path("action").asText(),n.path("ticks").intValue(),n.path("yaw").doubleValue(),n.path("pitch").doubleValue(),n.path("slot").intValue()));}return new PlayerControlPlan(result);
    }
    public static String instructions(){return "你是 Minecraft 真实玩家身体控制规划器。仅返回 JSON {\"steps\":[{\"action\":\"FORWARD\",\"ticks\":20,\"yaw\":0,\"pitch\":0,\"slot\":0}]}，每步必须恰有这五个字段。action 只能是 FORWARD/BACK/LEFT/RIGHT/JUMP/SNEAK/SPRINT_FORWARD/ATTACK/USE/WAIT/LOOK/HOTBAR。最多16步，每步1..100 ticks，总计<=600 ticks。20 ticks 约1秒。LOOK 的 yaw/pitch 为相对旋转，yaw -180..180，pitch -90..90；HOTBAR 的 slot 为0..8。不用的参数填0。仅控制当前本人，真实游戏规则照常；不传命令/脚本/URL。不知道周围情况时保守短步，不声称完成目标。";}
}
