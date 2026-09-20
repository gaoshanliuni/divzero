package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiAgentRpc.Command;

/** Opt-in graphical negative fixture: change actual layout/pixels, never the action or its returned receipt. */
final class CoordinateSmokePerturbation {
    private static boolean applied;
    private CoordinateSmokePerturbation(){}
    static void before(Command command){
        if(applied||!Boolean.getBoolean("mineagent.uiCoordinateSmoke")||!command.kind().equals("act"))return;
        String mode=System.getProperty("mineagent.coordinatePerturb","");if(mode.isBlank())return;
        if(!JsonParser.parseString(command.actionJson()).getAsJsonObject().get("action").getAsString().equals("clickAt"))return;
        var host=WebGuiHostAdapter.INSTANCE;
        if(mode.equals("layout")){
            host.browser().executeJavaScript("(()=>{const node=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==="+new Gson().toJson(command.viewId())+");node.style.width=(node.getBoundingClientRect().width+40)+'px';})();",host.browser().getURL(),0);
        }else if(mode.equals("pixels")){
            String url=host.packageUrl(command.viewId());boolean found=false;
            for(long id:host.browser().getFrameIdentifiers()){var frame=host.browser().getFrame(id);if(frame!=null&&!frame.isMain()&&url.equals(frame.getURL())){
                frame.executeJavaScript("(()=>{const canvas=document.querySelector('canvas');const ctx=canvas.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,5,5);})();",url,0);found=true;break;
            }}if(!found)throw new IllegalStateException("COORDINATE_PERTURB_FRAME_MISSING");
        }else throw new IllegalStateException("COORDINATE_PERTURB_MODE");
        applied=true;
    }
}
