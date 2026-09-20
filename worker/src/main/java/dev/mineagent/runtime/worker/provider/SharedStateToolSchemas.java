package dev.mineagent.runtime.worker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

public final class SharedStateToolSchemas {
    private static final ObjectMapper JSON=new ObjectMapper();
    private SharedStateToolSchemas(){}
    public static final String LIST=build("list"),READ=build("read"),TRANSACT=build("transact"),WATCH=build("watch"),SUBSCRIBE=build("subscribe");
    private static ObjectNode number(long min,long max){return JSON.createObjectNode().put("type","integer").put("minimum",min).put("maximum",max);}
    private static ObjectNode text(){return JSON.createObjectNode().put("type","string");}
    private static ObjectNode key(){return text().put("pattern","^[A-Za-z][A-Za-z0-9_.-]{0,63}$");}
    private static ObjectNode condition(){var o=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var p=o.putObject("properties");p.set("key",key());p.putObject("test").putArray("enum").add("ABSENT").add("EXISTS").add("EQ").add("LT").add("LTE").add("GT").add("GTE");p.set("value",JSON.createObjectNode());o.putArray("required").add("key").add("test");return o;}
    private static ObjectNode transaction(){var o=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var p=o.putObject("properties");p.set("schema_version",number(1,1000000));p.set("expected_revision",number(0,9007199254740990L));p.putObject("conditions").put("type","array").put("maxItems",16).set("items",condition());
        var write=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var w=write.putObject("properties");w.set("key",key());w.putObject("op").putArray("enum").add("PUT").add("PUT_IF_ABSENT").add("ADD").add("DELETE");w.set("value",JSON.createObjectNode());write.putArray("required").add("key").add("op");p.putObject("writes").put("type","array").put("minItems",1).put("maxItems",16).set("items",write);o.putArray("required").add("schema_version").add("conditions").add("writes");return o;}
    public static String finishSchema(String source){try{var root=(ObjectNode)JSON.readTree(source);var goal=(ObjectNode)JSON.readTree(READ);var p=(ObjectNode)goal.get("properties");p.remove("keys");p.putObject("kind").put("const","shared_state");p.set("key",key());p.set("schema_version",number(1,1000000));p.set("expected_value",JSON.createObjectNode());p.putObject("exists").put("type","boolean");var required=goal.putArray("required");for(String name:new String[]{"kind","package_id","instance_id","package_revision","canonical_sha256","namespace","schema_version","key","expected_value"})required.add(name);((ArrayNode)root.path("properties").path("checks").path("items").path("oneOf")).add(goal);return JSON.writeValueAsString(root);}catch(Exception e){throw new IllegalStateException("SHARED_FINISH_SCHEMA",e);}}
    private static String build(String mode){try{
        var o=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var p=o.putObject("properties");var required=o.putArray("required");
        for(String name:new String[]{"package_id","instance_id"}){p.set(name,text().put("format","uuid"));required.add(name);}p.set("package_revision",number(1,9007199254740991L));p.set("canonical_sha256",text().put("pattern","^[a-f0-9]{64}$"));required.add("package_revision").add("canonical_sha256");
        if(!mode.equals("list")){p.set("namespace",key());required.add("namespace");}
        switch(mode){case "list"->p.set("offset",number(0,256));case "read"->{p.putObject("keys").put("type","array").put("minItems",1).put("maxItems",8).put("uniqueItems",true).set("items",key());required.add("keys");}
            case "watch"->{p.set("after_revision",number(0,9007199254740991L));required.add("after_revision");}
            case "transact"->{p.set("transaction",transaction());required.add("transaction");}
            case "subscribe"->{p.set("schema_version",number(1,1000000));p.putObject("keys").put("type","array").put("minItems",1).put("maxItems",8).put("uniqueItems",true).set("items",key());p.putObject("when").put("type","array").put("maxItems",8).set("items",condition());required.add("schema_version").add("keys").add("when").add("mode");var common=JSON.readTree(EventToolSchemas.SUBSCRIBE).path("properties");for(String name:new String[]{"mode","goal","max_wakes","max_model_calls","pending_limit","cooldown_ms","ttl_seconds","script","push"})p.set(name,common.get(name));}
            default->throw new IllegalArgumentException("SHARED_SCHEMA_MODE");}
        return JSON.writeValueAsString(o);
    }catch(Exception e){throw new IllegalStateException("SHARED_TOOL_SCHEMA",e);}}
}
