package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Persistent high-level intent. It carries no native player or client objects. */
public record SkillSpec(String id,Kind kind,String actor,String dimension,String target,Area area,
                        List<Point> route,boolean repeat,boolean defend,boolean allowTeleport,boolean pingPong,boolean till,
                        double startDistance,double stopDistance,int dwellTicks,int limit,String crop,CombatPolicy combat,boolean resumePrevious) {
    public enum Kind { IDLE, WANDER, FOLLOW, PATROL, GUARD, COMBAT, FARM, FISH }
    public SkillSpec(String id,Kind kind,String actor,String dimension,String target,Area area,List<Point> route,boolean repeat,boolean defend,boolean allowTeleport,boolean pingPong,boolean till,double startDistance,double stopDistance,int dwellTicks,int limit,String crop){this(id,kind,actor,dimension,target,area,route,repeat,defend,allowTeleport,pingPong,till,startDistance,stopDistance,dwellTicks,limit,crop,null,false);}
    public String title(){return switch(kind){case IDLE->"待命";case WANDER->"区域漫步";case FOLLOW->"持续跟随";case PATROL->"路线巡逻";case GUARD->"区域警戒";case COMBAT->"战斗";case FARM->"维护农田";case FISH->"钓鱼";};}
    public SkillSpec withCombat(CombatPolicy policy){return new SkillSpec(id,kind,actor,dimension,target,area,route,repeat,policy.engagement()!=CombatPolicy.Engagement.NONE,allowTeleport,pingPong,till,startDistance,stopDistance,dwellTicks,limit,crop,policy,resumePrevious);}
    public record Point(double x,double y,double z){public Point{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000||Math.abs(y)>4096)throw new IllegalArgumentException("SKILL_POINT");}}
    public record Area(Point min,Point max){
        public Area{Objects.requireNonNull(min);Objects.requireNonNull(max);if(min.x>max.x||min.y>max.y||min.z>max.z||max.x-min.x>2047||max.y-min.y>2047||max.z-min.z>2047)throw new IllegalArgumentException("SKILL_AREA");}
        public boolean contains(Point p){return p.x>=min.x&&p.x<=max.x+1&&p.y>=min.y&&p.y<=max.y+2&&p.z>=min.z&&p.z<=max.z+1;}
        public long volume(){return ((long)Math.floor(max.x)-(long)Math.floor(min.x)+1)*((long)Math.floor(max.y)-(long)Math.floor(min.y)+1)*((long)Math.floor(max.z)-(long)Math.floor(min.z)+1);}
        public Point cell(long index){long sx=(long)Math.floor(max.x)-(long)Math.floor(min.x)+1,sz=(long)Math.floor(max.z)-(long)Math.floor(min.z)+1;return new Point(Math.floor(min.x)+index%sx,Math.floor(min.y)+index/(sx*sz),Math.floor(min.z)+index/sx%sz);}
    }
    public SkillSpec {
        if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}")||kind==null||!Set.of("ai","player").contains(actor)||dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("SKILL_IDENTITY");
        target=target==null?"":target;crop=crop==null?"":crop;route=List.copyOf(route);
        if(!target.isEmpty()&&!target.equals("$owner"))UUID.fromString(target);
        if(!Double.isFinite(startDistance)||!Double.isFinite(stopDistance)||stopDistance<1||startDistance<=stopDistance||startDistance>128||dwellTicks<0||dwellTicks>72000||limit<0||route.size()>128)throw new IllegalArgumentException("SKILL_POLICY");
        if(kind==Kind.FOLLOW&&target.isEmpty()||kind==Kind.PATROL&&route.isEmpty()||Set.of(Kind.FARM,Kind.WANDER,Kind.FISH).contains(kind)&&area==null||kind==Kind.GUARD&&area==null&&target.isBlank())throw new IllegalArgumentException("SKILL_TARGET_REQUIRED");
        if(kind==Kind.FOLLOW&&actor.equals("player")&&target.equals("$owner"))throw new IllegalArgumentException("SKILL_CANNOT_FOLLOW_SELF");
        if(!crop.isBlank()&&!crop.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("SKILL_CROP_ID");
        combat=combat==null?CombatPolicy.defaults(kind,defend,target):combat;
        if(combat.engagement()==CombatPolicy.Engagement.CLEAR_AREA&&combat.area()==null)combat=combat.withArea(area);
        if(combat.engagement()==CombatPolicy.Engagement.SPECIFIED&&combat.target().isBlank())throw new IllegalArgumentException("COMBAT_SPECIFIED_TARGET_REQUIRED");
        if(combat.engagement()==CombatPolicy.Engagement.CLEAR_AREA&&combat.area()==null)throw new IllegalArgumentException("COMBAT_AREA_REQUIRED");
    }
    public static SkillSpec parse(JsonNode n,Kind alias){
        if(!n.isObject())throw new IllegalArgumentException("SKILL_ARGUMENTS");var keys=Set.of("id","kind","actor","dimension","target","min","max","route","repeat","defend","allow_teleport","ping_pong","till","start_distance","stop_distance","dwell_ticks","limit","crop","expected_revision","combat","resume_previous");
        for(var e:n.properties())if(!keys.contains(e.getKey()))throw new IllegalArgumentException("SKILL_FIELD_"+e.getKey());
        for(String field:List.of("repeat","defend","allow_teleport","ping_pong","till","resume_previous"))if(n.has(field)&&!n.get(field).isBoolean())throw new IllegalArgumentException("SKILL_BOOLEAN");
        for(String field:List.of("dwell_ticks","limit"))if(n.has(field)&&(!n.get(field).isIntegralNumber()||!n.get(field).canConvertToInt()))throw new IllegalArgumentException("SKILL_INTEGER");
        var route=new ArrayList<Point>();if(n.has("route")){if(!n.get("route").isArray())throw new IllegalArgumentException("SKILL_ROUTE");for(var point:n.get("route"))route.add(point(point));}
        Kind kind=alias==null?Kind.valueOf(n.path("kind").asText().toUpperCase(Locale.ROOT)):alias;
        var policy=CombatPolicy.parse(n.get("combat"),CombatPolicy.defaults(kind,n.path("defend").asBoolean(true),n.path("target").asText()));
        return new SkillSpec(n.path("id").asText(),kind,n.path("actor").asText("ai"),n.path("dimension").asText(),n.path("target").asText(),n.has("min")||n.has("max")?new Area(point(n.path("min")),point(n.path("max"))):null,route,n.path("repeat").asBoolean(true),n.path("defend").asBoolean(true),n.path("allow_teleport").asBoolean(false),n.path("ping_pong").asBoolean(false),n.path("till").asBoolean(false),n.path("start_distance").asDouble(6),n.path("stop_distance").asDouble(2.5),n.path("dwell_ticks").asInt(40),n.path("limit").asInt(0),n.path("crop").asText(),policy,n.path("resume_previous").asBoolean(false));
    }
    public static Point point(JsonNode n){if(!n.isArray()||n.size()!=3||!n.get(0).isNumber()||!n.get(1).isNumber()||!n.get(2).isNumber())throw new IllegalArgumentException("SKILL_POINT");return new Point(n.get(0).asDouble(),n.get(1).asDouble(),n.get(2).asDouble());}
}
