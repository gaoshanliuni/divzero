package dev.mineagent.runtime.core.crypto;

import dev.mineagent.runtime.api.packages.ResourceIds;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Legacy single-source transport, now authenticating the source itself and its explicit resource ID. */
public final class SourceSignature {
    private static final byte[] VERSION={'D','V','Z','2'};
    @FunctionalInterface public interface Verifier{boolean verify(byte[] bytes,byte[] signature)throws Exception;}
    public static byte[] sign(IdentitySigner signer,String id,String source)throws Exception{
        byte[] proof=signer.sign(payload(id,source)),out=Arrays.copyOf(VERSION,VERSION.length+proof.length);System.arraycopy(proof,0,out,VERSION.length,proof.length);return out;
    }
    public static boolean modern(byte[] proof){return proof!=null&&proof.length==68&&Arrays.equals(proof,0,4,VERSION,0,4);}
    public static boolean verify(Verifier verifier,String id,String source,byte[] proof)throws Exception{return modern(proof)&&verifier.verify(payload(id,source),Arrays.copyOfRange(proof,4,proof.length));}
    private static byte[] payload(String id,String source){ResourceIds.require(id);if(source==null||source.length()>1_000_000)throw new IllegalArgumentException("SOURCE_SIGNATURE_BUDGET");return ("DivZero source v2\n"+id+"\n"+source).getBytes(StandardCharsets.UTF_8);}
    private SourceSignature(){}
}
