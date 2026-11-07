package dev.mineagent.runtime.worker.compile;

import dev.mineagent.runtime.scripting.javaext.JavaExtensionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceCompilerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void compilesOrdinaryRuntimeExtensionIntoLoadableJar() throws Exception {
        String className = "generated.ExampleExtension";
        String source = """
                package generated;
                public final class ExampleExtension implements dev.mineagent.runtime.scripting.javaext.RuntimeExtension {
                  public Object start(java.util.Map<String,Object> bindings) { return bindings.get("value"); }
                }
                """;
        Path jar = temporaryDirectory.resolve("extension.jar");

        var result = new JavaSourceCompiler().compile(className, source, jar);

        assertTrue(result.success(), result.diagnostics().toString());
        try (var manager = new JavaExtensionManager()) {
            var loaded = manager.load(jar, JavaExtensionManager.sha256(jar), className, Map.of("value", 9));
            assertEquals(9, loaded.startResult());
        }
    }

    @Test
    void returnsCompilerDiagnosticsWithoutCreatingJar() throws Exception {
        Path jar = temporaryDirectory.resolve("broken.jar");

        var result = new JavaSourceCompiler().compile(
                "generated.Broken", "package generated; public class Broken { invalid }", jar);

        assertFalse(result.success());
        assertFalse(result.diagnostics().isEmpty());
        assertFalse(java.nio.file.Files.exists(jar));
    }
    @Test void invalidCandidateLeavesAnExistingValidArtifactIntact()throws Exception{
        var compiler=new JavaSourceCompiler();Path jar=temporaryDirectory.resolve("running.jar");assertTrue(compiler.compile("generated.Running","package generated; public class Running {}",jar).success());byte[] before=java.nio.file.Files.readAllBytes(jar);String version=JavaExtensionManager.sha256(jar);
        var failed=compiler.compile("generated.Running","package generated; public class Running { invalid }",jar);assertFalse(failed.success());assertTrue(failed.diagnostics().stream().anyMatch(d->d.line()>0&&d.column()>0));assertArrayEquals(before,java.nio.file.Files.readAllBytes(jar));assertEquals(version,JavaExtensionManager.sha256(jar));
    }

    @Test void separateCompilationsKeepTheirSourceVersionAndAssignSeparateArtifactIds()throws Exception{
        String source="package generated; public final class Stable {}",version=dev.mineagent.runtime.api.packages.ResourceIds.create();var compiler=new JavaSourceCompiler();Path a=temporaryDirectory.resolve("a.jar"),b=temporaryDirectory.resolve("b.jar");var context=Map.<String,Object>of("physicalSide","CLIENT","environment","fixture","sourceVersion",version);
        assertTrue(compiler.compile("generated.Stable",source,a,java.util.List.of(),context).success());assertTrue(compiler.compile("generated.Stable",source,b,java.util.List.of(),context).success());assertNotEquals(JavaExtensionManager.sha256(a),JavaExtensionManager.sha256(b));
        try(var left=new java.util.jar.JarFile(a.toFile());var right=new java.util.jar.JarFile(b.toFile())){assertArrayEquals(left.getInputStream(left.getJarEntry("generated/Stable.class")).readAllBytes(),right.getInputStream(right.getJarEntry("generated/Stable.class")).readAllBytes());var metadata=new com.fasterxml.jackson.databind.ObjectMapper().readTree(left.getInputStream(left.getJarEntry("META-INF/mineagent/compilation.json")));assertEquals(version,metadata.path("sourceSha256").asText());}
    }

    @Test
    void infersQualifiedPublicExtensionClassName() {
        assertEquals("dev.example.Extension", JavaSourceCompiler.inferClassName("""
                /* leading comment */
                package dev.example;
                public final class Extension implements Runnable { public void run() {} }
                """));
        assertThrows(IllegalArgumentException.class,
                () -> JavaSourceCompiler.inferClassName("package dev.example; class Hidden {}"));
    }
}
