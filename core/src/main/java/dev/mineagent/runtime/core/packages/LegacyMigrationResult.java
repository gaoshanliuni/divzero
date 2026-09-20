package dev.mineagent.runtime.core.packages;

import java.nio.file.Path;

public record LegacyMigrationResult(int migratedCount, Path backupPath, LegacyMigrationReceipt receipt) {
}
