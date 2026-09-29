package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Live slot selection and server-confirmed moves/swaps, shared by F2 and the AI profile. */
public final class NativeInventoryPanel {
    private static final List<NativeInventoryPanel> OPEN=new ArrayList<>();
    private final String agent;private final BooleanSupplier current;private final UIElement slots=new UIElement();
    private final TextElement notice=WorkspacePanels.text("");private final TextElement page=WorkspacePanels.text("");
    private JsonObject state;private int offset,count,sourceSlot=-1;private String sourceSide="",signature="";
    private boolean loading,writing,equipmentOnly;private long nextRead;

    public static void open(NativeWorkspaceScreen host,String agent){
        if(host.revealWindow("inventory-"+agent))return;
        var window=host.window("inventory-"+agent,host.agentName(agent)+" · "+t("背包"),580,430);window.body.clearAllChildren();
        attach(window.body,agent,()->host.activeContext()&&!window.closed());
    }
    public static void attach(UIElement parent,String agent,BooleanSupplier current){OPEN.add(new NativeInventoryPanel(parent,agent,current));}
    private NativeInventoryPanel(UIElement parent,String agent,BooleanSupplier current){
        this.agent=agent;this.current=current;
        parent.addChild(WorkspacePanels.text(t("先选物品，再点目标格；不同物品可整组交换。")));
        var tools=WorkspacePanels.row();tools.getLayout().height(25);parent.addChild(tools);
        var amount=new Selector<String>();amount.setCandidates(List.of(t("全部"),"1","8","16","32","64"));amount.setValue(t("全部"),false);amount.setOnValueChanged(v->count=v.equals(t("全部"))?0:Integer.parseInt(v));amount.getLayout().width(85);tools.addChild(WorkspacePanels.text(t("数量")));tools.addChild(amount);
        tools.addChild(NativeUiTheme.button(t("取消选择"),()->{sourceSlot=-1;draw();}));tools.addChild(NativeUiTheme.button(t("刷新"),()->nextRead=0));
        tools.addChild(NativeUiTheme.button(t("背包 / 装备"),()->{equipmentOnly=!equipmentOnly;draw();}));
        slots.getLayout().widthPercent(100).flex(1).flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW).gapAll(8);parent.addChild(slots);
        var pages=WorkspacePanels.row();pages.getLayout().height(24);parent.addChild(pages);
        pages.addChild(NativeUiTheme.button(t("上一页"),()->{offset=Math.max(0,offset-9);signature="";nextRead=0;}));pages.addChild(page);pages.addChild(NativeUiTheme.button(t("下一页"),()->{offset=Math.min(27,offset+9);signature="";nextRead=0;}));parent.addChild(notice);
    }
    private static String t(String value){return ClientLanguage.t(value);}
    private void failure(Throwable error){while(error.getCause()!=null)error=error.getCause();String code=Objects.toString(error.getMessage(),"操作失败");notice.setText(Component.literal(t(switch(code){case "INVENTORY_CHANGED_REFRESH"->"背包已变化，请刷新后重试。";case "INVENTORY_TARGET_FULL"->"目标格已满。";case "INVENTORY_AGENT_OFFLINE","INVENTORY_WORLD_CHANGED"->"AI 当前离线或不在同一世界。";case "INVENTORY_PERMISSION_DENIED","PERMISSION_DENIED"->"没有管理该 AI 背包的权限。";case "INVENTORY_ITEM_IN_USE"->"当前正在使用物品，请稍后重试。";case "INVENTORY_CONTAINER_BUSY"->"请先关闭正在使用的容器。";case "INVENTORY_SLOT_REJECTED","INVENTORY_SWAP_REJECTED"->"该物品不能放入目标装备格。";default->code;})));}
    public static void tick(){OPEN.removeIf(panel->!panel.current.getAsBoolean());for(var panel:List.copyOf(OPEN))panel.poll();}
    private void poll(){
        long now=System.currentTimeMillis();if(loading||writing||now<nextRead||!NativeWorkspaceConnection.ready())return;loading=true;nextRead=now+700;int requested=offset;
        WorkspacePanels.request("agent.inventoryRead",Map.of("agentId",agent,"offset",Integer.toString(offset))).whenComplete((receipt,error)->{
            loading=false;if(!current.getAsBoolean()||requested!=offset)return;if(error!=null){failure(error);return;}var next=WorkspacePanels.state(receipt);String data=next.toString();
            if(data.equals(signature))return;
            if(state!=null&&sourceSlot>=0&&(!next.get("agentRevision").equals(state.get("agentRevision"))||!next.get("playerRevision").equals(state.get("playerRevision")))){sourceSlot=-1;notice.setText(Component.literal(t("背包已变化，请重新选择物品。")));}
            state=next;signature=data;draw();
        });
    }
    private void draw(){
        slots.clearAllChildren();page.setText(Component.literal((offset/9+1)+" / 4"));if(state==null)return;
        for(String side:List.of("agent","player")){
            var column=new UIElement();column.getLayout().flex(1).heightPercent(100);slots.addChild(column);column.addChild(WorkspacePanels.text(side.equals("agent")?state.get("agentName").getAsString():t("你的背包")));var list=WorkspacePanels.scroller(column);
            for(var raw:state.getAsJsonArray(side+"Slots")){
                var value=raw.getAsJsonObject();int index=value.get("slot").getAsInt();if((index>=36)!=equipmentOnly)continue;String label=slotName(index)+" · "+(value.get("empty").getAsBoolean()?"—":value.get("name").getAsString()+" × "+value.get("count").getAsInt());
                var button=NativeUiTheme.button((sourceSlot==index&&sourceSide.equals(side)?"● ":"")+label,()->select(side,index));button.setId("inventory-"+side+"-"+index);button.getLayout().height(28).widthPercent(100).paddingLeft(27).marginBottom(3);button.setActive(!writing&&state.get("canEdit").getAsBoolean());list.addScrollViewChild(button);
                if(!value.get("empty").getAsBoolean())try{
                    var mc=Minecraft.getInstance();ItemStack item=value.get("stack").getAsString().isEmpty()?new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(value.get("item").getAsString())),value.get("count").getAsInt()):ItemStack.CODEC.parse(mc.level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),net.minecraft.nbt.TagParser.parseCompoundFully(value.get("stack").getAsString())).getOrThrow();
                    var icon=new UIElement();icon.getLayout().positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE).left(3).top(3).width(22).height(22);icon.getStyle().backgroundTexture(new ItemStackTexture(item));button.addChild(icon);
                }catch(Exception ignored){/* The authoritative name/count remain visible; no client-created stack is sent. */}
            }
        }
    }
    private static String slotName(int slot){return switch(slot){case 36->t("鞋子");case 37->t("护腿");case 38->t("胸甲");case 39->t("头盔");case 40->t("副手");default->Integer.toString(slot+1);};}
    private void select(String side,int slot){
        if(writing||state==null)return;
        if(sourceSlot<0){sourceSide=side;sourceSlot=slot;draw();return;}
        if(sourceSide.equals(side)&&sourceSlot==slot){sourceSlot=-1;draw();return;}
        var args=new LinkedHashMap<String,String>();args.put("agentId",agent);args.put("offset",Integer.toString(offset));args.put("from",sourceSide);args.put("sourceSlot",Integer.toString(sourceSlot));args.put("to",side);args.put("targetSlot",Integer.toString(slot));args.put("count",Integer.toString(count));args.put("agentRevision",state.get("agentRevision").getAsString());args.put("playerRevision",state.get("playerRevision").getAsString());writing=true;
        WorkspacePanels.request("agent.inventoryWrite",args).whenComplete((receipt,error)->{writing=false;if(!current.getAsBoolean())return;sourceSlot=-1;if(error!=null){failure(error);nextRead=0;draw();return;}state=WorkspacePanels.state(receipt);signature=state.toString();notice.setText(Component.literal(t("已移动物品")+" · "+state.get("moved").getAsInt()));draw();nextRead=0;});
    }
}
