package dev.mineagent.runtime.legacy189;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Real, server-owned inventory storage; follows player save, clone and death rules. */
public final class NativeOffhand implements IExtendedEntityProperties {
    private static final String KEY = "divzero_legacy_offhand";
    private final EntityPlayer player;
    private long revision;
    private boolean loading;
    public final InventoryBasic inventory;
    private NativeOffhand(final EntityPlayer player) {
        this.player = player;
        inventory = new InventoryBasic("divzero.offhand", false, 1) {
            @Override public void markDirty() {
                super.markDirty();
                if (!loading) {
                    revision++;
                    if (player instanceof EntityPlayerMP && !(player instanceof NativeAgent) && !player.worldObj.isRemote) NativeNetwork.sync((EntityPlayerMP) player);
                }
            }
        };
    }
    public static NativeOffhand get(EntityPlayer player) {
        NativeOffhand value = (NativeOffhand) player.getExtendedProperties(KEY);
        if (value == null) { value = new NativeOffhand(player); player.registerExtendedProperties(KEY, value); }
        return value;
    }
    public long revision() { return revision; }
    public ItemStack stack() { return inventory.getStackInSlot(0); }
    public void set(ItemStack stack) { inventory.setInventorySlotContents(0, stack); }
    public void sync(ItemStack stack, long revision) {
        loading = true;
        try { inventory.setInventorySlotContents(0, stack); this.revision = revision; }
        finally { loading = false; }
    }
    public void swap(long expected) {
        if (revision != expected) throw new IllegalStateException("副手已变化，请重试当前操作");
        if (player.worldObj.isRemote || !player.isEntityAlive() || player.isSpectator()) throw new IllegalStateException("当前身体不能换装");
        ItemStack main = player.inventory.getCurrentItem(), off = stack();
        player.clearItemInUse();
        player.inventory.setInventorySlotContents(player.inventory.currentItem, off);
        set(main);
        player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
    }
    @Override public void saveNBTData(NBTTagCompound root) {
        NBTTagCompound tag = new NBTTagCompound(); tag.setLong("revision", revision);
        if (stack() != null) tag.setTag("item", stack().writeToNBT(new NBTTagCompound()));
        root.setTag(KEY, tag);
    }
    @Override public void loadNBTData(NBTTagCompound root) {
        NBTTagCompound tag = root.getCompoundTag(KEY);
        sync(tag.hasKey("item", 10) ? ItemStack.loadItemStackFromNBT(tag.getCompoundTag("item")) : null, Math.max(0, tag.getLong("revision")));
    }
    @Override public void init(Entity entity, World world) { }

    public static class Events {
        @SubscribeEvent public void constructed(EntityEvent.EntityConstructing event) {
            if (event.entity instanceof EntityPlayer) get((EntityPlayer) event.entity);
        }
        @SubscribeEvent public void clone(PlayerEvent.Clone event) {
            if (!event.wasDeath || event.entityPlayer.worldObj.getGameRules().getBoolean("keepInventory")) {
                NBTTagCompound saved = new NBTTagCompound(); get(event.original).saveNBTData(saved); get(event.entityPlayer).loadNBTData(saved);
            }
        }
        @SubscribeEvent public void drops(PlayerDropsEvent event) {
            if (event.entityPlayer.worldObj.getGameRules().getBoolean("keepInventory")) return;
            NativeOffhand state = get(event.entityPlayer);
            if (state.stack() != null) {
                net.minecraft.entity.item.EntityItem item = new net.minecraft.entity.item.EntityItem(event.entityPlayer.worldObj,
                        event.entityPlayer.posX, event.entityPlayer.posY, event.entityPlayer.posZ, state.stack().copy());
                item.setDefaultPickupDelay(); event.drops.add(item); state.set(null);
            }
        }
    }
    public static final class EquipmentContainer extends Container {
        private final EntityPlayer owner;
        public EquipmentContainer(EntityPlayer owner) {
            this.owner = owner;
            addSlotToContainer(new Slot(get(owner).inventory, 0, 80, 20));
            for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
                addSlotToContainer(new Slot(owner.inventory, 9 + row * 9 + column, 8 + column * 18, 50 + row * 18));
            for (int column = 0; column < 9; column++) addSlotToContainer(new Slot(owner.inventory, column, 8 + column * 18, 108));
        }
        @Override public boolean canInteractWith(EntityPlayer player) { return player == owner && owner.isEntityAlive() && !owner.isSpectator(); }
        @Override public ItemStack transferStackInSlot(EntityPlayer player, int index) {
            if (!canInteractWith(player) || index < 0 || index >= inventorySlots.size()) return null;
            Slot slot = inventorySlots.get(index);
            if (!slot.getHasStack()) return null;
            ItemStack stack = slot.getStack(), original = stack.copy();
            if (index == 0 ? !mergeItemStack(stack, 1, 37, true) : !mergeItemStack(stack, 0, 1, false)) return null;
            if (stack.stackSize == 0) slot.putStack(null); else slot.onSlotChanged();
            if (stack.stackSize == original.stackSize) return null;
            slot.onPickupFromSlot(player, stack); return original;
        }
    }
}
