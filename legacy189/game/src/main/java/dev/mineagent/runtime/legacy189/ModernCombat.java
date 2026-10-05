package dev.mineagent.runtime.legacy189;

import net.minecraft.enchantment.*;
import net.minecraft.entity.*;
import net.minecraft.entity.player.*;
import net.minecraft.init.Items;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.potion.Potion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.StatList;
import net.minecraft.util.*;
import net.minecraftforge.common.ISpecialArmor;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.*;
import java.util.*;

/** Modern vanilla melee rules use actual native hurt/armor/durability/velocity
 * paths. This is not a damage bonus or a client-side attack-speed bypass.
 */
public final class ModernCombat {
    public static volatile boolean serverEnabled, clientEnabled;
    private static final Map<EntityPlayer, Cooldown> COOLDOWNS = new WeakHashMap<EntityPlayer, Cooldown>();
    private static final Map<Item, Double> SPEED = new IdentityHashMap<Item, Double>();
    private static final Map<Item, Double> DAMAGE_DELTA = new IdentityHashMap<Item, Double>();
    private static boolean initialized;
    private ModernCombat() { }
    public static boolean enabled(Entity entity) { return entity != null && entity.worldObj != null && (entity.worldObj.isRemote ? clientEnabled : serverEnabled); }
    private static void initialize() {
        if (initialized) return; initialized = true;
        Item[] swords = {Items.wooden_sword, Items.stone_sword, Items.iron_sword, Items.diamond_sword, Items.golden_sword};
        for (Item item : swords) { SPEED.put(item, 1.6); DAMAGE_DELTA.put(item, -1d); }
        Item[] axes = {Items.wooden_axe, Items.stone_axe, Items.iron_axe, Items.diamond_axe, Items.golden_axe};
        double[] speeds = {.8, .8, .9, 1, 1}, deltas = {3, 4, 3, 2, 3};
        for (int i = 0; i < axes.length; i++) { SPEED.put(axes[i], speeds[i]); DAMAGE_DELTA.put(axes[i], deltas[i]); }
        for (Item item : new Item[] {Items.wooden_pickaxe, Items.stone_pickaxe, Items.iron_pickaxe, Items.diamond_pickaxe, Items.golden_pickaxe}) { SPEED.put(item, 1.2); DAMAGE_DELTA.put(item, -1d); }
        for (Item item : new Item[] {Items.wooden_shovel, Items.stone_shovel, Items.iron_shovel, Items.diamond_shovel, Items.golden_shovel}) { SPEED.put(item, 1d); DAMAGE_DELTA.put(item, .5); }
        Item[] hoes = {Items.wooden_hoe, Items.stone_hoe, Items.iron_hoe, Items.diamond_hoe, Items.golden_hoe};
        double[] hoeSpeeds = {1, 2, 3, 4, 1}; for (int i = 0; i < hoes.length; i++) SPEED.put(hoes[i], hoeSpeeds[i]);
    }
    public static double speed(EntityPlayer player) { initialize(); ItemStack stack = player.getHeldItem(); return stack == null ? 4 : SPEED.containsKey(stack.getItem()) ? SPEED.get(stack.getItem()) : stack.getItem() instanceof ItemSword ? 1.6 : 4; }
    private static Cooldown state(EntityPlayer player) {
        Cooldown value = COOLDOWNS.get(player); long now = MinecraftServer.getServer().getTickCounter();
        if (value == null) { value = new Cooldown(); value.last = now; COOLDOWNS.put(player, value); }
        ItemStack held = player.getHeldItem();
        if (!ItemStack.areItemStacksEqual(value.held, held)) { value.held = held == null ? null : held.copy(); value.last = now; }
        return value;
    }
    public static float strength(EntityPlayer player) { Cooldown value = state(player); return (float) clamp((MinecraftServer.getServer().getTickCounter() - value.last + .5) * speed(player) / 20, 0, 1); }
    public static void tick() { for (EntityPlayerMP player : MinecraftServer.getServer().getConfigurationManager().getPlayerList()) state(player); }
    public static void stop() { COOLDOWNS.clear(); serverEnabled = clientEnabled = false; }
    public static boolean reachable(EntityPlayer actor, Entity target) {
        Vec3 eye = actor.getPositionEyes(1); AxisAlignedBB box = target.getEntityBoundingBox();
        Vec3 hit = new Vec3(clamp(eye.xCoord, box.minX + .001, box.maxX - .001), clamp(eye.yCoord, box.minY + .001, box.maxY - .001), clamp(eye.zCoord, box.minZ + .001, box.maxZ - .001));
        double reach = actor.capabilities.isCreativeMode ? 4.5 : 3;
        return eye.squareDistanceTo(hit) <= reach * reach && actor.worldObj.rayTraceBlocks(eye, hit, false, true, false) == null;
    }
    private static double baseDamage(EntityPlayer player) {
        initialize(); double damage = player.getEntityAttribute(SharedMonsterAttributes.attackDamage).getAttributeValue();
        int strength = player.isPotionActive(Potion.damageBoost) ? player.getActivePotionEffect(Potion.damageBoost).getAmplifier() + 1 : 0;
        int weakness = player.isPotionActive(Potion.weakness) ? player.getActivePotionEffect(Potion.weakness).getAmplifier() + 1 : 0;
        if (strength > 0) damage /= 1 + 1.3 * strength;
        damage += .5 * weakness;
        ItemStack held = player.getHeldItem(); if (held != null && DAMAGE_DELTA.containsKey(held.getItem())) damage += DAMAGE_DELTA.get(held.getItem());
        return Math.max(0, damage + 3 * strength - 4 * weakness);
    }
    private static void attack(EntityPlayer player, Entity target) {
        if (!player.isEntityAlive() || player.isSpectator() || target == player || target.isDead || player.worldObj != target.worldObj
                || !reachable(player, target) || !target.canAttackWithItem() || target.hitByEntity(player)) return;
        if (target instanceof EntityPlayer && !player.canAttackPlayer((EntityPlayer) target)) return;
        float charge = strength(player); Cooldown cooldown = state(player); cooldown.last = MinecraftServer.getServer().getTickCounter();
        ItemStack weapon = player.getHeldItem();
        double base = baseDamage(player) * (.2 + .8 * charge * charge);
        float enchantment = EnchantmentHelper.func_152377_a(weapon, target instanceof EntityLivingBase ? ((EntityLivingBase) target).getCreatureAttribute() : EnumCreatureAttribute.UNDEFINED);
        int sharpness = weapon == null ? 0 : EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, weapon);
        if (sharpness > 0) enchantment += .5f + .5f * sharpness - 1.25f * sharpness;
        enchantment *= charge;
        boolean charged = charge > .9f, sprint = charged && player.isSprinting();
        boolean critical = charged && !player.isSprinting() && player.fallDistance > 0 && !player.onGround && !player.isOnLadder()
                && !player.isInWater() && !player.isPotionActive(Potion.blindness) && player.ridingEntity == null && target instanceof EntityLivingBase;
        if (critical) base *= 1.5;
        float damage = (float) Math.max(0, base + enchantment);
        int knockback = EnchantmentHelper.getKnockbackModifier(player) + (sprint ? 1 : 0), fire = EnchantmentHelper.getFireAspectModifier(player);
        boolean ignited = fire > 0 && !target.isBurning(); if (ignited) target.setFire(1);
        double oldX = target.motionX, oldY = target.motionY, oldZ = target.motionZ;
        boolean hit = damage > 0 && target.attackEntityFrom(DamageSource.causePlayerDamage(player), damage);
        if (!hit) { if (ignited) target.extinguish(); return; }
        if (knockback > 0) {
            double yaw = Math.toRadians(player.rotationYaw); target.addVelocity(-Math.sin(yaw) * knockback * .5, .1, Math.cos(yaw) * knockback * .5);
            player.motionX *= .6; player.motionZ *= .6; player.setSprinting(false);
        }
        boolean sweep = charged && !critical && !sprint && player.onGround && weapon != null && weapon.getItem() instanceof ItemSword
                && Math.hypot(player.posX - player.prevPosX, player.posZ - player.prevPosZ) < .1;
        if (sweep) for (EntityLivingBase nearby : player.worldObj.getEntitiesWithinAABB(EntityLivingBase.class, target.getEntityBoundingBox().expand(1, .25, 1))) {
            if (nearby != player && nearby != target && !player.isOnSameTeam(nearby) && player.getDistanceSqToEntity(nearby) < 9
                    && (!(nearby instanceof EntityPlayer) || player.canAttackPlayer((EntityPlayer) nearby))) {
                nearby.knockBack(player, 0, Math.sin(Math.toRadians(player.rotationYaw)) * .4, -Math.cos(Math.toRadians(player.rotationYaw)) * .4);
                nearby.attackEntityFrom(DamageSource.causePlayerDamage(player), 1);
            }
        }
        if (target instanceof EntityPlayerMP && !(target instanceof NativeAgent) && target.velocityChanged) {
            ((EntityPlayerMP) target).playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(target));
            target.velocityChanged = false; target.motionX = oldX; target.motionY = oldY; target.motionZ = oldZ;
        }
        if (critical) player.onCriticalHit(target); if (enchantment > 0) player.onEnchantmentCritical(target);
        player.setLastAttacker(target);
        if (target instanceof EntityLivingBase) {
            EnchantmentHelper.applyThornEnchantments((EntityLivingBase) target, player); EnchantmentHelper.applyArthropodEnchantments(player, target);
            if (weapon != null) { weapon.hitEntity((EntityLivingBase) target, player); if (weapon.stackSize <= 0) player.destroyCurrentEquippedItem(); }
        }
        if (fire > 0) target.setFire(fire * 4);
        player.addStat(StatList.damageDealtStat, Math.round(damage * 10)); player.addExhaustion(.1f);
        cooldown.held = player.getHeldItem() == null ? null : player.getHeldItem().copy();
    }
    public static float playerArmor(EntityLivingBase entity, ItemStack[] armor, DamageSource source, double damage) {
        if (!enabled(entity)) return ISpecialArmor.ArmorProperties.applyArmor(entity, armor, source, damage);
        for (ItemStack stack : armor) if (stack != null && stack.getItem() instanceof ISpecialArmor) return ISpecialArmor.ArmorProperties.applyArmor(entity, armor, source, damage);
        if (source.isUnblockable()) return (float) damage;
        float result = armor(entity, source, (float) damage);
        if (entity instanceof EntityPlayer) ((EntityPlayer) entity).inventory.damageArmor((float) damage);
        return result;
    }
    public static float armor(EntityLivingBase entity, DamageSource source, float damage) {
        if (source.isUnblockable()) return damage;
        float toughness = 0;
        for (ItemStack stack : entity.getInventory()) if (stack != null && stack.getItem() instanceof ItemArmor && ((ItemArmor) stack.getItem()).getArmorMaterial() == ItemArmor.ArmorMaterial.DIAMOND) toughness += 2;
        double armor = entity.getTotalArmorValue();
        double effective = clamp(armor - damage / (2 + toughness / 4), armor * .2, 20);
        return (float) (damage * (1 - effective / 25));
    }
    public static float protection(EntityLivingBase entity, DamageSource source, float damage) {
        if (source.isDamageAbsolute()) return damage;
        if (entity.isPotionActive(Potion.resistance) && source != DamageSource.outOfWorld)
            damage *= Math.max(0, 1 - .2f * (entity.getActivePotionEffect(Potion.resistance).getAmplifier() + 1));
        int protection = 0;
        for (ItemStack stack : entity.getInventory()) {
            if (stack == null) continue;
            NBTTagList tags = stack.getEnchantmentTagList(); if (tags == null) continue;
            for (int i = 0; i < tags.tagCount(); i++) {
                int id = tags.getCompoundTagAt(i).getShort("id"), level = tags.getCompoundTagAt(i).getShort("lvl");
                if (level <= 0) continue;
                if (id == Enchantment.protection.effectId) protection += level;
                else if (id == Enchantment.fireProtection.effectId && source.isFireDamage()) protection += level * 2;
                else if (id == Enchantment.featherFalling.effectId && source == DamageSource.fall) protection += level * 3;
                else if (id == Enchantment.blastProtection.effectId && source.isExplosion()) protection += level * 2;
                else if (id == Enchantment.projectileProtection.effectId && source.isProjectile()) protection += level * 2;
            }
        }
        return damage * (1 - Math.min(20, protection) / 25f);
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static final class Cooldown { long last; ItemStack held; }
    public static final class Events {
        @SubscribeEvent(priority = EventPriority.LOWEST) public void attack(AttackEntityEvent event) {
            if (!enabled(event.entityPlayer)) return;
            event.setCanceled(true); if (!event.entityPlayer.worldObj.isRemote) ModernCombat.attack(event.entityPlayer, event.target);
        }
    }
}
