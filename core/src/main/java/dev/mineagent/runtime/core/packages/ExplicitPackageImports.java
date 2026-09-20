package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
/** Explicit imported content has its own truthful provenance, without manufacturing a generation job. */
public final class ExplicitPackageImports implements AutoCloseable {
    private record Claim(UUID owner,String hash,long revision){}
    private static final String NS="explicit_package_owners_v1";
    private final SqliteRuntimeRepository repository;private final RuntimePackageLibrary library;private final Map<UUID,Claim> claims=new HashMap<>();private final ObjectMapper json=new ObjectMapper();
    private ExplicitPackageImports(Path db,RuntimePackageLibrary library)throws Exception{this.library=library;repository=new SqliteRuntimeRepository(db);try{for(var record:repository.list(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,NS))claims.put(UUID.fromString(record.recordId()),json.readValue(record.payload(),Claim.class));}catch(Exception failure){repository.close();throw failure;}}
    public static ExplicitPackageImports open(Path db,RuntimePackageLibrary library)throws Exception{return new ExplicitPackageImports(db,library);}
    public synchronized RuntimePackage install(UUID owner,RuntimePackage pack)throws Exception{
        if(owner==null||pack==null||pack.origin()!=PackageOrigin.EXPLICIT_IMPORT||pack.enabled())throw new IllegalArgumentException("EXPLICIT_IMPORT_REQUIRED");
        var claim=new Claim(owner,pack.canonicalSha256(),pack.revision());var old=claims.get(pack.packageId());
        if(old!=null&&!old.equals(claim)||old==null&&library.get(pack.packageId()).isPresent())throw new SecurityException("PACKAGE_IMPORT_OWNERSHIP");
        String error=library.validateCandidate(pack);if(!error.isEmpty())throw new IllegalArgumentException(error);
        if(old==null){if(claims.size()>=4096)throw new IllegalStateException("PACKAGE_IMPORT_BUDGET");var result=repository.compareAndSet(RuntimePackageLibrary.GLOBAL_LIBRARY_ID,NS,pack.packageId().toString(),0,json.writeValueAsString(claim),System.currentTimeMillis());if(!result.accepted()&&!json.readValue(result.record().payload(),Claim.class).equals(claim))throw new SecurityException("PACKAGE_IMPORT_OWNERSHIP");claims.put(pack.packageId(),claim);}
        var current=library.get(pack.packageId()).orElse(null);if(current!=null){if(!current.canonicalSha256().equals(claim.hash()))throw new IllegalStateException("PACKAGE_IMPORT_CHANGED");return current;}
        var result=library.install(pack);if(!result.accepted())throw new IllegalStateException(result.errorCode());return library.get(pack.packageId()).orElseThrow();
    }
    public synchronized boolean owns(UUID owner,UUID id,long revision){var c=claims.get(id);var p=library.get(id).orElse(null);return c!=null&&c.owner().equals(owner)&&p!=null&&p.origin()==PackageOrigin.EXPLICIT_IMPORT&&p.canonicalSha256().equals(c.hash())&&p.revision()==revision&&revision>=c.revision();}
    public synchronized List<RuntimePackage> heads(UUID owner){return library.all().stream().filter(p->owns(owner,p.packageId(),p.revision())).toList();}
    @Override public void close()throws Exception{repository.close();}
}
