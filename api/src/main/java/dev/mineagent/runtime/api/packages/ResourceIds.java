package dev.mineagent.runtime.api.packages;
import java.util.UUID;
/** Assigned opaque identifiers. The 64-character representation reads legacy references without computing digests. */
public final class ResourceIds {
 public static String create(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
 public static String require(String id){if(id==null||!id.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("RESOURCE_ID_INVALID");return id;}
 private ResourceIds(){}
}
