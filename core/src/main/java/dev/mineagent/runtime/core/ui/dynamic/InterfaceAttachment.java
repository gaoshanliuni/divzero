package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Explicit placement and target selection; a selector never grants access to a menu's inventory. */
public record InterfaceAttachment(String menu, String screenClass, String anchor, int x, int y,
                                  int range, boolean throughWalls, String hostStylesheet) {
    public static final InterfaceAttachment NONE=new InterfaceAttachment("","","absolute",0,0,64,false,"");
    public static InterfaceAttachment parse(JsonNode node, InterfaceDefinition.Surface surface) {
        if(node.isMissingNode()) {
            if(surface==InterfaceDefinition.Surface.SCREEN_OVERLAY)throw bad("TARGET_REQUIRED");
            return NONE;
        }
        if(!node.isObject())throw bad("OBJECT");
        for(var field:node.properties())if(!Set.of("menu","screen_class","anchor","x","y","range","through_walls","host_stylesheet").contains(field.getKey()))throw bad("FIELD");
        String menu=text(node,"menu","",128),screen=text(node,"screen_class","",256),anchor=text(node,"anchor","absolute",16);
        if(!menu.isEmpty()&&!menu.equals("furnace")&&!menu.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw bad("MENU");
        if(!screen.isEmpty()&&!screen.matches("[A-Za-z_$][A-Za-z0-9_.$]*"))throw bad("SCREEN");
        if(!Set.of("top","left","right","bottom","center","absolute").contains(anchor))throw bad("ANCHOR");
        if(surface==InterfaceDefinition.Surface.SCREEN_OVERLAY&&menu.isEmpty()&&screen.isEmpty())throw bad("TARGET_REQUIRED");
        if(surface!=InterfaceDefinition.Surface.SCREEN_OVERLAY&&(!menu.isEmpty()||!screen.isEmpty()))throw bad("TARGET_SURFACE");
        String style=text(node,"host_stylesheet","",16384);
        if(!style.isBlank()&&surface!=InterfaceDefinition.Surface.SCREEN_OVERLAY)throw bad("STYLE_SURFACE");
        if(style.contains("\u0000")||style.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(https?:|file:|javascript:|@import).*"))throw bad("STYLE_RESOURCE");
        if(node.has("through_walls")&&!node.get("through_walls").isBoolean())throw bad("BOOLEAN");
        return new InterfaceAttachment(menu,screen,anchor,number(node,"x",0,-32768,32768),number(node,"y",0,-32768,32768),number(node,"range",64,1,256),node.path("through_walls").asBoolean(false),style);
    }
    private static String text(JsonNode node,String key,String fallback,int max){if(!node.has(key))return fallback;var value=node.get(key);if(!value.isTextual()||value.asText().length()>max)throw bad("TEXT");return value.asText();}
    private static int number(JsonNode node,String key,int fallback,int min,int max){if(!node.has(key))return fallback;var value=node.get(key);if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<min||value.intValue()>max)throw bad("NUMBER");return value.intValue();}
    private static IllegalArgumentException bad(String code){return new IllegalArgumentException("INTERFACE_ATTACHMENT_"+code);}
}
