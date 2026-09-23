package dev.mineagent.runtime.core.host;
import java.util.*;
import java.nio.charset.StandardCharsets;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
/** Exact Python or pip approval payload; never interpreted by a command shell. */
public record HostCommandRequest(UUID operation,String purpose,String script,int timeoutSeconds,Kind kind,List<String> packages){
    public enum Kind { PYTHON, INSTALL_PACKAGES }
    public HostCommandRequest(UUID operation,String purpose,String script,int timeoutSeconds){this(operation,purpose,script,timeoutSeconds,Kind.PYTHON,List.of());}
    public HostCommandRequest{
        packages=List.copyOf(packages);if(operation==null||purpose==null||purpose.isBlank()||purpose.length()>200||kind==null||script==null||script.isBlank()||script.length()>8192||script.getBytes(StandardCharsets.UTF_8).length>24576||timeoutSeconds<1||timeoutSeconds>(kind==Kind.PYTHON?60:300))throw new IllegalArgumentException("HOST_COMMAND_ARGUMENTS");
        if(purpose.codePoints().anyMatch(c->Character.isISOControl(c)||bidi(c)))throw new IllegalArgumentException("HOST_COMMAND_PURPOSE");for(int c:script.codePoints().toArray())if(c==0||c<32&&c!='\n'&&c!='\r'&&c!='\t'||bidi(c))throw new IllegalArgumentException("HOST_COMMAND_CONTROL_CHARACTER");
        if(kind==Kind.PYTHON&&!packages.isEmpty())throw new IllegalArgumentException("HOST_PYTHON_PACKAGES");
        if(kind==Kind.INSTALL_PACKAGES){requirements(packages);if(!script.equals(installDisplay(packages)))throw new IllegalArgumentException("HOST_INSTALL_DISPLAY_MISMATCH");}
    }
    private static boolean bidi(int c){return c>=0x202a&&c<=0x202e||c>=0x2066&&c<=0x2069;}
    private static void requirements(List<String> values){if(values.isEmpty()||values.size()>16||new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException("PYTHON_PACKAGE_ARGUMENTS");for(String v:values)if(v.length()>160||!v.matches("[A-Za-z0-9][A-Za-z0-9._-]*(?:\\[[A-Za-z0-9_-]+(?:,[A-Za-z0-9_-]+)*\\])?(?:(?:===|==|!=|~=|>=|<=|>|<)[A-Za-z0-9][A-Za-z0-9.*+_-]*(?:,(?:==|!=|~=|>=|<=|>|<)[A-Za-z0-9][A-Za-z0-9.*+_-]*)*)?"))throw new IllegalArgumentException("PYTHON_PACKAGE_SPECIFIER");}
    private static String installDisplay(List<String> values){return "专用 Python 环境安装（PyPI）：\n"+String.join("\n",values);}
    public static HostCommandRequest install(UUID id,String purpose,List<String> values,int timeout){return new HostCommandRequest(id,purpose,installDisplay(values),timeout,Kind.INSTALL_PACKAGES,values);}
    public String sha256(){try{return RuntimePackageCanonicalizer.sha256("PYTHON_HOST_V1\n"+kind+"\n"+purpose+"\n"+timeoutSeconds+"\n"+script);}catch(Exception e){throw new IllegalStateException("HOST_COMMAND_HASH",e);}}
}
