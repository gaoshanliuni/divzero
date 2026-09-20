package dev.mineagent.runtime.client.trust;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Read-only setup projection for a newly created host document; never grants trust. */
public final class WebSetupNotice {
    private WebSetupNotice() {}
    public static Map<String,Object> read(Path trustFile,String serverId,Map<String,String> snapshot,boolean signatureValid)throws IOException{
        if(!signatureValid)return Map.of("trust","UNKNOWN","fingerprint","","initialized",false);
        String fingerprint=snapshot.getOrDefault("security.identityFingerprint","");
        return Map.of("trust",new ServerTrustStore(trustFile).status(serverId,fingerprint).name(),"fingerprint",fingerprint,
                "initialized",Boolean.parseBoolean(snapshot.getOrDefault("runtime.initialized","false")));
    }
}
