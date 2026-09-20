package dev.mineagent.runtime.scripting.javaext;

import dev.mineagent.runtime.api.packages.ActivationMode;

import dev.mineagent.runtime.core.packages.JavaDependencyGraph;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

public final class JavaExtensionManager implements AutoCloseable {
    private final Map<String, LoadedExtension> loaded = new LinkedHashMap<>();

    // Read by the library mutation guard without acquiring this manager's monitor.
    // Native callbacks can themselves use the library; never reverse that lock order.
    private volatile Map<String,JavaDependencyGraph> reservations=Map.of();
    private volatile Map<UUID,List<UUID>> consumerIndex=Map.of();
    private final Set<String> startAttempted=new HashSet<>();
    private static String key(UUID scope){return "publication:"+java.util.Objects.requireNonNull(scope);}
    public synchronized void pin(UUID consumer,JavaDependencyGraph graph){
        String key=key(consumer);var previous=reservations.get(key);
        if(previous!=null&&!previous.equals(graph))throw new IllegalStateException("JAVA_DEPENDENCY_RESERVATION_CHANGED");
        for(var node:graph.nodes()){
            var dependency=loaded.get(key(node.publication()));
            if(dependency==null||!dependency.hash().equals(node.artifact())||!dependency.entrypoint().equals(node.className()))throw new IllegalStateException("JAVA_DEPENDENCY_NOT_LOADED");
            graph.requireEmbedded(node,reservations.get(key(node.publication())));
        }
        if(previous==null){var next=new LinkedHashMap<>(reservations);next.put(key,graph);setReservations(next);}
    }
    public synchronized void requireLoaded(UUID scope,String hash,String entrypoint){
        var value=loaded.get(key(scope));if(value==null||!value.hash().equals(hash)||!value.entrypoint().equals(entrypoint))throw new IllegalStateException("JAVA_DEPENDENCY_NOT_LOADED");
    }
    public void requirePackageMutable(UUID packageId){
        if(reservations.values().stream().anyMatch(g->g.nodes().stream().anyMatch(n->n.packageId().equals(packageId))))throw new IllegalStateException("JAVA_DEPENDENCY_IN_USE");
    }
    public List<UUID> consumers(UUID dependency){return consumerIndex.getOrDefault(dependency,List.of());}
    public synchronized void requireCanUnload(UUID scope){requireCanUnloadKey(key(scope));}
    private void requireCanUnloadKey(String key){if(reservations.entrySet().stream().anyMatch(e->!e.getKey().equals(key)&&e.getValue().scopes().stream().anyMatch(id->key(id).equals(key))))throw new IllegalStateException("JAVA_DEPENDENCY_IN_USE");}
    public synchronized void releaseBeforeStart(UUID consumer){String key=key(consumer);if(!loaded.containsKey(key)&&!startAttempted.contains(key))release(key);}
    private void release(String key){var next=new LinkedHashMap<>(reservations);next.remove(key);setReservations(next);startAttempted.remove(key);}
    private void setReservations(Map<String,JavaDependencyGraph> values){
        var index=new LinkedHashMap<UUID,java.util.ArrayList<UUID>>();
        for(var entry:values.entrySet())for(var dependency:entry.getValue().scopes())index.computeIfAbsent(dependency,k->new java.util.ArrayList<>()).add(UUID.fromString(entry.getKey().substring("publication:".length())));
        var snapshot=new LinkedHashMap<UUID,List<UUID>>();index.forEach((id,list)->snapshot.put(id,list.stream().sorted().toList()));
        reservations=Map.copyOf(values);consumerIndex=Map.copyOf(snapshot);
    }
    public synchronized JavaExtensionLoadResult load(UUID scope,Path jar,String hash,String entrypoint,Map<String,Object> bindings,JavaDependencyGraph graph,Map<String,UUID> owners)throws Exception{
        if(!graph.equals(reservations.get(key(scope))))throw new IllegalStateException("JAVA_DEPENDENCY_RESERVATION_MISSING");
        pin(scope,graph);var routes=new LinkedHashMap<String,ClassLoader>();
        for(var entry:owners.entrySet()){
            if(!graph.scopes().contains(entry.getValue()))throw new IllegalStateException("JAVA_DEPENDENCY_CLASS_OWNER");
            var dependency=loaded.get(key(entry.getValue()));if(dependency==null)throw new IllegalStateException("JAVA_DEPENDENCY_NOT_LOADED");routes.put(entry.getKey(),dependency.classLoader());
        }
        var actualBindings=new LinkedHashMap<String,Object>(bindings);
        if(!graph.required().isEmpty()){
            var instances=new LinkedHashMap<UUID,RuntimeExtension>();for(var id:graph.required().keySet())instances.put(id,loaded.get(key(graph.node(id).publication())).extension());
            // Only declared direct dependencies, in this exact running graph; never a newly constructed service.
            actualBindings.put("dependencies",Map.copyOf(instances));
        }
        return loadKey(key(scope),jar,hash,entrypoint,actualBindings,Map.copyOf(routes));
    }

