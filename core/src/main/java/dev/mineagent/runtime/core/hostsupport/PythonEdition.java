package dev.mineagent.runtime.core.hostsupport;

import java.util.*;

/** Build identity shared by the Mod and its Worker; never probes system installations. */
public final class PythonEdition {
    private static final boolean BUNDLED=load();
    private static boolean load(){try(var in=PythonEdition.class.getResourceAsStream("/META-INF/divzero/edition.properties")){if(in==null)return false;var p=new Properties();p.load(in);return "bundled".equals(p.getProperty("python"));}catch(Exception error){throw new IllegalStateException("EDITION_METADATA_INVALID",error);}}
    public static boolean bundled(){return BUNDLED;}
    public static boolean hostTool(String name){return Set.of("inspect_host","read_host_output","python_execute","python_install_packages").contains(name);}
    public static Map<String,Object> unavailable(){return Map.of("status","REJECTED","error","PYTHON_UNSUPPORTED_EDITION","diagnostic","此版本不支持Python。游戏物品、界面、建筑等功能仍使用对应的游戏工具；不要尝试下载解释器或改用系统Python。","executionState","NOT_STARTED","worldModified",false);}
    public static String instruction(){return BUNDLED?"此版本内置完整 Windows x64 Python 与固定基础库，首次使用从JAR本地解压校验，不联网下载解释器。":"此版本不支持Python。Python运行时代码未包含在此构建中；不能下载、启用或改用系统Python。";}
    private PythonEdition(){}
}
