package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.CombatPolicy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Trusted Java extensions use the same actor lease; they must preserve native consumption and report observations. */
public interface CombatEquipmentAdapter {
    record Context(SkillActor actor,UUID session,UUID operation,LivingEntity target,
                   NativeCombatStates.Snapshot observed,CombatPolicy policy,boolean safeToEngage){}
    String id();
    /** Positive scores opt in. Unadapted items continue through the standard native controller. */
    int score(ItemStack stack,CombatPolicy policy);
    boolean tick(Context context);
    List<CombatEquipmentAdapter> REGISTRY=new CopyOnWriteArrayList<>();
    static void register(CombatEquipmentAdapter adapter){Objects.requireNonNull(adapter);if(REGISTRY.stream().anyMatch(a->a.id().equals(adapter.id())))throw new IllegalArgumentException("COMBAT_ADAPTER_DUPLICATE");REGISTRY.add(adapter);}
    static boolean execute(SkillWork w,LivingEntity target){
        CombatEquipmentAdapter best=null;int score=0,slot=-1;
        for(var adapter:REGISTRY)for(int i=0;i<36;i++){int value=adapter.score(w.player().getInventory().getItem(i),w.session.spec().combat());if(value>score){score=value;slot=i;best=adapter;}}
        if(best==null)return false;if(!w.actor.select(w.token(),slot))return true;
        if(w.extensionOperation==null)w.extensionOperation=UUID.randomUUID();
        return best.tick(new Context(w.actor,w.token(),w.extensionOperation,target,NativeCombatStates.read(target,w.player()),w.session.spec().combat(),!w.combat.flanked(w)&&w.combat.contacts(w)<=1));
    }
}
