package dev.mineagent.runtime.core.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record SharedStateSchema(long version,Map<String,Field> fields) {
    public record Field(String type,String scope,String read,String write,long minimum,long maximum,int maxBytes,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) long ttlSeconds){
        public Field(String type,String scope,String read,String write,long minimum,long maximum,int maxBytes){this(type,scope,read,write,minimum,maximum,maxBytes,0);}
        public Field{
            if(!Set.of("STRING","INTEGER","NUMBER","BOOLEAN","OBJECT","ARRAY","JSON").contains(type)
                    ||!Set.of("SHARED","ACTOR").contains(scope)||!Set.of("OWNER","PARTICIPANT").contains(read)
                    ||!Set.of("OWNER","PARTICIPANT").contains(write)||minimum>maximum||minimum < -SharedJson.SAFE_INTEGER
                    ||maximum>SharedJson.SAFE_INTEGER||maxBytes<1||maxBytes>8192||ttlSeconds<0||ttlSeconds>31_536_000)throw new IllegalArgumentException("SHARED_FIELD_SCHEMA");
        }
        public void validate(JsonNode value){
            boolean valid=switch(type){case "STRING"->value.isTextual();case "INTEGER"->value.isIntegralNumber()&&value.canConvertToLong();case "NUMBER"->value.isNumber();case "BOOLEAN"->value.isBoolean();case "OBJECT"->value.isObject();case "ARRAY"->value.isArray();default->true;};
            if(!valid||value.toString().getBytes(StandardCharsets.UTF_8).length>maxBytes||value.isNumber()&&(value.decimalValue().compareTo(java.math.BigDecimal.valueOf(minimum))<0||value.decimalValue().compareTo(java.math.BigDecimal.valueOf(maximum))>0))throw new IllegalArgumentException("SHARED_VALUE_SCHEMA");
            SharedJson.canonical(value,0);
        }
        public boolean canRead(SharedStateStore.Context c){return read.equals("PARTICIPANT")||c.ownerContext();}
        public boolean canWrite(SharedStateStore.Context c){return write.equals("PARTICIPANT")||c.ownerContext();}
        public String subject(SharedStateStore.Context c){return scope.equals("ACTOR")?c.actor().toString():"";}
    }
    public SharedStateSchema{fields=Collections.unmodifiableMap(new TreeMap<>(fields));if(version<1||version>1_000_000||fields.isEmpty()||fields.size()>64)throw new IllegalArgumentException("SHARED_SCHEMA_BUDGET");fields.keySet().forEach(SharedJson::name);}
    public static SharedStateSchema parse(String source){
        var n=SharedJson.parse(source,16384);SharedJson.keys(n,Set.of("version","fields"),Set.of("version","fields"));if(!n.get("fields").isObject())throw new IllegalArgumentException("SHARED_SCHEMA_FIELDS");var fields=new TreeMap<String,Field>();
        n.get("fields").properties().forEach(e->{var f=e.getValue();SharedJson.keys(f,Set.of("type","scope","read","write","minimum","maximum","max_bytes","ttl_seconds"),Set.of("type"));
            fields.put(SharedJson.name(e.getKey()),new Field(SharedJson.text(f,"type",""),SharedJson.text(f,"scope","ACTOR"),SharedJson.text(f,"read","PARTICIPANT"),SharedJson.text(f,"write","PARTICIPANT"),SharedJson.integer(f,"minimum",-SharedJson.SAFE_INTEGER),SharedJson.integer(f,"maximum",SharedJson.SAFE_INTEGER),Math.toIntExact(SharedJson.integer(f,"max_bytes",8192)),SharedJson.integer(f,"ttl_seconds",0)));});
        return new SharedStateSchema(SharedJson.integer(n,"version",-1),fields);
    }
}
