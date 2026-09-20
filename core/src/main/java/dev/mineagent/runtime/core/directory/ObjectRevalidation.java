package dev.mineagent.runtime.core.directory;
import dev.mineagent.runtime.api.directory.ObjectRef;
import java.util.*;
public record ObjectRevalidation(ObjectQuery query,List<ObjectRef> refs) {
    public ObjectRevalidation{Objects.requireNonNull(query);refs=List.copyOf(refs);if(!query.cursor().isEmpty()||refs.isEmpty()||refs.size()>32||refs.stream().map(r->r.kind()+":"+r.id()).distinct().count()!=refs.size())throw new IllegalArgumentException("DIRECTORY_REVALIDATION");}
    public static ObjectRevalidation parse(String text){try{
        if(text==null||text.length()>32768)throw new IllegalArgumentException();var n=ObjectQuery.JSON.readTree(text);ObjectQuery.keys(n,Set.of("query","refs"),Set.of("query","refs"));
        var values=n.get("refs");if(!values.isArray()||values.isEmpty()||values.size()>32)throw new IllegalArgumentException();var refs=new ArrayList<ObjectRef>();
        for(var r:values){var keys=Set.of("worldId","kind","id","generation","revision");ObjectQuery.keys(r,keys,keys);if(!r.get("revision").isIntegralNumber()||!r.get("revision").canConvertToLong())throw new IllegalArgumentException();
            refs.add(new ObjectRef(UUID.fromString(ObjectQuery.text(r,"worldId","")),ObjectRef.Kind.valueOf(ObjectQuery.text(r,"kind","")),ObjectQuery.text(r,"id",""),UUID.fromString(ObjectQuery.text(r,"generation","")),r.get("revision").longValue()));}
        return new ObjectRevalidation(ObjectQuery.parse(n.get("query")),refs);
    }catch(Exception e){throw new IllegalArgumentException("DIRECTORY_REVALIDATION_INVALID",e);}}
    public String canonical(){try{return ObjectQuery.JSON.writeValueAsString(this);}catch(Exception e){throw new IllegalArgumentException("DIRECTORY_REVALIDATION_ENCODING",e);}}
}
