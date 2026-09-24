package dev.mineagent.runtime.neoforge;

import dev.mineagent.runtime.neoforge.body.MineAgentBodyManager;
import dev.mineagent.runtime.neoforge.worker.MineAgentWorkerSupervisor;
import dev.mineagent.runtime.core.permission.PermissionService;
import dev.mineagent.runtime.core.decision.DecisionService;
import dev.mineagent.runtime.core.conversation.ConversationManager;
import dev.mineagent.runtime.scripting.packagehost.RuntimePackageManager;
import net.minecraft.server.MinecraftServer;

import java.util.IdentityHashMap;
import java.util.Map;

public final class MineAgentRuntimeServices {
    private static final Map<MinecraftServer,dev.mineagent.runtime.neoforge.task.NativeSharedStateRuntime> SHARED_STATES=new IdentityHashMap<>();
    private static final Map<MinecraftServer,dev.mineagent.runtime.neoforge.task.NativeScheduleRuntime> SCHEDULES=new IdentityHashMap<>();
    private static final Map<MinecraftServer,dev.mineagent.runtime.neoforge.task.NativeEventRuntime> EVENTS=new IdentityHashMap<>();
    private static final Map<MinecraftServer, MineAgentBodyManager> BODIES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, MineAgentWorkerSupervisor> WORKERS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, PermissionService> PERMISSIONS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, DecisionService> DECISIONS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, ConversationManager> CONVERSATIONS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.agent.AgentPersonaService> PERSONAS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, RuntimePackageManager> PACKAGES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, java.util.UUID> WORLD_IDS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.config.ServerConfigService> CONFIGS =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.basketball.BasketballScoreTracker> BASKETBALL =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.task.TaskManager> TASKS =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.scripting.studio.CodeDraftService> CODE_DRAFTS =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.memory.PlayerPreferenceStore> PREFERENCES=new java.util.IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.memory.MemoryService> MEMORIES =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.crypto.IdentitySigner> IDENTITIES =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.recovery.SnapshotService> SNAPSHOTS =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.media.MediaService> MEDIA =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.packages.ContentPackageService> CONTENT_PACKAGES =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.audit.AuditLogService> AUDIT =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.neoforge.task.AgentTaskExecutor> TASK_EXECUTORS =
            new IdentityHashMap<>();
    private static final Map<MinecraftServer,dev.mineagent.runtime.core.task.AgentUiTaskLinks> UI_TASK_LINKS=new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.scripting.javaext.JavaExtensionManager>
            JAVA_EXTENSIONS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.neoforge.media.MineAgentMediaCoordinator>
            MEDIA_COORDINATORS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.recovery.ChangeJournalService>
            CHANGE_JOURNALS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, dev.mineagent.runtime.core.scoreboard.ScoreboardService> SCOREBOARDS =
            new IdentityHashMap<>();

    private MineAgentRuntimeServices() {
    }

    public static synchronized MineAgentBodyManager bodies(MinecraftServer server) {
        return BODIES.computeIfAbsent(server, MineAgentBodyManager::new);
    }
    public static synchronized void prepareBodyShutdown(MinecraftServer server){var bodies=BODIES.get(server);if(bodies!=null)bodies.prepareForServerStop();}

    public static synchronized dev.mineagent.runtime.neoforge.task.NativeSharedStateRuntime sharedStatesIfPresent(MinecraftServer server){return SHARED_STATES.get(server);}
    public static synchronized dev.mineagent.runtime.neoforge.task.NativeSharedStateRuntime sharedStates(MinecraftServer server){return SHARED_STATES.computeIfAbsent(server,dev.mineagent.runtime.neoforge.task.NativeSharedStateRuntime::new);}
    public static synchronized dev.mineagent.runtime.neoforge.task.NativeScheduleRuntime schedules(MinecraftServer server){return SCHEDULES.computeIfAbsent(server,dev.mineagent.runtime.neoforge.task.NativeScheduleRuntime::new);}
    public static synchronized dev.mineagent.runtime.neoforge.task.NativeEventRuntime events(MinecraftServer server){return EVENTS.computeIfAbsent(server,dev.mineagent.runtime.neoforge.task.NativeEventRuntime::new);}
    public static synchronized dev.mineagent.runtime.neoforge.task.NativeEventRuntime eventsIfPresent(MinecraftServer server){return EVENTS.get(server);}

