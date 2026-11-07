package dev.mineagent.runtime.integrations.ysm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class YsmJarVerifierTest {
    @TempDir Path root;
    Path fixture(String version,String missing,boolean wrongClass)throws Exception{
        Path file=root.resolve(UUID.randomUUID()+".jar");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))){
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));zip.write(("[[mods]]\nmodId=\"yes_steve_model\"\nversion=\""+version+"\"\n[[dependencies.yes_steve_model]]\nmodId=\"minecraft\"\n").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            for(String name:YsmJarVerifier.REQUIRED_CLASSES){if(name.equals(missing))continue;zip.putNextEntry(new java.util.zip.ZipEntry(name.replace('.','/')+".class"));zip.write(java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of(wrongClass?"fixture.WrongClass":name),builder->{}));zip.closeEntry();}
        }return file;
    }
    @Test void supportedVersionAndRequiredClassStructureReplaceWholeFileChecksum()throws Exception{
        assertTrue(new YsmJarVerifier().verify(fixture(YsmJarVerifier.SUPPORTED_VERSION,"",false)));
        assertFalse(new YsmJarVerifier().verify(fixture("2.6.4-neoforge+mc26.1","",false)));
        assertFalse(new YsmJarVerifier().verify(fixture(YsmJarVerifier.SUPPORTED_VERSION,YsmJarVerifier.REQUIRED_CLASSES.iterator().next(),false)));
        assertFalse(new YsmJarVerifier().verify(fixture(YsmJarVerifier.SUPPORTED_VERSION,"",true)));
        Path text=root.resolve("not-a-jar.jar");Files.writeString(text,"fixture");assertFalse(new YsmJarVerifier().verify(text));assertEquals("1rPJlKDJ",YsmJarVerifier.MODRINTH_VERSION_ID);
    }
}
