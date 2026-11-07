package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Readable transcript backed by the same persistent messages as the actual model exchange. */
final class PeerDialoguePanel {
    private static PeerDialoguePanel smoke;
    private int renderedThreads,renderedMessages;
    private final UIElement parent;
    private final String agent;
    private final BooleanSupplier current;
    private long epoch;
    private int offset;
    private String conversation = "";
    private final Deque<Long> pages = new ArrayDeque<>();
    private long before;
    private PeerDialoguePanel(UIElement parent, String agent, BooleanSupplier current) { this.parent=parent;this.agent=agent;this.current=current;if(Boolean.getBoolean("mineagent.skillSmoke"))smoke=this;show(); }
    static boolean smokeReady(String agent,boolean messages){return Boolean.getBoolean("mineagent.skillSmoke")&&smoke!=null&&smoke.agent.equals(agent)&&smoke.current.getAsBoolean()&&(messages?smoke.renderedMessages:smoke.renderedThreads)>0;}
    static void smokeOpenFirst(String agent){if(!smokeReady(agent,false))throw new IllegalStateException("PEER_UI_NOT_READY");NativeBehaviorPanel.smokeClickElement(smoke.parent,t("查看对话"),false);NativeBehaviorPanel.smokeClickElement(smoke.parent,t("查看对话"),true);}
    static void attach(UIElement parent, String agent, BooleanSupplier current) { new PeerDialoguePanel(parent,agent,current); }
    static void open(NativeWorkspaceScreen host, String agent) {
        if(agent.isBlank())return;var window=host.window("peer-dialogues-"+agent,t("协作对话"),600,400);window.body.clearAllChildren();
        attach(window.body,agent,()->!window.closed());
    }
    private static String t(String value) { return ClientLanguage.t(value); }
    private void show() {
        if(!current.getAsBoolean())return;long ticket=++epoch;renderedThreads=renderedMessages=0;parent.clearAllChildren();
        var top=WorkspacePanels.row();top.getLayout().height(25).flexShrink(0);parent.addChild(top);
        top.addChild(NativeUiTheme.text(t("协作对话"),NativeUiTheme.ACCENT,11));
        top.addChild(NativeUiTheme.button(t("刷新"),this::show));
        if(!conversation.isBlank())top.addChild(NativeUiTheme.button(t("返回列表"),()->{conversation="";before=0;pages.clear();show();}));
        var notice=WorkspacePanels.text(t("读取 AI 间的真实消息…"));parent.addChild(notice);
        var list=WorkspacePanels.scroller(parent);list.getLayout().minHeight(0);
        var args=new LinkedHashMap<String,String>();args.put("agentId",agent);
        args.put("kind",conversation.isBlank()?"peerList":"peerMessages");
        if(conversation.isBlank())args.put("offset",Integer.toString(offset));else{args.put("conversationId",conversation);args.put("before",Long.toString(before));}
        WorkspacePanels.request("conversation.read",args).whenComplete((receipt,error)->{
            if(!current.getAsBoolean()||epoch!=ticket)return;
            if(error!=null){WorkspacePanels.failure(notice,error);return;}
            var data=WorkspacePanels.state(receipt);notice.setText(Component.literal(t("这里显示 AI 发送的消息与真实回复；任务状态请查看行为面板。")));
            if(conversation.isBlank()){
                for(var raw:data.getAsJsonArray("threads")){var row=raw.getAsJsonObject();var thread=row.getAsJsonObject("thread");
                    var card=WorkspacePanels.card(list,thread.get("senderName").getAsString()+" ↔ "+thread.get("recipientName").getAsString());
                    card.addChild(WorkspacePanels.text(t(row.get("active").getAsBoolean()?"正在回复":"消息已保存")+" · "+row.get("messageCount").getAsLong()));
                    card.addChild(NativeUiTheme.button(t("查看对话"),()->{conversation=thread.get("conversationId").getAsString();before=0;pages.clear();show();}));
                    renderedThreads++;
                }
                if(data.getAsJsonArray("threads").isEmpty())list.addScrollViewChild(WorkspacePanels.text(t("暂无 AI 协作对话")));
                var nav=WorkspacePanels.row();nav.getLayout().height(25).flexShrink(0);parent.addChild(nav);
                if(offset>0)nav.addChild(NativeUiTheme.button(t("上一页"),()->{offset=Math.max(0,offset-16);show();}));
                int next=data.get("nextOffset").getAsInt();if(next>=0)nav.addChild(NativeUiTheme.button(t("下一页"),()->{offset=next;show();}));
            }else{
                for(var raw:data.getAsJsonArray("messages")){var row=raw.getAsJsonObject();var meta=row.getAsJsonObject("message");
                    var card=WorkspacePanels.card(list,row.get("senderName").getAsString()+" → "+row.get("recipientName").getAsString());
                    String body=row.get("text").getAsString();var text=WorkspacePanels.text(body);card.addChild(text);
                    if(!body.isBlank())renderedMessages++;
                    String state=meta.get("status").getAsString();if(Set.of("PENDING","GENERATING").contains(state))card.addChild(WorkspacePanels.text(t("正在回复")));
                    if(!meta.get("errorCode").getAsString().isBlank())card.addChild(WorkspacePanels.text(t("本条未完成")+": "+meta.get("errorCode").getAsString()));
                    if(meta.get("textLength").getAsInt()>body.length()){
                        final int[] position={body.length()};final boolean[] loading={false};
                        var more=NativeUiTheme.button(t("继续展开"),()->{});
                        more.setOnClick(event->{if(loading[0])return;loading[0]=true;
                            WorkspacePanels.request("conversation.read",Map.of("kind","peerChunk","agentId",agent,"conversationId",conversation,"messageId",meta.get("messageId").getAsString(),"offset",Integer.toString(position[0])))
                                    .whenComplete((chunkResult,failed)->{loading[0]=false;if(!current.getAsBoolean()||epoch!=ticket)return;if(failed!=null){WorkspacePanels.failure(notice,failed);return;}var chunk=WorkspacePanels.state(chunkResult).getAsJsonObject("chunk");String appended=chunk.get("text").getAsString();text.setText(Component.literal(text.getText().getString()+appended));position[0]+=appended.length();more.setDisplay(position[0]<chunk.get("total").getAsInt());});
                        });card.addChild(more);
                    }
                }
                var nav=WorkspacePanels.row();nav.getLayout().height(25).flexShrink(0);parent.addChild(nav);
                long next=data.get("nextBefore").getAsLong();if(next>0)nav.addChild(NativeUiTheme.button(t("更早"),()->{pages.push(before);before=next;show();}));
                if(!pages.isEmpty())nav.addChild(NativeUiTheme.button(t("返回较新消息"),()->{before=pages.pop();show();}));
                nav.addChild(NativeUiTheme.button(t("最新"),()->{before=0;pages.clear();show();}));
            }
        });
    }
}
