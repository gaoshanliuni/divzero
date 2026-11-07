package dev.mineagent.runtime.core.host;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Short, Mod-owned interpreter storage; user scripts and results stay in the game instance. */
public final class PythonPaths {
    private record Scope(Path game,Path base){}
    private static final Map<Scope,Path> ROOTS=new HashMap<>();
    public static synchronized Path runtimeRoot(Path game)throws Exception{
        game=game.toRealPath();
        String configured=System.getProperty("divzero.pythonRuntimeRoot","");Path base;
        if(!configured.isBlank())base=Path.of(configured).toAbsolutePath().normalize();
        else{String local=System.getenv("LOCALAPPDATA");base=local==null||local.isBlank()?game.toRealPath().resolve("mineagent-host/unavailable-runtime"):Path.of(local).toRealPath().resolve("DivZero/python");}
        base=Files.createDirectories(base).toRealPath();var scope=new Scope(game,base);var cached=ROOTS.get(scope);if(cached!=null)return cached;
        Path workspace=Files.createDirectories(game.resolve("mineagent-host")).toRealPath();if(!workspace.getParent().equals(game))throw new IllegalStateException("PYTHON_WORKSPACE_PATH_CHANGED");
        Path index=workspace.resolve("runtime-locations-v2.json");var json=new com.fasterxml.jackson.databind.ObjectMapper();
        try(var channel=FileChannel.open(workspace.resolve("runtime-locations-v2.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.lock()){
            if(Files.isSymbolicLink(index))throw new IllegalStateException("PYTHON_RUNTIME_INDEX_LINK");
            var locations=new TreeMap<String,String>();if(Files.exists(index)){if(Files.size(index)>65536)throw new IllegalStateException("PYTHON_RUNTIME_INDEX_SIZE");locations.putAll(json.readValue(Files.readString(index),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));}
            String name=locations.get(base.toString());
            if(name==null){
                // Existing installations already record the original game path; no old digest is needed.
                try(var children=Files.list(base)){for(var child:children.toList()){
                    if(!Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(child))continue;Path owner=child.resolve("game.owner");
                    if(Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS)&&Files.size(owner)<=8192&&Files.readString(owner).equalsIgnoreCase(game.toString())){if(name!=null)throw new IllegalStateException("PYTHON_RUNTIME_MULTIPLE_OWNERS");name=child.getFileName().toString();}
                }}
                if(name==null)name="instance-"+UUID.randomUUID();
                locations.put(base.toString(),name);Path candidate=Files.createTempFile(workspace,"runtime-location-",".tmp");Files.writeString(candidate,json.writeValueAsString(locations));
                try{Files.move(candidate,index,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(candidate,index,StandardCopyOption.REPLACE_EXISTING);}
            }
            if(!name.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalStateException("PYTHON_RUNTIME_LOCATION_INVALID");Path result=base.resolve(name);ROOTS.put(scope,result);return result;
        }
    }
    public static void prepare(Path game,boolean create)throws Exception{
        Path root=runtimeRoot(game);if(create)Files.createDirectories(root);if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IllegalStateException("PYTHON_RUNTIME_PATH_CHANGED");
        Path owner=root.resolve("game.owner");String expected=game.toRealPath().toString();
        if(Files.exists(owner,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS)||Files.size(owner)>8192||!Files.readString(owner).equalsIgnoreCase(expected))throw new IllegalStateException("PYTHON_RUNTIME_OWNER_CHANGED");}
        else if(create)Files.writeString(owner,expected,StandardOpenOption.CREATE_NEW);else throw new IllegalStateException("PYTHON_RUNTIME_OWNER_MISSING");
    }
    private PythonPaths(){}
}
