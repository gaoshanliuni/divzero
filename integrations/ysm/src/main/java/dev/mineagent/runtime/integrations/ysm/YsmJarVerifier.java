package dev.mineagent.runtime.integrations.ysm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarFile;

public final class YsmJarVerifier {
    public static final String MODRINTH_VERSION_ID = "1rPJlKDJ";
    public static final String SUPPORTED_VERSION="2.6.5-neoforge+mc26.1";
    static final Set<String> REQUIRED_CLASSES=Set.of("com.elfmcys.yesstevemodel.YesSteveModel","com.elfmcys.yesstevemodel.o0oo0000oooOo0ooOOOo0OOo","com.elfmcys.yesstevemodel.ooo000Oo0O0o0oOoO0O00oOO","com.elfmcys.yesstevemodel.OOOOo0ooOooOOOo0O00o0ooo");
    private final String expectedVersion;

    public YsmJarVerifier() {
        this(SUPPORTED_VERSION);
    }

    public YsmJarVerifier(String expectedVersion) {
        if(expectedVersion==null||!expectedVersion.matches("[A-Za-z0-9_.+-]{1,128}"))throw new IllegalArgumentException("invalid YSM version");
        this.expectedVersion=expectedVersion;
    }

    public boolean verify(Path jar) {
        try {
            if(!Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS)||Files.size(jar)>512L*1024*1024)return false;
            // Compatibility metadata only. Native initialization and reflection contracts remain separate gates.
            try(var archive=new JarFile(jar.toFile(),false)){
                var names=new HashSet<String>();var entries=archive.entries();while(entries.hasMoreElements()){if(names.size()>=100000||!names.add(entries.nextElement().getName()))return false;}
                byte[] metadata=entry(archive,"META-INF/neoforge.mods.toml",65536);boolean selected=false,matched=false;String id="",version="";
                for(String line:new String(metadata,StandardCharsets.UTF_8).split("\\R")){
                    String value=line.strip();if(value.startsWith("[")){if(selected&&id.equals("yes_steve_model")&&version.equals(expectedVersion))matched=true;selected=value.equals("[[mods]]");id="";version="";continue;}
                    if(!selected)continue;var matcher=java.util.regex.Pattern.compile("^(modId|version)\\s*=\\s*\"([^\"]+)\"\\s*(?:#.*)?$").matcher(value);
                    if(matcher.matches()){if(matcher.group(1).equals("modId"))id=matcher.group(2);else version=matcher.group(2);}
                }
                if(selected&&id.equals("yes_steve_model")&&version.equals(expectedVersion))matched=true;if(!matched)return false;
                for(String name:REQUIRED_CLASSES){var model=java.lang.classfile.ClassFile.of().parse(entry(archive,name.replace('.','/')+".class",2*1024*1024));if(!model.thisClass().asInternalName().equals(name.replace('.','/')))return false;}
                return true;
            }
        } catch (Exception failure) {
            return false;
        }
    }

    private static byte[] entry(JarFile archive,String name,int maximum)throws Exception{
        var entry=archive.getJarEntry(name);if(entry==null||entry.isDirectory())throw new IllegalArgumentException("YSM_REQUIRED_ENTRY_MISSING");
        try(var input=archive.getInputStream(entry)){var bytes=input.readNBytes(maximum+1);if(bytes.length<1||bytes.length>maximum)throw new IllegalArgumentException("YSM_METADATA_LIMIT");return bytes;}
    }
}
