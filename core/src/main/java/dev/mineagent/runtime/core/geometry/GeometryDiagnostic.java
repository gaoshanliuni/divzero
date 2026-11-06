package dev.mineagent.runtime.core.geometry;

import java.util.*;

/** Stable machine code plus actionable location, without embedding the entire candidate. */
public final class GeometryDiagnostic extends IllegalArgumentException {
    private final Map<String,Object> details;
    public GeometryDiagnostic(String code,String field,String expected,Object actual){
        this(code,Map.of("field",field,"expected",expected,"actual",actual,"diagnostic",field+": expected "+expected+"; observed "+actual));
    }
    public GeometryDiagnostic(String code,Map<String,Object> details){super(code);this.details=Map.copyOf(details);}
    public Map<String,Object> details(){return details;}
    public static Map<String,Object> details(Throwable failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof GeometryDiagnostic d)return d.details();
        return Map.of();
    }
    public static Map<String,Object> merge(Map<String,Object> receipt,Throwable failure){var out=new LinkedHashMap<>(receipt);out.putAll(details(failure));return out;}
    public GeometryDiagnostic rebase(String prefix,String component){
        var data=new LinkedHashMap<>(details);String field=Objects.toString(data.get("field"),"");data.put("field",prefix+field);data.put("component",component);
        data.put("diagnostic",prefix+field+": "+data.getOrDefault("diagnostic",getMessage()));return new GeometryDiagnostic(getMessage(),data);
    }
}
