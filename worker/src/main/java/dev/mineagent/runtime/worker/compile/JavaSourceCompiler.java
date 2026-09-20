package dev.mineagent.runtime.worker.compile;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class JavaSourceCompiler {
    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile(
            "\\bpackage\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*;");
    private static final java.util.regex.Pattern PUBLIC_TYPE = java.util.regex.Pattern.compile(
            "\\bpublic\\s+(?:(?:abstract|final|sealed|non-sealed|strictfp)\\s+)*(?:class|record|enum)\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\b");

    public static String inferClassName(String source) {
        if (source == null || source.isBlank() || source.length() > 1_000_000) {
            throw new IllegalArgumentException("invalid Java source");
        }
        String structural = eraseCommentsAndLiterals(source);
        var type = PUBLIC_TYPE.matcher(structural);
        if (!type.find()) {
            throw new IllegalArgumentException("Java source must contain a public top-level class, record or enum");
        }
        var packageName = PACKAGE.matcher(structural);
        return (packageName.find() ? packageName.group(1) + "." : "") + type.group(1);
    }

    public JavaCompilationResult compile(String className,String source,Path outputJar)throws Exception{return compile(className,source,outputJar,List.of(),java.util.Map.of());}
    public JavaCompilationResult compile(String className, String source, Path outputJar,List<Path> nativeClasspath,java.util.Map<String,Object> compileContext) throws Exception {
        if(className==null||!className.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")||source==null||source.isBlank()||source.length()>1_000_000||outputJar==null)throw new IllegalArgumentException("invalid Java compilation request");
        return compileUnits(className,className.replace('.','/')+".java",java.util.Map.of(className.replace('.','/')+".java",source),outputJar,nativeClasspath,compileContext,dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(source),false);
    }
    public JavaCompilationResult compileWorkspace(String className,String entry,java.util.Map<String,String> sources,Path outputJar,List<Path> nativeClasspath,java.util.Map<String,Object> compileContext)throws Exception {
        return compileWorkspace(className,entry,sources,outputJar,nativeClasspath,compileContext,java.util.Map.of());
    }
    public JavaCompilationResult compileWorkspace(String className,String entry,java.util.Map<String,String> sources,Path outputJar,List<Path> nativeClasspath,java.util.Map<String,Object> compileContext,java.util.Map<java.util.UUID,String> dependencies)throws Exception {
        var refs=new java.util.TreeMap<String,dev.mineagent.runtime.api.packages.CodeDraft.SourceRef>();for(var file:sources.entrySet())refs.put(file.getKey(),dev.mineagent.runtime.core.packages.CodeDraftSources.ref(file.getValue()));
        dev.mineagent.runtime.core.packages.CodeDraftSources.validate(entry,refs);if(!dev.mineagent.runtime.core.packages.CodeDraftSources.java(entry))throw new IllegalArgumentException("STUDIO_WORKSPACE_LANGUAGE");
        var context=new java.util.LinkedHashMap<>(compileContext);context.put("workspaceEntry",entry);context.put("workspaceFileCount",sources.size());
        return compileUnits(className,entry,java.util.Map.copyOf(sources),outputJar,nativeClasspath,context,dev.mineagent.runtime.core.packages.CodeDraftSources.fingerprint(entry,refs,dependencies),true);
    }
    private JavaCompilationResult compileUnits(String className,String entry,java.util.Map<String,String> sources,Path outputJar,List<Path> nativeClasspath,java.util.Map<String,Object> compileContext,String sourceHash,boolean workspace)throws Exception {
        String source=sources.get(entry);
        if (className == null || !className.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")
                || source == null || source.isBlank() || source.length() > 1_000_000 || outputJar == null) {
            throw new IllegalArgumentException("invalid Java compilation request");
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Java compiler is unavailable; Worker must run on a JDK");
        }
        Path absoluteJar = outputJar.toAbsolutePath().normalize();
        if (absoluteJar.getParent() != null) {
            Files.createDirectories(absoluteJar.getParent());
        }
        Path classes = Files.createTempDirectory(absoluteJar.getParent(), ".mineagent-javac-");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var units=new java.util.ArrayList<JavaFileObject>();var names=new java.util.HashSet<String>();
            for(var file:new java.util.TreeMap<>(sources).entrySet()){
                if(file.getValue()==null||file.getValue().isBlank())throw new IllegalArgumentException("STUDIO_WORKSPACE_EMPTY_SOURCE");
                String virtual=file.getKey().equals(entry)?className.replace('.','/')+".java":file.getKey();
                if(!names.add(virtual.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("STUDIO_WORKSPACE_COMPILER_PATH_COLLISION");
                units.add(new SourceFile(virtual,file.getValue(),file.getKey()));
            }
            var paths = new java.util.ArrayList<Path>(List.copyOf(nativeClasspath));
            var workerPaths = files.getLocationAsPaths(javax.tools.StandardLocation.CLASS_PATH);
            if (workerPaths != null) workerPaths.forEach(paths::add);
            files.setLocationFromPaths(javax.tools.StandardLocation.CLASS_PATH, paths);
            files.setLocationFromPaths(javax.tools.StandardLocation.SOURCE_PATH, List.of());
            List<String> options = List.of(
                    "--release", "25",
                    "-encoding", "UTF-8",
                    "-proc:none",
                    "-d", classes.toString(),
                    "-Xlint:all"
            );
            boolean success = Boolean.TRUE.equals(compiler.getTask(
                    null, files, diagnostics, options, null, units).call());
            List<CompilationDiagnostic> converted = diagnostics.getDiagnostics().stream()
                    .map(diagnostic -> new CompilationDiagnostic(
                            diagnostic.getKind().name(), diagnostic.getLineNumber(), diagnostic.getColumnNumber(),
                            (workspace&&diagnostic.getSource()!=null?diagnostic.getSource().getName()+": ":"")+diagnostic.getMessage(Locale.SIMPLIFIED_CHINESE)))
                    .toList();
            if (!success) {
                Files.deleteIfExists(absoluteJar);
                return new JavaCompilationResult(false, absoluteJar, converted);
            }
            Path temporaryJar = Files.createTempFile(absoluteJar.getParent(), ".mineagent-extension-", ".jar");
            try {
                try (var output = new JarOutputStream(Files.newOutputStream(temporaryJar));
                     var outputs = Files.walk(classes)) {
                    if(!compileContext.isEmpty()){
                        var context=new java.util.TreeMap<String,Object>(compileContext);context.put("className",className);context.put("sourceSha256",sourceHash);
                        var metadata=new JarEntry("META-INF/mineagent/compilation.json");metadata.setTime(0);output.putNextEntry(metadata);output.write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(context));output.closeEntry();
                    }
                    for (Path path : outputs.filter(Files::isRegularFile).sorted().toList()) {
                        String entryName = classes.relativize(path).toString().replace('\\', '/');
                        var compiled=new JarEntry(entryName);compiled.setTime(0);output.putNextEntry(compiled);
                        Files.copy(path, output);
                        output.closeEntry();
                    }
                }
                try {
                    Files.move(temporaryJar, absoluteJar,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporaryJar, absoluteJar, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporaryJar);
            }
            return new JavaCompilationResult(true, absoluteJar, converted);
        } finally {
            deleteTree(classes);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;
        private final String logical;

        private SourceFile(String path, String source,String logical) {
            super(URI.create("string:///" + path),
                    JavaFileObject.Kind.SOURCE);
            this.source = source;
            this.logical=logical;
        }
        @Override public String getName(){return logical;}

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static String eraseCommentsAndLiterals(String source) {
        char[] value = source.toCharArray();
        int index = 0;
        while (index < value.length) {
            if (value[index] == '/' && index + 1 < value.length && value[index + 1] == '/') {
                int start = index;
                index += 2;
                while (index < value.length && value[index] != '\n' && value[index] != '\r') {
                    index++;
                }
                java.util.Arrays.fill(value, start, index, ' ');
            } else if (value[index] == '/' && index + 1 < value.length && value[index + 1] == '*') {
                int start = index;
                index += 2;
                while (index + 1 < value.length && !(value[index] == '*' && value[index + 1] == '/')) {
                    index++;
                }
                index = Math.min(value.length, index + 2);
                java.util.Arrays.fill(value, start, index, ' ');
            } else if (value[index] == '"' || value[index] == '\'') {
                char quote = value[index];
                int start = index++;
                boolean escaped = false;
                while (index < value.length) {
                    char current = value[index++];
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == quote) {
                        break;
                    }
                }
                java.util.Arrays.fill(value, start, index, ' ');
            } else {
                index++;
            }
        }
        return new String(value);
    }
}
