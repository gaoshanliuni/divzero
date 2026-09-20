package dev.mineagent.runtime.neoforge.client.screen;
import dev.mineagent.runtime.core.config.NativeSecretPayload;
import dev.mineagent.runtime.core.crypto.SecretChannel;
import dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter;
import dev.mineagent.runtime.neoforge.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** A key never belongs to the browser DOM, JavaScript draft store, logs, or model context. */
public final class NativeSecretScreen extends Screen {
    private final Screen parent;private final Object connection;private final String secretKey;private EditBox input;private Button save,clear;private StringWidget status,endpoint;
    private long snapshotGeneration;private long expectedRevision;private UUID world,instance,pending;private String publicKey="";private int ticks,waitingAt;private boolean waitingSnapshot=true,clearArmed;
    public NativeSecretScreen(Screen parent){this(parent,"provider.openai.apiKey");}
    public NativeSecretScreen(Screen parent,String secretKey){super(Component.literal(secretKey.equals("provider.asr.apiKey")?"ASR 独立 Key · 原生保密输入":"Provider API Key · 原生保密输入"));if(!NativeSecretPayload.KEYS.contains(secretKey))throw new IllegalArgumentException("SECRET_KEY_INVALID");this.secretKey=secretKey;this.parent=parent;connection=Minecraft.getInstance().getConnection();}
    @Override protected void init(){
        int left=Math.max(14,width/2-180),w=Math.min(360,width-28);
        addRenderableWidget(new StringWidget(left,28,w,22,getTitle(),font));
        addRenderableWidget(new StringWidget(left,55,w,18,Component.literal("密钥仅在此原生输入；网页不会接收输入值。"),font));
        endpoint=addRenderableWidget(new StringWidget(left,75,w,18,Component.literal("读取服务器配置…"),font));
        input=new EditBox(font,left,99,w,24,Component.literal("API Key 保密输入")){
            @Override protected MutableComponent createNarrationMessage(){return Component.literal("API Key 保密输入，内容已隐藏");}
        };input.setMaxLength(4096);input.addFormatter((value,cursor)->FormattedCharSequence.forward("•".repeat(value.length()),Style.EMPTY));input.setResponder(value->{clearArmed=false;if(clear!=null)clear.setMessage(Component.literal("清除已保存 Key…"));refreshButtons();});addRenderableWidget(input);
        status=addRenderableWidget(new StringWidget(left,130,w,36,Component.literal("正在读取当前配置版本…"),font));
        save=addRenderableWidget(Button.builder(Component.literal("加密替换并保存"),b->submit(input.getValue())).bounds(left,175,w/2-4,23).build());
        clear=addRenderableWidget(Button.builder(Component.literal("清除已保存 Key…"),b->{if(!clearArmed){clearArmed=true;clear.setMessage(Component.literal("再次点击确认清除"));status.setMessage(Component.literal("确认后清除服务器保存的 Key，不发起模型请求。"));}else submit("");}).bounds(left+w/2+4,175,w/2-4,23).build());
        addRenderableWidget(Button.builder(Component.literal("刷新版本（保留未提交输入）"),b->requestSnapshot()).bounds(left,207,w,22).build());
        addRenderableWidget(Button.builder(Component.literal("返回设置"),b->onClose()).bounds(left,239,w,22).build());
        requestSnapshot();setInitialFocus(input);refreshButtons();
    }
    private void requestSnapshot(){if(pending!=null)return;waitingSnapshot=true;clearArmed=false;snapshotGeneration=PanelSnapshotInbox.generation();ClientPacketDistributor.sendToServer(new MineAgentPayloads.PanelRequest());refreshButtons();}
    private boolean current(){return connection!=null&&connection==Minecraft.getInstance().getConnection()&&Minecraft.getInstance().player!=null;}
    private void refreshButtons(){if(save==null)return;boolean ready=current()&&!waitingSnapshot&&pending==null&&world!=null&&instance!=null&&!publicKey.isBlank()&&Boolean.parseBoolean(PanelSnapshotInbox.snapshot().values().getOrDefault("permission.manage_providers","false"));save.active=ready&&!input.getValue().isBlank();clear.active=ready;input.setEditable(current()&&pending==null);}
    private void submit(String value){
        if(!current()||waitingSnapshot||pending!=null||world==null||instance==null)return;
        try{
            UUID id=UUID.randomUUID();var key=KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            var envelope=SecretChannel.seal(key,new NativeSecretPayload(id,world,instance,expectedRevision,value,secretKey).encode());var encoder=Base64.getEncoder();pending=id;waitingAt=ticks;
            ClientPacketDistributor.sendToServer(new MineAgentPayloads.SecretConfigWrite(id,expectedRevision,world,instance,Map.of("ephemeral",encoder.encodeToString(envelope.ephemeralPublicKey()),"nonce",encoder.encodeToString(envelope.nonce()),"ciphertext",encoder.encodeToString(envelope.ciphertext()))));input.setValue("");status.setMessage(Component.literal("正在加密保存…"));refreshButtons();
        }catch(Exception failure){pending=null;input.setValue("");status.setMessage(Component.literal("密钥处理失败，请刷新后重试；未回显输入。"));refreshButtons();}
    }
    public static void accept(MineAgentPayloads.SecretConfigResult result){var mc=Minecraft.getInstance();if(mc.screen instanceof NativeSecretScreen screen&&screen.current()&&result.operation().equals(screen.pending)){screen.pending=null;screen.expectedRevision=result.revision();screen.clearArmed=false;screen.input.setValue("");screen.status.setMessage(Component.literal(result.accepted()?(result.configured()?"密钥已保存；没有调用模型。":"密钥已清除；没有调用模型。"):("未保存："+result.code()+"。请刷新版本再操作。")));screen.clear.setMessage(Component.literal("清除已保存 Key…"));screen.waitingSnapshot=!result.accepted();screen.refreshButtons();}}
    @Override public void tick(){super.tick();ticks++;if(!current()){input.setValue("");status.setMessage(Component.literal("连接已改变，已清除输入。"));refreshButtons();return;}
        if(waitingSnapshot&&pending==null&&PanelSnapshotInbox.generation()!=snapshotGeneration){var snapshot=PanelSnapshotInbox.snapshot();var values=snapshot.values();try{world=UUID.fromString(values.get("security.worldId"));instance=UUID.fromString(values.get("security.configInstance"));publicKey=values.getOrDefault("security.secretTransportPublicKey","");expectedRevision=snapshot.revision();String address=values.getOrDefault(secretKey.equals("provider.asr.apiKey")?"provider.asr.baseUrl":"provider.openai.baseUrl","");endpoint.setMessage(Component.literal(dev.mineagent.runtime.core.config.WebSettingsCatalog.safeUrl(address)?address:"地址含私密参数，已隐藏"));waitingSnapshot=false;status.setMessage(Component.literal(Boolean.parseBoolean(values.getOrDefault("permission.manage_providers","false"))?(values.containsKey(secretKey)?"已配置。留空不改变；替换或清除需要明确操作。":"尚未配置 API Key。保存不会测试或调用模型。") :"没有管理 Provider 的权限。"));}catch(Exception unavailable){status.setMessage(Component.literal("服务端密钥上下文未就绪。"));}snapshotGeneration=PanelSnapshotInbox.generation();refreshButtons();}
        if(pending!=null&&ticks-waitingAt>600){pending=null;waitingSnapshot=true;input.setValue("");status.setMessage(Component.literal("结果未知。先刷新查看状态，不自动重发。"));refreshButtons();}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){if(input!=null)input.setValue("");pending=null;super.removed();}
    @Override public void onClose(){if(input!=null)input.setValue("");Minecraft.getInstance().setScreen(current()?parent:null);WebGuiHostAdapter.INSTANCE.emit("settingsChanged",Map.of());}
}
