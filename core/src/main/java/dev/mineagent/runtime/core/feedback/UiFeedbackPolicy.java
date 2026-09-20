package dev.mineagent.runtime.core.feedback;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Data-only declaration. Parsing a policy never grants it or starts a consumer. */
public record UiFeedbackPolicy(String entry,String sha256,Map<String,Event> events) {
    public static final String RESOURCE="ui/feedback.json";
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public record Field(String type,int maxLength,BigDecimal minimum,BigDecimal maximum) {
        public Field {
            if(!Set.of("string","boolean","number").contains(type)||maxLength<0||maxLength>2048
                    ||!type.equals("string")&&maxLength!=0||type.equals("string")&&maxLength<1
                    ||!type.equals("number")&&(minimum!=null||maximum!=null)
                    ||minimum!=null&&maximum!=null&&minimum.compareTo(maximum)>0)throw invalid("FIELD");
        }
    }
    public record Event(String mode,String lifecycle,int maxEvents,long cooldownMillis,Map<String,Field> fields,Set<String> required) {
        public Event {
            if(!Set.of("RECORD_ONLY","DETERMINISTIC","AGENT_WAKE").contains(mode)||!Set.of("PAGE_BOUND","INDEPENDENT").contains(lifecycle)
                    ||maxEvents<1||maxEvents>64||cooldownMillis<0||cooldownMillis>600000||fields==null||fields.isEmpty()||fields.size()>16||required==null||!fields.keySet().containsAll(required))throw invalid("EVENT");
            fields.keySet().forEach(UiFeedbackPolicy::name);
            fields=Collections.unmodifiableMap(new TreeMap<>(fields));required=Collections.unmodifiableSet(new TreeSet<>(required));
        }
    }
    public UiFeedbackPolicy {
        if(entry==null||!entry.matches("ui/[A-Za-z0-9_./-]+\\.html")||entry.contains("..")||sha256==null||!sha256.matches("[a-f0-9]{64}")||events==null||events.isEmpty()||events.size()>8)throw invalid("POLICY");
        events.keySet().forEach(UiFeedbackPolicy::name);events=Collections.unmodifiableMap(new TreeMap<>(events));
    }
    @FunctionalInterface public interface Reader {byte[] read(String hash)throws IOException;}
    /** Caller still verifies the package signature, ownership and current grant before using this result. */
    public static UiFeedbackPolicy fromPackage(RuntimePackage pkg,String entry,Reader reader)throws Exception {
        if(pkg.entrypoints().values().stream().noneMatch(e->e.path().equals(entry)&&e.side()==RuntimeResourceSide.CLIENT))throw invalid("ENTRY");
        var ref=pkg.resources().get(RESOURCE);
        if(ref==null||!ref.path().equals(RESOURCE)||ref.side()!=RuntimeResourceSide.CLIENT||!ref.mediaType().equals("application/json")||ref.size()<1||ref.size()>32768)throw invalid("RESOURCE");
        byte[] bytes=reader.read(ref.sha256());
        if(bytes.length!=ref.size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(ref.sha256()))throw invalid("RESOURCE_INTEGRITY");
        return parse(bytes,entry);
    }
    public static UiFeedbackPolicy parse(byte[] bytes,String entry) {
        try {
            if(bytes==null||bytes.length<1||bytes.length>32768)throw invalid("BUDGET");
            var root=JSON.readTree(bytes);keys(root,Set.of("schema","entries"),Set.of("schema","entries"));
            if(integer(root,"schema")!=1)throw invalid("SCHEMA");var entries=root.get("entries");
            if(!entries.isObject()||entries.isEmpty()||entries.size()>8||!entries.has(entry))throw invalid("ENTRY");
            Map<String,Event> selected=null;
            var paths=entries.properties().iterator();while(paths.hasNext()){
                var page=paths.next();String path=page.getKey();if(!path.matches("ui/[A-Za-z0-9_./-]+\\.html")||path.contains(".."))throw invalid("ENTRY");
                keys(page.getValue(),Set.of("events"),Set.of("events"));var eventNodes=page.getValue().get("events");if(!eventNodes.isObject()||eventNodes.isEmpty()||eventNodes.size()>8)throw invalid("EVENTS");
                var parsed=new TreeMap<String,Event>();var iterator=eventNodes.properties().iterator();while(iterator.hasNext()){
                    var item=iterator.next();name(item.getKey());var value=item.getValue();
                    keys(value,Set.of("mode","lifecycle","max_events","cooldown_ms","fields","required"),Set.of("mode","lifecycle","max_events","cooldown_ms","fields","required"));
                    var properties=value.get("fields");if(!properties.isObject()||properties.isEmpty()||properties.size()>16)throw invalid("FIELDS");
                    var fields=new TreeMap<String,Field>();var fieldNodes=properties.properties().iterator();while(fieldNodes.hasNext()){
                        var property=fieldNodes.next();name(property.getKey());var v=property.getValue();String type=string(v,"type");
                        var allowed=switch(type){case "string"->Set.of("type","max_length");case "boolean"->Set.of("type");case "number"->Set.of("type","minimum","maximum");default->throw invalid("FIELD_TYPE");};
                        keys(v,allowed,type.equals("string")?allowed:Set.of("type"));
                        fields.put(property.getKey(),new Field(type,type.equals("string")?Math.toIntExact(integer(v,"max_length")):0,decimal(v,"minimum"),decimal(v,"maximum")));
                    }
                    var required=new TreeSet<String>();var requiredNode=value.get("required");if(!requiredNode.isArray()||requiredNode.size()>16)throw invalid("REQUIRED");
                    for(var key:requiredNode){if(!key.isTextual()||!required.add(key.asText()))throw invalid("REQUIRED");}
                    parsed.put(item.getKey(),new Event(string(value,"mode"),string(value,"lifecycle"),Math.toIntExact(integer(value,"max_events")),integer(value,"cooldown_ms"),fields,required));
                }
                if(path.equals(entry))selected=parsed;
            }
            return new UiFeedbackPolicy(entry,RuntimePackageCanonicalizer.sha256(bytes),selected);
        }catch(Exception e){throw new IllegalArgumentException("FEEDBACK_POLICY_INVALID",e);}
    }
    public Event event(String name){var value=events.get(name);if(value==null)throw invalid("UNDECLARED_EVENT");return value;}
    public String validate(String name,String payload){return validate(event(name),payload);}
    public static String validate(Event event,String payload) {
        try {
            if(payload==null||payload.getBytes(StandardCharsets.UTF_8).length>8192)throw invalid("PAYLOAD_BUDGET");
            var value=JSON.readTree(payload);keys(value,event.fields().keySet(),event.required());var normalized=new TreeMap<String,Object>();
            var values=value.properties().iterator();while(values.hasNext()){
                var field=values.next();var rule=event.fields().get(field.getKey());var data=field.getValue();
                switch(rule.type()){
                    case "string"->{if(!data.isTextual()||data.textValue().codePointCount(0,data.textValue().length())>rule.maxLength())throw invalid("PAYLOAD_STRING");normalized.put(field.getKey(),data.textValue());}
                    case "boolean"->{if(!data.isBoolean())throw invalid("PAYLOAD_BOOLEAN");normalized.put(field.getKey(),data.booleanValue());}
                    case "number"->{if(!data.isNumber()||!Double.isFinite(data.doubleValue())||data.decimalValue().abs().compareTo(BigDecimal.valueOf(9007199254740991L))>0)throw invalid("PAYLOAD_NUMBER");var n=data.decimalValue();if(rule.minimum()!=null&&n.compareTo(rule.minimum())<0||rule.maximum()!=null&&n.compareTo(rule.maximum())>0)throw invalid("PAYLOAD_RANGE");normalized.put(field.getKey(),n.stripTrailingZeros());}
                    default->throw invalid("PAYLOAD_TYPE");
                }
            }
            return JSON.writeValueAsString(normalized);
        }catch(Exception e){throw new IllegalArgumentException("FEEDBACK_PAYLOAD_INVALID",e);}
    }
    private static void keys(JsonNode node,Set<String> allowed,Set<String> required){if(node==null||!node.isObject())throw invalid("OBJECT");var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);if(!allowed.containsAll(actual)||!actual.containsAll(required))throw invalid("KEYS");}
    private static String string(JsonNode node,String key){if(node==null||!node.path(key).isTextual())throw invalid("STRING");return node.get(key).asText();}
    private static long integer(JsonNode node,String key){if(!node.path(key).isIntegralNumber()||!node.get(key).canConvertToLong())throw invalid("INTEGER");return node.get(key).longValue();}
    private static BigDecimal decimal(JsonNode node,String key){if(!node.has(key))return null;var value=node.get(key);if(!value.isNumber()||!Double.isFinite(value.doubleValue())||value.decimalValue().abs().compareTo(BigDecimal.valueOf(9007199254740991L))>0)throw invalid("NUMBER");return value.decimalValue();}
    private static void name(String value){if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||Set.of("__proto__","prototype","constructor").contains(value))throw invalid("NAME");}
    private static IllegalArgumentException invalid(String code){return new IllegalArgumentException("FEEDBACK_"+code);}
}
