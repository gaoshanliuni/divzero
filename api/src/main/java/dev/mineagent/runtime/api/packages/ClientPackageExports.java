package dev.mineagent.runtime.api.packages;

import java.util.List;

/** Optional Java extension surface callable by declared CLIENT dependants without exposing its instance. */
@FunctionalInterface
public interface ClientPackageExports {
    Object call(String exportName,List<?> arguments)throws Exception;
}
