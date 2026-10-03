package dev.mineagent.runtime.core.maintenance;
import static dev.mineagent.runtime.core.maintenance.JavaMaintenance.*;
import java.nio.file.*;import java.nio.channels.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
final class PythonMaintenance {private static final ObjectMapper JSON=new ObjectMapper();
    public static Map<String,Object> repair(Path game,String bundle)throws Exception{
        if(!bundle.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("PYTHON_BUNDLE_ID");Path root=directory(game.resolve("mineagent-host"));
        try(var c=FileChannel.open(child(root,"runtime.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lease=c.tryLock();var processes=FileChannel.open(child(root,"execution.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var executing=processes.tryLock()){
            if(lease==null||executing==null)throw new IllegalStateException("PYTHON_RUNTIME_IN_USE");String suffix=".quarantined-"+UUID.randomUUID();var moved=new ArrayList<String>();
            Path pointer=child(root,"environment-"+bundle+".json");
            if(Files.exists(pointer,LinkOption.NOFOLLOW_LINKS)){try{var p=JSON.readTree(NativeCompilationSnapshot.read(pointer,4*1024*1024));String env=p.path("directory").asText();if(p.path("bundleId").asText().equals(bundle)&&env.matches("environment-[a-f0-9-]{36}")){Path folder=child(root,env);if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS)){directory(folder);Files.move(folder,child(root,env+suffix),StandardCopyOption.ATOMIC_MOVE);moved.add(env);}}}catch(java.io.IOException invalid){/* Keep malformed pointer as evidence; never infer a directory from it. */}Files.move(pointer,child(root,pointer.getFileName()+suffix),StandardCopyOption.ATOMIC_MOVE);moved.add(pointer.getFileName().toString());}
            // Only the named vendor slot and its verified-bundle cache are managed; user scripts/output stay intact.
            for(String name:List.of("runtime-3.13.15-20260901","bundle-"+bundle)){Path folder=child(root,name);if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS)){directory(folder);Files.move(folder,child(root,name+suffix),StandardCopyOption.ATOMIC_MOVE);moved.add(name);}}
            Path cache=child(root,"cache");if(Files.isDirectory(cache,LinkOption.NOFOLLOW_LINKS)){directory(cache);String name="63d263ab0162f34a241a56dc5b283c22d6e131f5516117e6a921350c69ba7d4f.tar.gz";Path archive=child(cache,name);if(Files.exists(archive,LinkOption.NOFOLLOW_LINKS))Files.move(archive,child(cache,name+suffix),StandardCopyOption.ATOMIC_MOVE);}
            return Map.of("status","REPAIR_STAGED_FOR_NEXT_USE","preserved",moved,"nextUse","EXTRACT_FROM_BUNDLED_JAR","userWorkspacePreserved",true);
        }
    }
}
