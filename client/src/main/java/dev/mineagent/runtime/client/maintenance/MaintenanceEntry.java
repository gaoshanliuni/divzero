package dev.mineagent.runtime.client.maintenance;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** java -jar DivZero-....jar --maintenance ... also works when a bad extension prevents game startup. */
public final class MaintenanceEntry {
    public static void main(String[] arguments)throws Exception{
        if(arguments.length==0||!arguments[0].equals("--maintenance")){System.out.println("DivZero offline maintenance: java -jar <DivZero.jar> --maintenance --game <directory> --request <UUID> --kind boot-upgrade|boot-recovery --mods <directory> --action apply|rollback|remove|restore --operation <UUID> --plan-hash <sha256> --mod-id <id> --offline true. Use the F2 plan's exact values. Never run while a game/server shares these files.");return;}
        String[] args=Arrays.copyOfRange(arguments,1,arguments.length);Path game=null;for(int i=0;i+1<args.length;i++)if(args[i].equals("--game"))game=Path.of(args[i+1]);if(game==null)throw new IllegalArgumentException("MAINTENANCE_GAME_REQUIRED");
        Path helper=JavaMaintenanceLauncher.helper(game);Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").startsWith("Windows")?"java.exe":"java");
        var command=new ArrayList<String>(List.of(java.toString(),"-cp",helper.toString(),"dev.mineagent.runtime.core.maintenance.JavaMaintenance"));String runtimeBase=System.getProperty("divzero.pythonRuntimeRoot","");if(!runtimeBase.isBlank())command.add(1,"-Ddivzero.pythonRuntimeRoot="+runtimeBase);command.addAll(List.of(args));System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
    private MaintenanceEntry(){}
}