    public static synchronized void remove(MinecraftServer server) {
        dev.mineagent.runtime.neoforge.ui.ServerStudioCoder.stopServer(server);
        dev.mineagent.runtime.neoforge.ui.StudioScriptRuntime.stopServer(server);
        var schedules=SCHEDULES.remove(server);if(schedules!=null)try{schedules.close();}catch(Exception failure){MineAgentRuntimeMod.LOGGER.warn("Failed to close schedule runtime",failure);}
        var events=EVENTS.remove(server);if(events!=null)try{events.close();}catch(Exception failure){MineAgentRuntimeMod.LOGGER.warn("Failed to close event runtime",failure);}
        var shared=SHARED_STATES.remove(server);if(shared!=null)try{shared.close();}catch(Exception failure){MineAgentRuntimeMod.LOGGER.warn("Failed to close shared state runtime",failure);}
        dev.mineagent.runtime.neoforge.ui.ServerConversations.stop(server);
        dev.mineagent.runtime.neoforge.content.NativeDataPackRuntime.stop(server);
        dev.mineagent.runtime.neoforge.content.WorldContentRuntime.stop(server);
        dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.stop(server);
        var taskExecutor=TASK_EXECUTORS.remove(server);if(taskExecutor!=null)taskExecutor.close();
        var uiLinks=UI_TASK_LINKS.remove(server);if(uiLinks!=null)try{uiLinks.close();}catch(Exception failure){MineAgentRuntimeMod.LOGGER.warn("Failed to close UI task links",failure);}
        MineAgentBodyManager bodies = BODIES.remove(server);
        if (bodies != null) {
            bodies.close();
        }
        PERMISSIONS.remove(server);
        var decisions = DECISIONS.remove(server);
        if (decisions != null) {
            try { decisions.close(); } catch (Exception failure) { MineAgentRuntimeMod.LOGGER.warn("Failed to close decision store", failure); }
        }
        CONVERSATIONS.remove(server);
        var personas=PERSONAS.remove(server);if(personas!=null)try{personas.close();}catch(Exception failure){MineAgentRuntimeMod.LOGGER.warn("Failed to close Agent persona store",failure);}
        var runtimePackages = PACKAGES.remove(server);
        if (runtimePackages != null) {
            try {
                runtimePackages.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to stop MineAgent script packages", failure);
            }
        }
        WORLD_IDS.remove(server);
        var config = CONFIGS.remove(server);
        if (config != null) {
            try {
                config.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent configuration database", failure);
            }
        }
        BASKETBALL.remove(server);
        var tasks = TASKS.remove(server);
        if (tasks != null) {
            try {
                tasks.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent task database", failure);
            }
        }
        var drafts = CODE_DRAFTS.remove(server);
        if (drafts != null) {
            try {
                drafts.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent code draft database", failure);
            }
        }
        var preferences=PREFERENCES.remove(server);if(preferences!=null)try{preferences.close();}catch(Exception e){MineAgentRuntimeMod.LOGGER.warn("Preference store close failed");}
        var memories = MEMORIES.remove(server);
        if (memories != null) {
            try {
                memories.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent memory database", failure);
            }
        }
        var identity = IDENTITIES.remove(server);
        if (identity != null) {
            identity.close();
        }
        var snapshots = SNAPSHOTS.remove(server);
        if (snapshots != null) {
            try {
                snapshots.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent snapshot database", failure);
            }
        }
        var media = MEDIA.remove(server);
        if (media != null) {
            try {
                media.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent media database", failure);
            }
        }
        var contentPackages = CONTENT_PACKAGES.remove(server);
        if (contentPackages != null) {
            try {
                contentPackages.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent content package database", failure);
            }
        }
        var audit = AUDIT.remove(server);
        if (audit != null) {
            try {
                audit.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent audit database", failure);
            }
        }
        TASK_EXECUTORS.remove(server);
        var javaExtensions = JAVA_EXTENSIONS.remove(server);
        if (javaExtensions != null) {
            try {
                javaExtensions.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to stop MineAgent Java extensions", failure);
            }
        }
        MEDIA_COORDINATORS.remove(server);
        var changeJournal = CHANGE_JOURNALS.remove(server);
        if (changeJournal != null) {
            try {
                changeJournal.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent change journal", failure);
            }
        }
        var scoreboard = SCOREBOARDS.remove(server);
        if (scoreboard != null) {
            try {
                scoreboard.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to close MineAgent scoreboard database", failure);
            }
        }
        MineAgentWorkerSupervisor worker = WORKERS.remove(server);
        if (worker != null) {
            try {
                worker.close();
            } catch (Exception failure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to stop MineAgent Worker", failure);
            }
        }
    }

