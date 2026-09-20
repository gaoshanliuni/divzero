package dev.mineagent.runtime.neoforge.content;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.packs.PackType;
/** Identity of an actual FolderRepositorySource, not a file name or timestamp used as a process-liveness guess. */
public interface ManagedPackSourceIdentity { Path mineagent$folder(); PackType mineagent$packType(); UUID mineagent$sourceId(); }
