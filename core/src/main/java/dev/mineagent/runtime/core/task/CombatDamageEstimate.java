package dev.mineagent.runtime.core.task;

/** Vanilla armor/toughness estimate for route costs only; actual damage remains native. */
public final class CombatDamageEstimate {
    public static double afterArmor(double damage,double armor,double toughness){
        if(!Double.isFinite(damage)||!Double.isFinite(armor)||!Double.isFinite(toughness))throw new IllegalArgumentException("DAMAGE_ESTIMATE_NONFINITE");
        damage=Math.max(0,damage);armor=Math.max(0,armor);toughness=Math.max(0,toughness);
        double effective=Math.clamp(armor-damage/(2+toughness/4),Math.min(20,armor*.2),20);
        return damage*(1-effective/25);
    }
    private CombatDamageEstimate(){}
}
