package dev.mineagent.runtime.scripting.javaext;

/** Marker enforced by the CLIENT-only classloader; SERVER RuntimeExtension artifacts are not accepted here. */
public interface ClientRuntimeExtension extends RuntimeExtension {
}
