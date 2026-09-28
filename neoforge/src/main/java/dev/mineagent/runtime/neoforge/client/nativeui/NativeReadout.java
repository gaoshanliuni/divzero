package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.IntFunction;

/** Paginated technical evidence, with a mount token so old replies cannot replace a newer page. */
final class NativeReadout {
    static void open(NativeWorkspaceScreen host,String id,String title,String action,IntFunction<Map<String,String>> arguments,int step){page(host,id,title,action,arguments,step,0);}
    private static void page(NativeWorkspaceScreen host,String id,String title,String action,IntFunction<Map<String,String>> arguments,int step,int offset){
        var window=host.window(id,ClientLanguage.t(title),535,355);window.body.clearAllChildren();var root=new UIElement();root.getLayout().widthPercent(100).flex(1);window.body.addChild(root);var notice=WorkspacePanels.text(ClientLanguage.t("正在读取…"));root.addChild(notice);
        WorkspacePanels.request(action,arguments.apply(offset)).whenComplete((receipt,error)->{
            if(!host.activeContext()||window.closed()||root.getParent()!=window.body)return;if(error!=null){WorkspacePanels.failure(notice,error);return;}var value=receipt.values().containsKey("state")?WorkspacePanels.state(receipt):new Gson().toJsonTree(receipt.values()).getAsJsonObject();notice.setText(Component.literal(""));String text=value.has("text")?value.get("text").getAsString():value.has("source")?value.get("source").getAsString():new GsonBuilder().setPrettyPrinting().create().toJson(value);var editor=new NativeCodeEditor("JAVA");editor.load(text);editor.readOnly(true);root.addChild(editor);var navigation=WorkspacePanels.row();navigation.getLayout().height(25);root.addChild(navigation);
            var back=NativeUiTheme.button(ClientLanguage.t("上一页"),()->page(host,id,title,action,arguments,step,Math.max(0,offset-step)));back.setActive(offset>0);navigation.addChild(back);var next=NativeUiTheme.button(ClientLanguage.t("下一页"),()->page(host,id,title,action,arguments,step,value.get("nextOffset").getAsInt()));next.setActive(value.has("more")&&value.get("more").getAsBoolean());navigation.addChild(next);
        });
    }
    private NativeReadout(){}
}
