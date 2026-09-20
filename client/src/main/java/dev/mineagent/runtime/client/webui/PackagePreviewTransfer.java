package dev.mineagent.runtime.client.webui;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.packages.RuntimeResourceSide;
import dev.mineagent.runtime.core.packages.PackagePreviewBundle;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Connection-scoped caller supplies the previously confirmed server identity, not a key from this download. */
public final class PackagePreviewTransfer {
    @FunctionalInterface public interface SignatureVerifier { boolean verify(byte[] value, byte[] signature) throws Exception; }
    public record Resolved(RuntimePackage runtimePackage, String entry, Map<String, PackageUiResolver.Asset> assets) {}
    private PackagePreviewTransfer() {}
    public static Resolved decode(byte[] body, UUID packageId, long revision, SignatureVerifier verifier) throws Exception {
        if (body.length > PackagePreviewBundle.MAX_BYTES) throw new IllegalArgumentException("UI_BUNDLE_BUDGET");
        var bundle = PackagePreviewBundle.decode(body);
        var pkg = bundle.manifest();
        if (!pkg.packageId().equals(packageId) || pkg.revision() != revision) throw new IllegalArgumentException("STALE_PACKAGE");
        if (!RuntimePackageCanonicalizer.sha256(pkg).equals(pkg.canonicalSha256())) throw new IllegalArgumentException("MANIFEST_HASH");
        if (!verifier.verify(pkg.canonicalSha256().getBytes(StandardCharsets.US_ASCII), Base64.getDecoder().decode(pkg.signature())))
            throw new SecurityException("PACKAGE_SIGNATURE_UNTRUSTED");
        if (pkg.resources().size() > 256 || bundle.files().size() > 256 || !bundle.entry().startsWith("ui/") || !bundle.entry().endsWith(".html")
                || pkg.entrypoints().values().stream().noneMatch(e -> e.path().equals(bundle.entry()) && e.side() != RuntimeResourceSide.SERVER))
            throw new IllegalArgumentException("UI_ENTRYPOINT");
        var hashes = new HashSet<String>();
        for (var ref : pkg.resources().values()) if (ref.path().startsWith("ui/")) hashes.add(ref.sha256());
        if (!hashes.equals(bundle.files().keySet())) throw new IllegalArgumentException("UI_BUNDLE_RESOURCE_SET");
        var assets = PackageUiResolver.resolve(pkg.resources(), hash -> {
            String encoded = bundle.files().get(hash);
            if (encoded == null || encoded.length() > 12 * 1024 * 1024) throw new IllegalArgumentException("UI_RESOURCE_BUDGET");
            return Base64.getDecoder().decode(encoded);
        }, PackagePreviewBundle.MAX_UI_BYTES);
        if (!assets.containsKey(bundle.entry())) throw new IllegalArgumentException("UI_ENTRYPOINT_MISSING");
        return new Resolved(pkg, bundle.entry(), assets);
    }
    public static final class Assembler {
        private final byte[] body;
        private final String hash;
        private int offset;
        public Assembler(int size,String hash){this(size,hash,PackagePreviewBundle.MAX_BYTES);}
        public Assembler(int size,String hash,int maximumBytes){
            if(maximumBytes<1||maximumBytes>dev.mineagent.runtime.core.packages.ResourcePackPlan.MAX_BUNDLE)throw new IllegalArgumentException("PACKAGE_TRANSFER_LIMIT");
            if (size < 1 || size > maximumBytes || hash == null || !hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("UI_TRANSFER_BUDGET");
            body = new byte[size]; this.hash = hash;
        }
        public int offset() { return offset; }
        public int size() { return body.length; }
        public void append(int start, byte[] bytes) {
            if (start < 0 || start > offset || bytes.length < 1 || bytes.length > PackagePreviewBundle.CHUNK_BYTES || bytes.length > body.length - start)
                throw new IllegalArgumentException("UI_CHUNK_RANGE");
            if (start < offset) {
                if (start + bytes.length > offset || !Arrays.equals(body, start, start + bytes.length, bytes, 0, bytes.length))
                    throw new IllegalArgumentException("UI_CHUNK_CONFLICT");
                return;
            }
            System.arraycopy(bytes, 0, body, offset, bytes.length); offset += bytes.length;
        }
        public byte[] finish() throws Exception {
            if (offset != body.length) throw new IllegalStateException("UI_TRANSFER_INCOMPLETE");
            if (!RuntimePackageCanonicalizer.sha256(body).equals(hash)) throw new IllegalArgumentException("UI_TRANSFER_HASH");
            return body.clone();
        }
    }
}
