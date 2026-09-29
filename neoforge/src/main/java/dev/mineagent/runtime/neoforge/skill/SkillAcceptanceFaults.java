package dev.mineagent.runtime.neoforge.skill;
/** Opt-in isolated acceptance fault, after a genuine world effect and before its journal confirmation. */
final class SkillAcceptanceFaults {
    private static boolean used;
    static void beforePlantReceipt(){if(Boolean.getBoolean("mineagent.skillSmoke")&&Boolean.getBoolean("mineagent.skillSmokeUncertain")&&!used){used=true;throw new IllegalStateException("ACCEPTANCE_AFTER_NATIVE_PLANT_BEFORE_RECEIPT");}}
    private SkillAcceptanceFaults(){}
}
