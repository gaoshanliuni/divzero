package dev.mineagent.runtime.core.task;
import java.util.*;

/** Durable work survives interruptions; cancelled work never becomes runnable implicitly. */
public final class SkillSession {
    public enum State { RUNNING, WAITING, SUSPENDED, PAUSED, COMPLETED, FAILED, CANCELLED }
    public record Snapshot(UUID session,UUID owner,UUID agent,UUID world,UUID task,long taskIntent,SkillSpec spec,long revision,State state,String phase,String reason,long cursor,int waypoint,int direction,Map<String,Long> counters,Map<String,String> receipt,UUID previous){}
    private final UUID id,owner,agent,world,task;private final long taskIntent;private SkillSpec spec;private UUID previous;private long revision=1,cursor;private int waypoint,direction=1;
    private State state=State.RUNNING;private String phase="SCAN",reason="";private final Map<String,Long> counters=new LinkedHashMap<>();private Map<String,String> receipt=Map.of();
    public SkillSession(UUID id,UUID owner,UUID agent,UUID world,UUID task,SkillSpec spec){this(id,owner,agent,world,task,0,spec);}
    public SkillSession(UUID id,UUID owner,UUID agent,UUID world,UUID task,long taskIntent,SkillSpec spec){this.id=id;this.owner=owner;this.agent=agent;this.world=world;this.task=task;this.taskIntent=taskIntent;this.spec=spec;}
    public static SkillSession restore(Snapshot saved){var s=new SkillSession(saved.session,saved.owner,saved.agent,saved.world,saved.task,saved.taskIntent,saved.spec);s.previous=saved.previous;s.revision=saved.revision;s.cursor=saved.cursor;s.waypoint=saved.waypoint;s.direction=saved.direction;s.counters.putAll(saved.counters);s.receipt=Map.copyOf(saved.receipt);s.state=Set.of(State.COMPLETED,State.FAILED,State.CANCELLED).contains(saved.state)?saved.state:State.PAUSED;s.phase=saved.phase;s.reason=s.terminal()||Set.of("TEMPORARY_WORK","WAITING_RESPAWN").contains(saved.reason)?saved.reason:s.uncertain()?"RESTART_REQUIRES_RECONCILIATION":"RESTART_REQUIRES_RESUME";return s;}
    public Snapshot snapshot(){return new Snapshot(id,owner,agent,world,task,taskIntent,spec,revision,state,phase,reason,cursor,waypoint,direction,Map.copyOf(counters),receipt,previous);}
    public UUID previous(){return previous;}public void previous(UUID id){previous=id;}
    public void combat(long expected,CombatPolicy policy){if(expected!=revision||terminal())throw new IllegalStateException("SKILL_STALE_OR_TERMINAL");spec=spec.withCombat(policy);revision++;}
    public void adjust(long expected,SkillSpec next){
        if(expected!=revision||terminal())throw new IllegalStateException("SKILL_STALE_OR_TERMINAL");
        if(!next.id().equals(spec.id())||next.kind()!=spec.kind()||!next.actor().equals(spec.actor())||!next.dimension().equals(spec.dimension())||!next.combat().equals(spec.combat()))throw new IllegalArgumentException("SKILL_ADJUST_IDENTITY");
        if(uncertain())throw new IllegalStateException("SKILL_RECONCILIATION_REQUIRED");
        if(!Objects.equals(next.area(),spec.area())||!next.crop().equals(spec.crop()))cursor=0;
        if(!next.route().equals(spec.route())&&(waypoint>=next.route().size()||waypoint>=spec.route().size()||!next.route().get(waypoint).equals(spec.route().get(waypoint))))waypoint=0;
        spec=next;revision++;phase="SCAN";if(state!=State.PAUSED)reason="INTENT_ADJUSTED_RECHECK_TARGET";
    }
    public SkillSpec spec(){return spec;}public UUID id(){return id;}public UUID owner(){return owner;}public UUID agent(){return agent;}public long revision(){return revision;}public State state(){return state;}public String phase(){return phase;}public String reason(){return reason;}public long cursor(){return cursor;}public int waypoint(){return waypoint;}public Map<String,String> receipt(){return receipt;}
    public void waitForRespawn(){if(terminal())return;state=State.PAUSED;reason="WAITING_RESPAWN";revision++;}
    public void followRespawnDimension(String dimension){if(terminal()||!reason.equals("WAITING_RESPAWN"))throw new IllegalStateException("RESPAWN_INTENT_CHANGED");if(!spec.dimension().equals(dimension)){spec=spec.afterFollowRespawn(dimension);revision++;}}
    public boolean terminal(){return Set.of(State.CANCELLED,State.COMPLETED,State.FAILED).contains(state);}
    public boolean runnable(){return state==State.RUNNING||state==State.WAITING||state==State.SUSPENDED;}
    public void phase(String phase){this.phase=phase;}
    public void transition(State state,String reason){if(terminal()&&this.state!=state)throw new IllegalStateException("SKILL_TERMINAL");this.state=state;this.reason=Objects.requireNonNullElse(reason,"");}
    private boolean uncertain(){return receipt.getOrDefault("state","").equals("PREPARED")||receipt.getOrDefault("combatState","").equals("PREPARED");}
    public void control(long expected,String action){if(expected!=revision)throw new IllegalStateException("SKILL_STALE_REVISION");if(terminal())throw new IllegalStateException("SKILL_TERMINAL");switch(action){case "pause"->transition(State.PAUSED,"USER_PAUSED");case "resume"->{if(uncertain())throw new IllegalStateException("SKILL_RECONCILIATION_REQUIRED");transition(State.RUNNING,"");}case "stop"->transition(State.CANCELLED,"USER_CANCELLED");default->throw new IllegalArgumentException("SKILL_ACTION");}revision++;}
    public void cursor(long cursor){this.cursor=cursor;}public void waypoint(int index){waypoint=index;}public int direction(){return direction;}public void direction(int direction){this.direction=direction;}public void add(String counter,long amount){counters.merge(counter,amount,Long::sum);}public long count(String counter){return counters.getOrDefault(counter,0L);}
    public void receipt(Map<String,String> receipt){this.receipt=Map.copyOf(receipt);}
    public void reconcile(long expected,Map<String,String> observation){if(expected!=revision||terminal())throw new IllegalStateException("SKILL_STALE_OR_TERMINAL");var old=new LinkedHashMap<>(receipt);old.put("previousState",old.getOrDefault("state",""));old.put("previousCombatState",old.getOrDefault("combatState",""));old.putAll(observation);old.put("state","RECONCILED_OBSERVATION_ONLY");old.put("combatState","RECONCILED_OBSERVATION_ONLY");receipt=Map.copyOf(old);state=State.PAUSED;reason="RECONCILED_REQUIRES_EXPLICIT_RESUME";revision++;}
}
