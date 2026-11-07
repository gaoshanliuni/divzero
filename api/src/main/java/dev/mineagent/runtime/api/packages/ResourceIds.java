package dev.mineagent.runtime.api.packages;
import java.util.UUID;
/** Assigned opaque identifiers. The 64-character representation reads legacy references without computing digests. */
public final class ResourceIds {
 public static String create(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
 public static String require(String id){if(id==null||!id.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("RESOURCE_ID_INVALID");return id;}
 /** Lossless encoding of an existing object UUID, revision and domain; no content participates. */
 public static String version(UUID object,long revision,int domain){if(object==null||revision<0||domain<1)throw new IllegalArgumentException("RESOURCE_VERSION_INVALID");return object.toString().replace("-","")+String.format(java.util.Locale.ROOT,"%016x%016x",revision,(long)domain);}
 private ResourceIds(){}
}
