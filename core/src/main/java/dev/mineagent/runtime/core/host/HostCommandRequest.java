package dev.mineagent.runtime.core.host;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
/** Exact local approval payload. Not an authorization by itself. */
public record HostCommandRequest(UUID operation,String purpose,String script,int timeoutSeconds){
    public HostCommandRequest{if(operation==null||purpose==null||purpose.isBlank()||purpose.length()>200||script==null||script.isBlank()||script.length()>8192||script.getBytes(StandardCharsets.UTF_8).length>24576||timeoutSeconds<1||timeoutSeconds>60)throw new IllegalArgumentException("HOST_COMMAND_ARGUMENTS");if(purpose.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0x202a&&c<=0x202e||c>=0x2066&&c<=0x2069))throw new IllegalArgumentException("HOST_COMMAND_PURPOSE");for(int c:script.codePoints().toArray())if(c==0||c<32&&c!='\n'&&c!='\r'&&c!='\t'||c>=0x202a&&c<=0x202e||c>=0x2066&&c<=0x2069)throw new IllegalArgumentException("HOST_COMMAND_CONTROL_CHARACTER");}
    public String sha256(){try{return RuntimePackageCanonicalizer.sha256(purpose+"\n"+timeoutSeconds+"\n"+script);}catch(Exception e){throw new IllegalStateException("HOST_COMMAND_HASH",e);}}
}
