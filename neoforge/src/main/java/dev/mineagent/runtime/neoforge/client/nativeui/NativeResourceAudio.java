package dev.mineagent.runtime.neoforge.client.nativeui;

import dev.mineagent.runtime.core.conversation.SpeechWav;
import javax.sound.sampled.*;

/** Explicit play/stop of a decoded, bounded PCM segment. Never opens a URL or external player. */
final class NativeResourceAudio {
    private static final java.util.Set<NativeResourceAudio> LIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final byte[] wav;private SourceDataLine output;private volatile long epoch;private boolean playing;
    private NativeResourceAudio(byte[] wav){SpeechWav.validate(wav);this.wav=wav.clone();}
    static void add(WorkspaceWindow window,byte[] bytes){
        var audio=new NativeResourceAudio(bytes);LIVE.add(audio);window.onClose(()->{audio.stop();LIVE.remove(audio);});
        var row=WorkspacePanels.row();row.addChild(NativeUiTheme.button(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("播放片段"),audio::play));row.addChild(NativeUiTheme.button(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("停止播放"),audio::stop));window.body.addChild(row);
        window.body.addChild(WorkspacePanels.text(String.format(java.util.Locale.ROOT,"%.1f s · PCM 16 kHz",(bytes.length-44)/32000.0)));
    }
    static void workspaceRemoved(){for(var audio:LIVE)audio.stop();LIVE.clear();}
    private synchronized void play(){if(playing)return;playing=true;long token=++epoch;Thread.ofVirtual().name("resource-audio-preview").start(()->{SourceDataLine line=null;try{var format=new AudioFormat(16000,16,1,true,false);line=AudioSystem.getSourceDataLine(format);line.open(format,8192);synchronized(this){if(epoch!=token)return;output=line;}line.start();int at=44;while(epoch==token&&at<wav.length){int written=line.write(wav,at,Math.min(4096,wav.length-at));if(written<=0)break;at+=written;}if(epoch==token)line.drain();}catch(Exception e){if(epoch==token)net.minecraft.client.Minecraft.getInstance().execute(()->NativeWorkspaceScreen.notice(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("音频设备不可用。")));}finally{if(line!=null){line.stop();line.close();}synchronized(this){if(epoch==token){output=null;playing=false;}}}});}
    private synchronized void stop(){epoch++;playing=false;var line=output;output=null;if(line!=null){line.stop();line.flush();line.close();}}
}
