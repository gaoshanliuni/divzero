package dev.mineagent.runtime.core.directory;
import java.util.*;
public record AudienceToolRequest(String tool,AudienceStore.Create create,UUID id,long expectedRevision,String inspectKind) {
    public static final Set<String> TOOLS=Set.of("create_audience","resolve_audience","inspect_audience","revoke_audience");
    public static AudienceToolRequest parse(String tool,String source){try{
        if(!TOOLS.contains(tool)||source==null||source.length()>16384)throw new IllegalArgumentException();var n=ObjectQuery.JSON.readTree(source);
        if(tool.equals("create_audience")){
            ObjectQuery.keys(n,Set.of("mode","query","ttl_seconds"),Set.of("mode","query"));int ttl=3600;
            if(n.has("ttl_seconds")){if(!n.get("ttl_seconds").isIntegralNumber()||!n.get("ttl_seconds").canConvertToInt())throw new IllegalArgumentException();ttl=n.get("ttl_seconds").intValue();}
            return new AudienceToolRequest(tool,new AudienceStore.Create(ObjectQuery.text(n,"mode",""),ObjectQuery.parse(n.get("query")),ttl),null,0,"");
        }
        if(tool.equals("inspect_audience")){ObjectQuery.keys(n,Set.of("kind","id"),Set.of("kind","id"));String kind=ObjectQuery.text(n,"kind","");if(!Set.of("DEFINITION","SNAPSHOT","OPERATION").contains(kind))throw new IllegalArgumentException();return new AudienceToolRequest(tool,null,UUID.fromString(ObjectQuery.text(n,"id","")),0,kind);}
        ObjectQuery.keys(n,Set.of("audience_id","expected_revision"),Set.of("audience_id","expected_revision"));var r=n.get("expected_revision");if(!r.isIntegralNumber()||!r.canConvertToLong()||r.longValue()<1||r.longValue()>9_007_199_254_740_991L)throw new IllegalArgumentException();
        return new AudienceToolRequest(tool,null,UUID.fromString(ObjectQuery.text(n,"audience_id","")),r.longValue(),"");
    }catch(Exception e){throw new IllegalArgumentException("AUDIENCE_TOOL_ARGUMENTS",e);}}
    public String canonical(){try{
        var values=new LinkedHashMap<String,Object>();if(tool.equals("create_audience")){values.put("mode",create.mode());values.put("query",create.query());values.put("ttl_seconds",create.ttlSeconds());}
        else if(tool.equals("inspect_audience")){values.put("kind",inspectKind);values.put("id",id);}else{values.put("audience_id",id);values.put("expected_revision",expectedRevision);}return ObjectQuery.JSON.writeValueAsString(values);
    }catch(Exception e){throw new IllegalArgumentException("AUDIENCE_TOOL_ENCODING",e);}}
}
