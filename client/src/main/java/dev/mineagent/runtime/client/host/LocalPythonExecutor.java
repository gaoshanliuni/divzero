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
public final class LocalPythonExecutor {
    private static final ObjectMapper JSON=new ObjectMapper();public static final int OUTPUT_BYTES=4096;
    private final Path root;private final ManagedPythonRuntime runtime;
    public LocalPythonExecutor(Path gameDirectory){runtime=new ManagedPythonRuntime(gameDirectory);root=runtime.root();}
    public Map<String,Object> execute(HostCommandRequest request,String approvedHash,BooleanSupplier current){
        if(!request.sha256().equals(approvedHash))return Map.of("status","REJECTED","error","HOST_APPROVAL_HASH_MISMATCH");
        Process process=null;Path intent=root.resolve("operations").resolve(request.operation()+".json"),receipt=root.resolve("operations").resolve(request.operation()+".result.json");var children=new LinkedHashMap<Long,ProcessHandle>();Capture out=new Capture(root.resolve("operations").resolve(request.operation()+".stdout.txt")),err=new Capture(root.resolve("operations").resolve(request.operation()+".stderr.txt"));long started=System.nanoTime();boolean launched=false,reserved=false;
        try{
            if(!current.getAsBoolean())return Map.of("status","REJECTED","error","HOST_CONTEXT_CHANGED");
            runtime.prepareRoot();Files.createDirectories(ManagedPythonRuntime.safe(root,"operations"));Files.createDirectories(ManagedPythonRuntime.safe(root,"workspace"));
            Files.writeString(intent,JSON.writeValueAsString(Map.of("operation",request.operation(),"sha256",approvedHash,"state","PREPARING","createdAt",Instant.now().toString())),StandardOpenOption.CREATE_NEW);reserved=true;
            Path python=runtime.ensure(current);var command=new ArrayList<String>(List.of(python.toString(),"-I","-B","-X","utf8","-u"));
            if(request.kind()==HostCommandRequest.Kind.PYTHON){Path source=root.resolve("operations").resolve(request.operation()+".py");Files.writeString(source,request.script(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);command.add(source.toString());}
            else{command.addAll(List.of("-m","pip","--isolated","install","--disable-pip-version-check","--no-input","--cache-dir",root.resolve("cache/pip").toString(),"--index-url","https://pypi.org/simple","--report",root.resolve("operations").resolve(request.operation()+".pip-report.json").toString()));command.addAll(request.packages());}
            var builder=ManagedPythonRuntime.process(command,root.resolve("workspace"));
            if(!current.getAsBoolean())return record(receipt,Map.of("status","REJECTED","error","HOST_CONTEXT_CHANGED"));
            process=builder.start();launched=true;final Process active=process;
            Thread output=Thread.ofVirtual().start(()->out.read(active.getInputStream())),error=Thread.ofVirtual().start(()->err.read(active.getErrorStream()));
            process.getOutputStream().close();
            String outcome="EXECUTED";long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(request.timeoutSeconds());
            while(!process.waitFor(40,TimeUnit.MILLISECONDS)){process.descendants().forEach(p->children.put(p.pid(),p));if(!current.getAsBoolean()){outcome="CANCELLED";stop(process,children);break;}if(System.nanoTime()>deadline){outcome="TIMED_OUT";stop(process,children);break;}}
            output.join(1000);error.join(1000);if(outcome.equals("EXECUTED")&&process.exitValue()!=0)outcome="FAILED";var result=new LinkedHashMap<String,Object>();result.put("status",outcome);result.put("operation",request.operation().toString());result.put("scriptSha256",approvedHash);result.put("engine","JAVA_MANAGED_PYTHON");result.put("pythonVersion",ManagedPythonRuntime.VERSION);result.put("kind",request.kind().name());result.put("transport","UTF8_FILE_OR_ARGV_NO_SHELL");result.put("pid",process.pid());result.put("exitCode",process.isAlive()?-1:process.exitValue());result.put("stdout",out.text());result.put("stderr",err.text());result.put("outputTruncated",out.failed||err.failed||output.isAlive()||error.isAlive());result.put("outputPreviewOnly",out.total>OUTPUT_BYTES||err.total>OUTPUT_BYTES);result.put("stdoutBytes",out.total);result.put("stderrBytes",err.total);result.put("readFullOutput","read_host_output(operation_id,stream,offset=0), then nextOffset; no process rerun");if(out.failed||err.failed||output.isAlive()||error.isAlive()){result.put("status","UNKNOWN");result.put("error","HOST_OUTPUT_CAPTURE_INCOMPLETE");}result.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));result.put("sideEffectsMayPersist",!outcome.equals("EXECUTED")||process.exitValue()!=0);return record(receipt,result);
        }catch(FileAlreadyExistsException duplicate){if(!reserved)return Map.of("status","REJECTED","error","HOST_OPERATION_NOT_REPLAYABLE");var result=Map.<String,Object>of("status","REJECTED","error","HOST_LOCAL_FILE_EXISTS");try{return record(receipt,result);}catch(Exception ignored){return result;}}
        catch(Exception failure){if(process!=null&&process.isAlive())stop(process,children);var result=Map.<String,Object>of("status",launched?"UNKNOWN":"REJECTED","error",launched?"HOST_EXECUTION_OUTCOME_UNKNOWN":ManagedPythonRuntime.code(failure));try{return record(receipt,result);}catch(Exception ignored){return result;}}
    }
    private Map<String,Object> record(Path path,Map<String,Object> value)throws Exception{Files.writeString(path,JSON.writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);return Map.copyOf(value);}
    static void stop(Process process,Map<Long,ProcessHandle> children){process.descendants().forEach(p->children.put(p.pid(),p));for(var child:children.values())if(child.isAlive())child.destroyForcibly();process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    /** Full private bytes are retained; only the immediate model receipt is paged. */
    public Map<String,Object> output(UUID operation,String stream,int offset)throws Exception{
        if(!Set.of("stdout","stderr").contains(stream)||offset<0)throw new IllegalArgumentException("HOST_OUTPUT_ARGUMENTS");
        runtime.prepareRoot();Path file=ManagedPythonRuntime.safe(root,"operations/"+operation+"."+stream+".txt"),receipt=ManagedPythonRuntime.safe(root,"operations/"+operation+".result.json");
        if(!Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("HOST_OUTPUT_NOT_FINAL");
        try(var r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){long skipped=0;while(skipped<offset){long n=r.skip(offset-skipped);if(n==0)throw new IllegalArgumentException("HOST_OUTPUT_OFFSET");skipped+=n;}char[] chars=new char[4096];int count=r.read(chars);String text=count<0?"":new String(chars,0,count);if(!text.isEmpty()&&Character.isHighSurrogate(text.charAt(text.length()-1))){int c=r.read();if(c!=-1)text+=(char)c;}r.mark(1);boolean more=r.read()!=-1;return Map.of("operation",operation,"stream",stream,"offset",offset,"nextOffset",more?offset+text.length():-1,"text",text,"totalBytes",Files.size(file),"rerun",false);}
    }
    private static final class Capture {
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();private final Path file;volatile boolean failed;volatile long total;
        Capture(Path file){this.file=file;}
        void read(java.io.InputStream input){try(input;var saved=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){byte[] b=new byte[8192];int n;while((n=input.read(b))>=0){saved.write(b,0,n);synchronized(this){total+=n;int left=OUTPUT_BYTES-bytes.size();if(left>0)bytes.write(b,0,Math.min(n,left));}}}catch(java.io.IOException error){failed=true;}}
        synchronized String text(){return bytes.toString(StandardCharsets.UTF_8).replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]","").replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "");}
    }
}
