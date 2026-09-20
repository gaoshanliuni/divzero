package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.lang.classfile.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Complete bounded public/protected declaration metadata; no class loading, constants or method bodies. */
public final class JavaDependencyApi {
    public static final int MAX_BYTES=512*1024;
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private JavaDependencyApi(){}
    public record Snapshot(String graphHash,String sha256,int bytes){
        public Snapshot{if(graphHash==null||!graphHash.matches("[a-f0-9]{64}")||sha256==null||!sha256.matches("[a-f0-9]{64}")||bytes<1||bytes>MAX_BYTES)throw new IllegalArgumentException("STUDIO_CODER_DEPENDENCY_API_REF");}
    }
    public record Member(String kind,String name,String descriptor,String signature,int flags,List<String> exceptions){}
    public record Nesting(String outer,String sourceName,int flags){}
    public record Type(String name,String superclass,List<String> interfaces,String signature,int flags,Nesting nesting,List<Member> members){}
    private static final class TextBudget{private int left=MAX_BYTES;String take(String value){left-=value.length();if(left<0)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");return value;}}
    private static String signature(AttributedElement value){return value.findAttribute(Attributes.signature()).map(a->a.signature().stringValue()).orElse("");}
    private static boolean accessible(int flags){return (flags&5)!=0;}
    public static Snapshot capture(JavaDependencyGraph graph,ContentAddressedStore content,java.util.function.BooleanSupplier permit)throws Exception {
        if(graph==null||graph.nodes().isEmpty())throw new IllegalArgumentException("STUDIO_CODER_DEPENDENCY_CONTEXT");
        if(!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
        var verified=JavaDependencyArtifacts.inspect(graph,content);var packages=new ArrayList<Object>();var text=new TextBudget();int classCount=0,symbolCount=0,textBytes=0;
        for(var node:graph.nodes()){
            if(!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
            byte[] image;try(var in=Files.newInputStream(content.pathFor(node.artifact()))){image=in.readNBytes(16*1024*1024+1);}
            if(image.length>16*1024*1024||!RuntimePackageCanonicalizer.sha256(image).equals(node.artifact()))throw new IllegalStateException("JAVA_DEPENDENCY_ARTIFACT");
            var types=new ArrayList<Type>();
            try(var zip=new ZipInputStream(new java.io.ByteArrayInputStream(image))){ZipEntry entry;long expanded=0;int entries=0;
                while((entry=zip.getNextEntry())!=null){
                    if(++entries>100001)throw new IllegalStateException("JAVA_DEPENDENCY_ARCHIVE");
                    byte[] bytes=zip.readNBytes(8*1024*1024+1);expanded+=bytes.length;
                    if(bytes.length>8*1024*1024||expanded>128L*1024*1024)throw new IllegalStateException("JAVA_DEPENDENCY_ARCHIVE");
                    if(entry.isDirectory()||!entry.getName().endsWith(".class")||entry.getName().equals("module-info.class"))continue;
                    if(++classCount>4096)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");
                    if((classCount&31)==0&&!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
                    var model=ClassFile.of().parse(bytes);String name=text.take(model.thisClass().asInternalName().replace('/','.'));
                    if(!node.publication().equals(verified.classOwners().get(name)))throw new IllegalStateException("JAVA_DEPENDENCY_CLASS_OWNER");
                    var members=new ArrayList<Member>();
                    for(var field:model.fields())if(accessible(field.flags().flagsMask())){
                        if(++symbolCount>8192)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");
                        var member=new Member("FIELD",text.take(field.fieldName().stringValue()),text.take(field.fieldType().stringValue()),text.take(signature(field)),field.flags().flagsMask(),List.of());
                        textBytes+=JSON.writeValueAsBytes(member).length;if(textBytes>MAX_BYTES)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");members.add(member);
                    }
                    for(var method:model.methods())if(accessible(method.flags().flagsMask())){
                        if(++symbolCount>8192)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");
                        var exceptionAttribute=method.findAttribute(Attributes.exceptions());
                        if(exceptionAttribute.isPresent()&&exceptionAttribute.get().exceptions().size()>128)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");
                        var exceptions=exceptionAttribute.map(a->a.exceptions().stream().map(c->text.take(c.asInternalName().replace('/','.'))).toList()).orElse(List.of());
                        var member=new Member("METHOD",text.take(method.methodName().stringValue()),text.take(method.methodType().stringValue()),text.take(signature(method)),method.flags().flagsMask(),exceptions);
                        textBytes+=JSON.writeValueAsBytes(member).length;if(textBytes>MAX_BYTES)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");members.add(member);
                    }
                    members.sort(Comparator.comparing(Member::kind).thenComparing(Member::name).thenComparing(Member::descriptor));
                    if(model.interfaces().size()>128)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");
                    var nesting=model.findAttribute(Attributes.innerClasses()).stream().flatMap(a->a.classes().stream()).filter(c->c.innerClass().asInternalName().equals(model.thisClass().asInternalName())).findFirst()
                            .map(c->new Nesting(text.take(c.outerClass().map(o->o.asInternalName().replace('/','.')).orElse("")),text.take(c.innerName().map(n->n.stringValue()).orElse("")),c.flagsMask())).orElse(null);
                    var type=new Type(name,model.superclass().map(c->text.take(c.asInternalName().replace('/','.'))).orElse(""),model.interfaces().stream().map(c->text.take(c.asInternalName().replace('/','.'))).toList(),text.take(signature(model)),model.flags().flagsMask(),nesting,List.copyOf(members));
                    // Count class hierarchy/signature overhead before retaining another record.
                    textBytes+=JSON.writeValueAsBytes(new Type(type.name(),type.superclass(),type.interfaces(),type.signature(),type.flags(),type.nesting(),List.of())).length;
                    if(textBytes>MAX_BYTES)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");types.add(type);
                }
            }
            types.sort(Comparator.comparing(Type::name));packages.add(Map.of("packageId",node.packageId(),"version",node.version(),"artifact",node.artifact(),"entryClass",node.className(),"types",types));
        }
        byte[] encoded=JSON.writeValueAsBytes(Map.of("schema",1,"graphHash",graph.fingerprint(),"kind","DECLARED_PUBLIC_PROTECTED_METADATA_NO_INITIALIZATION","packages",packages));
        if(encoded.length>MAX_BYTES)throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT");
        JavaDependencyArtifacts.verify(graph,verified.paths());if(!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
        return new Snapshot(graph.fingerprint(),content.put(encoded).sha256(),encoded.length);
    }
    public static String read(Snapshot snapshot,JavaDependencyGraph graph,ContentAddressedStore content)throws Exception {
        if(snapshot==null||graph==null||!graph.fingerprint().equals(snapshot.graphHash()))throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CHANGED");
        byte[] bytes;try(var in=Files.newInputStream(content.pathFor(snapshot.sha256()))){bytes=in.readNBytes(MAX_BYTES+1);}
        if(bytes.length!=snapshot.bytes()||!RuntimePackageCanonicalizer.sha256(bytes).equals(snapshot.sha256()))throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CHANGED");
        var value=JSON.readTree(bytes);if(value.path("schema").asInt()!=1||!value.path("graphHash").asText().equals(snapshot.graphHash()))throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_API_CHANGED");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
}
