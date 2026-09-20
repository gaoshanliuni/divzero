package dev.mineagent.runtime.core.directory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.directory.ObjectRef.Kind;
import java.util.*;

/** Bounded, data-only discovery filters. Spatial origin is never inferred from a rendering viewer. */
public record ObjectQuery(Kind kind,String name,String match,List<String> ids,String team,String dimension,Near near,Region region,int limit,String cursor) {
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    public record Point(String dimension,double x,double y,double z){
        public Point{if(!resource(dimension)||!finite(x,y,z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000||Math.abs(y)>2048)throw new IllegalArgumentException("DIRECTORY_POSITION");}
    }
    public record Near(String reference,double radius,Point position){
        public Near{if(!Set.of("ACTOR","POSITION").contains(reference)||!Double.isFinite(radius)||radius<1||radius>128||reference.equals("ACTOR")&&position!=null||reference.equals("POSITION")&&position==null)throw new IllegalArgumentException("DIRECTORY_REFERENCE");}
    }
    public record Region(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        public Region{if(!finite(minX,minY,minZ,maxX,maxY,maxZ)||minX>maxX||minY>maxY||minZ>maxZ||maxX-minX>512||maxY-minY>512||maxZ-minZ>512||Math.max(Math.abs(minX),Math.abs(maxX))>30_000_000||Math.max(Math.abs(minZ),Math.abs(maxZ))>30_000_000||Math.max(Math.abs(minY),Math.abs(maxY))>2048)throw new IllegalArgumentException("DIRECTORY_REGION");}
    }
    public ObjectQuery {
        Objects.requireNonNull(kind);ids=List.copyOf(ids);
        for(var value:List.of(name,team,dimension,cursor))if(value.length()>128||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("DIRECTORY_QUERY_TEXT");
        if(!Set.of("EXACT","PREFIX").contains(match)||limit<1||limit>32||ids.size()>64||new HashSet<>(ids).size()!=ids.size()||ids.stream().anyMatch(s->s.isBlank()||s.length()>128||s.chars().anyMatch(Character::isISOControl))||!dimension.isEmpty()&&!resource(dimension))throw new IllegalArgumentException("DIRECTORY_QUERY");
        if(Set.of(Kind.PLAYER,Kind.ENTITY,Kind.INSTANCE).contains(kind))ids=ids.stream().map(s->UUID.fromString(s).toString()).toList();
        if(new HashSet<>(ids).size()!=ids.size()||kind==Kind.DIMENSION&&ids.stream().anyMatch(s->!resource(s)))throw new IllegalArgumentException("DIRECTORY_IDS");
        if(!team.isEmpty()&&kind!=Kind.PLAYER||near!=null&&region!=null||region!=null&&dimension.isEmpty()||(near!=null||region!=null)&&Set.of(Kind.TEAM,Kind.DIMENSION).contains(kind))throw new IllegalArgumentException("DIRECTORY_QUERY_FILTER");
        if(near!=null&&near.position()!=null&&!dimension.isEmpty()&&!dimension.equals(near.position().dimension()))throw new IllegalArgumentException("DIRECTORY_DIMENSION_CONFLICT");
        ids=ids.stream().sorted().toList();
    }
    public ObjectQuery withCursor(String value){return new ObjectQuery(kind,name,match,ids,team,dimension,near,region,limit,value);}
    public String canonical(){try{return JSON.writeValueAsString(this);}catch(Exception e){throw new IllegalArgumentException("DIRECTORY_QUERY_ENCODING",e);}}
    public static ObjectQuery parse(String text){try{if(text==null||text.length()>8192)throw new IllegalArgumentException();return parse(JSON.readTree(text));}catch(Exception e){throw new IllegalArgumentException("DIRECTORY_QUERY_INVALID",e);}}
    public static ObjectQuery parse(JsonNode n){
        keys(n,Set.of("kind","name","match","ids","team","dimension","near","region","limit","cursor"),Set.of("kind"));
        if(n.has("near")&&n.get("near").isNull()||n.has("region")&&n.get("region").isNull())throw new IllegalArgumentException("DIRECTORY_NULL_FILTER");
        var ids=new ArrayList<String>();if(n.has("ids")){if(!n.get("ids").isArray()||n.get("ids").size()>64)throw new IllegalArgumentException("DIRECTORY_IDS");for(var id:n.get("ids")){if(!id.isTextual())throw new IllegalArgumentException("DIRECTORY_IDS");ids.add(id.textValue());}}
        Near near=null;if(n.hasNonNull("near")){var v=n.get("near");keys(v,Set.of("reference","radius","position"),Set.of("reference","radius"));if(v.has("position")&&("ACTOR".equals(text(v,"reference",""))||v.get("position").isNull()))throw new IllegalArgumentException("DIRECTORY_REFERENCE");Point pos=null;if(v.hasNonNull("position")){var p=v.get("position");keys(p,Set.of("dimension","x","y","z"),Set.of("dimension","x","y","z"));pos=new Point(text(p,"dimension",""),number(p,"x"),number(p,"y"),number(p,"z"));}near=new Near(text(v,"reference",""),number(v,"radius"),pos);}
        Region region=null;if(n.hasNonNull("region")){var v=n.get("region");var names=Set.of("minX","minY","minZ","maxX","maxY","maxZ");keys(v,names,names);region=new Region(number(v,"minX"),number(v,"minY"),number(v,"minZ"),number(v,"maxX"),number(v,"maxY"),number(v,"maxZ"));}
        if(n.has("limit")&&(!n.get("limit").isIntegralNumber()||!n.get("limit").canConvertToInt()))throw new IllegalArgumentException("DIRECTORY_PAGE_LIMIT");
        return new ObjectQuery(Kind.valueOf(text(n,"kind","")),text(n,"name",""),text(n,"match","EXACT"),ids,text(n,"team",""),text(n,"dimension",""),near,region,n.path("limit").asInt(16),text(n,"cursor",""));
    }
    static void keys(JsonNode n,Set<String> allowed,Set<String> required){if(n==null||!n.isObject())throw new IllegalArgumentException("DIRECTORY_JSON");var keys=new HashSet<String>();n.fieldNames().forEachRemaining(keys::add);if(!allowed.containsAll(keys)||!keys.containsAll(required))throw new IllegalArgumentException("DIRECTORY_FIELDS");}
    static String text(JsonNode n,String key,String fallback){if(!n.has(key))return fallback;if(!n.get(key).isTextual())throw new IllegalArgumentException("DIRECTORY_TEXT");return n.get(key).textValue();}
    static double number(JsonNode n,String key){if(!n.path(key).isNumber())throw new IllegalArgumentException("DIRECTORY_NUMBER");return n.get(key).doubleValue();}
    private static boolean finite(double... values){for(double v:values)if(!Double.isFinite(v))return false;return true;}
    private static boolean resource(String id){return id!=null&&id.length()<=128&&id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");}
}
