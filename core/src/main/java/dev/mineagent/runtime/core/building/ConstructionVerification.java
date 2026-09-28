package dev.mineagent.runtime.core.building;

import java.util.*;

/** Completion evidence covers exact mutations and semantic requirements, never an unrelated readback. */
public final class ConstructionVerification {
    public record Scope(UUID world,UUID owner,UUID agent,String building,long revision,String footprintHash){
        public Scope{Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(agent);if(building==null||building.isBlank()||revision<1||footprintHash==null||!footprintHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("BUILDING_VERIFICATION_SCOPE");}
    }
    public record Check(String id,boolean passed,String detail){}
    public record Report(Scope scope,long observedAfterTick,long completedTick,long coveredChanges,boolean blocksMatch,List<Check> checks){
        public Report{checks=List.copyOf(checks);}
    }
    private final Scope scope;
    private final long changeCount,mutationTick;
    private final Set<String> requiredChecks;
    private Report accepted;
    public ConstructionVerification(Scope scope,long changeCount,long mutationTick,Set<String> requiredChecks){
        if(changeCount<0||mutationTick<0||requiredChecks.isEmpty())throw new IllegalArgumentException("BUILDING_VERIFICATION_REQUIREMENTS");
        this.scope=scope;this.changeCount=changeCount;this.mutationTick=mutationTick;this.requiredChecks=Set.copyOf(requiredChecks);
    }
    public String status(){return accepted==null?"UNVERIFIED":"VERIFIED";}
    public Optional<Report> accepted(){return Optional.ofNullable(accepted);}
    public boolean accept(Report report){
        if(!scope.equals(report.scope)||report.observedAfterTick<=mutationTick||report.completedTick<report.observedAfterTick||report.coveredChanges!=changeCount)throw new IllegalArgumentException("BUILDING_VERIFICATION_STALE_OR_UNRELATED");
        var checks=new HashMap<String,Check>();for(var check:report.checks)if(checks.putIfAbsent(check.id,check)!=null)throw new IllegalArgumentException("BUILDING_VERIFICATION_DUPLICATE_CHECK");
        if(!checks.keySet().equals(requiredChecks))throw new IllegalArgumentException("BUILDING_VERIFICATION_INCOMPLETE_CHECKS");
        if(!report.blocksMatch||checks.values().stream().anyMatch(c->!c.passed)){accepted=null;return false;}
        accepted=report;return true;
    }
    public void invalidate(){accepted=null;}
}
