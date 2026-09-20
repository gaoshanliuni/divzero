package dev.mineagent.runtime.core.packages;
import java.util.*;

/** Mutually exclusive ordinary/new-repair requests on the same trusted generation capability. */
public record PackageGenerationRequest(UUID agentId,String prompt,String purpose,UUID sourceOperation,long sourceRevision,String sourceSha256){
    public static PackageGenerationRequest parse(Map<String,String> args){try{
        boolean repair=args.keySet().stream().anyMatch(k->k.startsWith("repairSource")||k.equals("confirmed"));
        var expected=repair?Set.of("agentId","prompt","repairSourceOperationId","repairSourceRevision","repairSourceSha256","confirmed"):
                args.containsKey("purpose")?Set.of("agentId","prompt","purpose"):Set.of("agentId","prompt");
        if(!args.keySet().equals(expected))throw new IllegalArgumentException();
        var agent=UUID.fromString(args.get("agentId"));String prompt=args.get("prompt");
        if(prompt==null||prompt.isBlank()||prompt.length()>8192)throw new IllegalArgumentException();
        if(repair){long revision=Long.parseLong(args.get("repairSourceRevision"));String hash=args.get("repairSourceSha256");
            if(!"true".equals(args.get("confirmed"))||revision<1||revision>9007199254740991L||hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            return new PackageGenerationRequest(agent,prompt,"",UUID.fromString(args.get("repairSourceOperationId")),revision,hash);
        }
        String purpose=args.getOrDefault("purpose","UI_PACKAGE");if(!Set.of("UI_PACKAGE","WORLD_CONTENT").contains(purpose))throw new IllegalArgumentException();
        return new PackageGenerationRequest(agent,prompt,purpose,null,0,"");
    }catch(Exception invalid){throw new IllegalArgumentException("GENERATION_REQUEST_INVALID",invalid);}}
}
