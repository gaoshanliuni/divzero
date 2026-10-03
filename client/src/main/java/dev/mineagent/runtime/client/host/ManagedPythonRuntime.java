package dev.mineagent.runtime.client.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

/** Java owns offline extraction, validation and isolated environment creation from the bundled payload. */
public final class ManagedPythonRuntime {
 public static final String VERSION="3.13.15",RELEASE="20260901",SHA256="63d263ab0162f34a241a56dc5b283c22d6e131f5516117e6a921350c69ba7d4f";
 public static final String CONTENT_INDEX_SHA256="116c6227f172704cb597cf99292ddb0f50fa808a25ae35d1803acd2c13d9486b";
 public static final long ARCHIVE_BYTES=21936514;
 private static final ObjectMapper JSON=new ObjectMapper();private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
 // The Windows JVM may normalize argv[0] before CreateProcess. Normalize Python's own paths too.
 private static final String WINDOWS_ENTRY="""
import os, sys
prefix = chr(92) * 2 + '?' + chr(92)
def extended(p):
    if not isinstance(p, str) or not os.path.isabs(p) or p.startswith(prefix): return p
    return prefix + ('UNC' + chr(92) + p[2:] if p.startswith(chr(92) * 2) else p)
for key in ('executable', '_base_executable', 'prefix', 'exec_prefix', 'base_prefix', 'base_exec_prefix'):
    if hasattr(sys, key): setattr(sys, key, extended(getattr(sys, key)))
sys.path[:] = [extended(p) for p in sys.path]
import runpy
args = sys.argv[1:]
if args[:2] == ['-m', 'pip']:
    # pip's file URL conversion strips the extended Win32 prefix. Restore it
    # before opening a wheel, including on machines without LongPathsEnabled.
    import pip._internal.utils.urls as pip_urls
    import pip._internal.models.link as pip_link
    original_url_to_path = pip_urls.url_to_path
    def native_url_to_path(url): return extended(original_url_to_path(url))
    pip_urls.url_to_path = native_url_to_path
    pip_link.url_to_path = native_url_to_path
if args[0] == '-m':
    sys.argv = args[1:]
    runpy.run_module(sys.argv[0], run_name='__main__', alter_sys=True)
else:
    sys.argv = args
    runpy.run_path(args[0], run_name='__main__')
""";
 private final Path root,archiveSource;
 public static final class PreparationFailure extends IOException {
  private final String diagnostic;
  private PreparationFailure(String code,String diagnostic){super(code);this.diagnostic=diagnostic;}
  public String diagnostic(){return diagnostic;}
 }
 public ManagedPythonRuntime(Path gameDirectory){this(gameDirectory,null);}
 public ManagedPythonRuntime(Path gameDirectory,Path verifiedArchiveSource){try{root=gameDirectory.toRealPath().resolve("mineagent-host");}catch(IOException e){throw new IllegalArgumentException("PYTHON_GAME_DIRECTORY",e);}archiveSource=verifiedArchiveSource;}
 Path root(){return root;}
 record ExecutionLease(java.nio.channels.FileChannel channel,java.nio.channels.FileLock lock) implements AutoCloseable {public void close()throws Exception{try{lock.release();}finally{channel.close();}}}
 ExecutionLease executionLease()throws Exception{var channel=java.nio.channels.FileChannel.open(safe(root,"execution.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);try{var lock=channel.tryLock();if(lock==null)throw new IOException("PYTHON_RUNTIME_IN_USE");return new ExecutionLease(channel,lock);}catch(Exception error){channel.close();throw error;}}
 void prepareRoot()throws IOException{Files.createDirectories(root);if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IOException("PYTHON_RUNTIME_PATH_CHANGED");}
 public static boolean supported(){String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch","").toLowerCase(Locale.ROOT);return os.startsWith("windows")&&Set.of("amd64","x86_64").contains(arch);}
 public Map<String,Object> inspect(){return Map.of("supported",supported(),"pythonVersion",VERSION,"distribution","python-build-standalone install_only_stripped","release",RELEASE,"archiveSha256",SHA256,"bundledArchiveBytes",ARCHIVE_BYTES,"prepared",Files.isRegularFile(pointer(),LinkOption.NOFOLLOW_LINKS),"systemPythonUsed",false,"fixedLibraries",BundledPythonResources.requirements());}
 private static void current(BooleanSupplier live)throws IOException{if(!live.getAsBoolean())throw new IOException("HOST_CONTEXT_CHANGED");if(Thread.currentThread().isInterrupted())throw new IOException("HOST_EXECUTION_INTERRUPTED");}
 public Path ensure(BooleanSupplier live)throws Exception{return ensure(live,phase->{});}
 public Path ensure(BooleanSupplier live,java.util.function.Consumer<String> progress)throws Exception{
  if(!supported())throw new IOException("PYTHON_PLATFORM_UNSUPPORTED");synchronized(LOCKS.computeIfAbsent(root,k->new Object())){
   current(live);prepareRoot();
   Path runtime=root.resolve("runtime-"+VERSION+"-"+RELEASE),proof=runtime.resolve("installed.json");
   try(var lease=java.nio.channels.FileChannel.open(safe(root,"runtime.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=lease.tryLock()){
   if(lock==null)throw new IOException("PYTHON_RUNTIME_IN_USE");
   if(!Files.exists(runtime,LinkOption.NOFOLLOW_LINKS)){
    Path cache=safe(root,"cache");Files.createDirectories(cache);Path archive=cache.resolve(SHA256+".tar.gz");
    if(!Files.exists(archive,LinkOption.NOFOLLOW_LINKS)){progress.accept("READING_BUNDLED_RUNTIME");if(archiveSource!=null){verifyArchive(archiveSource);Files.copy(archiveSource,archive);}else BundledPythonResources.copyRuntime(archive,live);}
    progress.accept("VERIFYING_ARCHIVE");verifyArchive(archive);current(live);progress.accept("EXTRACTING_RUNTIME");Path stage=Files.createTempDirectory(root,"python-extract-");var hashes=extract(archive,stage,live);
    if(!indexHash(hashes).equals(CONTENT_INDEX_SHA256))throw new IOException("PYTHON_DISTRIBUTION_CONTENT_CHANGED");
    for(String required:List.of("python/python.exe","python/pythonw.exe","python/python313.dll","python/Lib/os.py","python/Lib/encodings/__init__.py","python/DLLs/_ssl.pyd","python/DLLs/libcrypto-3-x64.dll","python/DLLs/libssl-3-x64.dll","python/LICENSE.txt"))if(!hashes.containsKey(required))throw new IOException("PYTHON_DISTRIBUTION_LAYOUT");
    Files.writeString(stage.resolve("installed.json"),JSON.writeValueAsString(Map.of("archiveSha256",SHA256,"version",VERSION,"files",hashes)),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);current(live);Files.move(stage,runtime,StandardCopyOption.ATOMIC_MOVE);
   }
   if(!Files.isRegularFile(proof,LinkOption.NOFOLLOW_LINKS)||Files.size(proof)>4*1024*1024)throw new IOException("PYTHON_RUNTIME_INCOMPLETE");var state=JSON.readTree(Files.readString(proof));
   if(!SHA256.equals(state.path("archiveSha256").asText())||!VERSION.equals(state.path("version").asText())||!state.path("files").isObject()||state.path("files").size()<100)throw new IOException("PYTHON_RUNTIME_PROOF_INVALID");
   var contentIndex=new TreeMap<String,String>();for(var entry:state.path("files").properties())contentIndex.put(entry.getKey(),entry.getValue().asText());if(!indexHash(contentIndex).equals(CONTENT_INDEX_SHA256))throw new IOException("PYTHON_RUNTIME_PROOF_INVALID");
   progress.accept("VERIFYING_RUNTIME");for(var entry:state.path("files").properties()){current(live);Path file=safe(runtime,entry.getKey());if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!hash(file).equals(entry.getValue().asText()))throw new IOException("PYTHON_RUNTIME_INTEGRITY_FAILED");}
   Path base=runtime.resolve("python/python.exe"),pointer=pointer(),env;
   if(Files.exists(pointer,LinkOption.NOFOLLOW_LINKS)){
    if(!Files.isRegularFile(pointer,LinkOption.NOFOLLOW_LINKS)||Files.size(pointer)>4*1024*1024)throw new IOException("PYTHON_ENV_PROOF_INVALID");var n=JSON.readTree(Files.readString(pointer));String directory=n.path("directory").asText();if(!directory.matches("environment-[a-f0-9-]{36}")||!SHA256.equals(n.path("archiveSha256").asText())||!BundledPythonResources.ID.equals(n.path("bundleId").asText()))throw new IOException("PYTHON_ENV_PROOF_INVALID");env=root.resolve(directory);
   }else{
    progress.accept("CREATING_ENVIRONMENT");env=root.resolve("environment-"+UUID.randomUUID());bootstrap(List.of(base.toString(),"-I","-B","-X","utf8","-m","venv","--copies","--without-pip",commandPath(env)),live);progress.accept("BOOTSTRAPPING_PIP");bootstrap(List.of(env.resolve("Scripts/python.exe").toString(),"-I","-B","-X","utf8","-m","ensurepip","--upgrade","--default-pip"),live);
    progress.accept("INSTALLING_BUNDLED_LIBRARIES");Path wheels=BundledPythonResources.wheels(root,live);
    var install=new ArrayList<String>(List.of(env.resolve("Scripts/python.exe").toString(),"-I","-B","-X","utf8","-m","pip","--isolated","install","--no-index","--no-deps","--no-compile","--disable-pip-version-check","--find-links",commandPath(wheels)));
    install.addAll(BundledPythonResources.requirements());bootstrap(install,live);
    var files=environmentIndex(env);current(live);Files.writeString(pointer,JSON.writeValueAsString(Map.of("directory",env.getFileName().toString(),"archiveSha256",SHA256,"bundleId",BundledPythonResources.ID,"files",files)),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
   }
   Path exe=safe(env,"Scripts/python.exe"),cfg=safe(env,"pyvenv.cfg");if(!Files.isRegularFile(exe,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(cfg,LinkOption.NOFOLLOW_LINKS))throw new IOException("PYTHON_ENV_INCOMPLETE");
   String exeHash=hash(exe);if(!contentIndex.get("python/python.exe").equals(exeHash)&&!Objects.equals(contentIndex.get("python/Lib/venv/scripts/nt/venvlauncher.exe"),exeHash))throw new IOException("PYTHON_ENV_EXECUTABLE_CHANGED");
   var configuration=new HashMap<String,String>();for(String line:Files.readAllLines(cfg)){int i=line.indexOf('=');if(i>0)configuration.put(line.substring(0,i).strip(),line.substring(i+1).strip());}if(!"false".equals(configuration.get("include-system-site-packages"))||!Path.of(configuration.getOrDefault("home","")).toRealPath().equals(base.getParent().toRealPath()))throw new IOException("PYTHON_ENV_CONFIGURATION_CHANGED");
   var installed=JSON.readTree(Files.readString(pointer));if(!installed.path("files").isObject()||installed.path("files").size()<100)throw new IOException("PYTHON_ENV_PROOF_INVALID");
   for(var entry:installed.path("files").properties()){current(live);var file=safe(env,entry.getKey());if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!hash(file).equals(entry.getValue().asText()))throw new IOException("PYTHON_ENV_INTEGRITY_FAILED");}
   BundledPythonResources.wheels(root,live);current(live);return exe;
   }
  }
 }
 private Path pointer(){return root.resolve("environment-"+BundledPythonResources.ID+".json");}
 public String bundleId(){return BundledPythonResources.ID;}
 public Path constraints(BooleanSupplier live)throws Exception{return BundledPythonResources.wheels(root,live).resolve("constraints.txt");}
 private static SortedMap<String,String> environmentIndex(Path env)throws Exception{var files=new TreeMap<String,String>();try(var walk=Files.walk(env)){for(var file:walk.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()){String name=env.relativize(file).toString().replace('\\','/');if(!name.endsWith(".pyc"))files.put(name,hash(file));}}return files;}
 private void bootstrap(List<String> command,BooleanSupplier live)throws Exception{
  Path log=root.resolve("bootstrap-"+UUID.randomUUID()+".log");var p=process(command,root).redirectErrorStream(true).redirectOutput(log.toFile()).start();p.getOutputStream().close();var children=new LinkedHashMap<Long,ProcessHandle>();long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);
  try{while(!p.waitFor(50,TimeUnit.MILLISECONDS)){p.descendants().forEach(c->children.put(c.pid(),c));current(live);if(System.nanoTime()>deadline)throw new IOException("PYTHON_ENV_SETUP_TIMEOUT");}if(p.exitValue()!=0){String detail;try(var reader=Files.newBufferedReader(log,StandardCharsets.UTF_8)){char[] text=new char[4096];int count=reader.read(text);detail=count<0?"":new String(text,0,count);}throw new PreparationFailure("PYTHON_ENV_SETUP_FAILED",detail);}}finally{if(p.isAlive())LocalPythonExecutor.stop(p,children);}
 }
 /** Extended Win32 paths avoid a dependency on the machine-wide LongPathsEnabled setting. */
 public static String commandPath(Path path){String value=path.toAbsolutePath().normalize().toString();if(!supported()||value.startsWith("\\\\?\\"))return value;return value.startsWith("\\\\")?"\\\\?\\UNC\\"+value.substring(2):"\\\\?\\"+value;}
 public static ProcessBuilder process(List<String> command,Path cwd){
  var args=new ArrayList<>(command);args.set(0,commandPath(Path.of(args.getFirst())));
  if(supported())for(int i=1;i<args.size();i++)if(args.get(i).equals("-m")||args.get(i).endsWith(".py")){args.add(i,"-c");args.add(i+1,WINDOWS_ENTRY);break;}
  var b=new ProcessBuilder(args).directory(new File(commandPath(cwd)));b.environment().keySet().removeIf(k->k.toUpperCase(Locale.ROOT).matches(".*(?:TOKEN|SECRET|PASSWORD|API_KEY|AUTHORIZATION).*|PYTHON.*|VIRTUAL_ENV|PIP_.*"));b.environment().put("PYTHONUTF8","1");b.environment().put("PYTHONDONTWRITEBYTECODE","1");return b;
 }
 public static void verifyArchive(Path file)throws Exception{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=ARCHIVE_BYTES||!hash(file).equals(SHA256))throw new IOException("PYTHON_ARCHIVE_INTEGRITY_FAILED");}
 private static String indexHash(SortedMap<String,String> values)throws Exception{var b=new StringBuilder();values.forEach((k,v)->b.append(k).append('\0').append(v).append('\n'));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));}
 private static String hash(Path p)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());}
 /** Strict tar subset needed by the pinned Windows archive. Reject links, absolute paths, ADS and traversals. */
 static SortedMap<String,String> extract(Path archive,Path destination,BooleanSupplier live)throws Exception{
  var hashes=new TreeMap<String,String>();long total=0;String longName=null;int headers=0;byte[] header=new byte[512];
  try(var in=new GZIPInputStream(Files.newInputStream(archive))){while(true){current(live);int n=in.readNBytes(header,0,512);if(n==0)break;if(n!=512)throw new IOException("PYTHON_TAR_TRUNCATED");boolean zero=true;for(byte v:header)zero&=v==0;if(zero)break;if(++headers>10000)throw new IOException("PYTHON_TAR_LIMIT");long sum=0;for(int i=0;i<512;i++)sum+=i>=148&&i<156?32:header[i]&255;if(sum!=octal(header,148,8))throw new IOException("PYTHON_TAR_CHECKSUM");
   long size=octal(header,124,12);if(size<0||size>128L*1024*1024||(total+=size)>200L*1024*1024)throw new IOException("PYTHON_TAR_LIMIT");int type=header[156];String name=cstring(header,0,100),prefix=cstring(header,345,155);if(!prefix.isEmpty())name=prefix+"/"+name;
   if(type=='L'){if(size>4096||longName!=null)throw new IOException("PYTHON_TAR_LONG_NAME");byte[] bytes=in.readNBytes((int)size);if(bytes.length!=size)throw new IOException("PYTHON_TAR_TRUNCATED");longName=cstring(bytes,0,bytes.length);}
   else{if(longName!=null){name=longName;longName=null;}if(!name.startsWith("python/"))throw new IOException("PYTHON_TAR_ROOT");Path out=safe(destination,name);if(type==0||type=='0'){if(hashes.containsKey(name))throw new IOException("PYTHON_TAR_DUPLICATE");Files.createDirectories(out.getParent());var digest=MessageDigest.getInstance("SHA-256");try(var stream=Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)){byte[] b=new byte[65536];long left=size;while(left>0){current(live);int count=in.read(b,0,(int)Math.min(left,b.length));if(count<0)throw new IOException("PYTHON_TAR_TRUNCATED");stream.write(b,0,count);digest.update(b,0,count);left-=count;}}Files.setLastModifiedTime(out,java.nio.file.attribute.FileTime.fromMillis(Math.multiplyExact(octal(header,136,12),1000)));if(name.endsWith(".pyc"))Files.setAttribute(out,"dos:readonly",true);hashes.put(name,HexFormat.of().formatHex(digest.digest()));}else if(type=='5'&&size==0)Files.createDirectories(out);else throw new IOException("PYTHON_TAR_ENTRY_UNSUPPORTED");}
   in.skipNBytes((512-size%512)%512);
  }}if(longName!=null)throw new IOException("PYTHON_TAR_TRUNCATED");return hashes;
 }
 static Path safe(Path root,String relative)throws IOException{if(relative.isEmpty()||relative.contains("\\")||relative.contains(":")||relative.startsWith("/")||relative.chars().anyMatch(c->c<32))throw new IOException("PYTHON_PATH_INVALID");for(String part:relative.split("/"))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" ")||part.matches("(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))throw new IOException("PYTHON_PATH_INVALID");Path out=root.resolve(relative).normalize();if(!out.startsWith(root)||out.equals(root))throw new IOException("PYTHON_PATH_INVALID");for(Path p=out;p!=null&&p.startsWith(root);p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(root.toAbsolutePath().normalize())))throw new IOException("PYTHON_PATH_LINK");return out;}
 private static String cstring(byte[] b,int off,int length){int end=off;while(end<off+length&&b[end]!=0)end++;return new String(b,off,end-off,StandardCharsets.UTF_8);}
 private static long octal(byte[] b,int off,int length)throws IOException{String text=cstring(b,off,length).strip();if(!text.matches("[0-7]+"))throw new IOException("PYTHON_TAR_NUMBER");return Long.parseLong(text,8);}
 public static String code(Throwable e){String value=Objects.toString(e.getMessage(),"");return value.matches("(?:PYTHON|HOST)_[A-Z0-9_]{1,80}")?value:"PYTHON_RUNTIME_PREPARATION_FAILED";}
}