    public ActivationMode classify(Path jarPath) throws Exception {
        Path jar = requireJar(jarPath);
        boolean assets = false;
        boolean data = false;
        boolean worldReopen = false;
        try (var archive = new JarFile(jar.toFile(), false)) {
            int count = 0;
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (++count > 100_000) {
                    throw new IllegalArgumentException("extension JAR entry limit exceeded");
                }
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if ((lower.startsWith("mixins.") && lower.endsWith(".json"))
                        || lower.contains("/mixin/")
                        || lower.equals("meta-inf/services/cpw.mods.modlauncher.api.itransformationservice")
                        || lower.equals("meta-inf/neoforge.mods.toml")) {
                    return ActivationMode.BOOT_EXTENSION;
                }
                assets |= lower.startsWith("assets/");
                data |= lower.startsWith("data/");
                worldReopen |= lower.matches("data/[^/]+/(?:dimension|dimension_type|worldgen)/.+");
            }
        }
        if (worldReopen) return ActivationMode.WORLD_REOPEN;
        if (data) {
            return ActivationMode.DATA_RELOAD;
        }
        if (assets) {
            return ActivationMode.RESOURCE_RELOAD;
        }
        return ActivationMode.HOT_RUNTIME;
    }

    public synchronized JavaExtensionLoadResult load(
            Path jarPath,
            String expectedSha256,
            String entrypointClass,
            Map<String, Object> bindings
    ) throws Exception {
        return loadKey("path:"+requireJar(jarPath),jarPath,expectedSha256,entrypointClass,bindings);
    }
    public synchronized JavaExtensionLoadResult load(java.util.UUID scope,Path jar,String hash,String entrypoint,Map<String,Object> bindings)throws Exception{
        return loadKey("publication:"+java.util.Objects.requireNonNull(scope),jar,hash,entrypoint,bindings);
    }
    private JavaExtensionLoadResult loadKey(String key,Path jarPath,String expectedSha256,String entrypointClass,Map<String,Object> bindings)throws Exception{
        var graph=reservations.get(key);if(graph!=null&&!graph.nodes().isEmpty())throw new IllegalStateException("JAVA_DEPENDENCY_CONTEXT_REQUIRED");
        return loadKey(key,jarPath,expectedSha256,entrypointClass,bindings,Map.of());
    }
    private JavaExtensionLoadResult loadKey(String key,Path jarPath,String expectedSha256,String entrypointClass,Map<String,Object> bindings,Map<String,ClassLoader> routes)throws Exception{
        requireCanUnloadKey(key);
        if(startAttempted.contains(key)&&!loaded.containsKey(key))throw new IllegalStateException("JAVA_PREVIOUS_OUTCOME_UNKNOWN");
        Path jar = requireJar(jarPath);
        if (!sha256(jar).equals(expectedSha256)) {
            throw new IllegalArgumentException("extension SHA-256 mismatch");
        }
        ActivationMode mode = classify(jar);
        if (mode != ActivationMode.HOT_RUNTIME) {
            return new JavaExtensionLoadResult(mode, null, null);
        }
        if (entrypointClass == null || !entrypointClass.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,255}")) {
            throw new IllegalArgumentException("invalid extension entrypoint");
        }
        LoadedExtension previous = loaded.remove(key);
        if (previous != null) {
            previous.close();
        }
        var classLoader = new ChildFirstClassLoader(new URL[]{jar.toUri().toURL()},
                new DependencyClassLoader(routes));
        startAttempted.add(key);
        try {
            Class<?> entrypoint = Class.forName(entrypointClass, false, classLoader);
            if (!RuntimeExtension.class.isAssignableFrom(entrypoint)) {
                throw new IllegalArgumentException("entrypoint does not implement RuntimeExtension");
            }
            RuntimeExtension extension = (RuntimeExtension) entrypoint.getDeclaredConstructor().newInstance();
            Object result = extension.start(Map.copyOf(bindings));
            loaded.put(key, new LoadedExtension(extension, classLoader,expectedSha256,entrypointClass));
            return new JavaExtensionLoadResult(mode, result, classLoader);
        } catch (Exception | LinkageError failure) {
            try{classLoader.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof Exception exception)throw exception;
            throw new IllegalStateException("JAVA_START_LINKAGE_UNCERTAIN",failure);
        }
    }

    public synchronized boolean isLoaded(Path path){return path!=null&&loaded.containsKey("path:"+path.toAbsolutePath().normalize());}
    public synchronized boolean isLoaded(java.util.UUID scope){return scope!=null&&loaded.containsKey("publication:"+scope);}
    public synchronized boolean unload(java.util.UUID scope)throws Exception{return unloadKey("publication:"+java.util.Objects.requireNonNull(scope));}
    public synchronized boolean unload(Path jarPath) throws Exception {return unloadKey("path:"+requireJar(jarPath));}
    private boolean unloadKey(String key)throws Exception{
        requireCanUnloadKey(key);
        LoadedExtension removed = loaded.remove(key);
        if (removed == null) {
            return false;
        }
        removed.close();
        release(key);
        return true;
    }

    public static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path requireJar(Path value) {
        if (value == null) {
            throw new IllegalArgumentException("extension JAR is required");
        }
        Path jar = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar) || !zipMagic(jar)) {
            throw new IllegalArgumentException("invalid extension JAR");
        }
        return jar;
    }

    private static boolean zipMagic(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] magic = input.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K'
                    && ((magic[2] == 3 && magic[3] == 4) || (magic[2] == 5 && magic[3] == 6));
        } catch (java.io.IOException failure) {
            return false;
        }
    }

    @Override
    public synchronized void close() throws Exception {
        var failures = new ArrayList<Exception>();
        for(var key:List.copyOf(reservations.keySet()))if(!startAttempted.contains(key)&&!loaded.containsKey(key))release(key);
        // Consumers stop before their dependencies. A failed/unknown stop retains its pins.
        while(!loaded.isEmpty()){
            String candidate=loaded.keySet().stream().filter(k->{try{requireCanUnloadKey(k);return true;}catch(IllegalStateException inUse){return false;}}).findFirst().orElse(null);
            if(candidate==null){failures.add(new IllegalStateException("JAVA_DEPENDENCY_CLEANUP_BLOCKED"));break;}
            try{unloadKey(candidate);}catch(Exception failure){failures.add(failure);}
        }
        if (!failures.isEmpty()) {
            throw failures.getFirst();
        }
    }

    private record LoadedExtension(RuntimeExtension extension, ChildFirstClassLoader classLoader,String hash,String entrypoint) {
        private void close() throws Exception {
            try {
                extension.stop();
            } catch(LinkageError failure){throw new IllegalStateException("JAVA_STOP_LINKAGE_UNCERTAIN",failure);
            } finally {
                classLoader.close();
            }
        }
    }

    private static final class DependencyClassLoader extends ClassLoader {
        private final Map<String,ClassLoader> routes;
        private DependencyClassLoader(Map<String,ClassLoader> routes){super(RuntimeExtension.class.getClassLoader());this.routes=routes;}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            var owner=routes.get(name);return owner==null?super.loadClass(name,resolve):owner.loadClass(name);
        }
    }
    private static final class ChildFirstClassLoader extends URLClassLoader {
        private ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loadedClass = findLoadedClass(name);
                if (loadedClass == null) {
                    boolean parentFirst = name.startsWith("java.")
                            || name.startsWith("javax.")
                            || name.startsWith("dev.mineagent.runtime.api.")
                            || name.equals(RuntimeExtension.class.getName());
                    if (!parentFirst) {
                        try {
                            loadedClass = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            loadedClass = super.loadClass(name, false);
                        }
                    } else {
                        loadedClass = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loadedClass);
                }
                return loadedClass;
            }
        }
    }
}
