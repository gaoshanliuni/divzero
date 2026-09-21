package dev.mineagent.runtime.neoforge.client.chat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.*;
/** Native full-resolution 3D preview from the synced stack. No added world entity and no enlarged 16px atlas. */
final class SphereModelPreviewScreen extends Screen {
    private final ItemStack stack;private final float centerY,extent;
    SphereModelPreviewScreen(ItemStack stack){
        super(Component.literal("Native model inspection"));this.stack=stack.copy();var mesh=dev.mineagent.runtime.neoforge.content.RuntimeItem.binding(stack).mesh();
        var x=mesh.vertices().stream().mapToDouble(v->v.x()).summaryStatistics();var y=mesh.vertices().stream().mapToDouble(v->v.y()).summaryStatistics();var z=mesh.vertices().stream().mapToDouble(v->v.z()).summaryStatistics();
        centerY=(float)((y.getMin()+y.getMax())/2);extent=(float)java.lang.Math.max(x.getMax()-x.getMin(),java.lang.Math.max(y.getMax()-y.getMin(),z.getMax()-z.getMin()));
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float partial){
        g.fill(0,0,width,height,0xEE111B24);g.centeredText(font,"Native 3D / parametric sphere",width/2,24,0xFFE0EEE9);g.centeredText(font,stack.getHoverName().getString(),width/2,height-35,0xFFE0EEE9);
        int size=java.lang.Math.min(width,height)-110,x=(width-size)/2,y=(height-size)/2;var state=dev.mineagent.runtime.neoforge.client.objects.RuntimeThrownItemRenderer.preview(stack);
        g.entity(state,size*.78f/extent,new Vector3f(0,centerY,0),new Quaternionf().rotateZ((float)java.lang.Math.PI),null,x,y,x+size,y+size);
    }
}
