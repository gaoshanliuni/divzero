package dev.mineagent.runtime.client.webui;
import java.nio.charset.StandardCharsets;

/** Translucency belongs to game composition: the private browser texture stays independent of world/other-view pixels. */
public final class WebGuiTheme {
    public static final int MANAGED_TINT=0xe0ffffff;
    private static final String CSS=resource("package-theme.css"),SCRIPT=resource("package-theme.js");
    private WebGuiTheme(){}
    public static int tint(boolean managed){return managed?MANAGED_TINT:0xffffffff;}
    public static String packageCss(){return CSS;}
    public static String packageScript(){return SCRIPT;}
    public static String passiveScript(){return resource("passive-hud.js");}
    private static String resource(String name){try(var in=WebGuiTheme.class.getResourceAsStream("/assets/mineagent_runtime/webui/"+name)){
        if(in==null)throw new IllegalStateException("WEBGUI_THEME_RESOURCE_MISSING");return new String(in.readAllBytes(),StandardCharsets.UTF_8);
    }catch(java.io.IOException e){throw new IllegalStateException("WEBGUI_THEME_RESOURCE_FAILED",e);}}
}