    public static synchronized dev.mineagent.runtime.core.task.AgentUiTaskLinks agentUiLinks(MinecraftServer server){
        var existing=UI_TASK_LINKS.get(server);if(existing!=null)return existing;
        try{var created=dev.mineagent.runtime.core.task.AgentUiTaskLinks.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),worldId(server),java.time.Clock.systemUTC(),tasks(server));UI_TASK_LINKS.put(server,created);return created;}
        catch(Exception failure){throw new IllegalStateException("UI_TOOL_STORE_UNAVAILABLE",failure);}
    }
    public static synchronized MineAgentWorkerSupervisor worker(MinecraftServer server) {
        return WORKERS.computeIfAbsent(server, ignored -> new MineAgentWorkerSupervisor());
    }
    public static synchronized boolean workerReady(MinecraftServer server){var worker=WORKERS.get(server);return worker!=null&&worker.isAlive();}

    public static synchronized PermissionService permissions(MinecraftServer server) {
        var existing = PERMISSIONS.get(server);
        if (existing != null) {
            return existing;
        }
        var created = new PermissionService();
        config(server).snapshot().values().forEach((key, value) -> {
            if (!key.startsWith("permission.player.")) {
                return;
            }
            try {
                java.util.UUID playerId = java.util.UUID.fromString(key.substring("permission.player.".length()));
                var actions = java.util.Arrays.stream(value.split(","))
                        .map(String::strip).filter(action -> !action.isBlank())
                        .map(dev.mineagent.runtime.api.permission.PermissionAction::valueOf)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                created.setTrustedActions(playerId, actions);
            } catch (RuntimeException invalid) {
                MineAgentRuntimeMod.LOGGER.warn("Ignored invalid persisted permission entry {}", key);
            }
        });
        PERMISSIONS.put(server, created);
        return created;
    }

    public static synchronized DecisionService decisions(MinecraftServer server) {
        return DECISIONS.computeIfAbsent(server, ignored -> {
            try { return DecisionService.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"), worldId(server)); }
            catch (Exception failure) { throw new IllegalStateException("Cannot open decision store", failure); }
        });
    }

    public static synchronized ConversationManager conversations(MinecraftServer server) {
        return CONVERSATIONS.computeIfAbsent(server, ignored -> new ConversationManager());
    }
    public static synchronized dev.mineagent.runtime.core.agent.AgentPersonaService personas(MinecraftServer server){
        return PERSONAS.computeIfAbsent(server,ignored->{try{return dev.mineagent.runtime.core.agent.AgentPersonaService.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),worldId(server),java.time.Clock.systemUTC());}catch(Exception e){throw new IllegalStateException("PERSONA_STORE_UNAVAILABLE",e);}});
    }

    public static synchronized RuntimePackageManager packages(MinecraftServer server) {
        return PACKAGES.computeIfAbsent(server, ignored -> new RuntimePackageManager());
    }

    public static synchronized java.util.UUID worldId(MinecraftServer server) {
        return WORLD_IDS.computeIfAbsent(server,WorldIdentityRuntime::scope);
    }
    public static synchronized dev.mineagent.runtime.core.config.ServerConfigService config(MinecraftServer server) {
        var existing = CONFIGS.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.config.ServerConfigService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"));
            CONFIGS.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent configuration database", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.core.basketball.BasketballScoreTracker basketball(
            MinecraftServer server
    ) {
        return BASKETBALL.computeIfAbsent(server,
                ignored -> new dev.mineagent.runtime.core.basketball.BasketballScoreTracker());
    }

    public static synchronized dev.mineagent.runtime.core.task.TaskManager tasks(MinecraftServer server) {
        var existing = TASKS.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.task.TaskManager.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC());
            TASKS.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent task database", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.scripting.studio.CodeDraftService codeDrafts(
            MinecraftServer server
    ) {
        var existing = CODE_DRAFTS.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.scripting.studio.CodeDraftService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC(), packages(server));
            CODE_DRAFTS.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent code draft database", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.core.memory.PlayerPreferenceStore preferences(MinecraftServer server){
        var value=PREFERENCES.get(server);if(value!=null)return value;try{value=new dev.mineagent.runtime.core.memory.PlayerPreferenceStore(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),java.time.Clock.systemUTC());PREFERENCES.put(server,value);return value;}catch(Exception e){throw new IllegalStateException("PREFERENCE_STORE_UNAVAILABLE",e);}
    }
    public static synchronized dev.mineagent.runtime.core.memory.MemoryService memories(MinecraftServer server) {
        var existing = MEMORIES.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.memory.MemoryService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC());
            MEMORIES.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent memory database", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.core.crypto.IdentitySigner identity(MinecraftServer server) {
        var existing = IDENTITIES.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.crypto.IdentitySigner.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("identity"));
            IDENTITIES.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent server identity", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.core.recovery.SnapshotService snapshots(MinecraftServer server) {
        var existing = SNAPSHOTS.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.recovery.SnapshotService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC(), 10L * 1024 * 1024 * 1024,
                    java.time.Duration.ofDays(7));
            SNAPSHOTS.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent snapshot database", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.core.media.MediaService media(MinecraftServer server) {
        var existing = MEDIA.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.media.MediaService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC(),
                    mediaAllowedHosts(server));
            MEDIA.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent media database", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.core.packages.ContentPackageService contentPackages(
            MinecraftServer server
    ) {
        var existing = CONTENT_PACKAGES.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.packages.ContentPackageService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC(), identity(server).publicKeyEncoded());
            CONTENT_PACKAGES.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent content package database", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.core.audit.AuditLogService audit(MinecraftServer server) {
        var existing = AUDIT.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.audit.AuditLogService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC());
            AUDIT.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent audit database", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.core.scoreboard.ScoreboardService scoreboards(
            MinecraftServer server
    ) {
        var existing = SCOREBOARDS.get(server);
        if (existing != null) {
            return existing;
        }
        dev.mineagent.runtime.core.scoreboard.ScoreboardService created;
        try {
            created = dev.mineagent.runtime.core.scoreboard.ScoreboardService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC(),
                    new dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort(server));
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent scoreboard database", failure);
        }
        try {
            created.refreshSources();
            SCOREBOARDS.put(server, created);
            return created;
        } catch (Exception failure) {
            try { created.close(); } catch (Exception closing) { failure.addSuppressed(closing); }
            throw new IllegalStateException("cannot read MineAgent native scoreboard sources", failure);
        }
    }

    public static synchronized dev.mineagent.runtime.neoforge.task.AgentTaskExecutor taskExecutor(
            MinecraftServer server
    ) {
        return TASK_EXECUTORS.computeIfAbsent(server,
                dev.mineagent.runtime.neoforge.task.AgentTaskExecutor::new);
    }

    public static synchronized dev.mineagent.runtime.scripting.javaext.JavaExtensionManager javaExtensions(
            MinecraftServer server
    ) {
        return JAVA_EXTENSIONS.computeIfAbsent(server,
                ignored -> new dev.mineagent.runtime.scripting.javaext.JavaExtensionManager());
    }

    public static synchronized dev.mineagent.runtime.neoforge.media.MineAgentMediaCoordinator mediaCoordinator(
            MinecraftServer server
    ) {
        return MEDIA_COORDINATORS.computeIfAbsent(server,
                dev.mineagent.runtime.neoforge.media.MineAgentMediaCoordinator::new);
    }

    public static java.util.Set<String> mediaAllowedHosts(MinecraftServer server) {
        var hosts = new java.util.LinkedHashSet<String>();
        java.util.Arrays.stream(config(server).snapshot().values().getOrDefault("media.allowedHosts", "").split(","))
                .map(String::strip).filter(value -> !value.isBlank()).forEach(hosts::add);
        if (Boolean.getBoolean("mineagent.multiplayerSmokeServer")) {
            hosts.add("127.0.0.1");
        }
        return java.util.Set.copyOf(hosts);
    }

    public static synchronized dev.mineagent.runtime.core.recovery.ChangeJournalService changeJournal(
            MinecraftServer server
    ) {
        var existing = CHANGE_JOURNALS.get(server);
        if (existing != null) {
            return existing;
        }
        try {
            var created = dev.mineagent.runtime.core.recovery.ChangeJournalService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    worldId(server), java.time.Clock.systemUTC());
            CHANGE_JOURNALS.put(server, created);
            return created;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot open MineAgent change journal", failure);
        }
    }
}
