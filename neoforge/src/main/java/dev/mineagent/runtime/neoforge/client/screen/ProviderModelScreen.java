package dev.mineagent.runtime.neoforge.client.screen;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.client.ProviderModelsClient;
import dev.mineagent.runtime.neoforge.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;

/** Native model directory plus explicit custom model ID; both save with the same revision check. */
public final class ProviderModelScreen extends Screen {
    private final Screen parent;private final Object connection;private int ticks,nextPoll,offset,epoch;private boolean loading,saving,contextReady;private EditBox customInput;private Button customSave;private String custom="",query="",selected="",status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("读取模型列表…");private long revision,generation;private JsonObject data=new JsonObject();
    public ProviderModelScreen(Screen parent){super(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("选择模型")));this.parent=parent;connection=Minecraft.getInstance().getConnection();}
    private int pageSize(){return Math.max(1,Math.min(10,(height-170)/24));}
    @Override protected void init(){drawWidgets();load(false);}
    private void drawWidgets(){
        boolean focusCustom=customInput!=null&&getFocused()==customInput;boolean focusSearch=!focusCustom&&getFocused() instanceof EditBox;int cursor=getFocused() instanceof EditBox box?box.getCursorPosition():0;
        clearWidgets();int w=Math.min(440,width-24),x=(width-w)/2;
        addRenderableWidget(new StringWidget(x,16,w,20,getTitle(),font));
        EditBox search=new EditBox(font,x,42,w-86,20,Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("搜索模型")));search.setHint(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("搜索模型")));search.setMaxLength(128);search.setValue(query);search.setResponder(v->{query=v;offset=0;epoch++;loading=false;nextPoll=ticks+8;});search.setEditable(!saving);addRenderableWidget(search);if(focusSearch){setFocused(search);search.setCursorPosition(Math.min(cursor,query.length()));}
        var refresh=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("刷新")),b->load(true)).bounds(x+w-80,42,80,20).build());refresh.active=!loading&&!saving;
        customInput=new EditBox(font,x,66,w-86,20,Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("自定义模型名称")));customInput.setHint(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("自定义模型名称")));customInput.setMaxLength(256);customInput.setValue(custom);customInput.setEditable(!saving);customInput.setResponder(v->{custom=v;if(customSave!=null)customSave.active=canSaveCustom();});addRenderableWidget(customInput);
        customSave=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("保存")),b->select(custom.strip())).bounds(x+w-80,66,80,20).build());customSave.active=canSaveCustom();if(focusCustom){setFocused(customInput);customInput.setCursorPosition(Math.min(cursor,custom.length()));}
        addRenderableWidget(new StringWidget(x,90,w,20,Component.literal(status),font));
        var rows=data.has("models")?data.getAsJsonArray("models"):new JsonArray();
        for(int i=0;i<Math.min(rows.size(),pageSize());i++){String model=rows.get(i).getAsString();var b=addRenderableWidget(Button.builder(Component.literal((model.equals(selected)?"✓ ":"")+model),ignored->select(model)).bounds(x,114+i*24,w,22).build());b.active=!saving&&!loading&&data.has("status")&&data.get("status").getAsString().equals("READY");}
        int y=height-50;var prev=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("上一页")),b->{offset=Math.max(0,offset-pageSize());load(false);}).bounds(x,y,90,20).build());prev.active=offset>0&&!loading&&!saving;
        var next=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("下一页")),b->{offset+=pageSize();load(false);}).bounds(x+96,y,90,20).build());next.active=data.has("total")&&offset+pageSize()<data.get("total").getAsInt()&&!loading&&!saving;
        addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("返回")),b->onClose()).bounds(x+w-90,y,90,20).build());
    }
    private void load(boolean refresh){if(loading||saving||!current())return;loading=true;nextPoll=0;final int ticket=++epoch;status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("读取模型列表…");drawWidgets();ProviderModelsClient.query(refresh,offset,query).whenComplete((value,error)->Minecraft.getInstance().execute(()->{
        if(ticket!=epoch||!current()||Minecraft.getInstance().screen!=this)return;loading=false;
        if(error!=null){status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("模型列表读取失败，点击刷新");data=new JsonObject();drawWidgets();return;}
        try{data=JsonParser.parseString(value).getAsJsonObject();String state=data.get("status").getAsString();contextReady=data.has("revision")&&data.has("selected");if(contextReady){revision=data.get("revision").getAsLong();selected=data.get("selected").getAsString();}if(state.equals("LOADING")){status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("正在获取模型列表…");nextPoll=ticks+16;}else if(state.equals("READY")){status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("点击模型即可保存 · ")+data.get("total").getAsInt()+dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(" 个");revision=data.get("revision").getAsLong();selected=data.get("selected").getAsString();}else{String code=data.has("error")?data.get("error").getAsString():"";status=message(code);}}catch(Exception invalid){status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("模型列表格式无效");data=new JsonObject();}drawWidgets();
    }));}
    private boolean canSaveCustom(){return contextReady&&!saving&&!loading&&current()&&!custom.isBlank()&&custom.length()<=256&&custom.chars().noneMatch(Character::isISOControl);}
    private void select(String model){if(saving||loading||!contextReady||!current())return;saving=true;selected=model;generation=PanelSnapshotInbox.generation();nextPoll=ticks+120;status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("正在保存模型…");drawWidgets();ClientPacketDistributor.sendToServer(new MineAgentPayloads.ConfigPatch(revision,Map.of("provider.openai.model",model)));}
    private boolean current(){return connection!=null&&connection==Minecraft.getInstance().getConnection()&&Minecraft.getInstance().player!=null;}
    public static String message(String code){return switch(code){case "MODELS_URL_REQUIRED"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("请先保存 API URL");case "MODELS_KEY_REQUIRED"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("请先保存 API Key");case "MODELS_AUTH_FAILED"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("Key 无效或无权读取模型，请检查配置");case "MODELS_UNSUPPORTED"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("该地址不支持 models，请检查 API URL");case "MODELS_EMPTY"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("接口没有返回可选模型");case "MODELS_RATE_LIMITED"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("服务限流，请稍后点击刷新");case "MODELS_FORBIDDEN"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("没有管理 Provider 的权限");case "MODELS_REDIRECT_DENIED"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("接口发生重定向，请保存最终 API URL");default->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("模型列表读取失败，点击刷新");};}
    @Override public void tick(){super.tick();ticks++;if(!current()){status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("连接已改变，请返回");if(loading||saving){loading=false;saving=false;epoch++;drawWidgets();}return;}
        if(saving){if(PanelSnapshotInbox.generation()!=generation&&PanelSnapshotInbox.snapshot().revision()>revision){saving=false;nextPoll=0;if(PanelSnapshotInbox.snapshot().values().getOrDefault("provider.openai.model","").equals(selected)){onClose();return;}status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("未保存：")+PanelSnapshotInbox.lastErrorCode();drawWidgets();}else if(ticks>=nextPoll){saving=false;nextPoll=0;status=dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("结果未知，请返回设置核对");drawWidgets();}return;}
        if(nextPoll>0&&ticks>=nextPoll){nextPoll=0;load(false);}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){epoch++;loading=false;super.removed();}
    @Override public void onClose(){Minecraft.getInstance().setScreen(current()?parent:null);dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.emit("settingsChanged",Map.of());}
}
