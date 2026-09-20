package dev.mineagent.runtime.worker.generation;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.packages.PackageGenerationJob;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Rebuild the candidate from verified content, not arbitrary worker response metadata or paths. */
public record WorkerPackageResult(RuntimePackage runtimePackage, String providerId, String rawOutputSha256,String errorCode) {
    public WorkerPackageResult(RuntimePackage runtimePackage,String providerId,String rawOutputSha256){this(runtimePackage,providerId,rawOutputSha256,"");}
    public static WorkerPackageResult prepare(PackageGenerationJob job, WorkerEnvelope envelope,
            ContentAddressedStore content, IdentitySigner signer) throws Exception {
        if (envelope == null || !envelope.requestId().equals(job.operationId()) || !java.util.Set.of("runtime_package.result","runtime_package.failure").contains(envelope.type()))
            throw new IllegalArgumentException("WORKER_RESULT_CONTEXT");
        var p = envelope.payload();
        if (!job.worldId().toString().equals(p.get("worldId")) || !job.agentId().toString().equals(p.get("agentId"))
                || !job.taskId().toString().equals(p.get("taskId")) || !job.packageId().toString().equals(p.get("packageId"))
                || !Long.toString(job.taskIntentRevision()).equals(String.valueOf(p.get("taskRevision")))
                || !Long.toString(job.packageRevision()).equals(String.valueOf(p.get("packageRevision")))
                || !"GENERATED".equals(p.get("origin"))||!job.purpose().equals(p.getOrDefault("purpose","UI_PACKAGE"))
                || !(job.nativeSelection()==null?"":job.nativeSelection().fingerprint()).equals(p.getOrDefault("nativeSelectionHash",""))) throw new IllegalArgumentException("WORKER_RESULT_CONTEXT");
        var repair=job.repairSource();
        if(!(repair==null?"":repair.operationId().toString()).equals(p.getOrDefault("repairSourceOperationId",""))
                ||!(repair==null?"":repair.rawOutputSha256()).equals(p.getOrDefault("repairSourceSha256",""))
                ||!(repair==null?"":Long.toString(repair.jobRevision())).equals(String.valueOf(p.getOrDefault("repairSourceRevision",""))))throw new IllegalArgumentException("WORKER_REPAIR_SOURCE_CONTEXT");
        String rawHash = String.valueOf(p.get("rawOutputSha256"));
        long size = Long.parseLong(String.valueOf(p.get("rawOutputSize")));
        String provider = String.valueOf(p.get("providerId"));
        if (!provider.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("PROVIDER_ID");
        if(envelope.type().equals("runtime_package.failure")){
            if(size==0){if(!rawHash.isEmpty())throw new IllegalArgumentException("RAW_OUTPUT_SIZE");}
            else if(size<1||size>24*1024*1024||Files.size(content.pathFor(rawHash))!=size||content.read(rawHash).length!=size)throw new IllegalArgumentException("RAW_OUTPUT_SIZE");
            return new WorkerPackageResult(null,provider,rawHash,PackageGenerationFailure.normalize(p.get("code")));
        }
        if (size < 1 || size > 24 * 1024 * 1024 || Files.size(content.pathFor(rawHash)) != size)
            throw new IllegalArgumentException("RAW_OUTPUT_SIZE");
        byte[] raw = content.read(rawHash);
        if (raw.length != size) throw new IllegalArgumentException("RAW_OUTPUT_SIZE");
        var parsed = new RuntimePackageOutputParser().parse(GeneratedMetadata.complete(new String(raw, StandardCharsets.UTF_8)));
        for (var f : parsed.files()) {
            if (Files.size(content.pathFor(f.sha256())) != f.content().length || !Arrays.equals(f.content(), content.read(f.sha256())))
                throw new IllegalArgumentException("GENERATED_RESOURCE_MISMATCH");
        }
        return new WorkerPackageResult(RuntimePackagePublisher.prepare(parsed, job.packageId(), signer), provider, rawHash);
    }
}
