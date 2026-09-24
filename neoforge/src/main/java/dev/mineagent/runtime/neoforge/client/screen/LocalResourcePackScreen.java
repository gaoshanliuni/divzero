package dev.mineagent.runtime.neoforge.client.screen;

import dev.mineagent.runtime.neoforge.client.webui.ClientResourcePacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Local operator controls remain available without a server or web Session. No download or server command is issued here. */
public final class LocalResourcePackScreen extends Screen {
    private final Screen parent;private int offset;private String notice=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("资源选择是本机全局的，会影响其它世界、菜单与共用此目录的账号。");
    public LocalResourcePackScreen(Screen parent){super(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("MineAgent · 本机资源包")));this.parent=parent;}
    @Override protected void init(){
        int left=Math.max(12,width/2-265),w=Math.min(530,width-24);addRenderableWidget(new StringWidget(left,16,w,22,getTitle(),font));addRenderableWidget(new StringWidget(left,41,w,24,Component.literal(notice),font));int rows=Math.max(1,Math.min(4,(height-136)/50));
        try{var view=ClientResourcePacks.read(offset);@SuppressWarnings("unchecked")var items=(List<Map<String,Object>>)view.get("items");int index=0;
            for(var item:items.stream().limit(rows).toList()){
                int y=75+index++*50;String name=String.valueOf(item.get("name"));if(name.length()>30)name=name.substring(0,30);addRenderableWidget(new StringWidget(left,y,w-182,18,Component.literal(name+" · "+item.get("state")),font));
                String status=Boolean.TRUE.equals(item.get("loadedNow"))?dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("当前实际装入"):dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("当前未装入");if(!String.valueOf(item.get("error")).isEmpty())status+=" · "+item.get("error");addRenderableWidget(new StringWidget(left,y+20,w-182,19,Component.literal(status),font));
                var enable=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("启用…")),b->confirm(item,view,"ENABLE")).bounds(left+w-176,y,84,24).build());enable.active=Boolean.TRUE.equals(item.get("canEnable"));
                var disable=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("停用…")),b->confirm(item,view,"DISABLE")).bounds(left+w-88,y,84,24).build());disable.active=Boolean.TRUE.equals(item.get("canDisable"));
            }
            if(items.isEmpty())addRenderableWidget(new StringWidget(left,82,w,24,Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("没有已下载资源包；进入服务器后从 F2 包目录下载。")),font));
            var previous=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("上一页")),b->{offset=Math.max(0,offset-rows);rebuildWidgets();}).bounds(left,height-56,w/3-4,22).build());previous.active=offset>0;
            addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("只读刷新")),b->rebuildWidgets()).bounds(left+w/3,height-56,w/3-4,22).build());var next=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("下一页")),b->{offset+=rows;rebuildWidgets();}).bounds(left+2*w/3,height-56,w/3-4,22).build());next.active=items.size()>rows||Boolean.TRUE.equals(view.get("more"));
        }catch(Exception failure){addRenderableWidget(new StringWidget(left,85,w,28,Component.literal(ClientResourcePacks.code(failure)),font));addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("重读状态")),b->rebuildWidgets()).bounds(left,height-56,w,22).build());}
        addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("返回")),b->onClose()).bounds(left,height-29,w,22).build());
    }
    private void confirm(Map<String,Object> item,Map<String,Object> view,String action){Minecraft.getInstance().setScreen(new ConfirmScreen(accepted->{
        Minecraft.getInstance().setScreen(this);if(!accepted)return;
        try{ClientResourcePacks.change(UUID.randomUUID(),String.valueOf(item.get("filename")),action,((Number)item.get("revision")).longValue(),String.valueOf(view.get("selection")),String.valueOf(view.get("environment")),true,"LOCAL_OPERATOR",()->Minecraft.getInstance().screen==this);notice=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("已受理本机操作；重载结束后只读刷新查看实际结果。");}catch(Exception failure){notice=ClientResourcePacks.code(failure);}rebuildWidgets();
    },Component.literal(action.equals("ENABLE")?dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("全局启用这个来源的资源包？"):dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("全局停用这个来源的资源包？")),Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("影响本机所有世界/菜单/共用账号；重载失败时 Vanilla 可能清空其它选择。这里不会借用服务器权限，也不会自动恢复旧选择。"))));}
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
}
