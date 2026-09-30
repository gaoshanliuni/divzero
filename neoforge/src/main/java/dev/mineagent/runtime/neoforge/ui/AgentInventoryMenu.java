package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** Vanilla click protocol and real inventory stacks, with the AI's native equipment Slot rules. */
public final class AgentInventoryMenu extends AbstractContainerMenu {
    public static final int AGENT_SLOTS=41;
    private final MineAgentPlayer body;
    public final UUID agentId;
    public final DataSlot selectedHotbar=DataSlot.standalone();
    private final Player viewer;
    public AgentInventoryMenu(int id,Inventory inventory,RegistryFriendlyByteBuf data){this(id,inventory,null,data.readUUID());}
    public AgentInventoryMenu(int id,Inventory inventory,MineAgentPlayer body){this(id,inventory,body,body.agentId());}
    private AgentInventoryMenu(int id,Inventory inventory,MineAgentPlayer body,UUID agentId){
        super(MineAgentRegistries.AGENT_INVENTORY.get(),id);this.body=body;this.agentId=agentId;viewer=inventory.player;addDataSlot(selectedHotbar);if(body!=null)selectedHotbar.set(body.getInventory().getSelectedSlot());
        var mirror=new SimpleContainer(41);
        // Equipment, main inventory and hotbar match the original player inventory layout.
        for(int row=0;row<4;row++)addAgentSlot(mirror,39-row,8,18+18*row);
        addAgentSlot(mirror,40,77,72);
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)addAgentSlot(mirror,9+row*9+col,8+18*col,98+18*row);
        for(int col=0;col<9;col++)addAgentSlot(mirror,col,8+18*col,156);
        addStandardInventorySlots(inventory,8,187);
    }
    private void addAgentSlot(SimpleContainer mirror,int logical,int x,int y){
        if(body!=null){
            int nativeIndex=logical<9?logical+36:logical<36?logical:logical==40?45:44-logical;
            var original=body.inventoryMenu.getSlot(nativeIndex);
            addSlot(new Slot(body.getInventory(),logical,x,y){
                private boolean available(){return !body.isUsingItem()||!(logical==40&&body.getUsedItemHand()==net.minecraft.world.InteractionHand.OFF_HAND||logical==body.getInventory().getSelectedSlot()&&body.getUsedItemHand()==net.minecraft.world.InteractionHand.MAIN_HAND);}
                @Override public boolean mayPlace(ItemStack stack){return available()&&original.mayPlace(stack);}
                @Override public boolean mayPickup(Player player){return available()&&original.mayPickup(player);}
                @Override public int getMaxStackSize(){return original.getMaxStackSize();}
                @Override public int getMaxStackSize(ItemStack stack){return original.getMaxStackSize(stack);}
                @Override public void setByPlayer(ItemStack stack,ItemStack previous){original.setByPlayer(stack,previous);}
                @Override public void onTake(Player player,ItemStack stack){original.onTake(player,stack);}
                @Override public boolean isActive(){return original.isActive();}
                @Override public net.minecraft.resources.Identifier getNoItemIcon(){return original.getNoItemIcon();}
            });
        }else addSlot(new Slot(mirror,logical,x,y){
            @Override public boolean mayPlace(ItemStack stack){return logical<36||logical==40||stack.canEquip(equipment(logical),viewer);}
            @Override public int getMaxStackSize(){return logical>=36&&logical<40?1:super.getMaxStackSize();}
            @Override public net.minecraft.resources.Identifier getNoItemIcon(){return switch(logical){case 39->InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;case 38->InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;case 37->InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;case 36->InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;case 40->InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;default->null;};}
        });
    }
    private static EquipmentSlot equipment(int slot){return switch(slot){case 39->EquipmentSlot.HEAD;case 38->EquipmentSlot.CHEST;case 37->EquipmentSlot.LEGS;default->EquipmentSlot.FEET;};}
    @Override public void broadcastChanges(){if(body!=null)selectedHotbar.set(body.getInventory().getSelectedSlot());super.broadcastChanges();}
    @Override public boolean stillValid(Player player){
        if(player!=viewer)return false;if(player.level().isClientSide())return true;
        try{return ServerAgentInventory.body((net.minecraft.server.level.ServerPlayer)player,java.util.Map.of("agentId",agentId.toString()))==body;}catch(RuntimeException invalid){return false;}
    }
    @Override public void clicked(int slot,int button,ContainerInput input,Player player){if(stillValid(player)){
        if(body!=null&&body.isUsingItem())dev.mineagent.runtime.neoforge.skill.SkillRuntime.inventoryEdited(body);
        super.clicked(slot,button,input,player);
    }}
    @Override public ItemStack quickMoveStack(Player player,int index){
        if(!stillValid(player)||index<0||index>=slots.size())return ItemStack.EMPTY;
        Slot source=slots.get(index);if(!source.hasItem()||!source.mayPickup(player))return ItemStack.EMPTY;
        ItemStack stack=source.getItem(),before=stack.copy();
        if(index<AGENT_SLOTS){if(!moveItemStackTo(stack,AGENT_SLOTS,slots.size(),true))return ItemStack.EMPTY;}
        else {
            boolean moved=false;
            // Try applicable empty armor/offhand slots first, then the AI inventory and hotbar.
            var eq=(body==null?viewer:body).getEquipmentSlotForItem(stack);int armor=switch(eq){case HEAD->0;case CHEST->1;case LEGS->2;case FEET->3;case OFFHAND->4;default->-1;};
            if(armor>=0&&!slots.get(armor).hasItem())moved=moveItemStackTo(stack,armor,armor+1,false);
            if(!stack.isEmpty())moved=moveItemStackTo(stack,5,AGENT_SLOTS,false)||moved;
            if(!moved)return ItemStack.EMPTY;
        }
        if(stack.isEmpty())source.setByPlayer(ItemStack.EMPTY,before);else source.setChanged();
        if(stack.getCount()==before.getCount())return ItemStack.EMPTY;source.onTake(player,stack);return before;
    }
}
