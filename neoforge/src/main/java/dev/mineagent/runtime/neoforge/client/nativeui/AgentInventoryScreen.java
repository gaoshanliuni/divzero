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
    private static final Identifier TEXTURE=Identifier.withDefaultNamespace("textures/gui/container/inventory.png");
    public AgentInventoryScreen(AgentInventoryMenu menu,Inventory inventory,Component title){super(menu,inventory,title,176,273);inventoryLabelY=179;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractBackground(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){
        super.extractBackground(graphics,mouseX,mouseY,partial);
        int x=leftPos,y=topPos;
        // Use vanilla artwork, retaining texture-pack support. Hide the unused crafting area.
        graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x,y,0,0,176,83,256,256);
        graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x,y+83,0,77,176,89,256,256);
        graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x,y+172,0,67,176,101,256,256);
        graphics.fill(x+94,y+16,x+170,y+77,0xffc6c6c6);
        for(int i=0;i<menu.slots.size();i++){var slot=menu.slots.get(i);graphics.blit(RenderPipelines.GUI_TEXTURED,TEXTURE,x+slot.x-1,y+slot.y-1,7,83,18,18,256,256);}
        int selected=Math.clamp(menu.selectedHotbar.get(),0,8);int sx=x+7+18*selected;
        graphics.fill(sx,y+154,sx+18,y+156,0xff4b8f36);graphics.fill(sx,y+172,sx+18,y+174,0xff4b8f36);
        var body=minecraft.level==null?null:minecraft.level.getPlayerByUUID(menu.agentId);
        if(body!=null)InventoryScreen.extractEntityInInventoryFollowsMouse(graphics,x+27,y+18,x+73,y+89,29,0.0625f,mouseX,mouseY,body);
        graphics.text(font,Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("主手槽位")),x+96,y+25,0xff404040,false);
    }
}
