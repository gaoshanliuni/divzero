package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Data contract for arbitrary native widget trees. No HTML, executable Java or remote script URLs. */
public record InterfaceDefinition(String id, String title, Surface surface, JsonNode root,
                                  Map<String, JsonNode> data, String stylesheet,int order,Map<String,InterfaceSources.Source> sources,Map<String,InterfaceHandlers.Handler> handlers,InterfaceAttachment attachment) {
    public enum Surface { SCREEN, HUD, SCREEN_OVERLAY, ENTITY_HUD }
    public static final int MAX_SOURCE_BYTES=256*1024, MAX_NODES=2048, MAX_DEPTH=48;
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final Set<String> TYPES=Set.of("panel","row","column","scroll","label","button","input","toggle","select","progress","image","window");
    private static final Set<String> NODE_FIELDS=Set.of("id","type","text","value","bind","bindings","style","classes","resource","events","children","visible","enabled","secret","options");
    public static final String CONTRACT="""
        DivZero native UI v1: JSON, rendered with LDLib2 and built by the DivZero KubeJS bridge.
        {"id":"shop","title":"Shop","surface":"SCREEN","root":{"id":"root","type":"row","children":[...]},"data":{},"stylesheet":""}
        surface=SCREEN, HUD, SCREEN_OVERLAY or ENTITY_HUD. HUD is passive by default; it never grabs the mouse or blocks movement.
        SCREEN_OVERLAY attaches arbitrary controls to an existing game screen, without replacing its menu or slots. attachment:{"menu":"minecraft:furnace","anchor":"top","x":0,"y":-6}; menu="furnace" matches furnace/blast furnace/smoker. Or use exact screen_class and optional screen_title discovered by inspect_native_screen to avoid changing unrelated screens that share a base class. anchor=top/left/right/bottom/center/absolute, offsets are GUI units. It renders/interacts only while the target screen is open. Optional host_stylesheet applies LSS to a real LDLib2 host root and is removed when detached; other screen types still support injected decorations/controls, not arbitrary host-style mutation.
        ENTITY_HUD renders one passive copy of the root above each visible tracked living entity. attachment:{"range":64,"through_walls":false}; range1..256, hidden/behind-camera/out-of-range entities are skipped. Read-only data.entity has id/name/health/maxHealth/distance. Use get(data.entity,"health") and division by maxHealth for bars; no events/handlers are allowed. It does not grab mouse input.
        window is an arbitrary child-control container with draggable title, resize border, minimize/restore task button, maximize and close. It can be combined with panels, launchers and styles for a desktop. The default theme is MC; an explicit desktop request may style its local tree.
        Optional order is an integer -10000..10000 for ordering independent HUD panels. HUD coordinates use GUI-scaled screen units; root LSS left/top/right/bottom can position the panel.
        Optional sources binds data keys to live server data, without AI polling or reload:
        "sources":{"balance":{"kind":"score","objective":"coins","holder":"$viewer"},"health":{"kind":"agent","field":"health"}}.
        score holder may be $viewer (the viewing player's score name), $agent, or an exact scoreboard holder. A missing score is 0; an unavailable source is reported separately.
        Score sources require the existing MANAGE_SCOREBOARD permission; declaring a source never grants it.
        agent fields: health/max_health/food/name, scoped to this UI's AI. task sources use task_id plus status/title/revision/completed_steps/total_steps and must belong to this owner and AI.
        player fields read only the current viewer: health/max_health/food/name/x/y/z/yaw/pitch/experience. No player UUID selector is accepted.
        world fields: game_time/dimension/raining/thundering in that viewer's current dimension.
        block fields id/state/solid/fluid/position/loaded read an optional relative offset:[dx,dy,dz] from the viewer's feet, each axis -2047..2047. Unloaded chunks are never forced to load; inventories and block-entity NBT are not exposed.
        blocks sources use radius:1..2047, offset:nonnegative page cursor (default0). Values contain center/dimension/radius/offset/nextOffset/totalCells/counts/unknown/gameTick, at most4096 cells per read. -1 nextOffset means that page finishes the region; other pages are not included. Do not combine pages from different centers or ticks into one claimed instantaneous observation. Per-view declarations share a4096-cell read budget and server ticks schedule them fairly.
        A menu source uses exactly {"kind":"menu","field":"furnace_remaining_seconds"}; do not use name/path/type instead of field. furnace_lit is boolean (use it directly or not); furnace_progress is already normalized 0..1 (do not multiply it for a progress value). Menu selector "furnace" matches the furnace family; "minecraft:furnace" matches only the normal furnace.
        menu fields read the currently open real menu: type, furnace_remaining_seconds, furnace_progress (0..1), furnace_lit_seconds, furnace_lit, brewing_seconds, brewing_fuel. Inactive machine fields are zero/false. Use inspect_brewing_recipes to discover registered recipes, then buttons with open_preview(kind=recipe,recipe_id=...) handlers in a SCREEN_OVERLAY anchored left of minecraft:brewing_stand.
        Sources only read world data. Setting a local bound number never changes the real score, health or task.
        Arbitrary nested panel/row/column/scroll/label/button/input/toggle/select/progress/image nodes, each with a stable unique id.
        Node fields: id,type,text,value,bind,style,classes,resource,events,children,visible,enabled.
        style and stylesheet use LDLib2 LSS, not browser CSS. resource is a Minecraft namespaced resource, never a URL or local path.
        bind refers to one data key; text/value are defaults. Keep node ids and bind keys when restyling to preserve live input.
        select requires options:[{"value":"oak","label":"Oak"},...], up to256 unique nonempty values; bind stores the selected value. Its change event behaves like input. Secret inputs use secret:true and are excluded from observation/capture/drafts.
        Optional bindings map text/value/visible/enabled to bounded expressions, evaluated on each data update.
        Expressions: primitive literal; {"data":"key"}; {"literal":anyJSON}; {"op":"contains","args":["Stone bricks",{"data":"query"}]}.
        Operators: add/sub/mul/div/min/max/eq/ne/lt/lte/gt/gte/and/or/not/if/contains/startsWith/lower/upper/concat/length/at/get/number/string/join/clamp/round/object/array/json. number explicitly converts numeric input text; arithmetic never silently coerces strings.
        contains is case-insensitive; if is lazy. Numeric operations are finite, booleans are typed. Expressions have bounded depth/work/output; they never execute Java or access files.
        Example working search: an input binds query, and each product card binds visible to contains(productName, query).
        events: {"click" or "change":[{"op":"set","key":"query","value":"..."},
          {"op":"set","key":"query","from":"$event"},{"op":"toggle","key":"expanded"},
          {"op":"emit","action":"purchase","args":{"item":"..."}}]}.
        set may use expr instead of value/from, e.g. {"op":"set","key":"counter","expr":{"op":"add","args":[{"data":"counter"},1]}}.
        Arithmetic here changes UI data only. Never represent local balance or an emit intent as an executed world transaction.
        emit is an intent; the server must validate owner, world, agent, view and current revision, then enforce action permissions.
        Register application callbacks in root handlers: {"giveStone":{"tool":"give_item","arguments":{"item":"minecraft:stone","count":1},"resultKey":"purchaseResult"}}.
        An emit action must name a declared handler to execute. Optional handler bindings map tool argument names to the same bounded expressions; data key event contains the declared emit args, and eventValue is the input event value.
        Each callback has a durable event/operation id and a real tool receipt. Duplicate or uncertain callbacks are never re-executed. Result data is stored under resultKey; get(object,key) reads receipt fields for status labels.
        Game callbacks use the current player's real permissions and this UI's AI. Host/Python tools, player-body takeover and recursive native-UI management are not UI callbacks. Use their normal dedicated entrypoints.
        UI definitions and client data never grant game or host permissions. A local balance or enabled button is not a business transaction guard; game-tool/server application checks still decide the effect.
        Data changes update bound controls without rebuilding. Structure changes build and validate a candidate before replacing the old tree.
        Bad candidates preserve the working UI and return a path-specific error. No restart, world exit, copied scripts or global reload.
        """;

    public InterfaceDefinition(String id,String title,Surface surface,JsonNode root,Map<String,JsonNode> data,String stylesheet,int order,Map<String,InterfaceSources.Source> sources,Map<String,InterfaceHandlers.Handler> handlers){this(id,title,surface,root,data,stylesheet,order,sources,handlers,InterfaceAttachment.NONE);}
    public InterfaceDefinition {
        root=root.deepCopy();
        sources=Map.copyOf(sources);
        handlers=Map.copyOf(handlers);
        var copy=new LinkedHashMap<String,JsonNode>();data.forEach((k,v)->copy.put(k,v.deepCopy()));data=Collections.unmodifiableMap(copy);
    }
    @Override public JsonNode root(){return root.deepCopy();}
    @Override public Map<String,JsonNode> data(){var copy=new LinkedHashMap<String,JsonNode>();data.forEach((k,v)->copy.put(k,v.deepCopy()));return Collections.unmodifiableMap(copy);}

    public static InterfaceDefinition parse(String source) {
        if(source==null||source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_SOURCE_BYTES)throw error("$","SOURCE_SIZE");
        final JsonNode doc;
        try {doc=JSON.readTree(source);}catch(Exception e){throw error("$","INVALID_JSON");}
        fields(doc,Set.of("id","title","surface","root","data","stylesheet","order","sources","handlers","attachment"),"$");
        String id=id(doc.path("id"),"$.id"),title=string(doc.path("title"),"$.title",256);
        Surface surface;
        try {surface=Surface.valueOf(doc.path("surface").textValue());}catch(Exception e){throw error("$.surface","SCREEN_HUD_OVERLAY_OR_ENTITY");}
        var ids=new HashSet<String>();validateNode(doc.path("root"),"$.root",0,ids);
        var data=new LinkedHashMap<String,JsonNode>();
        if(doc.has("data")){
            if(!doc.get("data").isObject()||doc.get("data").size()>MAX_NODES)throw error("$.data","DATA_OBJECT");
            for(var e:doc.get("data").properties()){checkId(e.getKey(),"$.data");data.put(e.getKey(),e.getValue().deepCopy());}
        }
        String stylesheet=doc.has("stylesheet")?style(doc.get("stylesheet"),"$.stylesheet",65536):"";
        int order=0;if(doc.has("order")){var value=doc.get("order");if(!value.isIntegralNumber()||!value.canConvertToInt()||Math.abs((long)value.intValue())>10000)throw error("$.order","ORDER");order=value.intValue();}
        var sources=InterfaceSources.parse(doc.path("sources"));var handlers=InterfaceHandlers.parse(doc.path("handlers"));
        for(var handler:handlers.values())if(sources.containsKey(handler.resultKey()))throw error("$.handlers","READ_ONLY_RESULT_KEY");
        if(surface==Surface.ENTITY_HUD){data.put("entity",JSON.createObjectNode().put("id","").put("name","").put("health",0).put("maxHealth",1).put("distance",0));if(!handlers.isEmpty())throw error("$.handlers","ENTITY_HUD_PASSIVE");walk(doc.get("root"),node->{if(node.has("events"))throw error("$.root","ENTITY_HUD_PASSIVE");});}
        return new InterfaceDefinition(id,title,surface,doc.get("root"),data,stylesheet,order,sources,handlers,InterfaceAttachment.parse(doc.path("attachment"),surface));
    }
    public Map<String,String> inputBindings(){
        var bindings=new LinkedHashMap<String,String>();walk(root,n->{String type=n.path("type").asText();if(Set.of("input","toggle","select").contains(type)&&n.has("bind"))bindings.put(n.path("id").asText(),type+":"+n.path("bind").asText());});return Map.copyOf(bindings);
    }
    public Set<String> secretKeys(){var keys=new HashSet<String>();walk(root,node->{if(node.path("type").asText().equals("input")&&node.has("bind")&&node.path("secret").asBoolean())keys.add(node.path("bind").asText());});return Set.copyOf(keys);}
    /** Secret editor values stay on this client; ordinary inspection/acks do not persist or disclose them. */
    public Map<String,JsonNode> observableData(Map<String,JsonNode> values){var result=new LinkedHashMap<String,JsonNode>();var privateKeys=secretKeys();values.forEach((key,value)->{if(!privateKeys.contains(key))result.put(key,value.deepCopy());});return result;}
    public Optional<JsonNode> node(String id){var found=new ArrayList<JsonNode>();walk(root,n->{if(n.path("id").asText().equals(id))found.add(n.deepCopy());});return found.stream().findFirst();}
    public boolean interactiveNode(String id){return interactiveNode(id,data);}
    public boolean interactiveNode(String id,Map<String,JsonNode> values){var path=new ArrayList<JsonNode>();if(!path(root,id,path))return false;for(var node:path)if(!boundBoolean(node,"visible",values)||!boundBoolean(node,"enabled",values))return false;return true;}
    private static boolean path(JsonNode node,String id,List<JsonNode> path){path.add(node);if(node.path("id").asText().equals(id))return true;for(var child:node.path("children"))if(path(child,id,path))return true;path.removeLast();return false;}
    public static boolean boundBoolean(JsonNode node,String key,Map<String,JsonNode> values){return node.path("bindings").has(key)?InterfaceExpression.truth(InterfaceExpression.evaluate(node.get("bindings").get(key),values)):node.path(key).asBoolean(true);}
    public static void walk(JsonNode n,java.util.function.Consumer<JsonNode> visitor){visitor.accept(n);for(var child:n.path("children"))walk(child,visitor);}
    private static void validateNode(JsonNode n,String path,int depth,Set<String> ids){
        if(depth>MAX_DEPTH)throw error(path,"TREE_DEPTH");fields(n,NODE_FIELDS,path);
        String nodeId=id(n.path("id"),path+".id");if(!ids.add(nodeId))throw error(path+".id","DUPLICATE_ID");if(ids.size()>MAX_NODES)throw error(path,"NODE_COUNT");
        String type=string(n.path("type"),path+".type",24);if(!TYPES.contains(type))throw error(path+".type","UNKNOWN_WIDGET");
        if(n.has("text"))string(n.get("text"),path+".text",16384);
        if(type.equals("select")){var options=n.path("options");if(!options.isArray()||options.isEmpty()||options.size()>256)throw error(path+".options","SELECT_OPTIONS");var values=new HashSet<String>();for(var option:options){fields(option,Set.of("value","label"),path+".options");String value=string(option.path("value"),path+".options.value",256);string(option.path("label"),path+".options.label",512);if(value.isEmpty()||!values.add(value))throw error(path+".options","SELECT_DUPLICATE");}}
        else if(n.has("options"))throw error(path+".options","SELECT_REQUIRED");
        if(n.has("secret")&&(!type.equals("input")||!n.get("secret").isBoolean()))throw error(path+".secret","SECRET_INPUT_REQUIRED");
        if(n.has("bind"))id(n.get("bind"),path+".bind");
        if(n.has("bindings")){fields(n.get("bindings"),Set.of("text","value","visible","enabled"),path+".bindings");for(var entry:n.get("bindings").properties())InterfaceExpression.validate(entry.getValue());}
        if(n.has("style"))style(n.get("style"),path+".style",8192);
        if(n.has("resource")&&!string(n.get("resource"),path+".resource",512).matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw error(path+".resource","RESOURCE_ID");
        if(n.has("resource")&&n.get("resource").asText().contains(".."))throw error(path+".resource","RESOURCE_PATH");
        for(String b:List.of("visible","enabled"))if(n.has(b)&&!n.get(b).isBoolean())throw error(path+"."+b,"BOOLEAN");
        if(n.has("classes")){if(!n.get("classes").isArray()||n.get("classes").size()>32)throw error(path+".classes","CLASSES");for(var c:n.get("classes"))id(c,path+".classes");}
        if(n.has("events")){
            fields(n.get("events"),Set.of("click","change"),path+".events");
            for(var e:n.get("events").properties()){
                if(!e.getValue().isArray()||e.getValue().size()>32)throw error(path+".events."+e.getKey(),"ACTION_LIST");
                int i=0;for(var action:e.getValue())validateAction(action,path+".events."+e.getKey()+"["+(i++)+"]");
            }
        }
        if(n.has("children")){
            if(!n.get("children").isArray()||!Set.of("panel","row","column","scroll","window").contains(type))throw error(path+".children","CONTAINER_REQUIRED");
            int i=0;for(var child:n.get("children"))validateNode(child,path+".children["+(i++)+"]",depth+1,ids);
        }
    }
    private static void validateAction(JsonNode a,String path){
        String op=a.path("op").asText();
        switch(op){
            case "set"->{fields(a,Set.of("op","key","value","from","expr"),path);id(a.path("key"),path+".key");if((a.has("value")?1:0)+(a.has("from")?1:0)+(a.has("expr")?1:0)!=1||a.has("from")&&!a.path("from").asText().equals("$event"))throw error(path,"SET_VALUE_OR_EVENT");if(a.has("expr"))InterfaceExpression.validate(a.get("expr"));}
            case "toggle"->{fields(a,Set.of("op","key"),path);id(a.path("key"),path+".key");}
            case "emit"->{fields(a,Set.of("op","action","args"),path);id(a.path("action"),path+".action");if(a.has("args")&&!a.get("args").isObject())throw error(path+".args","OBJECT");}
            default->throw error(path+".op","UNKNOWN_ACTION");
        }
    }
    private static String style(JsonNode n,String p,int max){String s=string(n,p,max);if(s.contains("\u0000")||s.toLowerCase(Locale.ROOT).matches("(?s).*(https?:|file:|javascript:|@import).*"))throw error(p,"EXTERNAL_RESOURCE");return s;}
    private static String id(JsonNode n,String p){String s=string(n,p,96);checkId(s,p);return s;}
    private static void checkId(String s,String p){if(!s.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw error(p,"IDENTIFIER");}
    private static String string(JsonNode n,String p,int max){if(!n.isTextual()||n.textValue().length()>max)throw error(p,"STRING");return n.textValue();}
    private static void fields(JsonNode n,Set<String> allowed,String p){if(n==null||!n.isObject())throw error(p,"OBJECT");for(var e:n.properties())if(!allowed.contains(e.getKey()))throw error(p+"."+e.getKey(),"UNKNOWN_FIELD");}
    public static IllegalArgumentException error(String path,String code){return new IllegalArgumentException("INTERFACE_"+code+" at "+path);}
}
