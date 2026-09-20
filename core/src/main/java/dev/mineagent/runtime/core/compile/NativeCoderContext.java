package dev.mineagent.runtime.core.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Exact user-selected declarations and optional method implementations from one immutable Native snapshot. */
public record NativeCoderContext(String snapshot,String environment,String physicalSide,String mappingStatus,List<Type> types,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<Method> methods,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<Source> sources,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<Overlay> overlays) {
    public static final int MAX_TYPES=16,MAX_BYTES=512*1024;
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public record Type(String module,String moduleHash,String className){
        public Type{if(module==null||!module.matches("[A-Za-z0-9_.-]{1,160}")||!hash(moduleHash)||className==null||className.length()>512||className.isBlank()||className.contains("..")||className.contains("/")||className.contains("\\"))throw new IllegalArgumentException("NATIVE_CODER_TYPE");}
    }
    public record Method(String module,String className,String name,String descriptor){
        public Method{if(module==null||!module.matches("[A-Za-z0-9_.-]{1,160}")||className==null||className.isBlank()||className.length()>512||className.contains("..")||className.contains("/")||className.contains("\\")||name==null||name.isBlank()||name.length()>512||descriptor==null||descriptor.length()>2048)throw new IllegalArgumentException("NATIVE_CODER_METHOD");try{java.lang.constant.MethodTypeDesc.ofDescriptor(descriptor);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("NATIVE_CODER_METHOD",invalid);}}
    }
    public record Source(String module,String className,String method,String descriptor){
        public Source{if(module==null||!module.matches("[A-Za-z0-9_.-]{1,160}")||className==null||className.isBlank()||className.length()>512||className.contains("..")||className.contains("/")||className.contains("\\")||method==null||descriptor==null||method.length()>512||descriptor.length()>2048||method.isEmpty()!=descriptor.isEmpty())throw new IllegalArgumentException("NATIVE_CODER_SOURCE");if(!descriptor.isEmpty())try{java.lang.constant.MethodTypeDesc.ofDescriptor(descriptor);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("NATIVE_CODER_SOURCE",invalid);}}
    }
    public record Overlay(String module,String className,String classRef,String classHash,String rawClassHash,String provenance,String processEpoch,String loaderKind,String method,String descriptor,String audit){
        public Overlay{module=module==null?"":module;if(!module.isEmpty()&&!module.matches("[A-Za-z0-9_.-]{1,160}")||className==null||className.isBlank()||className.length()>512||className.contains("..")||className.contains("/")||className.contains("\\")||className.startsWith("java.")||className.startsWith("jdk.")||!hash(classRef)||!classRef.equals(classHash)||rawClassHash==null||!rawClassHash.matches("(?:[a-f0-9]{64})?")||!Set.of("FML_TRANSFORM_PIPELINE_PREDEFINE","JVM_RETRANSFORM_LIVE_DEFINITION").contains(provenance)||processEpoch==null||processEpoch.length()>64||loaderKind==null||loaderKind.length()>512||method==null||descriptor==null||method.length()>512||descriptor.length()>2048||method.isEmpty()!=descriptor.isEmpty()||audit==null||audit.length()>8192)throw new IllegalArgumentException("NATIVE_CODER_OVERLAY");if(!descriptor.isEmpty())try{java.lang.constant.MethodTypeDesc.ofDescriptor(descriptor);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("NATIVE_CODER_OVERLAY",invalid);}}
    }
    public record Snapshot(String selectionHash,String sha256,int bytes,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) String compilationSnapshot){
        public Snapshot(String selectionHash,String sha256,int bytes){this(selectionHash,sha256,bytes,"");}
        public Snapshot{compilationSnapshot=compilationSnapshot==null?"":compilationSnapshot;if(!hash(selectionHash)||!hash(sha256)||bytes<1||bytes>MAX_BYTES||!compilationSnapshot.matches("(?:[a-f0-9]{64})?"))throw new IllegalArgumentException("NATIVE_CODER_CONTEXT_REF");}
    }
    public NativeCoderContext(String snapshot,String environment,String physicalSide,String mappingStatus,List<Type> types){this(snapshot,environment,physicalSide,mappingStatus,types,List.of(),List.of(),List.of());}
    public NativeCoderContext(String snapshot,String environment,String physicalSide,String mappingStatus,List<Type> types,List<Method> methods){this(snapshot,environment,physicalSide,mappingStatus,types,methods,List.of(),List.of());}
    public NativeCoderContext(String snapshot,String environment,String physicalSide,String mappingStatus,List<Type> types,List<Method> methods,List<Source> sources){this(snapshot,environment,physicalSide,mappingStatus,types,methods,sources,List.of());}
    public NativeCoderContext {
        if(!hash(snapshot)||!hash(environment)||!Set.of("CLIENT","SERVER").contains(physicalSide)||!"RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS".equals(mappingStatus)||types==null||types.size()>MAX_TYPES)throw new IllegalArgumentException("NATIVE_CODER_CONTEXT");
        types=types.stream().sorted(Comparator.comparing(Type::module).thenComparing(Type::className)).toList();
        if(types.stream().map(t->t.module()+"\n"+t.className()).distinct().count()!=types.size())throw new IllegalArgumentException("NATIVE_CODER_TYPE_DUPLICATE");
        var typeKeys=types.stream().map(t->t.module()+"\n"+t.className()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        methods=methods==null?List.of():methods.stream().sorted(Comparator.comparing(Method::module).thenComparing(Method::className).thenComparing(Method::name).thenComparing(Method::descriptor)).toList();
        if(methods.size()>MAX_TYPES||methods.stream().distinct().count()!=methods.size()||methods.stream().anyMatch(m->!typeKeys.contains(m.module()+"\n"+m.className())))throw new IllegalArgumentException("NATIVE_CODER_METHOD_SELECTION");
        sources=sources==null?List.of():sources.stream().sorted(Comparator.comparing(Source::module).thenComparing(Source::className).thenComparing(Source::method).thenComparing(Source::descriptor)).toList();if(sources.size()>MAX_TYPES||sources.stream().distinct().count()!=sources.size()||sources.stream().anyMatch(s->!typeKeys.contains(s.module()+"\n"+s.className())))throw new IllegalArgumentException("NATIVE_CODER_SOURCE_SELECTION");
        overlays=overlays==null?List.of():overlays.stream().sorted(Comparator.comparing(Overlay::className).thenComparing(Overlay::provenance).thenComparing(Overlay::method).thenComparing(Overlay::descriptor)).toList();if(overlays.size()>MAX_TYPES||overlays.stream().distinct().count()!=overlays.size()||types.isEmpty()&&overlays.isEmpty()||types.size()+overlays.size()>MAX_TYPES)throw new IllegalArgumentException("NATIVE_CODER_OVERLAY_SELECTION");
    }
    private static boolean hash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    public String fingerprint(){try{return RuntimePackageCanonicalizer.sha256(JSON.writeValueAsBytes(this));}catch(Exception e){throw new IllegalStateException("NATIVE_CODER_CONTEXT_HASH",e);}}
    public static NativeCoderContext merge(List<NativeCoderContext> values){
        if(values==null||values.isEmpty()||values.size()>MAX_TYPES)throw new IllegalArgumentException("NATIVE_CODER_CONTEXT_MERGE");var first=values.getFirst();var types=new LinkedHashMap<String,Type>();var methods=new LinkedHashMap<String,Method>();
        var sources=new LinkedHashMap<String,Source>();var overlays=new LinkedHashMap<String,Overlay>();for(var value:values){if(value==null||!value.snapshot().equals(first.snapshot())||!value.environment().equals(first.environment())||!value.physicalSide().equals(first.physicalSide())||!value.mappingStatus().equals(first.mappingStatus()))throw new IllegalArgumentException("NATIVE_CODER_CONTEXT_MERGE");for(var type:value.types()){String key=type.module()+"\n"+type.className();var old=types.putIfAbsent(key,type);if(old!=null&&!old.equals(type))throw new IllegalArgumentException("NATIVE_CODER_CONTEXT_MERGE");}for(var method:value.methods())methods.putIfAbsent(method.module()+"\n"+method.className()+"\n"+method.name()+"\n"+method.descriptor(),method);for(var source:value.sources())sources.putIfAbsent(source.module()+"\n"+source.className()+"\n"+source.method()+"\n"+source.descriptor(),source);for(var overlay:value.overlays()){String key=overlay.className()+"\n"+overlay.method()+"\n"+overlay.descriptor();var old=overlays.putIfAbsent(key,overlay);if(old!=null&&!old.equals(overlay))throw new IllegalArgumentException("NATIVE_CODER_CONTEXT_MERGE");}}
        return new NativeCoderContext(first.snapshot(),first.environment(),first.physicalSide(),first.mappingStatus(),List.copyOf(types.values()),List.copyOf(methods.values()),List.copyOf(sources.values()),List.copyOf(overlays.values()));
    }
    public static NativeCoderContext resolve(NativeCompilationSnapshot source,String snapshot,String environment,List<Map<String,String>> requested,java.util.function.BooleanSupplier permit)throws Exception {
        return resolve(source,snapshot,environment,requested,List.of(),permit);
    }
    public static NativeCoderContext resolve(NativeCompilationSnapshot source,String snapshot,String environment,List<Map<String,String>> requested,List<Map<String,String>> methodRequests,java.util.function.BooleanSupplier permit)throws Exception {
        return resolve(source,snapshot,environment,requested,methodRequests,List.of(),permit);
    }
    public static NativeCoderContext resolve(NativeCompilationSnapshot source,String snapshot,String environment,List<Map<String,String>> requested,List<Map<String,String>> methodRequests,List<Map<String,String>> sourceRequests,java.util.function.BooleanSupplier permit)throws Exception {
        if(source==null||!source.environment().fingerprint().equals(environment)||requested==null||requested.isEmpty()||requested.size()>MAX_TYPES)throw new IllegalArgumentException("NATIVE_CODER_SELECTION");
        var selected=new ArrayList<Type>();
        for(var request:requested){
            if(!permit.getAsBoolean())throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");
            if(!request.keySet().equals(Set.of("module","class")))throw new IllegalArgumentException("NATIVE_CODER_SELECTION");
            String module=request.get("module"),name=request.get("class");var declaration=source.module(module);selected.add(new Type(module,declaration.sha256(),name));
        }
        if(methodRequests==null||methodRequests.size()>MAX_TYPES)throw new IllegalArgumentException("NATIVE_CODER_METHOD_SELECTION");var methods=new ArrayList<Method>();
        for(var request:methodRequests){if(!permit.getAsBoolean()||!request.keySet().equals(Set.of("module","class","method","descriptor")))throw new IllegalArgumentException("NATIVE_CODER_METHOD_SELECTION");methods.add(new Method(request.get("module"),request.get("class"),request.get("method"),request.get("descriptor")));}
        if(sourceRequests==null||sourceRequests.size()>MAX_TYPES)throw new IllegalArgumentException("NATIVE_CODER_SOURCE_SELECTION");var sources=new ArrayList<Source>();for(var request:sourceRequests){if(!permit.getAsBoolean()||!request.keySet().equals(Set.of("module","class","method","descriptor")))throw new IllegalArgumentException("NATIVE_CODER_SOURCE_SELECTION");sources.add(new Source(request.get("module"),request.get("class"),request.get("method"),request.get("descriptor")));}
        return new NativeCoderContext(snapshot,environment,source.physicalSide(),source.mappingStatus(),selected,methods,sources,List.of());
    }
    public Snapshot capture(NativeCompilationSnapshot source,ContentAddressedStore content,java.util.function.BooleanSupplier permit)throws Exception {
        var stored=NativeCompilationSnapshot.read(content,snapshot);
        if(!stored.equals(source)||!source.environment().fingerprint().equals(environment)||!source.physicalSide().equals(physicalSide)||!source.mappingStatus().equals(mappingStatus))throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");
        var declarations=new ArrayList<Object>();var implementations=new ArrayList<Object>();var sourceMappings=new ArrayList<Object>();var liveClasses=new ArrayList<Object>();int totalMembers=0,totalInstructions=0,totalSourceChars=0;
        for(var type:types){
            if(!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
            var module=source.module(type.module());if(!module.sha256().equals(type.moduleHash()))throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");byte[] bytes=source.classBytes(content,type.module(),type.className());
            String classHash=RuntimePackageCanonicalizer.sha256(bytes);var symbols=ClassFileSymbols.inspectAll(bytes);totalMembers+=symbols.total();
            if(totalMembers>8192)throw new IllegalStateException("NATIVE_CODER_SYMBOL_LIMIT");declarations.add(Map.of("module",type.module(),"moduleHash",type.moduleHash(),"classHash",classHash,"symbols",symbols));
            for(var method:methods.stream().filter(m->m.module().equals(type.module())&&m.className().equals(type.className())).toList()){var body=ClassFileMethodBody.inspectAll(bytes,method.name(),method.descriptor());totalInstructions+=body.total();if(totalInstructions>8192)throw new IllegalStateException("NATIVE_CODER_METHOD_LIMIT");implementations.add(Map.of("module",method.module(),"class",method.className(),"classHash",classHash,"method",body));}
            for(var sourceSelection:sources.stream().filter(s->s.module().equals(type.module())&&s.className().equals(type.className())).toList()){var view=NativeSourceView.inspectAll(source,content,sourceSelection.module(),sourceSelection.className(),sourceSelection.method(),sourceSelection.descriptor());totalSourceChars+=view.text().length();if(totalSourceChars>256*1024)throw new IllegalStateException("NATIVE_CODER_SOURCE_LIMIT");sourceMappings.add(view);}
        }
        for(var overlay:overlays){if(!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");byte[] bytes=NativeCompilationSnapshot.read(content.pathFor(overlay.classRef()),8*1024*1024);if(!RuntimePackageCanonicalizer.sha256(bytes).equals(overlay.classHash())||!java.lang.classfile.ClassFile.of().parse(bytes).thisClass().asInternalName().replace('/','.').equals(overlay.className()))throw new IllegalStateException("NATIVE_CODER_OVERLAY_CHANGED");var symbols=ClassFileSymbols.inspectAll(bytes);totalMembers+=symbols.total();if(totalMembers>8192)throw new IllegalStateException("NATIVE_CODER_SYMBOL_LIMIT");var value=new LinkedHashMap<String,Object>();value.put("selection",overlay);value.put("symbols",symbols);if(!overlay.method().isEmpty()){var body=ClassFileMethodBody.inspectAll(bytes,overlay.method(),overlay.descriptor());totalInstructions+=body.total();if(totalInstructions>8192)throw new IllegalStateException("NATIVE_CODER_METHOD_LIMIT");value.put("method",body);}liveClasses.add(Map.copyOf(value));}
        var payload=new LinkedHashMap<String,Object>();payload.put("schema",1);payload.put("kind",contextKind());payload.put("selection",this);payload.put("classes",declarations);if(!implementations.isEmpty())payload.put("methodImplementations",implementations);if(!sourceMappings.isEmpty())payload.put("sourceMappings",sourceMappings);if(!liveClasses.isEmpty())payload.put("liveClasses",liveClasses);byte[] encoded=JSON.writeValueAsBytes(payload);
        if(encoded.length>MAX_BYTES)throw new IllegalStateException("NATIVE_CODER_CONTEXT_LIMIT");if(!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
        String compilation="";if(!overlays.isEmpty())compilation=NativeOverlaySnapshot.apply(source,content,overlays,permit).hash();return new Snapshot(fingerprint(),content.put(encoded).sha256(),encoded.length,compilation);
    }
    public String read(Snapshot reference,ContentAddressedStore content)throws Exception {
        if(reference==null||!reference.selectionHash().equals(fingerprint()))throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");byte[] bytes;try(var in=Files.newInputStream(content.pathFor(reference.sha256()))){bytes=in.readNBytes(MAX_BYTES+1);}
        if(bytes.length!=reference.bytes()||!RuntimePackageCanonicalizer.sha256(bytes).equals(reference.sha256()))throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");var value=JSON.readTree(bytes);
        if(value.path("schema").asInt()!=1||!JSON.treeToValue(value.path("selection"),NativeCoderContext.class).equals(this)||!value.path("kind").asText().equals(contextKind())||methods.isEmpty()!=value.path("methodImplementations").isMissingNode()||sources.isEmpty()!=value.path("sourceMappings").isMissingNode()||overlays.isEmpty()!=value.path("liveClasses").isMissingNode()||overlays.isEmpty()!=reference.compilationSnapshot().isEmpty())throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");if(!reference.compilationSnapshot().isEmpty()&&!NativeCompilationSnapshot.read(content,reference.compilationSnapshot()).mappingStatus().equals("RAW_MODULE_RESOURCES_WITH_EXPLICIT_LIVE_OVERLAYS"))throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private String contextKind(){if(!overlays.isEmpty())return "SELECTED_RAW_AND_EXPLICIT_LIVE_OVERLAY_CONTEXT_NO_EXECUTION";if(!sources.isEmpty())return methods.isEmpty()?"SELECTED_RAW_CLASS_DECLARATIONS_AND_EXACT_SOURCE_MAPPINGS_NO_INITIALIZATION":"SELECTED_RAW_CLASS_DECLARATIONS_METHOD_IMPLEMENTATIONS_AND_EXACT_SOURCE_MAPPINGS_NO_INITIALIZATION";return methods.isEmpty()?"SELECTED_RAW_CLASS_DECLARATIONS_NO_BODIES_OR_INITIALIZATION":"SELECTED_RAW_CLASS_DECLARATIONS_AND_METHOD_IMPLEMENTATIONS_NO_INITIALIZATION";}
}
