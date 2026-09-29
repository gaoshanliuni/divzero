package dev.mineagent.runtime.neoforge.client.nativeui;

import net.minecraft.network.chat.Component;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import java.util.*;

/** Trusted provider configuration editor, never a package-owned view or model input. */
final class NativeWorkflowPanel {
    static void open(NativeWorkspaceScreen host){
        if(host.revealWindow("provider-workflow"))return;var window=host.window("provider-workflow",t("ComfyUI 工作流"),580,410);
        var notice=WorkspacePanels.text(t("保存 Workflow 不会发起模型请求。JSON 需包含 ${prompt}。"));window.body.addChild(notice);var editor=new NativeCodeEditor("JAVA");window.body.addChild(editor);editor.readOnly(true);
        long[] revision={-1};boolean[] busy={false},unknown={false};String[] saved={""};
        Runnable[] load={null};load[0]=()->{if(busy[0])return;busy[0]=true;WorkspacePanels.request("settings.read",Map.of("kind","workflow")).whenComplete((receipt,error)->{busy[0]=false;if(!host.activeContext()||window.closed())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}var values=receipt.values();revision[0]=Long.parseLong(values.get("revision"));String source=values.get("source.0")+values.get("source.1");if(!unknown[0])editor.load(source);else notice.setText(Component.literal(source.equals(saved[0])?t("已确认服务器保存了该 Workflow。草稿仍保留。"):t("服务器版本已读取，草稿保留；请核对后再保存。")));unknown[0]=false;editor.readOnly(false);});};
        var controls=WorkspacePanels.row();controls.getLayout().height(26);window.body.addChild(controls);
        controls.addChild(NativeUiTheme.button(t("读取已保存版本"),()->{if(unknown[0])load[0].run();else com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog.showCheckBox(t("读取已保存版本"),t("用服务器版本替换当前未保存草稿。"),yes->{if(yes)load[0].run();}).show(window.body);}));
        controls.addChild(NativeUiTheme.button(t("保存 Workflow"),()->{if(busy[0]||unknown[0]||revision[0]<0)return;String source=editor.source();if(source.length()>32768){notice.setText(Component.literal(t("Workflow 超过 32768 字符。")));return;}try{if(!source.isBlank()&&(!com.google.gson.JsonParser.parseString(source).isJsonObject()||!source.contains("${prompt}")))throw new IllegalArgumentException();}catch(Exception invalid){notice.setText(Component.literal(t("Workflow 必须是包含 ${prompt} 的 JSON 对象")));return;}
            busy[0]=true;saved[0]=source;editor.readOnly(true);WorkspacePanels.request("settings.write",Map.of("kind","workflow","revision",Long.toString(revision[0]),"source.0",source.substring(0,Math.min(16000,source.length())),"source.1",source.substring(Math.min(16000,source.length())))).whenComplete((receipt,error)->{busy[0]=false;if(!host.activeContext()||window.closed())return;editor.readOnly(false);if(error!=null){unknown[0]=true;WorkspacePanels.failure(notice,error);return;}revision[0]=Long.parseLong(receipt.values().get("revision"));notice.setText(Component.literal(t("Workflow 已保存；没有调用模型。")));});
        }));load[0].run();
    }
    private static String t(String value){return ClientLanguage.t(value);}
    private NativeWorkflowPanel(){}
}
