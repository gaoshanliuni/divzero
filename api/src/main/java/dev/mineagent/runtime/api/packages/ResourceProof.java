package dev.mineagent.runtime.api.packages;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Direct Ed25519 proof bound to a resource ID. Trust comes from the enclosing immutable reference, not this key alone. */
public final class ResourceProof {
    private static final KeyPair KEY=createKey();
    private static KeyPair createKey(){try{return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}catch(GeneralSecurityException error){throw new ExceptionInInitializerError(error);}}
    private static void input(Signature signature,String id,byte[] bytes)throws SignatureException{signature.update(("DivZero/source/v2/"+ResourceIds.require(id)+"/"+bytes.length+"\n").getBytes(StandardCharsets.UTF_8));signature.update(bytes);}
    public static String issue(String id,byte[] bytes){try{var signature=Signature.getInstance("Ed25519");signature.initSign(KEY.getPrivate());input(signature,id,bytes);return Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded())+"."+Base64.getEncoder().encodeToString(signature.sign());}catch(GeneralSecurityException error){throw new IllegalStateException("SOURCE_PROOF_UNAVAILABLE",error);}}
    public static boolean verify(String id,byte[] bytes,String proof){try{if(proof==null||proof.length()>256)return false;String[] parts=proof.split("\\.",-1);if(parts.length!=2)return false;byte[] key=Base64.getDecoder().decode(parts[0]),signed=Base64.getDecoder().decode(parts[1]);if(key.length!=44||signed.length!=64)return false;var signature=Signature.getInstance("Ed25519");signature.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key)));input(signature,id,bytes);return signature.verify(signed);}catch(GeneralSecurityException|IllegalArgumentException error){return false;}}
    private ResourceProof(){}
}
