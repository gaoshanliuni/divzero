package dev.mineagent.runtime.client.host;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.host.HostCommandRequest;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Executes ONLY after the Native client has approved the exact request. Not a filesystem sandbox. */
public final class LocalPowerShellExecutor {
    private static final ObjectMapper JSON=new ObjectMapper();public static final int OUTPUT_BYTES=4096;
    private final Path root,pwsh;
    public LocalPowerShellExecutor(Path gameDirectory,Path executable){root=gameDirectory.toAbsolutePath().normalize().resolve("mineagent-host");pwsh=executable.toAbsolutePath().normalize();if(!pwsh.getFileName().toString().equalsIgnoreCase("pwsh.exe")||!Files.isRegularFile(pwsh))throw new IllegalArgumentException("POWERSHELL_7_NOT_FOUND");}
    public static Optional<Path> discover(){if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))return Optional.empty();var candidates=new ArrayList<Path>();String program=System.getenv("ProgramFiles");if(program!=null)candidates.add(Path.of(program,"PowerShell","7","pwsh.exe"));for(String entry:System.getenv().getOrDefault("PATH","").split(java.io.File.pathSeparator))try{if(!entry.isBlank()){var p=Path.of(entry.replace("\"","")).resolve("pwsh.exe");if(p.isAbsolute())candidates.add(p);}}catch(Exception ignored){}return candidates.stream().filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).findFirst();}
    public Map<String,Object> execute(HostCommandRequest request,String approvedHash,BooleanSupplier current){
        if(!request.sha256().equals(approvedHash))return Map.of("status","REJECTED","error","HOST_APPROVAL_HASH_MISMATCH");
        Process process=null;Path intent=root.resolve("operations").resolve(request.operation()+".json"),receipt=root.resolve("operations").resolve(request.operation()+".result.json");var children=new LinkedHashMap<Long,ProcessHandle>();Capture out=new Capture(),err=new Capture();long started=System.nanoTime();boolean launched=false;
        try{
            if(!current.getAsBoolean())return Map.of("status","REJECTED","error","HOST_CONTEXT_CHANGED");
            Files.createDirectories(root.resolve("operations"));Files.createDirectories(root.resolve("workspace"));
            Files.writeString(intent,JSON.writeValueAsString(Map.of("operation",request.operation(),"sha256",approvedHash,"state","PREPARING","createdAt",Instant.now().toString())),StandardOpenOption.CREATE_NEW);
            // Windows ProcessBuilder uses redirected pipes/no new console. pwsh 7.6 -WindowStyle cannot be applied to this already-consoleless process.
            // UTF-16LE is pwsh documented command transport: it preserves quotes/Unicode, not secrecy. Native approval always shows the full plaintext script.
            String script="$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); if($PSVersionTable.PSVersion.Major -lt 7){ throw 'POWERSHELL_7_REQUIRED' }; $PSStyle.OutputRendering='PlainText'; try { & {\n"+request.script()+"\n}; if(-not $?){exit 1} } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }\n\n";
            var builder=new ProcessBuilder(pwsh.toString(),"-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))).directory(root.resolve("workspace").toFile());
            builder.environment().keySet().removeIf(k->k.toUpperCase(Locale.ROOT).matches(".*(?:TOKEN|SECRET|PASSWORD|API_KEY|AUTHORIZATION).*"));
            if(!current.getAsBoolean())return record(receipt,Map.of("status","REJECTED","error","HOST_CONTEXT_CHANGED"));
            process=builder.start();launched=true;final Process active=process;
            Thread output=Thread.ofVirtual().start(()->out.read(active.getInputStream())),error=Thread.ofVirtual().start(()->err.read(active.getErrorStream()));
            process.getOutputStream().close();
            String outcome="EXECUTED";long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(request.timeoutSeconds());
            while(!process.waitFor(40,TimeUnit.MILLISECONDS)){process.descendants().forEach(p->children.put(p.pid(),p));if(!current.getAsBoolean()){outcome="CANCELLED";stop(process,children);break;}if(System.nanoTime()>deadline){outcome="TIMED_OUT";stop(process,children);break;}}
            output.join(1000);error.join(1000);if(outcome.equals("EXECUTED")&&process.exitValue()!=0)outcome="FAILED";var result=new LinkedHashMap<String,Object>();result.put("status",outcome);result.put("operation",request.operation().toString());result.put("scriptSha256",approvedHash);result.put("shell","PowerShell 7");result.put("transport","UTF16LE_ENCODED_COMMAND");result.put("pid",process.pid());result.put("exitCode",process.isAlive()?-1:process.exitValue());result.put("stdout",out.text());result.put("stderr",err.text());result.put("outputTruncated",out.truncated||err.truncated||output.isAlive()||error.isAlive());result.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));result.put("sideEffectsMayPersist",!outcome.equals("EXECUTED")||process.exitValue()!=0);return record(receipt,result);
        }catch(FileAlreadyExistsException duplicate){return Map.of("status","REJECTED","error","HOST_OPERATION_NOT_REPLAYABLE");}
        catch(Exception failure){if(process!=null&&process.isAlive())stop(process,children);var result=Map.<String,Object>of("status",launched?"UNKNOWN":"REJECTED","error",launched?"HOST_EXECUTION_OUTCOME_UNKNOWN":"HOST_PROCESS_NOT_STARTED");try{return record(receipt,result);}catch(Exception ignored){return result;}}
    }
    private Map<String,Object> record(Path path,Map<String,Object> value)throws Exception{Files.writeString(path,JSON.writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);return Map.copyOf(value);}
    private static void stop(Process process,Map<Long,ProcessHandle> children){process.descendants().forEach(p->children.put(p.pid(),p));for(var child:children.values())if(child.isAlive())child.destroyForcibly();process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static final class Capture {private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();volatile boolean truncated;void read(java.io.InputStream input){try(input){byte[] b=new byte[2048];int n;while((n=input.read(b))>=0)synchronized(this){int left=OUTPUT_BYTES-bytes.size();if(n>left)truncated=true;if(left>0)bytes.write(b,0,Math.min(n,left));}}catch(java.io.IOException ignored){}}synchronized String text(){return bytes.toString(StandardCharsets.UTF_8).replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]","").replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "");}}
}
