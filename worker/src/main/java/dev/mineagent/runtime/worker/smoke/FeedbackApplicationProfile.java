package dev.mineagent.runtime.worker.smoke;
import java.nio.file.*;
/** Copies only explicitly selected local Provider settings into a fresh isolated profile; never prints a credential. */
public final class FeedbackApplicationProfile {
    private FeedbackApplicationProfile(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=3||!args[2].equals("deepseek-flash"))throw new IllegalArgumentException("FEEDBACK_MODEL_PROFILE_ARGUMENTS");
        Path source=Path.of(args[0]).toRealPath(),profile=Path.of(args[1]).toRealPath();
        if(!profile.getParent().getFileName().toString().equals("run-content-delivery")||!Files.isRegularFile(profile.resolve("profile.json")))throw new IllegalArgumentException("FEEDBACK_MODEL_PROFILE_SCOPE");
        java.util.UUID.fromString(profile.getFileName().toString());ProductionJointAppearanceLauncher.copyProvider(source,profile.resolve("server"),args[2]);
        System.out.println("FEEDBACK_APPLICATION_PROVIDER_PREPARED model="+args[2]);
    }
}
