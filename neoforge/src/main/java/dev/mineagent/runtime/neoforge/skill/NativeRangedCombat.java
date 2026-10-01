package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.body.NativeNavigationBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import java.util.*;

/** Native charge/use differences are explicit; a camera aim or submitted release is never a hit receipt. */
final class NativeRangedCombat {
    static final class State {final RangedContactEscape contact=new RangedContactEscape();String adapter="";Vec3 aim,origin,target;int planned=-10000,shootStage,dodgeUntil=-10000,dodgeSide;UUID release,issuedOperation;int issuedAt=-10000;boolean deferred;}
    private record Vanilla(String id,Item item,Use use,double speed,double gravity,double range,double pitchOffset,double areaRadius) implements RangedWeaponAdapter {
        public boolean matches(ItemStack stack){return stack.is(item);}
        public boolean ammunition(net.minecraft.server.level.ServerPlayer p,ItemStack stack){
            if(stack.is(Items.TRIDENT)&&net.minecraft.world.item.enchantment.EnchantmentHelper.getTridentSpinAttackStrength(stack,p)>0)return false;
            if(stack.is(Items.BOW)||stack.is(Items.CROSSBOW))return stack.is(Items.CROSSBOW)&&CrossbowItem.isCharged(stack)||p.hasInfiniteMaterials()||!p.getProjectile(stack).isEmpty();
            if(stack.is(Items.SPLASH_POTION)){var effects=stack.get(DataComponents.POTION_CONTENTS);if(effects==null)return false;for(var effect:effects.getAllEffects())if(effect.getEffect().value().getCategory()==net.minecraft.world.effect.MobEffectCategory.HARMFUL)return true;return false;}
            return true;
        }
        public boolean ready(net.minecraft.server.level.ServerPlayer p,ItemStack stack){
            if(stack.is(Items.CROSSBOW))return CrossbowItem.isCharged(stack);
            if(stack.is(Items.BOW))return p.isUsingItem()&&p.getUsedItemHand()==InteractionHand.MAIN_HAND&&p.getUseItem().is(Items.BOW)&&BowItem.getPowerForTime(p.getTicksUsingItem())>=.99f;
            if(stack.is(Items.TRIDENT))return p.isUsingItem()&&p.getUsedItemHand()==InteractionHand.MAIN_HAND&&p.getUseItem().is(Items.TRIDENT)&&p.getTicksUsingItem()>=TridentItem.THROW_THRESHOLD_TIME;
            return true;
        }
        public BallisticIntercept.Physics physics(net.minecraft.server.level.ServerPlayer p,ItemStack stack){return new BallisticIntercept.Physics(speed,gravity,.99,60);}
        public Vec3 inheritedMovement(net.minecraft.server.level.ServerPlayer p){if(item==Items.CROSSBOW)return Vec3.ZERO;var movement=p.getKnownMovement();return new Vec3(movement.x,p.onGround()?0:movement.y,movement.z);}
    }
    private static final List<RangedWeaponAdapter> VANILLA=List.of(
            new Vanilla("minecraft:bow",Items.BOW,RangedWeaponAdapter.Use.CHARGE_RELEASE,3,.05,24,0,0),
            new Vanilla("minecraft:crossbow",Items.CROSSBOW,RangedWeaponAdapter.Use.CHARGE_CLICK,3.15,.05,24,0,0),
            new Vanilla("minecraft:trident",Items.TRIDENT,RangedWeaponAdapter.Use.CHARGE_RELEASE,2.5,.05,18,0,0),
            new Vanilla("minecraft:snowball",Items.SNOWBALL,RangedWeaponAdapter.Use.CLICK,1.5,.03,12,0,0),
            new Vanilla("minecraft:egg",Items.EGG,RangedWeaponAdapter.Use.CLICK,1.5,.03,12,0,0),
            new Vanilla("minecraft:splash_potion",Items.SPLASH_POTION,RangedWeaponAdapter.Use.CLICK,.5,.05,5,-20,4));
    private record Selected(RangedWeaponAdapter adapter,int slot){}
    static boolean rangedOnly(SkillWork w){return select(w)!=null&&!CombatEquipmentAdapter.hasMeleeWeapon(w);}
    static boolean loadedShot(SkillWork w){var selected=select(w);return selected!=null&&selected.adapter.matches(w.player().getMainHandItem())&&selected.adapter.ready(w.player(),w.player().getMainHandItem());}
    private static Selected select(SkillWork work){
        var p=work.player();var adapters=new ArrayList<>(RangedWeaponAdapter.REGISTERED);adapters.addAll(VANILLA);
        for(var adapter:adapters)for(int slot=0;slot<36;slot++){var stack=p.getInventory().getItem(slot);if(stack.isEmpty()||stack.isDamageableItem()&&stack.getDamageValue()>=stack.getMaxDamage()-1||p.getCooldowns().isOnCooldown(stack))continue;
            if(stack.is(Items.CROSSBOW)&&stack.getOrDefault(DataComponents.CHARGED_PROJECTILES,net.minecraft.world.item.component.ChargedProjectiles.EMPTY).contains(Items.FIREWORK_ROCKET))continue;
            if(adapter.matches(stack)&&adapter.ammunition(p,stack))return new Selected(adapter,slot);
        }return null;
    }
    static boolean tick(SkillWork w,LivingEntity target){
        var p=w.player();var strategy=w.session.spec().combat().strategy();double distance=p.distanceTo(target);
        if(strategy!=CombatPolicy.Strategy.AUTO&&strategy!=CombatPolicy.Strategy.RANGED_KITE&&strategy!=CombatPolicy.Strategy.HOLD_POSITION)return false;
        boolean melee=false;for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.SWORDS)||p.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.AXES)){melee=true;break;}
        if(melee&&distance<4||strategy==CombatPolicy.Strategy.AUTO&&melee&&distance<6)return false;
        var selected=select(w);if(selected==null)return false;var adapter=selected.adapter;var state=w.ranged;
        if(!adapter.id().equals(state.adapter)){w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;state.shootStage=0;state.adapter=adapter.id();state.planned=-10000;}
        if(!adapter.matches(p.getMainHandItem())){if(p.isUsingItem())w.actor.stop(w.token());w.actor.select(w.token(),selected.slot);return true;}
        if(w.combatStage==0){w.combatOperation=UUID.randomUUID();w.combatAt=w.tick();w.combatStage=1;w.combatAmmo=w.count(Items.ARROW);w.shotLogged=false;state.shootStage=0;state.release=null;w.session.add("rangedAttempts_"+adapter.id(),1);}
        if(w.combatStage==1&&adapter.use()!=RangedWeaponAdapter.Use.CLICK&&!adapter.ready(p,p.getMainHandItem())){w.actor.aim(w.token(),target.getEyePosition());w.actor.useHand(w.token(),w.combatOperation,InteractionHand.MAIN_HAND);}
        // A loaded shot gets its own checked aim before optional retreat planning consumes the shared slice.
        boolean priorityShot=adapter.ready(p,p.getMainHandItem());Vec3 priorityAim=priorityShot?plan(w,target,adapter,true):null;
        double desired=Math.clamp(adapter.range()*.55,3,12);w.actor.sprint(w.token(),false);
        boolean dodge=w.combat.incoming(w)&&strategy!=CombatPolicy.Strategy.HOLD_POSITION;
        if(dodge&&state.dodgeUntil<w.tick()){int side=w.footwork.choose(w.tick(),w.positioning.sideRisk(w,1),w.positioning.sideRisk(w,-1));if(side!=0){state.dodgeSide=side;state.dodgeUntil=w.tick()+10;w.session.add("rangedProjectileEvasions",1);}}
        if(distance<Math.min(4,desired-.6)||distance>adapter.range()*.9||!p.hasLineOfSight(target)){
            if(strategy==CombatPolicy.Strategy.HOLD_POSITION){w.actor.haltMotion(w.token());w.session.phase("RANGED_HOLD_RANGE");return true;}
            var point=distance<desired?w.positioning.attackExit(w,target):null;if(point==null)point=w.positioning.choose(w,distance<desired?"RETREAT":"RANGED",desired);
            if(point==null){w.actor.haltMotion(w.token());if(!w.positioning.pending()&&!p.isUsingItem()&&!priorityShot)w.actor.recover(w.token(),target.position());}
            else w.actor.moveTactically(w.token(),w.positioning.route());
        }else if(state.dodgeUntil>=w.tick()){
            var point=w.positioning.choose(w,state.dodgeSide>0?"SIDE_LEFT":"SIDE_RIGHT",Math.max(4,distance));if(point!=null)w.actor.moveTactically(w.token(),w.positioning.route());else w.actor.haltMotion(w.token());
        }else w.actor.haltMotion(w.token());
        Vec3 aim=priorityShot?priorityAim:plan(w,target,adapter,w.combatStage>=2);
        if(aim==null&&state.deferred){if((w.combatStage==1||p.isUsingItem())&&adapter.use()!=RangedWeaponAdapter.Use.CLICK)w.actor.useHand(w.token(),w.combatOperation,InteractionHand.MAIN_HAND);w.session.phase("RANGED_PLANNING");return true;}
        if(aim==null){w.session.phase("RANGED_LINE_CHECK");if(p.isUsingItem())w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;return true;}
        w.actor.aim(w.token(),aim);
        if(w.tick()-w.combatAt>120){w.actor.stop(w.token());w.combatOperation=null;w.combatStage=0;state.planned=-10000;w.session.add("rangedAttemptsExpired",1);return true;}
        if(w.combatStage==1){
            w.session.phase("RANGED_CHARGING");
            if(!adapter.ready(p,p.getMainHandItem())){w.actor.useHand(w.token(),w.combatOperation,InteractionHand.MAIN_HAND);return true;}
            if(adapter.use()==RangedWeaponAdapter.Use.CHARGE_CLICK&&p.isUsingItem()){w.actor.releaseItem(w.token(),w.combatOperation);return true;}
            w.combatStage=2;w.combatAt=w.tick();
        }
        if(w.combatStage==2){
            if(p.getLookAngle().dot(aim.subtract(p.getEyePosition()).normalize())<.997)return true;
            w.session.phase("RANGED_RELEASE");
            state.issuedOperation=w.combatOperation;state.issuedAt=w.tick();
            if(adapter.use()==RangedWeaponAdapter.Use.CHARGE_RELEASE)w.actor.releaseItem(w.token(),w.combatOperation);
            else {if(state.release==null){state.release=UUID.randomUUID();w.combatOperation=state.release;}state.issuedOperation=w.combatOperation;w.actor.useOnce(w.token(),w.combatOperation,InteractionHand.MAIN_HAND);}
            if(!p.isUsingItem()&&w.tick()-w.combatAt>=6){w.combatStage=0;w.combatOperation=null;w.lastAttackAt=w.tick();state.planned=-10000;}
        }return true;
    }
    private static Vec3 plan(SkillWork w,LivingEntity target,RangedWeaponAdapter adapter,boolean force){
        var state=w.ranged;var p=w.player();
        if(!force&&state.aim!=null&&w.tick()-state.planned<=3&&p.position().distanceToSqr(state.origin)<.16&&target.position().distanceToSqr(state.target)<.25)return state.aim;
        state.deferred=false;var budget=NativeNavigationBudget.get(w.runtime.server);if(budget.claim(w.token(),w.tick())==0){state.deferred=true;return null;}
        var start=p.getEyePosition().add(0,-.1,0);var offset=target.getBoundingBox().getCenter().subtract(target.position());
        var friends=p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().minmax(target.getBoundingBox()).inflate(5),e->e!=p&&e!=target&&e.isAlive()&&(!SkillRuntime.attackAllowed(w,e)||!(e instanceof net.minecraft.world.entity.monster.Enemy)));
        var shot=BallisticIntercept.solve(point(start),adapter.physics(p,p.getMainHandItem()),point(adapter.inheritedMovement(p)),time->point(w.prediction.intercept(w,target,time).add(offset)),Math.max(.25,Math.min(.5,target.getBbWidth()*.6)),(a,b,time)->{
            var from=vec(a);var to=vec(b);if(!p.level().hasChunkAt(BlockPos.containing(to)))return false;
            if(p.level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getType()!=HitResult.Type.MISS)return false;
            for(var friend:friends){var box=friend.getBoundingBox().move(friend.getDeltaMovement().scale(Math.min(time,6))).inflate(.35);if(box.contains(from)||box.clip(from,to).isPresent())return false;if(adapter.areaRadius()>0&&friend.position().distanceTo(to)<adapter.areaRadius())return false;}
            return true;
        },budget::timeAvailable);
        if(shot.isEmpty()){state.deferred=!budget.timeAvailable();return null;}var direction=vec(shot.get().direction());double offsetAngle=Math.toRadians(adapter.pitchOffset());double pitch=Math.atan((direction.y/Math.max(.00001,direction.horizontalDistance())+Math.sin(offsetAngle))/Math.cos(offsetAngle));var horizontal=new Vec3(direction.x,0,direction.z).normalize();
        state.aim=p.getEyePosition().add(horizontal.scale(Math.cos(pitch)*16)).add(0,Math.sin(pitch)*16,0);state.origin=p.position();state.target=target.position();state.planned=w.tick();w.session.add("projectileInterceptPlans",1);return state.aim;
    }
    private static MotionForecast.Point point(Vec3 p){return new MotionForecast.Point(p.x,p.y,p.z);}
    private static Vec3 vec(MotionForecast.Point p){return new Vec3(p.x(),p.y(),p.z());}
    private NativeRangedCombat(){}
}
