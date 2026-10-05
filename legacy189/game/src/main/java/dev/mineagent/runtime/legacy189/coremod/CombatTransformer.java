package dev.mineagent.runtime.legacy189.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Conditional hooks preserve the original Forge path when modern rules are off.
 * No game classes are loaded while transforming. Required hooks fail visibly if
 * another transformer changes their signatures instead of silently mixing rules.
 */
public final class CombatTransformer implements IClassTransformer {
    private static final String HOOK = "dev/mineagent/runtime/legacy189/ModernCombat";
    @Override public byte[] transform(String name, String transformed, byte[] bytes) {
        boolean living = "net.minecraft.entity.EntityLivingBase".equals(transformed);
        boolean player = "net.minecraft.entity.player.EntityPlayer".equals(transformed);
        if (bytes == null || (!living && !player)) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (living && is(method, "applyArmorCalculations", "func_70655_b")) { injectFloat(method, "armor"); changed++; }
            if (living && is(method, "applyPotionDamageCalculations", "func_70672_c")) { injectFloat(method, "protection"); changed++; }
            if (player && is(method, "isBlocking", "func_70632_aY")) {
                InsnList start = enabled(); LabelNode original = new LabelNode();
                start.add(new JumpInsnNode(Opcodes.IFEQ, original)); start.add(new InsnNode(Opcodes.ICONST_0)); start.add(new InsnNode(Opcodes.IRETURN));
                start.add(original); start.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null)); method.instructions.insert(start); changed++;
            }
            if (player && is(method, "damageEntity", "func_70665_d")) {
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (call.owner.equals("net/minecraftforge/common/ISpecialArmor$ArmorProperties") && call.name.equals("applyArmor")) {
                            if (!call.desc.equals("(Lnet/minecraft/entity/EntityLivingBase;[Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/DamageSource;D)F")) throw new IllegalStateException("DIVZERO_ARMOR_DESCRIPTOR");
                            call.owner = HOOK; call.name = "playerArmor"; changed++;
                        }
                    }
                }
            }
        }
        if (changed != 2) throw new IllegalStateException("DIVZERO_COMBAT_HOOKS_MISSING " + transformed + " " + changed);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static boolean is(MethodNode method, String mcp, String srg) { return method.name.equals(mcp) || method.name.equals(srg); }
    private static InsnList enabled() {
        InsnList list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "enabled", "(Lnet/minecraft/entity/Entity;)Z", false)); return list;
    }
    private static void injectFloat(MethodNode method, String hook) {
        InsnList start = enabled(); LabelNode original = new LabelNode(); start.add(new JumpInsnNode(Opcodes.IFEQ, original));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0)); start.add(new VarInsnNode(Opcodes.ALOAD, 1)); start.add(new VarInsnNode(Opcodes.FLOAD, 2));
        start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hook, "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/util/DamageSource;F)F", false));
        start.add(new InsnNode(Opcodes.FRETURN)); start.add(original); start.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(start);
    }
}
