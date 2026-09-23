package dev.mineagent.runtime.core.packages;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Owner library metadata and truthful immutable asset derivation receipts; never world instance state. */
public final class PackageAssetMetadata {
    private PackageAssetMetadata(){}
    public record Input(UUID operation,UUID world,UUID owner,String action,UUID source,long sourceRevision,String sourceHash,String name,UUID shelf,long expectedRevision){
        public Input{Objects.requireNonNull(operation);Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(source);if(!Set.of("RENAME","COPY","COPY_VERSION","SAVE_ASSET","REUSE_ASSET","WITHDRAW_ASSET","RESTORE_ASSET").contains(action)||sourceRevision<1||sourceHash==null||!sourceHash.matches("[a-f0-9]{64}")||name==null||name.length()>128||name.codePoints().anyMatch(c->Character.isISOControl(c))||expectedRevision<0||Set.of("COPY","COPY_VERSION","REUSE_ASSET").contains(action)&&name.isBlank()||Set.of("REUSE_ASSET","WITHDRAW_ASSET","RESTORE_ASSET").contains(action)&&shelf==null||Set.of("COPY","COPY_VERSION","RENAME","SAVE_ASSET").contains(action)&&shelf!=null)throw new IllegalArgumentException("PACKAGE_ASSET_INPUT");}
        public UUID targetId(){return UUID.nameUUIDFromBytes(("mineagent:asset-copy:v1:"+world+":"+owner+":"+operation).getBytes(StandardCharsets.UTF_8));}
    }
    public record Alias(String name,long revision){}
    public record Shelf(UUID id,UUID owner,UUID sourceWorld,UUID packageId,long packageRevision,String canonical,String payloadHash,String version,String sourceName,String origin,boolean active,long revision,long createdAt){}
    public record Derivation(UUID owner,UUID world,UUID operation,UUID target,UUID source,long sourceRevision,String sourceHash,String sourcePayloadHash,String action,UUID shelf,String targetHash,String name,String adaptation,long createdAt){}
    public record Receipt(Input input,String outcome,UUID target,String canonical,UUID shelf,long revision,Derivation derivation){}
    public record Page<T>(List<T> items,long total,int offset,int nextOffset,boolean more){public Page{items=List.copyOf(items);}}
}
