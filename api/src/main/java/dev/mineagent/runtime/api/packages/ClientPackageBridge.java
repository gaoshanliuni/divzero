package dev.mineagent.runtime.api.packages;

import java.util.List;
import java.util.UUID;

/** Calls an explicitly declared, already-running CLIENT dependency through the neutral data contract. */
@FunctionalInterface
public interface ClientPackageBridge {
    Object call(UUID packageId,String exportName,List<?> arguments)throws Exception;
}
