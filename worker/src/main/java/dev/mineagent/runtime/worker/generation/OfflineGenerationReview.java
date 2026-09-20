package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.Objects;
import java.util.UUID;

/** Explicit local diagnostic only. No Provider, content-store write, signer, library or Native execution. */
public final class OfflineGenerationReview {
    private static final int MAX_RAW_BYTES=24*1024*1024;
    private OfflineGenerationReview() {}
    public record Report(String mode, UUID operationId, String rawSha256, int rawBytes, String stage,
                         boolean parserAccepted, String code, String diagnostic,
                         int providerCalls, boolean published, boolean businessVerified) {}

    public static Report inspect(Path raw, String expectedSha256, UUID operationId) throws Exception {
        Objects.requireNonNull(operationId,"operationId");
        if(expectedSha256==null||!expectedSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("RAW_HASH_REQUIRED");
        byte[] bytes;
        // Bound the actual read as well as the parser input; a file changing size cannot bypass the limit.
        try(var input=Files.newInputStream(raw)) { bytes=input.readNBytes(MAX_RAW_BYTES+1); }
        if(bytes.length>MAX_RAW_BYTES)throw new IllegalArgumentException("RAW_BYTE_LIMIT");
        String hash=RuntimePackageCanonicalizer.sha256(bytes);
        if(!hash.equals(expectedSha256))throw new IllegalArgumentException("RAW_HASH_MISMATCH");
        String source;
        try { source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException invalid){throw new IllegalArgumentException("RAW_UTF8_INVALID");}
        String stage="METADATA";
        try {
            String completed=GeneratedMetadata.complete(source);
            stage="PARSER";
            new RuntimePackageOutputParser().parse(completed);
            return new Report("OFFLINE_PARSE_ONLY",operationId,hash,bytes.length,stage,true,
                    "PARSE_ACCEPTED","Structural parse only; lifecycle, publication and business checks not performed.",0,false,false);
        } catch(PackageOutputException invalid) {
            // This diagnostic is untrusted source-derived text for an explicit local report, not a UI/Planner instruction.
            String diagnostic=Objects.toString(invalid.getMessage(),invalid.code());
            if(diagnostic.length()>2048)diagnostic=diagnostic.substring(0,2048);
            return new Report("OFFLINE_PARSE_ONLY",operationId,hash,bytes.length,stage,false,
                    invalid.code(),diagnostic,0,false,false);
        }
    }

    /** raw file, expected immutable SHA-256, original operation UUID, new report path. Rejections are report data. */
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Usage: OfflineGenerationReview raw.json sha256 operation-uuid new-report.json");
        var report=inspect(Path.of(args[0]),args[1],UUID.fromString(args[2]));
        Files.writeString(Path.of(args[3]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n",
                StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        System.out.println("OFFLINE_GENERATION_REVIEW code="+report.code()+" parserAccepted="+report.parserAccepted()+" providerCalls=0 published=false businessVerified=false");
    }
}
