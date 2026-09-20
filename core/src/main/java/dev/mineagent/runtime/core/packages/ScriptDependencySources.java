package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Complete owned dependency source context, never an evaluation or an inferred live export table. */
public record ScriptDependencySources(ScriptDependencyGraph graph,Map<UUID,Map<String,CodeDraft.SourceRef>> files) {
    public static final int MAX_BYTES=512*1024;
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public record Snapshot(String graphHash,String sha256,int bytes){
        public Snapshot{if(graphHash==null||!graphHash.matches("[a-f0-9]{64}")||sha256==null||!sha256.matches("[a-f0-9]{64}")||bytes<1||bytes>MAX_BYTES)throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_CONTEXT_REF");}
    }
    public ScriptDependencySources {
        Objects.requireNonNull(graph);Objects.requireNonNull(files);
        if(graph.nodes().isEmpty()||files.size()!=graph.nodes().size())throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_CONTEXT_GRAPH");
        var copy=new TreeMap<UUID,Map<String,CodeDraft.SourceRef>>();long bytes=0;int count=0;
        for(var node:graph.nodes()){
            var refs=files.get(node.packageId());if(refs==null)throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_CONTEXT_FILES");
            CodeDraftSources.validate(node.entry(),refs);count+=refs.size();bytes+=refs.values().stream().mapToInt(CodeDraft.SourceRef::bytes).sum();
            if(count>1024||bytes>MAX_BYTES)throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_SOURCE_CONTEXT_LIMIT");
            try{if(!CodeDraftSources.fingerprint(node.entry(),refs,node.dependencies()).equals(node.sourceHash()))throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_CONTEXT_SOURCE_CHANGED");}
            catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_CONTEXT_SOURCE_CHANGED",e);}
            copy.put(node.packageId(),Collections.unmodifiableMap(new TreeMap<>(refs)));
        }
        files=Collections.unmodifiableMap(copy);
    }
    public Snapshot capture(ContentAddressedStore content,java.util.function.BooleanSupplier permit)throws Exception {
        var packages=new ArrayList<Object>();
        for(var node:graph.nodes()){
            if(!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
            var texts=new TreeMap<String,String>();for(var file:files.get(node.packageId()).entrySet())texts.put(file.getKey(),CodeDraftSources.read(content,file.getValue()));
            packages.add(Map.of("packageId",node.packageId(),"version",node.version(),"publication",node.publication(),"canonical",node.canonical(),"sourceHash",node.sourceHash(),"entry",node.entry(),"files",texts));
        }
        byte[] bytes=JSON.writeValueAsBytes(Map.of("schema",1,"kind","VERIFIED_RHINO_DEPENDENCY_SOURCES_NOT_LIVE_EXPORTS","graphHash",graph.fingerprint(),"packages",packages));
        if(bytes.length>MAX_BYTES)throw new IllegalStateException("STUDIO_CODER_SCRIPT_SOURCE_CONTEXT_LIMIT");
        if(!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
        return new Snapshot(graph.fingerprint(),content.put(bytes).sha256(),bytes.length);
    }
    public String read(Snapshot snapshot,ContentAddressedStore content)throws Exception {
        if(snapshot==null||!graph.fingerprint().equals(snapshot.graphHash()))throw new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_CHANGED");
        byte[] bytes;try(var in=Files.newInputStream(content.pathFor(snapshot.sha256()))){bytes=in.readNBytes(MAX_BYTES+1);}
        if(bytes.length!=snapshot.bytes()||!RuntimePackageCanonicalizer.sha256(bytes).equals(snapshot.sha256()))throw new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_CHANGED");
        var value=JSON.readTree(bytes);if(value.path("schema").asInt()!=1||!value.path("graphHash").asText().equals(snapshot.graphHash())||!value.path("kind").asText().equals("VERIFIED_RHINO_DEPENDENCY_SOURCES_NOT_LIVE_EXPORTS"))throw new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_CHANGED");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
}
