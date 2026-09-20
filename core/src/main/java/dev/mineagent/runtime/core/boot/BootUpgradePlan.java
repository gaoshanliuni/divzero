package dev.mineagent.runtime.core.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.util.*;

/** Exact, immutable offline swap approval. The loader slot remains one filename across replacements. */
public record BootUpgradePlan(int schema,UUID operation,UUID world,UUID owner,UUID packageId,UUID previousBuild,UUID nextBuild,
        String directory,String filename,String modId,String previousArtifact,String nextArtifact,String previousCanonical,
        String nextCanonical,String previousPhase,String nextPhase,long approvedAt,boolean globalConsent) {
    public BootUpgradePlan {
        for(var id:List.of(operation,world,owner,packageId,previousBuild,nextBuild))Objects.requireNonNull(id);
        if(schema!=1||previousBuild.equals(nextBuild)||directory==null||directory.length()>4096||filename==null||!filename.matches("mineagent-boot-"+packageId+"-[a-f0-9]{64}\\.jar")||modId==null||!modId.matches("[a-z][a-z0-9_]{1,63}")||approvedAt<1||!globalConsent)throw new IllegalArgumentException("BOOT_UPGRADE_PLAN");
        for(var hash:List.of(previousArtifact,nextArtifact,previousCanonical,nextCanonical))if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("BOOT_UPGRADE_HASH");
        if(previousArtifact.equals(nextArtifact)||previousCanonical.equals(nextCanonical)||!Set.of("INSTALLED_PENDING_RESTART","FILE_STATE_UNKNOWN").contains(previousPhase)||!nextPhase.equals("BUILT"))throw new IllegalArgumentException("BOOT_UPGRADE_SOURCE");
    }
    public byte[] encode()throws Exception {return new ObjectMapper().writeValueAsBytes(this);}
    public String sha256()throws Exception {return RuntimePackageCanonicalizer.sha256(encode());}
}
