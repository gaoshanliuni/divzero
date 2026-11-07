package dev.mineagent.runtime.client.maintenance;

import java.nio.file.*;
import java.util.*;

/** Copies the signed-distribution helper and starts this game's Java directly. No external scripts. */
public final class JavaMaintenanceLauncher {
    private static final Map<Path,Path> HELPERS=new HashMap<>();
    public static synchronized Path helper(Path game)throws Exception{
        Path directory=game.toRealPath(),root=directory.resolve("mineagent-maintenance");Files.createDirectories(root);if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IllegalStateException("MAINTENANCE_DIRECTORY_LINK");
        var existing=HELPERS.get(directory);if(existing!=null&&Files.isRegularFile(existing,LinkOption.NOFOLLOW_LINKS))return existing;
        Path stage=Files.createTempFile(root,"helper-",".pending");try{
            try(var in=JavaMaintenanceLauncher.class.getResourceAsStream("/META-INF/mineagent/worker/mineagent-worker.jar");var out=Files.newOutputStream(stage)){if(in==null)throw new IllegalStateException("MAINTENANCE_HELPER_NOT_BUNDLED");byte[] b=new byte[65536];int n;long size=0;while((n=in.read(b))!=-1){size+=n;if(size>512L*1024*1024)throw new IllegalStateException("MAINTENANCE_HELPER_LIMIT");out.write(b,0,n);}}
            try(var archive=new java.util.zip.ZipFile(stage.toFile())){if(archive.getEntry("dev/mineagent/runtime/core/maintenance/JavaMaintenance.class")==null)throw new IllegalStateException("MAINTENANCE_HELPER_LAYOUT");}
            Path jar=root.resolve("helper-"+UUID.randomUUID()+".jar");Files.move(stage,jar,StandardCopyOption.ATOMIC_MOVE);HELPERS.put(directory,jar);return jar;
        }finally{Files.deleteIfExists(stage);}
    }
    public static Map<String,Object> schedule(Path game,Map<String,String> operation)throws Exception{
        Path directory=game.toRealPath(),root=directory.resolve("mineagent-maintenance"),jar=helper(game);
        String request=UUID.randomUUID().toString();var current=ProcessHandle.current();var start=current.info().startInstant().orElseThrow();Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").startsWith("Windows")?"javaw.exe":"java");if(!Files.isRegularFile(java))throw new IllegalStateException("MAINTENANCE_JAVA_UNAVAILABLE");
        var args=new ArrayList<String>(List.of(java.toString(),"-cp",jar.toString(),"dev.mineagent.runtime.core.maintenance.JavaMaintenance","--game",directory.toString(),"--request",request,"--wait-pid",Long.toString(current.pid()),"--wait-start",start.toString()));
        String runtimeBase=System.getProperty("divzero.pythonRuntimeRoot","");if(!runtimeBase.isBlank())args.add(1,"-Ddivzero.pythonRuntimeRoot="+runtimeBase);
        if(!Set.of("kind","operation","build","plan-hash","mod-id","action","mods","bundle","preview").containsAll(operation.keySet()))throw new IllegalArgumentException("MAINTENANCE_ARGUMENTS");operation.forEach((key,value)->{args.add("--"+key);args.add(value);});
        var child=new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(root.resolve(request+".log").toFile()).start();child.getOutputStream().close();
        return Map.of("status","WAITING_FOR_GAME_EXIT","request",request,"helperPid",child.pid(),"receipt",root.resolve(request+".result.json").toString(),"shellUsed",false);
    }
    private JavaMaintenanceLauncher(){}
}
