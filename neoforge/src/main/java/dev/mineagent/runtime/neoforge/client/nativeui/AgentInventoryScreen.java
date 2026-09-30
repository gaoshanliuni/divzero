package dev.mineagent.runtime.neoforge.client.nativeui;

import dev.mineagent.runtime.neoforge.ui.AgentInventoryMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/** Native container mouse/keyboard handling, with the familiar player inventory texture. */
public final class AgentInventoryScreen extends AbstractContainerScreen<AgentInventoryMenu> {
    private boolean detached;
    private void detach(){
        if(detached)return;detached=true;
        if(minecraft.player!=null&&minecraft.getConnection()!=null&&minecraft.player.containerMenu==menu){
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.InventoryDetach(menu.containerId,menu.viewToken));
            minecraft.player.containerMenu=minecraft.player.inventoryMenu;
        }
    }
    @Override public void onClose(){detach();if(minecraft.player!=null)minecraft.player.clientSideCloseContainer();else minecraft.setScreen(null);}
    @Override public void removed(){detach();super.removed();}
    private static final Identifier TEXTURE=Identifier.withDefaultNamespace("textures/gui/container/inventory.png");
    public AgentInventoryScreen(AgentInventoryMenu menu,Inventory inventory,Component title){super(menu,inventory,title,176,269);inventoryLabelY=175;}
    @Override public boolean isPauseScreen(){return false;}
    int smokeHoveredSlot(){if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return hoveredSlot==null?-1:menu.slots.indexOf(hoveredSlot);}
    @Override public void extractBackground(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){
        super.extractBackground(graphics,mouseX,mouseY,partial);
        int x=leftPos,y=topPos;
        // Stretch only neutral panel/border pixels. Copying whole inventory bands would
        // leave a second, non-interactive set of slots behind the real container slots.
        graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x+4,y+4,4,4,168,imageHeight-8,1,1,256,256);
        graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x,y,0,0,176,4,256,256);
        graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x,y+imageHeight-4,0,162,176,4,256,256);
        graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x,y+4,0,4,4,imageHeight-8,4,1,256,256);
        graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x+172,y+4,172,4,4,imageHeight-8,4,1,256,256);
        graphics.fill(x+27,y+17,x+74,y+90,0xff404040);
        for(int i=0;i<menu.slots.size();i++){var slot=menu.slots.get(i);graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x+slot.x-1,y+slot.y-1,7,83,18,18,256,256);}
        int selected=Math.clamp(menu.selectedHotbar.get(),0,8);int sx=x+7+18*selected;
        graphics.fill(sx,y+154,sx+18,y+156,0xff4b8f36);graphics.fill(sx,y+172,sx+18,y+174,0xff4b8f36);
        graphics.item(menu.getSlot(32+selected).getItem(),x+115,y+45);
        var body=minecraft.level==null?null:minecraft.level.getPlayerByUUID(menu.agentId);
        if(body!=null)InventoryScreen.extractEntityInInventoryFollowsMouse(graphics,x+27,y+18,x+73,y+89,29,0.0625f,mouseX,mouseY,body);
        graphics.text(font,Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("主手槽位")),x+96,y+25,0xff404040,false);
    }
}
