package dev.mineagent.runtime.neoforge.client.screen;

import dev.mineagent.runtime.core.conversation.SpeechWav;
import dev.mineagent.runtime.core.config.WebSettingsCatalog;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.client.audio.NativeSpeechRecorder;
import dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter;
import dev.mineagent.runtime.neoforge.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;

/** Explicit record -> stop -> confirm upload -> return a review draft. Never sends conversation text. */
public final class NativeSpeechScreen extends Screen {
    private final Screen parent;private final Object connection;private final UUID world,agent,conversation,context;
    private NativeSpeechRecorder.Recording recording;private byte[] wav;private UUID operation;private String transcript="",state="IDLE",hash="";private long revision,submittedRevision,generation,snapshotGeneration,requestedAt;private boolean configured,confirmUpload,adopted,uploadSent;
    private Button record,stop,upload,adopt,cancel;private StringWidget endpoint,status,preview;
    public NativeSpeechScreen(Screen parent,UUID world,UUID agent,UUID conversation,UUID context){super(Component.literal("语音输入 · 原生麦克风"));this.parent=parent;this.world=world;this.agent=agent;this.conversation=conversation;this.context=context;connection=Minecraft.getInstance().getConnection();}
    private boolean current(){return connection!=null&&connection==Minecraft.getInstance().getConnection()&&Minecraft.getInstance().player!=null;}
    private boolean pending(){return Set.of("UPLOADING","TRANSCRIBING","WAITING").contains(state);}
    @Override protected void init(){int left=Math.max(12,width/2-210),w=Math.min(420,width-24),top=Math.max(0,(height-240)/2);
        addRenderableWidget(new StringWidget(left,top+8,w,16,getTitle(),font));addRenderableWidget(new StringWidget(left,top+26,w,16,Component.literal("系统默认麦克风 · 16kHz mono · 最长30秒 · 默认不录音"),font));
        endpoint=addRenderableWidget(new StringWidget(left,top+44,w,18,Component.literal("读取独立 ASR 配置…"),font));status=addRenderableWidget(new StringWidget(left,top+65,w,18,Component.literal("未录音。开始录音后，仍需另行确认上传。"),font));
        record=addRenderableWidget(Button.builder(Component.literal("开始录音 / 重新录音"),b->record()).bounds(left,top+88,w/2-4,20).build());stop=addRenderableWidget(Button.builder(Component.literal("停止录音"),b->{if(recording!=null)recording.stop();else if(operation!=null)send("status",0,new byte[0]);}).bounds(left+w/2+4,top+88,w/2-4,20).build());
        upload=addRenderableWidget(Button.builder(Component.literal("准备上传并转写…"),b->upload()).bounds(left,top+112,w,21).build());
        preview=addRenderableWidget(new StringWidget(left,top+136,w,16,Component.literal("原始录音仅保存在内存，不写客户端磁盘。"),font));
        adopt=addRenderableWidget(Button.builder(Component.literal("返回会话页，校对完整转写"),b->adopt()).bounds(left,top+156,w,21).build());
        cancel=addRenderableWidget(Button.builder(Component.literal("取消本次转写 / 丢弃"),b->cancelRequest()).bounds(left,top+182,w/2-4,20).build());var back=addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(left+w/2+4,top+182,w/2-4,20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新 ASR 配置（不会调用服务）"),b->requestConfig()).bounds(left,top+207,w,20).build());requestConfig();setInitialFocus(back);buttons();
    }
    private void requestConfig(){snapshotGeneration=PanelSnapshotInbox.generation();configured=false;confirmUpload=false;if(upload!=null)upload.setMessage(Component.literal("准备上传并转写…"));ClientPacketDistributor.sendToServer(new MineAgentPayloads.PanelRequest());}
    private void buttons(){if(record==null)return;record.active=current()&&configured&&recording==null&&!pending();stop.active=current()&&(recording!=null||operation!=null);stop.setMessage(Component.literal(recording!=null?"停止录音":"读取当前转写状态"));upload.active=current()&&configured&&recording==null&&!pending()&&wav!=null;adopt.active=current()&&state.equals("READY")&&!transcript.isBlank();cancel.active=current()&&(recording!=null||operation!=null);}
    private void record(){if(!current()||!configured||pending())return;cancelRequest();wav=null;transcript="";confirmUpload=false;long token=++generation;state="RECORDING";recording=NativeSpeechRecorder.start();status.setMessage(Component.literal("正在录音；停止前不会上传。"));var handle=recording;
        handle.result.whenComplete((audio,error)->Minecraft.getInstance().execute(()->{if(Minecraft.getInstance().screen!=this||token!=generation||!current())return;recording=null;if(error!=null){state="FAILED";status.setMessage(Component.literal("录音未完成或设备不可用，请明确重新录音。"));}else{wav=audio;state="RECORDED";status.setMessage(Component.literal("已停止录音。音频尚未上传，准备转写需要再次确认。"));}buttons();}));buttons();
    }
    private void upload(){if(wav==null||!current()||!configured||pending())return;if(!confirmUpload){confirmUpload=true;upload.setMessage(Component.literal("再次点击：确认上传到上述 ASR（可能计费）"));status.setMessage(Component.literal("录音将经服务器发往独立 ASR；不是 DeepSeek 聊天请求。"));return;}
        try{submittedRevision=revision;operation=UUID.randomUUID();hash=RuntimePackageCanonicalizer.sha256(wav);uploadSent=false;state="WAITING";requestedAt=System.currentTimeMillis();confirmUpload=false;upload.setMessage(Component.literal("准备上传并转写…"));send("begin",0,new byte[0]);status.setMessage(Component.literal("等待服务器接受本次上传；未知结果不会自动重发。"));}
        catch(Exception failure){state="FAILED";confirmUpload=false;status.setMessage(Component.literal("本次提交失败或结果未知，未自动重发。"));}buttons();
    }
    private void send(String action,int index,byte[] data){if(!current()||operation==null)return;int length=wav==null?0:wav.length;ClientPacketDistributor.sendToServer(new SpeechInputPayloads.Command(operation,world,agent,conversation,context,revision,action,hash,length,index,length==0?0:(length+SpeechWav.CHUNK_BYTES-1)/SpeechWav.CHUNK_BYTES,data));}
    private void cancelRequest(){generation++;if(recording!=null){recording.cancel();recording=null;}if(operation!=null)send("discard",0,new byte[0]);operation=null;wav=null;hash="";transcript="";state="IDLE";confirmUpload=false;if(upload!=null)upload.setMessage(Component.literal("准备上传并转写…"));if(status!=null)status.setMessage(Component.literal("已取消本次本地流程；已上传请求是否处理以服务端为准。"));buttons();}
    private void adopt(){if(!state.equals("READY")||transcript.isBlank()||!current())return;var result=Map.of("worldId",world,"agentId",agent,"conversationId",conversation,"contextId",context,"speechOperation",operation,"text",transcript);adopted=true;onClose();if(current())WebGuiHostAdapter.INSTANCE.emit("conversationSpeechDraft",result);}
    public static void accept(SpeechInputPayloads.Reply reply,Object source){var mc=Minecraft.getInstance();if(!(mc.screen instanceof NativeSpeechScreen screen)||!screen.current()||mc.getConnection().getConnection()!=source||!reply.operation().equals(screen.operation)||!reply.world().equals(screen.world)||!reply.agent().equals(screen.agent)||!reply.conversation().equals(screen.conversation)||!reply.context().equals(screen.context))return;
        screen.state=reply.state();screen.status.setMessage(Component.literal(reply.state()+(reply.errorCode().isEmpty()?"":" · "+reply.errorCode())));
        if(reply.state().equals("UPLOADING")&&reply.mayUpload()&&!screen.uploadSent&&screen.wav!=null){screen.uploadSent=true;for(int i=0;i<(screen.wav.length+SpeechWav.CHUNK_BYTES-1)/SpeechWav.CHUNK_BYTES;i++)screen.send("chunk",i,Arrays.copyOfRange(screen.wav,i*SpeechWav.CHUNK_BYTES,Math.min(screen.wav.length,(i+1)*SpeechWav.CHUNK_BYTES)));}
        if(reply.state().equals("READY")){screen.status.setMessage(Component.literal("READY · 本次配置 r"+screen.submittedRevision+" · 请求模型 "+(reply.model().isEmpty()?"未记录":reply.model())));screen.transcript=reply.text();screen.wav=null;String visible=reply.text().replace('\n',' ').replace('\r',' ');screen.preview.setMessage(Component.literal("转写 "+reply.text().length()+" 字符："+visible.substring(0,Math.min(visible.length(),60))+"…（返回页面核对全文）"));}
        screen.buttons();
    }
    @Override public void tick(){super.tick();if(!current()){if(recording!=null)recording.cancel();wav=null;transcript="";configured=false;buttons();return;}
        if(PanelSnapshotInbox.generation()!=snapshotGeneration){var s=PanelSnapshotInbox.snapshot();var v=s.values();if(revision!=s.revision()){confirmUpload=false;upload.setMessage(Component.literal("准备上传并转写…"));}revision=s.revision();String url=v.getOrDefault("provider.asr.baseUrl",""),model=v.getOrDefault("provider.asr.model","");configured=world.toString().equals(v.get("security.worldId"))&&Boolean.parseBoolean(v.getOrDefault("voice.input.enabled","false"))&&!url.isBlank()&&!model.isBlank()&&WebSettingsCatalog.safeUrl(url);endpoint.setMessage(Component.literal(configured?"下一请求 ASR "+url+" · "+model:"ASR 尚未启用/配置。请返回“配置与权限”。"));snapshotGeneration=PanelSnapshotInbox.generation();}
        if(recording!=null)status.setMessage(Component.literal(String.format(java.util.Locale.ROOT,"正在录音 %.1f / 30 秒；尚未上传。",recording.seconds())));
        if(pending()&&System.currentTimeMillis()-requestedAt>125000){send("cancel",0,new byte[0]);state="FAILED";status.setMessage(Component.literal("转写结果未知，已请求取消；如需重试必须新建请求。"));}
        buttons();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){generation++;if(recording!=null){recording.cancel();recording=null;}if(!adopted&&operation!=null)send("discard",0,new byte[0]);operation=null;wav=null;transcript="";super.removed();}
    @Override public void onClose(){if(!adopted)cancelRequest();wav=null;transcript="";Minecraft.getInstance().setScreen(current()?parent:null);}
}
