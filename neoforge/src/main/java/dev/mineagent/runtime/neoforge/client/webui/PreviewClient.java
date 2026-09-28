package dev.mineagent.runtime.neoforge.client.webui;
/** Compatibility event entry; all previews are native LDLib2 windows. */
public final class PreviewClient {
    public static void open(String id){dev.mineagent.runtime.neoforge.client.nativeui.NativePreview.open(id);}
    private PreviewClient(){}
}
