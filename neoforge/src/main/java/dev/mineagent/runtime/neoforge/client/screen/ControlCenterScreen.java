package dev.mineagent.runtime.neoforge.client.screen;

import dev.mineagent.runtime.api.config.PanelSection;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.client.control.AppearancePanelStatus;
import dev.mineagent.runtime.client.control.ControlCenterModel;
import dev.mineagent.runtime.core.crypto.SecretChannel;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.LinkedHashMap;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

public final class ControlCenterScreen extends Screen {
    private static final int NAV_WIDTH = 126;
    private final Screen parent;
    private final ControlCenterModel model;
    private final boolean operator;
    private String agentNameDraft = "Steve";
    private long observedSnapshotGeneration = -1;
    private String openAiBaseUrlDraft = "https://api.openai.com/v1";
    private String openAiModelDraft = "";
    private String ollamaBaseUrlDraft = "http://127.0.0.1:11434";
    private String ollamaModelDraft = "";
    private String openAiApiKeyDraft = "";
    private String comfyUiBaseUrlDraft = "http://127.0.0.1:8188";
    private String comfyUiWorkflowDraft = "";
    private String creatorPromptDraft = "";
    private int navigationOffset;
    private int providerPage;
    private MineAgentPayloads.PromptResult observedPromptResult = PanelSnapshotInbox.promptResult();
    private MineAgentPayloads.DecisionState observedDecisionState = PanelSnapshotInbox.decisionState();
    private final LinkedHashSet<String> decisionSelections = new LinkedHashSet<>();
    private String decisionCustomDraft = "";
    private String observedDecisionId = "";
    private int decisionOptionOffset;
    private MineAgentPayloads.ConversationState observedConversationState = PanelSnapshotInbox.conversationState();
    private long observedConversationStreamGeneration = -1;
    private int conversationPage;
    private int selectedConversationAgentIndex;
    private String conversationDraft = "";
    private boolean conversationPrivateResponse;
    private int agentPage;
    private int selectedAgentSettingsIndex;
    private String agentVoiceDraft = "zh-CN-XiaoxiaoNeural";
    private AgentMode agentCreateMode = AgentMode.CREATOR;
    private int agentSettingsDetailPage;
    private String agentRenameDraft = "";
    private String collaboratorDraft = "";
    private String veinMiningLimitDraft = "32";
    private MineAgentPayloads.TaskState observedTaskState = PanelSnapshotInbox.taskState();
    private int taskPage;
    private int taskAgentIndex;
    private int selectedTaskIndex;
    private String taskTitleDraft = "";
    private MineAgentPayloads.CodeState observedCodeState = PanelSnapshotInbox.codeState();
    private String requestedCodeDraftId="",requestedCodeWorld="";
    private MineAgentPayloads.MemoryState observedMemoryState = PanelSnapshotInbox.memoryState();
    private int memoryPage;
    private int selectedMemoryIndex;
    private dev.mineagent.runtime.api.memory.MemoryKind memoryKind =
            dev.mineagent.runtime.api.memory.MemoryKind.PLAYER_PREFERENCE;
    private String memoryKeyDraft = "";
    private String memoryValueDraft = "";
    private String trustError = "";
    private MineAgentPayloads.ModKnowledgeState observedModKnowledgeState = PanelSnapshotInbox.modKnowledgeState();
    private int selectedModIndex;
    private MineAgentPayloads.BackupState observedBackupState = PanelSnapshotInbox.backupState();
    private int backupPage;
    private int selectedSnapshotIndex;
    private int selectedChangeIndex;
    private int snapshotRadius = 4;
    private String snapshotLabelDraft = "施工前快照";
    private MineAgentPayloads.MediaState observedMediaState = PanelSnapshotInbox.mediaState();
    private int mediaPage;
    private int selectedMediaIndex;
    private dev.mineagent.runtime.api.media.MediaKind mediaKind = dev.mineagent.runtime.api.media.MediaKind.URL;
    private String mediaTitleDraft = "";
    private String mediaUrlDraft = "";
    private String mediaAllowedHostsDraft = "";
    private MineAgentPayloads.AppearanceState observedAppearanceState = PanelSnapshotInbox.appearanceState();
    private int appearanceAgentIndex;
    private String appearanceModelDraft = "";
    private String appearanceTextureDraft = "";
    private String appearanceAnimationDraft = "";
    private MineAgentPayloads.PackageState observedPackageState = PanelSnapshotInbox.packageState();
    private int selectedPackageIndex;
    private int packagePage;
    private String packageTransferDraft = "";
    private int creatorMode;
    private MineAgentPayloads.PermissionState observedPermissionState = PanelSnapshotInbox.permissionState();
    private int permissionPage;
    private String permissionPlayerDraft = "";
    private dev.mineagent.runtime.api.permission.PermissionAction permissionAction =
            dev.mineagent.runtime.api.permission.PermissionAction.CREATE_AGENT;
    private MineAgentPayloads.DiagnosticsState observedDiagnosticsState = PanelSnapshotInbox.diagnosticsState();
    private int selectedDiagnosticIndex;

    private ControlCenterScreen(Screen parent, boolean operator) {
        this(parent,operator,operator?ControlCenterModel.forOperator():ControlCenterModel.forRegularPlayer());
    }
    private ControlCenterScreen(Screen parent,boolean operator,ControlCenterModel model){
        super(Component.translatable("screen.mineagent_runtime.control_center"));
        this.parent = parent;
        this.operator = operator;
        this.model = model;
    }

    public static ControlCenterScreen create(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean operator = minecraft.player == null
                || minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return new ControlCenterScreen(parent, operator);
    }

    public static ControlCenterScreen create(Screen parent, PanelSection section) {
        ControlCenterScreen screen = create(parent);
        screen.model.select(section);
        return screen;
    }
    public static ControlCenterScreen forCoderDraft(Screen parent,String draftId,String world){
        java.util.UUID.fromString(draftId);java.util.UUID.fromString(world);var screen=create(parent,PanelSection.CODE_STUDIO);
        screen.requestedCodeDraftId=draftId;screen.requestedCodeWorld=world;return screen;
    }
    /** The inline editor is retired; mutable source now belongs to NativeCodeStudioScreen. */
    @Deprecated public boolean hasUnsavedCodeDraft(){return false;}

    public static ControlCenterScreen workflowEditor(Screen parent){
        if(!Boolean.parseBoolean(PanelSnapshotInbox.snapshot().values().getOrDefault("permission.manage_providers","false")))throw new SecurityException("PROVIDER_EDITOR_FORBIDDEN");
        var screen=new ControlCenterScreen(parent,false,ControlCenterModel.forProviderManager());screen.model.select(PanelSection.PROVIDERS);screen.providerPage=3;return screen;
    }

    @Override
    protected void init() {
        int navX = 12;
        int navY = 32;
        int buttonHeight = 20;
        int visibleNavigationItems = Math.max(4, (this.height - 100) / buttonHeight);
        var visibleSections = model.page(navigationOffset, visibleNavigationItems);

        addRenderableWidget(new StringWidget(
                navX, 10, Math.max(200, this.width - 24), 16,
                this.title, this.font
        ));

        for (int index = 0; index < visibleSections.size(); index++) {
                    PanelSection section = visibleSections.get(index);
            Button button = Button.builder(Component.literal(section.displayName()), ignored -> {
                        model.select(section);
                        if (section == PanelSection.CONVERSATIONS && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.DecisionRequestPayload());
                            requestSelectedConversation();
                        }
                        if (section == PanelSection.TASKS && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.TaskCommand("refresh", Map.of()));
                        }
                        if (section == PanelSection.MEMORY && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.MemoryCommand("refresh", Map.of()));
                        }
                        if (section == PanelSection.MOD_KNOWLEDGE && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.ModKnowledgeRequest());
                        }
                        if (section == PanelSection.BACKUPS && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.BackupCommand("refresh", Map.of()));
                        }
                        if (section == PanelSection.MEDIA && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.MediaCommand("refresh", Map.of()));
                        }
                        if (section == PanelSection.PACKAGES && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.PackageCommand("refresh", Map.of()));
                        }
                        if (section == PanelSection.PERMISSIONS && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.PermissionCommand("refresh", Map.of()));
                        }
                        if (section == PanelSection.DIAGNOSTICS && this.minecraft.getConnection() != null) {
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.DiagnosticsRequest());
                        }
                        rebuildWidgets();
                    })
                    .bounds(navX, navY + index * buttonHeight, NAV_WIDTH, buttonHeight - 1)
                    .build();
            button.active = section != model.selectedSection();
            addRenderableWidget(button);
        }
        Button previousPage = Button.builder(Component.literal("▲"), ignored -> {
                    navigationOffset = Math.max(0, navigationOffset - visibleNavigationItems);
                    rebuildWidgets();
                })
                .bounds(navX, this.height - 52, 60, 18)
                .build();
        previousPage.active = navigationOffset > 0;
        addRenderableWidget(previousPage);
        Button nextPage = Button.builder(Component.literal("▼"), ignored -> {
                    navigationOffset = Math.min(
                            Math.max(0, model.sections().size() - visibleNavigationItems),
                            navigationOffset + visibleNavigationItems
                    );
                    rebuildWidgets();
                })
                .bounds(navX + 66, this.height - 52, 60, 18)
                .build();
        nextPage.active = navigationOffset + visibleSections.size() < model.sections().size();
        addRenderableWidget(nextPage);

        int contentX = navX + NAV_WIDTH + 18;
        int contentWidth = Math.max(120, this.width - contentX - 18);
        addRenderableWidget(new StringWidget(
                contentX, 38, contentWidth, 20,
                Component.literal(model.selectedSection().displayName()), this.font
        ));
        addRenderableWidget(new StringWidget(
                contentX, 68, contentWidth, 20,
                Component.literal("配置版本: " + PanelSnapshotInbox.snapshot().revision()), this.font
        ));
        if (model.selectedSection() != PanelSection.CONVERSATIONS) {
            addRenderableWidget(new StringWidget(
                    contentX, 92, contentWidth, 20,
                    Component.translatable("screen.mineagent_runtime.server_authoritative"), this.font
            ));
        }

        if (model.selectedSection() == PanelSection.AGENTS) {
            addAgentControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.OVERVIEW) {
            addOverviewControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.CONVERSATIONS) {
            addConversationControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.TASKS) {
            addTaskControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.CODE_STUDIO) {
            addCodeStudioControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.MEMORY) {
            addMemoryControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.PERMISSIONS) {
            addPermissionControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.MOD_KNOWLEDGE) {
            addModKnowledgeControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.BACKUPS) {
            addBackupControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.MEDIA) {
            addMediaControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.APPEARANCE) {
            addAppearanceControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.PACKAGES) {
            addPackageControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.DIAGNOSTICS) {
            addDiagnosticsControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.CREATOR) {
            addCreatorControls(contentX, contentWidth);
        } else if (model.selectedSection() == PanelSection.PROVIDERS) {
            addProviderControls(contentX, contentWidth);
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose())
                .bounds(Math.max(12, this.width - 112), this.height - 22, 100, 18)
                .build());
    }

    private void addAgentControls(int contentX, int contentWidth) {
        int tabWidth = Math.max(56, Math.min(90, (contentWidth - 4) / 2));
        Button createTab = Button.builder(Component.literal("创建"), ignored -> {
                    agentPage = 0;
                    rebuildWidgets();
                }).bounds(contentX, 108, tabWidth, 18).build();
        createTab.active = agentPage != 0;
        addRenderableWidget(createTab);
        Button settingsTab = Button.builder(Component.literal("设置"), ignored -> {
                    agentPage = 1;
                    loadSelectedAgentVoice();
                    rebuildWidgets();
                }).bounds(contentX + tabWidth + 4, 108, tabWidth, 18).build();
        settingsTab.active = agentPage != 1;
        addRenderableWidget(settingsTab);
        if (agentPage == 1) {
            addAgentSettingsControls(contentX, contentWidth);
            return;
        }
        addRenderableWidget(new StringWidget(
                contentX, 126, contentWidth, 18,
                Component.translatable("screen.mineagent_runtime.agent_name"), this.font
        ));
        EditBox name = new EditBox(
                this.font, contentX, 148, Math.min(220, contentWidth), 20,
                Component.translatable("screen.mineagent_runtime.agent_name")
        );
        name.setMaxLength(32);
        name.setValue(agentNameDraft);
        name.setResponder(value -> agentNameDraft = value);
        addRenderableWidget(name);

        Button create = Button.builder(
                        Component.translatable("screen.mineagent_runtime.create_agent"),
                        ignored -> createAgent()
                )
                .bounds(contentX + Math.min(100, contentWidth / 2) + 4, 178,
                        Math.max(60, Math.min(130, contentWidth - Math.min(100, contentWidth / 2) - 4)), 20)
                .build();
        create.active = Boolean.parseBoolean(PanelSnapshotInbox.snapshot().values()
                .getOrDefault("permission.create_agent", Boolean.toString(operator))) && !agentNameDraft.isBlank();
        addRenderableWidget(create);
        addRenderableWidget(Button.builder(Component.literal(
                        agentCreateMode == AgentMode.CREATOR ? "模式: 创造" : "模式: 生存"), ignored -> {
                    agentCreateMode = agentCreateMode == AgentMode.CREATOR ? AgentMode.SURVIVAL : AgentMode.CREATOR;
                    rebuildWidgets();
                }).bounds(contentX, 178, Math.min(100, contentWidth / 2), 20).build());
    }

    private void addOverviewControls(int contentX, int contentWidth) {
        Map<String, String> values = PanelSnapshotInbox.snapshot().values();
        String[] lines = {
                "Worker: " + (Boolean.parseBoolean(values.getOrDefault("runtime.workerAlive", "false")) ? "READY" : "OFFLINE"),
                "AI 玩家: " + values.getOrDefault("agent.count", "0") + " / "
                        + values.getOrDefault("runtime.maxAgents", "4"),
                "任务: " + values.getOrDefault("runtime.taskCount", "0")
                        + "  代码草稿: " + values.getOrDefault("runtime.codeDraftCount", "0"),
                "记忆: " + values.getOrDefault("runtime.memoryCount", "0")
                        + "  媒体: " + values.getOrDefault("runtime.mediaCount", "0"),
                "内容包: " + values.getOrDefault("runtime.packageCount", "0")
        };
        int y = 116;
        for (String line : lines) {
            addRenderableWidget(new StringWidget(contentX, y, contentWidth, 16,
                    Component.literal(line), this.font).setMaxWidth(contentWidth));
            y += 19;
        }
    }

    private void addAppearanceControls(int contentX, int contentWidth) {
        Map<String, String> values = PanelSnapshotInbox.snapshot().values();
        String ysmStatus = !Boolean.parseBoolean(values.getOrDefault("ysm.installed", "false"))
                ? "未安装 YSM"
                : Boolean.parseBoolean(values.getOrDefault("ysm.runtimeAvailable", "false"))
                ? "YSM READY " + values.getOrDefault("ysm.version", "")
                : "YSM 不可用 " + values.getOrDefault("ysm.version", "") + " "
                + values.getOrDefault("ysm.diagnostic", "");
        if (Boolean.parseBoolean(values.getOrDefault("ysm.installed", "false"))) {
            ysmStatus += " · BOOT_EXTENSION，停用/卸载需重启";
        }
        addRenderableWidget(new StringWidget(contentX, 112, contentWidth, 14,
                Component.literal(ysmStatus), this.font).setMaxWidth(contentWidth));
        int count = parseBoundedInt(values.get("agent.count"), 0, 4, 0);
        if (count == 0) {
            return;
        }
        appearanceAgentIndex = Math.min(appearanceAgentIndex, count - 1);
        String prefix = "agent." + appearanceAgentIndex + ".";
        String agentId = values.getOrDefault(prefix + "id", "");
        addRenderableWidget(Button.builder(Component.literal("AI 玩家: "
                        + values.getOrDefault(prefix + "name", "AI")), ignored -> {
                    appearanceAgentIndex = (appearanceAgentIndex + 1) % count;
                    loadAppearanceDraft();
                    rebuildWidgets();
                }).bounds(contentX, 128, contentWidth, 18).build());
        int editorWidth = Math.max(70, contentWidth - 88);
        EditBox model = new EditBox(this.font, contentX, 148, editorWidth, 18, Component.literal("YSM 模型 ID"));
        model.setMaxLength(256);
        model.setHint(Component.literal("YSM 模型 ID"));
        model.setValue(appearanceModelDraft);
        model.setResponder(value -> appearanceModelDraft = value);
        addRenderableWidget(model);
        addRenderableWidget(Button.builder(Component.literal("模型列表"), ignored -> {
                    appearanceModelDraft = nextChoice(values.get("ysm.modelChoices"), appearanceModelDraft, "default");
                    rebuildWidgets();
                }).bounds(contentX + editorWidth + 4, 148, 84, 18).build());
        EditBox texture = new EditBox(this.font, contentX, 168, editorWidth, 18, Component.literal("贴图 ID（可空）"));
        texture.setMaxLength(256);
        texture.setHint(Component.literal("贴图 ID（可空）"));
        texture.setValue(appearanceTextureDraft);
        texture.setResponder(value -> appearanceTextureDraft = value);
        addRenderableWidget(texture);
        addRenderableWidget(Button.builder(Component.literal("贴图列表"), ignored -> {
                    appearanceTextureDraft = nextChoice(values.get("ysm.textureChoices"), appearanceTextureDraft, "default");
                    rebuildWidgets();
                }).bounds(contentX + editorWidth + 4, 168, 84, 18).build());
        EditBox animation = new EditBox(this.font, contentX, 188, editorWidth, 18,
                Component.literal("动画 ID（可空）"));
        animation.setMaxLength(256);
        animation.setHint(Component.literal("动画 ID（可空）"));
        animation.setValue(appearanceAnimationDraft);
        animation.setResponder(value -> appearanceAnimationDraft = value);
        addRenderableWidget(animation);
        addRenderableWidget(Button.builder(Component.literal("动画列表"), ignored -> {
                    appearanceAnimationDraft = nextChoice(values.get("ysm.animationChoices"), appearanceAnimationDraft, "idle");
                    rebuildWidgets();
                }).bounds(contentX + editorWidth + 4, 188, 84, 18).build());
        Button apply = Button.builder(Component.literal("应用并预览"), ignored -> submitAppearance(agentId))
                .bounds(contentX, 208, Math.min(100, contentWidth), 18).build();
        apply.active = !appearanceModelDraft.isBlank()
                && Boolean.parseBoolean(values.getOrDefault(prefix + "mutable", "false"));
        addRenderableWidget(apply);
        Button choice = Button.builder(Component.literal("选择卡"), ignored -> {
                    ClientPacketDistributor.sendToServer(new MineAgentPayloads.DecisionCommand(
                            "openAppearance", Map.of("agentId", agentId)));
                    this.model.select(PanelSection.CONVERSATIONS);
                    conversationPage = 1;
                    rebuildWidgets();
                }).bounds(contentX + 104, 208, Math.min(64, Math.max(40, contentWidth - 104)), 18).build();
        choice.active = Boolean.parseBoolean(values.getOrDefault(prefix + "mutable", "false"));
        addRenderableWidget(choice);
        var state = PanelSnapshotInbox.appearanceState();
        String diagnostic = AppearancePanelStatus.formatForAgent(
                values, "agent." + agentId + ".", agentId, state.agentId(),
                state.accepted(), state.errorCode(), state.revision());
        addRenderableWidget(new StringWidget(contentX + 172, 208, Math.max(16, contentWidth - 172), 18,
                Component.literal(diagnostic), this.font).setMaxWidth(Math.max(16, contentWidth - 172)));
    }

    private static String nextChoice(String encoded, String current, String fallback) {
        var choices = java.util.Arrays.stream((encoded == null ? "" : encoded).split(","))
                .map(String::strip).filter(value -> !value.isBlank()).distinct().toList();
        if (choices.isEmpty()) {
            return fallback;
        }
        int index = choices.indexOf(current);
        return choices.get((index + 1) % choices.size());
    }

    private void loadAppearanceDraft() {
        Map<String, String> values = PanelSnapshotInbox.snapshot().values();
        String agentId = values.get("agent." + appearanceAgentIndex + ".id");
        if (agentId != null) {
            var draft = AppearancePanelStatus.requestedDraft(values, "agent." + agentId + ".");
            appearanceModelDraft = draft.modelId();
            appearanceTextureDraft = draft.textureId();
            appearanceAnimationDraft = draft.animationId();
        }
    }

    private void submitAppearance(String agentId) {
        String expectedRevision = PanelSnapshotInbox.snapshot().values()
                .getOrDefault("agent." + agentId + ".appearanceRevision", "0");
        ClientPacketDistributor.sendToServer(new MineAgentPayloads.AppearanceCommand(
                agentId,
                appearanceModelDraft.strip(),
                appearanceTextureDraft.strip(),
                appearanceAnimationDraft.strip(),
                Long.parseLong(expectedRevision),
                UUID.randomUUID().toString()
        ));
    }

    public boolean runYsmAppearanceUiSmoke(String modelId, String textureId, String animationId) {
        Map<String, String> values = PanelSnapshotInbox.snapshot().values();
        if (!Boolean.parseBoolean(values.getOrDefault("ysm.runtimeAvailable", "false"))
                || parseBoundedInt(values.get("agent.count"), 0, 4, 0) == 0
                || !Boolean.parseBoolean(values.getOrDefault("agent.0.mutable", "false"))) {
            return false;
        }
        model.select(PanelSection.APPEARANCE);
        appearanceAgentIndex = 0;
        appearanceModelDraft = modelId;
        appearanceTextureDraft = textureId;
        appearanceAnimationDraft = animationId;
        rebuildWidgets();
        for (var child : children()) {
            if (child instanceof Button button && "应用并预览".equals(button.getMessage().getString())) {
                if (!button.active) {
                    return false;
                }
                press(button);
                return true;
            }
        }
        return false;
    }

    public boolean runYsmChoiceCardOpenSmoke() {
        model.select(PanelSection.APPEARANCE);
        rebuildWidgets();
        for (var child : children()) {
            if (child instanceof Button button && "选择卡".equals(button.getMessage().getString()) && button.active) {
                press(button);
                return true;
            }
        }
        return false;
    }

    public boolean runYsmFirstChoiceSubmitSmoke() {
        Map<String, String> state = PanelSnapshotInbox.decisionState().values();
        if (!"AI 外观选择".equals(state.get("title"))) {
            return false;
        }
        model.select(PanelSection.CONVERSATIONS);
        conversationPage = 1;
        rebuildWidgets();
        for (var child : children()) {
            if (child instanceof Button button && button.getMessage().getString().startsWith("[ ] ")) {
                press(button);
                break;
            }
        }
        for (var child : children()) {
            if (child instanceof Button button && "提交".equals(button.getMessage().getString()) && button.active) {
                press(button);
                return true;
            }
        }
        return false;
    }

    private static void press(Button button) {
        button.onPress(new net.minecraft.client.input.InputWithModifiers() {
            @Override
            public int input() {
                return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
            }

            @Override
            public int modifiers() {
                return 0;
            }
        });
    }

    private void addPackageControls(int contentX, int contentWidth) {
        Map<String, String> state = PanelSnapshotInbox.packageState().values();
        int tabWidth = Math.max(54, Math.min(90, (contentWidth - 4) / 2));
        Button manageTab = Button.builder(Component.literal("管理"), ignored -> {
            packagePage = 0;
            rebuildWidgets();
        }).bounds(contentX, 108, tabWidth, 18).build();
        manageTab.active = packagePage != 0;
        addRenderableWidget(manageTab);
        Button transferTab = Button.builder(Component.literal("导入/导出"), ignored -> {
            packagePage = 1;
            rebuildWidgets();
        }).bounds(contentX + tabWidth + 4, 108, tabWidth, 18).build();
        transferTab.active = packagePage != 1;
        addRenderableWidget(transferTab);
        if (packagePage == 1) {
            MultiLineEditBox transfer = MultiLineEditBox.builder()
                    .setX(contentX).setY(132).setPlaceholder(Component.literal("粘贴签名内容包 JSON"))
                    .build(this.font, contentWidth, 58, Component.literal("内容包 JSON"));
            transfer.setCharacterLimit(32_000);
            transfer.setValue(packageTransferDraft);
            transfer.setValueListener(value -> packageTransferDraft = value);
            addRenderableWidget(transfer);
            Button importButton = Button.builder(Component.literal("验证并导入/迁移"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.PackageCommand(
                                    "import", Map.of("json", packageTransferDraft))))
                    .bounds(contentX, 194, Math.min(140, contentWidth), 18).build();
            importButton.active = !packageTransferDraft.isBlank();
            addRenderableWidget(importButton);
            addRenderableWidget(new StringWidget(contentX, 216, contentWidth, 14,
                    Component.literal(PanelSnapshotInbox.packageState().errorCode().isBlank()
                            ? "导入时校验依赖、版本、SHA-256 与 Ed25519 签名"
                            : "错误: " + PanelSnapshotInbox.packageState().errorCode()), this.font)
                    .setMaxWidth(contentWidth));
            return;
        }
        int count = parseBoundedInt(state.get("packageCount"), 0, 20, 0);
        if (count == 0) {
            addRenderableWidget(new StringWidget(contentX, 134, contentWidth, 16,
                    Component.literal("暂无已发布内容包"), this.font));
            addRenderableWidget(Button.builder(Component.literal("打开 Code Studio"), ignored -> {
                        model.select(PanelSection.CODE_STUDIO);
                        ClientPacketDistributor.sendToServer(new MineAgentPayloads.CodeCommand("refresh", Map.of()));
                        rebuildWidgets();
                    }).bounds(contentX, 158, Math.min(130, contentWidth), 18).build());
            return;
        }
        selectedPackageIndex = Math.min(selectedPackageIndex, count - 1);
        String prefix = "package." + selectedPackageIndex + ".";
        addRenderableWidget(Button.builder(Component.literal(state.getOrDefault(prefix + "name", "内容包")), ignored -> {
                    selectedPackageIndex = (selectedPackageIndex + 1) % count;
                    rebuildWidgets();
                }).bounds(contentX, 132, contentWidth, 18).build());
        addRenderableWidget(new StringWidget(contentX, 154, contentWidth, 16,
                Component.literal("版本 " + state.getOrDefault(prefix + "version", "") + "  "
                        + state.getOrDefault(prefix + "mode", "")), this.font));
        addRenderableWidget(new StringWidget(contentX, 172, contentWidth, 16,
                Component.literal("依赖: " + state.getOrDefault(prefix + "dependencies", "0")
                        + "  SHA: " + abbreviate(state.getOrDefault(prefix + "sha256", ""), 18)), this.font));
        boolean enabled = Boolean.parseBoolean(state.getOrDefault(prefix + "enabled", "false"));
        Button toggle = Button.builder(Component.literal(enabled ? "停用" : "启用"), ignored ->
                        ClientPacketDistributor.sendToServer(new MineAgentPayloads.PackageCommand("toggle", Map.of(
                                "packageId", state.getOrDefault(prefix + "id", ""),
                                "expectedRevision", state.getOrDefault(prefix + "revision", "0"),
                                "enabled", Boolean.toString(!enabled)))))
                .bounds(contentX, 192, Math.min(80, contentWidth), 18).build();
        toggle.active = Boolean.parseBoolean(PanelSnapshotInbox.snapshot().values()
                .getOrDefault("permission.manage_packages", "false"));
        addRenderableWidget(toggle);
        Button export = Button.builder(Component.literal("导出 JSON"), ignored ->
                        ClientPacketDistributor.sendToServer(new MineAgentPayloads.PackageCommand("export", Map.of(
                                "packageId", state.getOrDefault(prefix + "id", "")))))
                .bounds(contentX + 84, 192, Math.min(90, Math.max(40, contentWidth - 84)), 18).build();
        export.active = toggle.active;
        addRenderableWidget(export);
        addRenderableWidget(new StringWidget(contentX, 214, contentWidth, 14,
                Component.literal(PanelSnapshotInbox.packageState().errorCode().isBlank()
                        ? "签名与哈希已校验" : "错误: " + PanelSnapshotInbox.packageState().errorCode()), this.font));
    }

    private void addDiagnosticsControls(int contentX, int contentWidth) {
        Map<String, String> values = PanelSnapshotInbox.snapshot().values();
        Map<String, String> diagnostics = PanelSnapshotInbox.diagnosticsState().values();
        addRenderableWidget(new StringWidget(contentX, 112, contentWidth, 16,
                Component.literal("Worker=" + values.getOrDefault("runtime.workerAlive", "false")
                        + " 签名=" + PanelSnapshotInbox.signatureValid()
                        + " Threads=" + diagnostics.getOrDefault("threadCount", "?")), this.font));
        addRenderableWidget(new StringWidget(contentX, 130, contentWidth, 16,
                Component.literal("内存: " + diagnostics.getOrDefault("usedMemoryBytes", "?")
                        + "  Config错误: " + emptyAsNone(PanelSnapshotInbox.lastErrorCode())), this.font));
        int count = parseBoundedInt(diagnostics.get("eventCount"), 0, 10, 0);
        if (count > 0) {
            selectedDiagnosticIndex = Math.min(selectedDiagnosticIndex, count - 1);
            String prefix = "event." + selectedDiagnosticIndex + ".";
            addRenderableWidget(Button.builder(Component.literal(
                            diagnostics.getOrDefault(prefix + "action", "事件") + " @ "
                                    + diagnostics.getOrDefault(prefix + "target", "")), ignored -> {
                        selectedDiagnosticIndex = (selectedDiagnosticIndex + 1) % count;
                        rebuildWidgets();
                    }).bounds(contentX, 150, contentWidth, 18).build());
            addRenderableWidget(new StringWidget(contentX, 170, contentWidth, 16,
                    Component.literal("Actor: " + diagnostics.getOrDefault(prefix + "actor", "")), this.font));
            addRenderableWidget(new StringWidget(contentX, 186, contentWidth, 16,
                    Component.literal(diagnostics.getOrDefault(prefix + "payload", "")), this.font)
                    .setMaxWidth(contentWidth));
        }
        addRenderableWidget(Button.builder(Component.literal("刷新诊断"), ignored ->
                        ClientPacketDistributor.sendToServer(new MineAgentPayloads.DiagnosticsRequest()))
                .bounds(contentX, 202, Math.min(90, contentWidth), 14).build());
    }

    private static String emptyAsNone(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    private void addAgentSettingsControls(int contentX, int contentWidth) {
        Map<String, String> snapshot = PanelSnapshotInbox.snapshot().values();
        int count = parseBoundedInt(snapshot.get("agent.count"), 0, 4, 0);
        if (count == 0) {
            addRenderableWidget(new StringWidget(contentX, 132, contentWidth, 18,
                    Component.literal("尚未创建 AI 玩家"), this.font));
            return;
        }
        selectedAgentSettingsIndex = Math.min(selectedAgentSettingsIndex, count - 1);
        String prefix = "agent." + selectedAgentSettingsIndex + ".";
        String name = snapshot.getOrDefault(prefix + "name", "AI 玩家");
        addRenderableWidget(Button.builder(Component.literal("AI 玩家: " + name), ignored -> {
                    selectedAgentSettingsIndex = (selectedAgentSettingsIndex + 1) % count;
                    loadSelectedAgentVoice();
                    rebuildWidgets();
                }).bounds(contentX, 130, contentWidth, 18).build());
        String detailTitle = switch (agentSettingsDetailPage) {
            case 0 -> "基本设置";
            case 1 -> "语音设置";
            case 2 -> "协作者";
            default -> "技能";
        };
        addRenderableWidget(Button.builder(Component.literal(detailTitle + " ▶"), ignored -> {
                    agentSettingsDetailPage = (agentSettingsDetailPage + 1) % 4;
                    rebuildWidgets();
                }).bounds(contentX, 150, Math.min(100, contentWidth), 16).build());
        boolean mutable = Boolean.parseBoolean(snapshot.getOrDefault(prefix + "mutable", "false"));
        String agentId = snapshot.getOrDefault(prefix + "id", "");
        if (agentSettingsDetailPage == 0) {
            EditBox rename = new EditBox(this.font, contentX, 168, contentWidth, 18, Component.literal("显示名称"));
            rename.setMaxLength(32);
            rename.setValue(agentRenameDraft);
            rename.setResponder(value -> agentRenameDraft = value);
            addRenderableWidget(rename);
            int width = Math.max(36, (contentWidth - 12) / 4);
            Button renameButton = Button.builder(Component.literal("重命名"), ignored -> sendAgentAction(
                            "rename", Map.of("agentId", agentId, "name", agentRenameDraft.strip())))
                    .bounds(contentX, 190, width, 18).build();
            renameButton.active = mutable && !agentRenameDraft.isBlank();
            addRenderableWidget(renameButton);
            String mode = snapshot.getOrDefault(prefix + "mode", "CREATOR");
            Button modeButton = Button.builder(Component.literal("CREATOR".equals(mode) ? "转生存" : "转创造"), ignored ->
                            sendAgentAction("set_mode", Map.of("agentId", agentId, "mode",
                                    "CREATOR".equals(mode) ? "SURVIVAL" : "CREATOR")))
                    .bounds(contentX + width + 4, 190, width, 18).build();
            modeButton.active = mutable;
            addRenderableWidget(modeButton);
            Button follow = Button.builder(Component.literal("跟随"), ignored ->
                            sendAgentAction("follow", Map.of("agentId", agentId)))
                    .bounds(contentX + (width + 4) * 2, 190, width, 18).build();
            follow.active = mutable;
            addRenderableWidget(follow);
            Button delete = Button.builder(Component.literal("删除"), ignored ->
                            sendAgentAction("delete", Map.of("agentId", agentId)))
                    .bounds(contentX + (width + 4) * 3, 190, width, 18).build();
            delete.active = mutable;
            addRenderableWidget(delete);
        } else if (agentSettingsDetailPage == 1) {
            EditBox voice = new EditBox(this.font, contentX, 168, contentWidth, 18, Component.literal("Edge TTS 声音"));
            voice.setMaxLength(80);
            voice.setHint(Component.literal("Edge TTS 声音"));
            voice.setValue(agentVoiceDraft);
            voice.setResponder(value -> agentVoiceDraft = value);
            addRenderableWidget(voice);
            Button save = Button.builder(Component.literal("保存声音"), ignored -> saveAgentVoice())
                    .bounds(contentX, 190, Math.min(110, contentWidth), 18).build();
            save.active = mutable && !agentVoiceDraft.isBlank();
            addRenderableWidget(save);
        } else if (agentSettingsDetailPage == 2) {
            EditBox collaborator = new EditBox(this.font, contentX, 168, contentWidth, 18,
                    Component.literal("玩家 UUID"));
            collaborator.setMaxLength(36);
            collaborator.setHint(Component.literal("玩家 UUID"));
            collaborator.setValue(collaboratorDraft);
            collaborator.setResponder(value -> collaboratorDraft = value);
            addRenderableWidget(collaborator);
            int width = Math.max(54, (contentWidth - 4) / 2);
            Button add = Button.builder(Component.literal("添加协作者"), ignored -> sendCollaborator(agentId, true))
                    .bounds(contentX, 190, width, 18).build();
            add.active = mutable && !collaboratorDraft.isBlank();
            addRenderableWidget(add);
            Button remove = Button.builder(Component.literal("移除协作者"), ignored -> sendCollaborator(agentId, false))
                    .bounds(contentX + width + 4, 190, width, 18).build();
            remove.active = mutable && !collaboratorDraft.isBlank();
            addRenderableWidget(remove);
        } else {
            EditBox limit = new EditBox(this.font, contentX, 168, contentWidth, 18,
                    Component.literal("连锁挖掘上限"));
            limit.setMaxLength(3);
            limit.setHint(Component.literal("1–128"));
            limit.setValue(veinMiningLimitDraft);
            limit.setResponder(value -> veinMiningLimitDraft = value);
            addRenderableWidget(limit);
            addRenderableWidget(Button.builder(Component.literal("保存技能设置"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.ConfigPatch(
                                    PanelSnapshotInbox.snapshot().revision(),
                                    Map.of("skill.veinMining.maxBlocks", veinMiningLimitDraft.strip()))))
                    .bounds(contentX, 190, Math.min(120, contentWidth), 18).build());
        }
    }

    private void loadSelectedAgentVoice() {
        Map<String, String> values = PanelSnapshotInbox.snapshot().values();
        String agentId = values.get("agent." + selectedAgentSettingsIndex + ".id");
        if (agentId != null) {
            agentVoiceDraft = values.getOrDefault("agent." + agentId + ".voice",
                    values.getOrDefault("voice.default", "zh-CN-XiaoxiaoNeural"));
            agentRenameDraft = values.getOrDefault(
                    "agent." + selectedAgentSettingsIndex + ".name", agentRenameDraft);
        }
    }

    private void sendCollaborator(String agentId, boolean enabled) {
        sendAgentAction("collaborator", Map.of(
                "agentId", agentId, "playerId", collaboratorDraft.strip(), "enabled", Boolean.toString(enabled)));
    }

    private void sendAgentAction(String action, Map<String, String> values) {
        if (this.minecraft.getConnection() != null) {
            var versioned = new LinkedHashMap<>(values);
            String agentId = versioned.get("agentId");
            if (agentId != null && !"follow".equals(action)) {
                Map<String, String> panel = PanelSnapshotInbox.snapshot().values();
                int count = parseBoundedInt(panel.get("agent.count"), 0, 4, 0);
                for (int index = 0; index < count; index++) {
                    if (agentId.equals(panel.get("agent." + index + ".id"))) {
                        versioned.put("expectedRevision", panel.getOrDefault("agent." + index + ".revision", "0"));
                        break;
                    }
                }
            }
            ClientPacketDistributor.sendToServer(new MineAgentPayloads.AgentCommand(action, versioned));
        }
    }

    private void saveAgentVoice() {
        Map<String, String> values = PanelSnapshotInbox.snapshot().values();
        String agentId = values.get("agent." + selectedAgentSettingsIndex + ".id");
        if (agentId == null || agentVoiceDraft.isBlank()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new MineAgentPayloads.ConfigPatch(
                PanelSnapshotInbox.snapshot().revision(),
                Map.of("agent." + agentId + ".voice", agentVoiceDraft.strip())
        ));
    }

    private void createAgent() {
        if (this.minecraft.player == null || agentNameDraft.isBlank()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new MineAgentPayloads.AgentCommand("create", Map.of(
                "name", agentNameDraft.strip(), "mode", agentCreateMode.name()
        )));
    }

    private void addProviderControls(int contentX, int contentWidth) {
        int fieldWidth = Math.min(300, contentWidth);
        int tabWidth = Math.max(34, Math.min(50, (contentWidth - 60) / 4));
        Button openAiTab = Button.builder(Component.literal("OpenAI"), ignored -> {
                    providerPage = 0;
                    rebuildWidgets();
                }).bounds(contentX, 108, tabWidth, 18).build();
        openAiTab.active = providerPage != 0;
        addRenderableWidget(openAiTab);
        Button keyTab = Button.builder(Component.literal("Key"), ignored -> {
                    providerPage = 1;
                    rebuildWidgets();
                }).bounds(contentX + tabWidth + 4, 108, tabWidth, 18).build();
        keyTab.active = providerPage != 1;
        addRenderableWidget(keyTab);
        Button ollamaTab = Button.builder(Component.literal("Ollama"), ignored -> {
                    providerPage = 2;
                    rebuildWidgets();
                }).bounds(contentX + (tabWidth + 4) * 2, 108, tabWidth, 18).build();
        ollamaTab.active = providerPage != 2;
        addRenderableWidget(ollamaTab);
        Button comfyTab = Button.builder(Component.literal("Comfy"), ignored -> {
                    providerPage = 3;
                    rebuildWidgets();
                }).bounds(contentX + (tabWidth + 4) * 3, 108, tabWidth, 18).build();
        comfyTab.active = providerPage != 3;
        addRenderableWidget(comfyTab);

        if (providerPage == 0) {
            addRenderableWidget(field(contentX, 130, fieldWidth, "OpenAI-compatible Base URL", openAiBaseUrlDraft,
                    value -> openAiBaseUrlDraft = value));
            addRenderableWidget(field(contentX, 176, fieldWidth, "OpenAI-compatible 模型", openAiModelDraft,
                    value -> openAiModelDraft = value));
        } else if (providerPage == 1) {
            addRenderableWidget(new StringWidget(contentX,130,fieldWidth,20,Component.literal("API Key 不在网页或普通设置字段中显示。"),font));
            addRenderableWidget(Button.builder(Component.literal("打开原生保密 Key 输入"),b->Minecraft.getInstance().setScreen(new NativeSecretScreen(this))).bounds(contentX,160,fieldWidth,22).build());
            addRenderableWidget(new StringWidget(contentX,190,fieldWidth,20,Component.literal("保存不测试模型；实际对话/任务请求可能计费。"),font));
        } else if (providerPage == 2) {
            addRenderableWidget(field(contentX, 130, fieldWidth, "Ollama Base URL", ollamaBaseUrlDraft,
                    value -> ollamaBaseUrlDraft = value));
            addRenderableWidget(field(contentX, 176, fieldWidth, "Ollama 模型", ollamaModelDraft,
                    value -> ollamaModelDraft = value));
        } else {
            addRenderableWidget(field(contentX, 130, fieldWidth, "ComfyUI Base URL", comfyUiBaseUrlDraft,
                    value -> comfyUiBaseUrlDraft = value));
            addRenderableWidget(field(contentX, 176, fieldWidth, "ComfyUI Workflow JSON", comfyUiWorkflowDraft,
                    value -> comfyUiWorkflowDraft = value));
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.mineagent_runtime.save_provider"), ignored -> saveProviderSettings())
                .bounds(contentX + (tabWidth + 4) * 4, 108,
                        Math.max(40, contentWidth - (tabWidth + 4) * 4), 18)
                .build());
    }

    private void addCreatorControls(int contentX, int contentWidth) {
        String[] modes = {"建筑", "实体/NPC", "机器", "世界规则/玩法", "图像", "脚本"};
        addRenderableWidget(Button.builder(Component.literal("模式: " + modes[creatorMode]), ignored -> {
                    creatorMode = (creatorMode + 1) % modes.length;
                    rebuildWidgets();
                }).bounds(contentX, 112, Math.min(100, contentWidth), 18).build());
        addRenderableWidget(new StringWidget(contentX, 132, contentWidth, 16,
                Component.translatable("screen.mineagent_runtime.creator_prompt"), this.font));
        EditBox prompt = new EditBox(this.font, contentX, 150, contentWidth, 20,
                Component.translatable("screen.mineagent_runtime.creator_prompt"));
        prompt.setMaxLength(16_384);
        prompt.setValue(creatorPromptDraft);
        prompt.setResponder(value -> creatorPromptDraft = value);
        addRenderableWidget(prompt);
        Button submit = Button.builder(creatorMode==4?Component.translatable("screen.mineagent_runtime.creator_submit"):Component.literal("Coder 预算与候选"), ignored -> {
                    if (this.minecraft.getConnection() != null) {
                        if(creatorMode==4&&!creatorPromptDraft.isBlank())ClientPacketDistributor.sendToServer(new MineAgentPayloads.PromptRequest("IMAGE",creatorPromptDraft));
                        else if(creatorMode!=4)this.minecraft.setScreen(new NativeCoderScreen(this,creatorPromptDraft.isBlank()?"":"创造类别="+modes[creatorMode]+"。"+creatorPromptDraft));
                    }
                }).bounds(contentX, 174, Math.min(120, contentWidth), 20).build();
        submit.active = creatorMode!=4||!creatorPromptDraft.isBlank();
        addRenderableWidget(submit);
        var result = PanelSnapshotInbox.promptResult();
        String resultText = creatorMode!=4?"代码请求与结果请查看 Coder 候选历史；不使用旧 PromptResult 作为新结果。":result.accepted()
                ? result.providerId() + ": " + result.text()
                : "状态: " + result.errorCode();
        addRenderableWidget(new StringWidget(contentX, 198, contentWidth, 16,
                Component.literal(resultText), this.font).setMaxWidth(contentWidth));
    }

    private void addDecisionControls(int contentX, int contentWidth) {
        Map<String, String> state = PanelSnapshotInbox.decisionState().values();
        if (!Boolean.parseBoolean(state.getOrDefault("present", "false"))) {
            addRenderableWidget(new StringWidget(contentX, 122, contentWidth, 18,
                    Component.literal("当前没有待回答的选择"), this.font));
            addRenderableWidget(Button.builder(Component.literal("刷新"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.DecisionRequestPayload()))
                    .bounds(contentX, 148, Math.min(80, contentWidth), 18).build());
            return;
        }
        addRenderableWidget(new StringWidget(contentX, 112, contentWidth, 16,
                Component.literal(state.getOrDefault("title", "选择")), this.font).setMaxWidth(contentWidth));
        addRenderableWidget(new StringWidget(contentX, 128, contentWidth, 16,
                Component.literal(state.getOrDefault("question", "")), this.font).setMaxWidth(contentWidth));

        int optionCount = parseBoundedInt(state.get("optionCount"), 0, 64, 0);
        int availableRows = Math.max(1, Math.min(4, (this.height - 230) / 20));
        int visibleEnd = Math.min(optionCount, decisionOptionOffset + availableRows);
        int y = 146;
        for (int index = decisionOptionOffset; index < visibleEnd; index++) {
            String optionId = state.getOrDefault("option." + index + ".id", "");
            String title = state.getOrDefault("option." + index + ".title", optionId);
            boolean selected = decisionSelections.contains(optionId);
            addRenderableWidget(Button.builder(Component.literal((selected ? "[x] " : "[ ] ") + title), ignored -> {
                        toggleDecisionOption(optionId, state);
                        rebuildWidgets();
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 20;
        }
        if (optionCount > availableRows) {
            int half = Math.max(32, Math.min(55, contentWidth / 4));
            Button previous = Button.builder(Component.literal("上一项"), ignored -> {
                        decisionOptionOffset = Math.max(0, decisionOptionOffset - availableRows);
                        rebuildWidgets();
                    }).bounds(contentX, y, half, 18).build();
            previous.active = decisionOptionOffset > 0;
            addRenderableWidget(previous);
            Button next = Button.builder(Component.literal("下一项"), ignored -> {
                        decisionOptionOffset = Math.min(Math.max(0, optionCount - 1), decisionOptionOffset + availableRows);
                        rebuildWidgets();
                    }).bounds(contentX + half + 4, y, half, 18).build();
            next.active = visibleEnd < optionCount;
            addRenderableWidget(next);
            y += 20;
        }
        if (Boolean.parseBoolean(state.getOrDefault("allowCustomInput", "false"))) {
            EditBox custom = new EditBox(this.font, contentX, y, contentWidth, 18, Component.literal("自由回答"));
            custom.setMaxLength(16_384);
            custom.setHint(Component.literal("自由回答"));
            custom.setValue(decisionCustomDraft);
            custom.setResponder(value -> decisionCustomDraft = value);
            addRenderableWidget(custom);
            y += 20;
        }
        int buttonWidth = Math.max(44, (contentWidth - 8) / 3);
        String status = state.getOrDefault("status", "OPEN");
        if ("DEFERRED".equals(status)) {
            addRenderableWidget(Button.builder(Component.literal("继续回答"), ignored -> sendDecisionCommand("resume", state))
                    .bounds(contentX, y, buttonWidth, 18).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("提交"), ignored -> sendDecisionCommand("submit", state))
                    .bounds(contentX, y, buttonWidth, 18).build());
            addRenderableWidget(Button.builder(Component.literal("稍后"), ignored -> sendDecisionCommand("defer", state))
                    .bounds(contentX + buttonWidth + 4, y, buttonWidth, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("取消任务"), ignored -> sendDecisionCommand("cancel", state))
                .bounds(contentX + (buttonWidth + 4) * 2, y, buttonWidth, 18).build());
    }

    private void addConversationControls(int contentX, int contentWidth) {
        int tabWidth = Math.max(60, Math.min(96, (contentWidth - 4) / 2));
        Button chatTab = Button.builder(Component.literal("聊天"), ignored -> {
                    conversationPage = 0;
                    requestSelectedConversation();
                    rebuildWidgets();
                }).bounds(contentX, 90, tabWidth, 18).build();
        chatTab.active = conversationPage != 0;
        addRenderableWidget(chatTab);
        Button choiceTab = Button.builder(Component.literal("待回答选择"), ignored -> {
                    conversationPage = 1;
                    ClientPacketDistributor.sendToServer(new MineAgentPayloads.DecisionRequestPayload());
                    rebuildWidgets();
                }).bounds(contentX + tabWidth + 4, 90, tabWidth, 18).build();
        choiceTab.active = conversationPage != 1;
        addRenderableWidget(choiceTab);
        if (conversationPage == 0) {
            addChatControls(contentX, contentWidth);
        } else {
            addDecisionControls(contentX, contentWidth);
        }
    }

    private void addTaskControls(int contentX, int contentWidth) {
        int tabWidth = Math.max(58, Math.min(90, (contentWidth - 4) / 2));
        Button createTab = Button.builder(Component.literal("发起任务"), ignored -> {
                    taskPage = 0;
                    rebuildWidgets();
                }).bounds(contentX, 108, tabWidth, 18).build();
        createTab.active = taskPage != 0;
        addRenderableWidget(createTab);
        Button manageTab = Button.builder(Component.literal("管理任务"), ignored -> {
                    taskPage = 1;
                    ClientPacketDistributor.sendToServer(new MineAgentPayloads.TaskCommand("refresh", Map.of()));
                    rebuildWidgets();
                }).bounds(contentX + tabWidth + 4, 108, tabWidth, 18).build();
        manageTab.active = taskPage != 1;
        addRenderableWidget(manageTab);
        if (taskPage == 0) {
            addTaskCreationControls(contentX, contentWidth);
        } else {
            addTaskManagementControls(contentX, contentWidth);
        }
    }

    private void addCodeStudioControls(int contentX,int contentWidth){
        addRenderableWidget(new StringWidget(contentX,112,contentWidth,38,Component.literal("原生编辑已统一到独立 Code Studio：按 Owner/world/revision 读取，明确 Agent，不使用共享列表快照覆盖本地源码。"),font).setMaxWidth(contentWidth));
        addRenderableWidget(Button.builder(Component.literal(requestedCodeDraftId.isEmpty()?"打开全部本人草稿":"打开指定采用稿"),b->{
            this.minecraft.setScreen(requestedCodeDraftId.isEmpty()?new NativeCodeStudioScreen(this):new NativeCodeStudioScreen(this,requestedCodeDraftId,requestedCodeWorld));
        }).bounds(contentX,154,contentWidth,20).build());
        int width=(contentWidth-8)/3;
        addRenderableWidget(Button.builder(Component.literal("新 JS"),b->this.minecraft.setScreen(NativeCodeStudioScreen.createDraft(this,"script.js"))).bounds(contentX,179,width,20).build());
        addRenderableWidget(Button.builder(Component.literal("新 mjs"),b->this.minecraft.setScreen(NativeCodeStudioScreen.createDraft(this,"script.mjs"))).bounds(contentX+width+4,179,width,20).build());
        addRenderableWidget(Button.builder(Component.literal("新 Java"),b->this.minecraft.setScreen(NativeCodeStudioScreen.createDraft(this,"Extension.java"))).bounds(contentX+2*(width+4),179,contentWidth-2*(width+4),20).build());
        addRenderableWidget(Button.builder(Component.literal("Coder 请求与候选（另行确认费用）"),b->this.minecraft.setScreen(new NativeCoderScreen(this,""))).bounds(contentX,204,contentWidth,20).build());
        addRenderableWidget(new StringWidget(contentX,228,contentWidth,30,Component.literal("创建、保存、源码发布、运行、停止分别确认；打开界面不创建示例或 Task。"),font).setMaxWidth(contentWidth));
    }

    private void addMemoryControls(int contentX, int contentWidth) {
        int tabWidth = Math.max(54, Math.min(86, (contentWidth - 4) / 2));
        Button createTab = Button.builder(Component.literal("新增记忆"), ignored -> {
                    memoryPage = 0;
                    rebuildWidgets();
                }).bounds(contentX, 108, tabWidth, 18).build();
        createTab.active = memoryPage != 0;
        addRenderableWidget(createTab);
        Button editTab = Button.builder(Component.literal("编辑记忆"), ignored -> {
                    memoryPage = 1;
                    loadSelectedMemory();
                    rebuildWidgets();
                }).bounds(contentX + tabWidth + 4, 108, tabWidth, 18).build();
        editTab.active = memoryPage != 1;
        addRenderableWidget(editTab);
        if (memoryPage == 0) {
            addMemoryCreationControls(contentX, contentWidth);
        } else {
            addMemoryEditControls(contentX, contentWidth);
        }
    }

    private void addPermissionControls(int contentX, int contentWidth) {
        int tabWidth = Math.max(54, Math.min(86, (contentWidth - 4) / 2));
        Button trustTab = Button.builder(Component.literal("服务器信任"), ignored -> {
                    permissionPage = 0;
                    rebuildWidgets();
                }).bounds(contentX, 108, tabWidth, 18).build();
        trustTab.active = permissionPage != 0;
        addRenderableWidget(trustTab);
        Button accessTab = Button.builder(Component.literal("权限组"), ignored -> {
                    permissionPage = 1;
                    ClientPacketDistributor.sendToServer(new MineAgentPayloads.PermissionCommand("refresh", Map.of()));
                    rebuildWidgets();
                }).bounds(contentX + tabWidth + 4, 108, tabWidth, 18).build();
        accessTab.active = permissionPage != 1;
        addRenderableWidget(accessTab);
        if (permissionPage == 1) {
            addPermissionGroupControls(contentX, contentWidth);
            return;
        }
        Map<String, String> values = PanelSnapshotInbox.snapshot().values();
        String fingerprint = values.getOrDefault("security.identityFingerprint", "未收到");
        String publicKey = values.getOrDefault("security.identityPublicKey", "");
        String serverId = currentServerId();
        dev.mineagent.runtime.client.trust.TrustStatus status = dev.mineagent.runtime.client.trust.TrustStatus.UNKNOWN;
        try {
            status = trustStore().status(serverId, fingerprint);
        } catch (Exception failure) {
            trustError = failure.getMessage();
        }
        addRenderableWidget(new StringWidget(contentX, 130, contentWidth, 16,
                Component.literal("签名: " + (PanelSnapshotInbox.signatureValid() ? "有效" : "无效")), this.font));
        addRenderableWidget(new StringWidget(contentX, 148, contentWidth, 16,
                Component.literal("指纹: " + abbreviate(fingerprint, 28)), this.font).setMaxWidth(contentWidth));
        addRenderableWidget(new StringWidget(contentX, 166, contentWidth, 16,
                Component.literal("信任状态: " + switch (status) {
                    case UNKNOWN -> "未确认";
                    case TRUSTED -> "已信任";
                    case MISMATCH -> "指纹变化";
                }), this.font));
        Button trust = Button.builder(Component.literal(status == dev.mineagent.runtime.client.trust.TrustStatus.MISMATCH
                        ? "确认更新指纹" : "信任此服务器"), ignored -> {
                    try {
                        trustStore().confirm(serverId, fingerprint, java.util.Base64.getDecoder().decode(publicKey));
                        trustError = "";
                        if (!Boolean.parseBoolean(values.getOrDefault("runtime.initialized", "false"))) {
                            model.select(PanelSection.PROVIDERS);
                        }
                    } catch (Exception failure) {
                        trustError = failure.getMessage();
                    }
                    rebuildWidgets();
                }).bounds(contentX, 184, Math.min(130, contentWidth), 18).build();
        trust.active = PanelSnapshotInbox.signatureValid()
                && status != dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED;
        addRenderableWidget(trust);
        addRenderableWidget(new StringWidget(contentX, 204, contentWidth, 14,
                Component.literal(trustError.isBlank()
                        ? "创建AI=" + values.getOrDefault("permission.create_agent", "false")
                        + " 代码=" + values.getOrDefault("permission.run_code", "false")
                        : "错误: " + trustError), this.font).setMaxWidth(contentWidth));
    }

    private void addPermissionGroupControls(int contentX, int contentWidth) {
        boolean mayManage = Boolean.parseBoolean(PanelSnapshotInbox.snapshot().values()
                .getOrDefault("permission.manage_permissions", "false"));
        if (!mayManage) {
            addRenderableWidget(new StringWidget(contentX, 132, contentWidth, 18,
                    Component.literal("你可以查看自己的权限，但不能管理权限组"), this.font).setMaxWidth(contentWidth));
            return;
        }
        EditBox player = new EditBox(this.font, contentX, 132, contentWidth, 18, Component.literal("玩家 UUID"));
        player.setMaxLength(36);
        player.setHint(Component.literal("玩家 UUID"));
        player.setValue(permissionPlayerDraft);
        player.setResponder(value -> permissionPlayerDraft = value);
        addRenderableWidget(player);
        addRenderableWidget(Button.builder(Component.literal("权限: " + permissionAction.name()), ignored -> {
                    var values = dev.mineagent.runtime.api.permission.PermissionAction.values();
                    permissionAction = values[(permissionAction.ordinal() + 1) % values.length];
                    rebuildWidgets();
                }).bounds(contentX, 154, contentWidth, 18).build());
        int width = Math.max(60, (contentWidth - 4) / 2);
        Button grant = Button.builder(Component.literal("授予"), ignored -> sendPermissionChange(true))
                .bounds(contentX, 176, width, 18).build();
        grant.active = !permissionPlayerDraft.isBlank();
        addRenderableWidget(grant);
        Button revoke = Button.builder(Component.literal("撤销"), ignored -> sendPermissionChange(false))
                .bounds(contentX + width + 4, 176, width, 18).build();
        revoke.active = !permissionPlayerDraft.isBlank();
        addRenderableWidget(revoke);
        addRenderableWidget(new StringWidget(contentX, 198, contentWidth, 16,
                Component.literal("受信玩家: " + PanelSnapshotInbox.permissionState().values()
                        .getOrDefault("playerCount", "0") + "  "
                        + emptyAsNone(PanelSnapshotInbox.permissionState().errorCode())), this.font));
    }

    private void sendPermissionChange(boolean enabled) {
        ClientPacketDistributor.sendToServer(new MineAgentPayloads.PermissionCommand("set", Map.of(
                "playerId", permissionPlayerDraft.strip(), "permission", permissionAction.name(),
                "enabled", Boolean.toString(enabled))));
    }

    private void addModKnowledgeControls(int contentX, int contentWidth) {
        var payload = PanelSnapshotInbox.modKnowledgeState();
        Map<String, String> state = payload.values();
        int count = parseBoundedInt(state.get("modCount"), 0, 30, 0);
        if (count == 0) {
            String text = "INDEXING".equals(payload.errorCode()) ? "正在索引 Mod JAR…" : "尚无可用 Mod 索引";
            addRenderableWidget(new StringWidget(contentX, 120, contentWidth, 18,
                    Component.literal(text), this.font));
            addRenderableWidget(Button.builder(Component.literal("扫描 mods 目录"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.ModKnowledgeRequest()))
                    .bounds(contentX, 146, Math.min(120, contentWidth), 18).build());
            return;
        }
        selectedModIndex = Math.min(selectedModIndex, count - 1);
        String prefix = "mod." + selectedModIndex + ".";
        addRenderableWidget(Button.builder(Component.literal(
                        state.getOrDefault(prefix + "name", state.getOrDefault(prefix + "id", "Mod"))), ignored -> {
                    selectedModIndex = (selectedModIndex + 1) % count;
                    rebuildWidgets();
                }).bounds(contentX, 116, contentWidth, 18).build());
        addRenderableWidget(new StringWidget(contentX, 140, contentWidth, 16,
                Component.literal("ID: " + state.getOrDefault(prefix + "id", "")
                        + "  版本: " + state.getOrDefault(prefix + "version", "")), this.font));
        addRenderableWidget(new StringWidget(contentX, 158, contentWidth, 16,
                Component.literal("Classes: " + state.getOrDefault(prefix + "classes", "0")
                        + "  Sources: " + state.getOrDefault(prefix + "sources", "0")), this.font));
        addRenderableWidget(new StringWidget(contentX, 176, contentWidth, 16,
                Component.literal("SHA-256: " + abbreviate(state.getOrDefault(prefix + "sha256", ""), 28)), this.font));
        addRenderableWidget(Button.builder(Component.literal("重新扫描"), ignored ->
                        ClientPacketDistributor.sendToServer(new MineAgentPayloads.ModKnowledgeRequest()))
                .bounds(contentX, 196, Math.min(90, contentWidth), 18).build());
    }

    private void addBackupControls(int contentX, int contentWidth) {
        int tabWidth = Math.max(42, Math.min(72, (contentWidth - 8) / 3));
        Button createTab = Button.builder(Component.literal("创建快照"), ignored -> {
                    backupPage = 0;
                    rebuildWidgets();
                }).bounds(contentX, 108, tabWidth, 18).build();
        createTab.active = backupPage != 0;
        addRenderableWidget(createTab);
        Button restoreTab = Button.builder(Component.literal("预览恢复"), ignored -> {
                    backupPage = 1;
                    ClientPacketDistributor.sendToServer(new MineAgentPayloads.BackupCommand("refresh", Map.of()));
                    rebuildWidgets();
                }).bounds(contentX + tabWidth + 4, 108, tabWidth, 18).build();
        restoreTab.active = backupPage != 1;
        addRenderableWidget(restoreTab);
        Button journalTab = Button.builder(Component.literal("变更记录"), ignored -> {
                    backupPage = 2;
                    ClientPacketDistributor.sendToServer(new MineAgentPayloads.BackupCommand("refresh", Map.of()));
                    rebuildWidgets();
                }).bounds(contentX + (tabWidth + 4) * 2, 108, tabWidth, 18).build();
        journalTab.active = backupPage != 2;
        addRenderableWidget(journalTab);
        if (backupPage == 0) {
            EditBox label = new EditBox(this.font, contentX, 132, contentWidth, 18, Component.literal("快照名称"));
            label.setMaxLength(128);
            label.setValue(snapshotLabelDraft);
            label.setResponder(value -> snapshotLabelDraft = value);
            addRenderableWidget(label);
            addRenderableWidget(Button.builder(Component.literal("半径: " + snapshotRadius), ignored -> {
                        snapshotRadius = snapshotRadius == 4 ? 8 : snapshotRadius == 8 ? 16 : 4;
                        rebuildWidgets();
                    }).bounds(contentX, 154, Math.min(90, contentWidth), 18).build());
            Button create = Button.builder(Component.literal("立即创建局部快照"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.BackupCommand("create", Map.of(
                                    "label", snapshotLabelDraft.strip(), "radius", Integer.toString(snapshotRadius)))))
                    .bounds(contentX, 178, Math.min(140, contentWidth), 18).build();
            create.active = !snapshotLabelDraft.isBlank();
            addRenderableWidget(create);
            addRenderableWidget(new StringWidget(contentX, 200, contentWidth, 16,
                    Component.literal("默认保留 7 天，上限 10 GB"), this.font));
        } else if (backupPage == 1) {
            Map<String, String> state = PanelSnapshotInbox.backupState().values();
            int count = parseBoundedInt(state.get("snapshotCount"), 0, 10, 0);
            if (count == 0) {
                addRenderableWidget(new StringWidget(contentX, 134, contentWidth, 18,
                        Component.literal("当前没有快照"), this.font));
                return;
            }
            selectedSnapshotIndex = Math.min(selectedSnapshotIndex, count - 1);
            String prefix = "snapshot." + selectedSnapshotIndex + ".";
            addRenderableWidget(Button.builder(Component.literal(state.getOrDefault(prefix + "label", "快照")), ignored -> {
                        selectedSnapshotIndex = (selectedSnapshotIndex + 1) % count;
                        rebuildWidgets();
                    }).bounds(contentX, 132, contentWidth, 18).build());
            addRenderableWidget(new StringWidget(contentX, 156, contentWidth, 16,
                    Component.literal("方块: " + state.getOrDefault(prefix + "blocks", "0")
                            + "  估算字节: " + state.getOrDefault(prefix + "bytes", "0")), this.font));
            addRenderableWidget(new StringWidget(contentX, 174, contentWidth, 16,
                    Component.literal("到期: " + state.getOrDefault(prefix + "expires", "")), this.font));
            addRenderableWidget(Button.builder(Component.literal("恢复此快照"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.BackupCommand("restore", Map.of(
                                    "snapshotId", state.getOrDefault(prefix + "id", "")))))
                    .bounds(contentX, 196, Math.min(120, contentWidth), 18).build());
        } else {
            Map<String, String> state = PanelSnapshotInbox.backupState().values();
            int count = parseBoundedInt(state.get("changeCount"), 0, 10, 0);
            if (count == 0) {
                addRenderableWidget(new StringWidget(contentX, 134, contentWidth, 18,
                        Component.literal("当前没有可撤销变更"), this.font));
                return;
            }
            selectedChangeIndex = Math.min(selectedChangeIndex, count - 1);
            String prefix = "change." + selectedChangeIndex + ".";
            addRenderableWidget(Button.builder(Component.literal(
                            state.getOrDefault(prefix + "action", "变更")), ignored -> {
                        selectedChangeIndex = (selectedChangeIndex + 1) % count;
                        rebuildWidgets();
                    }).bounds(contentX, 132, contentWidth, 18).build());
            addRenderableWidget(new StringWidget(contentX, 156, contentWidth, 16,
                    Component.literal("方块: " + state.getOrDefault(prefix + "blocks", "0")
                            + "  Revision: " + state.getOrDefault(prefix + "revision", "0")), this.font));
            boolean reverted = Boolean.parseBoolean(state.getOrDefault(prefix + "reverted", "false"));
            addRenderableWidget(new StringWidget(contentX, 176, contentWidth, 16,
                    Component.literal(reverted ? "状态: 已撤销" : "状态: 可撤销"), this.font));
            Button undo = Button.builder(Component.literal("撤销此变更"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.BackupCommand(
                                    "undo_change", Map.of(
                                    "changeId", state.getOrDefault(prefix + "id", ""),
                                    "expectedRevision", state.getOrDefault(prefix + "revision", "0")))))
                    .bounds(contentX, 196, Math.min(120, contentWidth), 18).build();
            undo.active = !reverted;
            addRenderableWidget(undo);
        }
    }

    private void addMediaControls(int contentX, int contentWidth) {
        int tabWidth = Math.max(42, Math.min(72, (contentWidth - 8) / 3));
        Button addTab = Button.builder(Component.literal("添加媒体"), ignored -> {
                    mediaPage = 0;
                    rebuildWidgets();
                }).bounds(contentX, 108, tabWidth, 18).build();
        addTab.active = mediaPage != 0;
        addRenderableWidget(addTab);
        Button controlTab = Button.builder(Component.literal("播放控制"), ignored -> {
                    mediaPage = 1;
                    ClientPacketDistributor.sendToServer(new MineAgentPayloads.MediaCommand("refresh", Map.of()));
                    rebuildWidgets();
                }).bounds(contentX + tabWidth + 4, 108, tabWidth, 18).build();
        controlTab.active = mediaPage != 1;
        addRenderableWidget(controlTab);
        Button scopeTab = Button.builder(Component.literal("网络范围"), ignored -> {
                    mediaPage = 2;
                    rebuildWidgets();
                }).bounds(contentX + (tabWidth + 4) * 2, 108, tabWidth, 18).build();
        scopeTab.active = mediaPage != 2;
        addRenderableWidget(scopeTab);
        if (mediaPage == 0) {
            addRenderableWidget(Button.builder(Component.literal("类型: " + mediaKind.name()), ignored -> {
                        var values = dev.mineagent.runtime.api.media.MediaKind.values();
                        mediaKind = values[(mediaKind.ordinal() + 1) % values.length];
                        rebuildWidgets();
                    }).bounds(contentX, 130, contentWidth, 18).build());
            EditBox title = new EditBox(this.font, contentX, 152, contentWidth, 18, Component.literal("标题"));
            title.setMaxLength(256);
            title.setHint(Component.literal("标题"));
            title.setValue(mediaTitleDraft);
            title.setResponder(value -> mediaTitleDraft = value);
            addRenderableWidget(title);
            EditBox url = new EditBox(this.font, contentX, 174, contentWidth, 18, Component.literal("HTTP(S) URL"));
            url.setMaxLength(4_096);
            url.setHint(Component.literal("HTTP(S) URL"));
            url.setValue(mediaUrlDraft);
            url.setResponder(value -> mediaUrlDraft = value);
            addRenderableWidget(url);
            Button add = Button.builder(Component.literal("加入播放列表"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.MediaCommand("create", Map.of(
                                    "kind", mediaKind.name(), "title", mediaTitleDraft.strip(),
                                    "url", mediaUrlDraft.strip()))))
                    .bounds(contentX, 196, Math.min(120, contentWidth), 18).build();
            add.active = !mediaTitleDraft.isBlank() && !mediaUrlDraft.isBlank();
            addRenderableWidget(add);
        } else if (mediaPage == 1) {
            Map<String, String> state = PanelSnapshotInbox.mediaState().values();
            int count = parseBoundedInt(state.get("mediaCount"), 0, 20, 0);
            if (count == 0) {
                addRenderableWidget(new StringWidget(contentX, 134, contentWidth, 18,
                        Component.literal("播放列表为空"), this.font));
                return;
            }
            selectedMediaIndex = Math.min(selectedMediaIndex, count - 1);
            String prefix = "media." + selectedMediaIndex + ".";
            addRenderableWidget(Button.builder(Component.literal(state.getOrDefault(prefix + "title", "媒体")), ignored -> {
                        selectedMediaIndex = (selectedMediaIndex + 1) % count;
                        rebuildWidgets();
                    }).bounds(contentX, 130, contentWidth, 18).build());
            addRenderableWidget(new StringWidget(contentX, 152, contentWidth, 16,
                    Component.literal(state.getOrDefault(prefix + "kind", "URL") + "  "
                            + abbreviate(state.getOrDefault(prefix + "url", ""), 28)), this.font));
            addRenderableWidget(new StringWidget(contentX, 170, contentWidth, 16,
                    Component.literal("绑定: " + state.getOrDefault(prefix + "binding", "未绑定")), this.font));
            String id = state.getOrDefault(prefix + "id", "");
            String revision = state.getOrDefault(prefix + "revision", "0");
            int width = Math.max(44, (contentWidth - 8) / 3);
            String playAction = Boolean.parseBoolean(state.getOrDefault(prefix + "playing", "false")) ? "pause" : "play";
            addRenderableWidget(Button.builder(Component.literal("play".equals(playAction) ? "播放" : "暂停"), ignored ->
                            sendMediaAction(playAction, id, revision))
                    .bounds(contentX, 190, width, 18).build());
            addRenderableWidget(Button.builder(Component.literal("绑定当前位置"), ignored ->
                            sendMediaAction("bind_here", id, revision))
                    .bounds(contentX + width + 4, 190, width * 2 + 4, 18).build());
        } else {
            EditBox allowed = new EditBox(this.font, contentX, 134, contentWidth, 18,
                    Component.literal("允许访问的私有主机"));
            allowed.setMaxLength(4_096);
            allowed.setHint(Component.literal("逗号分隔，例如 media.internal,192.168.1.20"));
            allowed.setValue(mediaAllowedHostsDraft);
            allowed.setResponder(value -> mediaAllowedHostsDraft = value);
            addRenderableWidget(allowed);
            addRenderableWidget(new StringWidget(contentX, 156, contentWidth, 32,
                    Component.literal("默认阻止 private/special IP 与 DNS 解析结果；仅显式主机可例外。"),
                    this.font).setMaxWidth(contentWidth));
            addRenderableWidget(Button.builder(Component.literal("保存网络范围"), ignored ->
                            ClientPacketDistributor.sendToServer(new MineAgentPayloads.ConfigPatch(
                                    PanelSnapshotInbox.snapshot().revision(),
                                    Map.of("media.allowedHosts", mediaAllowedHostsDraft.strip()))))
                    .bounds(contentX, 194, Math.min(120, contentWidth), 18).build());
        }
    }

    private void sendMediaAction(String action, String id, String revision) {
        ClientPacketDistributor.sendToServer(new MineAgentPayloads.MediaCommand(action, Map.of(
                "mediaId", id, "expectedRevision", revision, "positionMillis", "0")));
    }

    private dev.mineagent.runtime.client.trust.ServerTrustStore trustStore() throws java.io.IOException {
        return new dev.mineagent.runtime.client.trust.ServerTrustStore(
                this.minecraft.gameDirectory.toPath().resolve("config").resolve("mineagent-trusted-servers.properties"));
    }

    private String currentServerId() {
        var server = this.minecraft.getCurrentServer();
        return server == null ? "local-integrated" : server.ip;
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    private void addMemoryCreationControls(int contentX, int contentWidth) {
        addRenderableWidget(Button.builder(Component.literal("类型: " + switch (memoryKind) {
                    case PLAYER_PREFERENCE -> "玩家偏好";
                    case WORLD_FACT -> "世界事实";
                    case SKILL -> "技能";
                }), ignored -> {
                    var values = dev.mineagent.runtime.api.memory.MemoryKind.values();
                    memoryKind = values[(memoryKind.ordinal() + 1) % values.length];
                    rebuildWidgets();
                }).bounds(contentX, 130, contentWidth, 18).build());
        EditBox key = new EditBox(this.font, contentX, 152, contentWidth, 18, Component.literal("记忆键"));
        key.setMaxLength(128);
        key.setHint(Component.literal("记忆键"));
        key.setValue(memoryKeyDraft);
        key.setResponder(value -> memoryKeyDraft = value);
        addRenderableWidget(key);
        EditBox value = new EditBox(this.font, contentX, 174, contentWidth, 18, Component.literal("记忆内容"));
        value.setMaxLength(16_384);
        value.setHint(Component.literal("记忆内容"));
        value.setValue(memoryValueDraft);
        value.setResponder(text -> memoryValueDraft = text);
        addRenderableWidget(value);
        Button create = Button.builder(Component.literal("保存记忆"), ignored -> {
                    ClientPacketDistributor.sendToServer(new MineAgentPayloads.MemoryCommand("create", Map.of(
                            "kind", memoryKind.name(), "key", memoryKeyDraft.strip(),
                            "value", memoryValueDraft.strip())));
                    memoryKeyDraft = "";
                    memoryValueDraft = "";
                }).bounds(contentX, 196, Math.min(100, contentWidth), 18).build();
        create.active = !memoryKeyDraft.isBlank() && !memoryValueDraft.isBlank();
        addRenderableWidget(create);
    }

    private void addMemoryEditControls(int contentX, int contentWidth) {
        Map<String, String> state = PanelSnapshotInbox.memoryState().values();
        int count = parseBoundedInt(state.get("memoryCount"), 0, 20, 0);
        if (count == 0) {
            addRenderableWidget(new StringWidget(contentX, 132, contentWidth, 18,
                    Component.literal("当前没有可见记忆"), this.font));
            return;
        }
        selectedMemoryIndex = Math.min(selectedMemoryIndex, count - 1);
        String prefix = "memory." + selectedMemoryIndex + ".";
        addRenderableWidget(Button.builder(Component.literal(
                        state.getOrDefault(prefix + "kind", "MEMORY") + ": "
                                + state.getOrDefault(prefix + "key", "")), ignored -> {
                    selectedMemoryIndex = (selectedMemoryIndex + 1) % count;
                    loadSelectedMemory();
                    rebuildWidgets();
                }).bounds(contentX, 130, contentWidth, 18).build());
        EditBox value = new EditBox(this.font, contentX, 152, contentWidth, 18, Component.literal("记忆内容"));
        value.setMaxLength(16_384);
        value.setValue(memoryValueDraft);
        value.setResponder(text -> memoryValueDraft = text);
        addRenderableWidget(value);
        String id = state.getOrDefault(prefix + "id", "");
        String revision = state.getOrDefault(prefix + "revision", "0");
        int width = Math.max(60, (contentWidth - 4) / 2);
        addRenderableWidget(Button.builder(Component.literal("更新"), ignored -> sendMemoryAction(
                        "update", id, revision, memoryValueDraft))
                .bounds(contentX, 174, width, 18).build());
        addRenderableWidget(Button.builder(Component.literal("删除"), ignored -> sendMemoryAction(
                        "delete", id, revision, ""))
                .bounds(contentX + width + 4, 174, width, 18).build());
        addRenderableWidget(new StringWidget(contentX, 196, contentWidth, 16,
                Component.literal(PanelSnapshotInbox.memoryState().errorCode().isBlank()
                        ? "Revision " + revision : "错误: " + PanelSnapshotInbox.memoryState().errorCode()), this.font));
    }

    private void loadSelectedMemory() {
        Map<String, String> state = PanelSnapshotInbox.memoryState().values();
        memoryValueDraft = state.getOrDefault("memory." + selectedMemoryIndex + ".value", "");
    }

    private void sendMemoryAction(String action, String id, String revision, String value) {
        var values = new LinkedHashMap<String, String>();
        values.put("memoryId", id);
        values.put("expectedRevision", revision);
        if ("update".equals(action)) {
            values.put("value", value.strip());
        }
        ClientPacketDistributor.sendToServer(new MineAgentPayloads.MemoryCommand(action, values));
    }

    private void addTaskCreationControls(int contentX, int contentWidth) {
        Map<String, String> panel = PanelSnapshotInbox.snapshot().values();
        int agents = parseBoundedInt(panel.get("agent.count"), 0, 4, 0);
        if (agents == 0) {
            addRenderableWidget(new StringWidget(contentX, 132, contentWidth, 18,
                    Component.literal("请先创建 AI 玩家"), this.font));
            return;
        }
        taskAgentIndex = Math.min(taskAgentIndex, agents - 1);
        addRenderableWidget(Button.builder(Component.literal("执行者: "
                        + panel.getOrDefault("agent." + taskAgentIndex + ".name", "AI 玩家")), ignored -> {
                    taskAgentIndex = (taskAgentIndex + 1) % agents;
                    rebuildWidgets();
                }).bounds(contentX, 130, contentWidth, 18).build());
        EditBox title = new EditBox(this.font, contentX, 152, contentWidth, 18, Component.literal("任务描述"));
        title.setMaxLength(256);
        title.setHint(Component.literal("任务描述"));
        title.setValue(taskTitleDraft);
        title.setResponder(value -> taskTitleDraft = value);
        addRenderableWidget(title);
        Button create = Button.builder(Component.literal("开始任务"), ignored -> {
                    String agentId = panel.getOrDefault("agent." + taskAgentIndex + ".id", "");
                    if (!agentId.isBlank() && !taskTitleDraft.isBlank()) {
                        ClientPacketDistributor.sendToServer(new MineAgentPayloads.TaskCommand("create", Map.of(
                                "agentId", agentId, "title", taskTitleDraft.strip(), "priority", "50")));
                        taskTitleDraft = "";
                    }
                }).bounds(contentX, 174, Math.min(100, contentWidth), 18).build();
        create.active = !taskTitleDraft.isBlank();
        addRenderableWidget(create);
    }

    private void addTaskManagementControls(int contentX, int contentWidth) {
        Map<String, String> state = PanelSnapshotInbox.taskState().values();
        int count = parseBoundedInt(state.get("taskCount"), 0, 20, 0);
        if (count == 0) {
            addRenderableWidget(new StringWidget(contentX, 132, contentWidth, 18,
                    Component.literal("当前没有任务"), this.font));
            return;
        }
        selectedTaskIndex = Math.min(selectedTaskIndex, count - 1);
        String prefix = "task." + selectedTaskIndex + ".";
        addRenderableWidget(Button.builder(Component.literal(state.getOrDefault(prefix + "title", "任务")), ignored -> {
                    selectedTaskIndex = (selectedTaskIndex + 1) % count;
                    rebuildWidgets();
                }).bounds(contentX, 130, contentWidth, 18).build());
        String status = state.getOrDefault(prefix + "status", "RUNNING");
        String runnable = state.getOrDefault(prefix + "runnable", "");
        addRenderableWidget(new StringWidget(contentX, 151, contentWidth, 16,
                Component.literal("状态: " + status + "  可执行: " + runnable), this.font).setMaxWidth(contentWidth));
        String taskId = state.getOrDefault(prefix + "id", "");
        String revision = state.getOrDefault(prefix + "revision", "0");
        int width = Math.max(44, (contentWidth - 8) / 3);
        String primaryAction = "PAUSED".equals(status) ? "resume" : "pause";
        addRenderableWidget(Button.builder(Component.literal("PAUSED".equals(status) ? "恢复" : "暂停"), ignored ->
                        sendTaskAction(primaryAction, taskId, revision))
                .bounds(contentX, 170, width, 18).build());
        addRenderableWidget(Button.builder(Component.literal("重规划"), ignored ->
                        sendTaskAction("replan", taskId, revision))
                .bounds(contentX + width + 4, 170, width, 18).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), ignored ->
                        sendTaskAction("cancel", taskId, revision))
                .bounds(contentX + (width + 4) * 2, 170, width, 18).build());
        addRenderableWidget(new StringWidget(contentX, 192, contentWidth, 16,
                Component.literal("变更: " + state.getOrDefault(prefix + "changeReason", "")), this.font)
                .setMaxWidth(contentWidth));
    }

    private void sendTaskAction(String action, String taskId, String revision) {
        var values = new LinkedHashMap<String, String>();
        values.put("taskId", taskId);
        values.put("expectedRevision", revision);
        if ("replan".equals(action)) {
            values.put("reason", "玩家在任务面板请求重规划");
        }
        ClientPacketDistributor.sendToServer(new MineAgentPayloads.TaskCommand(action, values));
    }

    private void addChatControls(int contentX, int contentWidth) {
        addRenderableWidget(new StringWidget(contentX,112,contentWidth,36,Component.literal("普通对话已迁移到持久 WebGUI 会话。请明确新建或选择会话；不再自动续聊最近的 AI。"),this.font).setMaxWidth(contentWidth));
        addRenderableWidget(Button.builder(Component.literal("打开完整会话与历史"),ignored->dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.open()).bounds(contentX,154,contentWidth,22).build());
        if(!conversationDraft.isEmpty()){
            EditBox retained=new EditBox(this.font,contentX,186,contentWidth,36,Component.literal("旧版未发送草稿（不会自动发送）"));retained.setMaxLength(16384);retained.setValue(conversationDraft);retained.setResponder(value->conversationDraft=value);addRenderableWidget(retained);
            addRenderableWidget(Button.builder(Component.literal("复制旧草稿（自行选择目标后粘贴）"),ignored->this.minecraft.keyboardHandler.setClipboard(conversationDraft)).bounds(contentX,226,contentWidth,22).build());
        }
    }
    private void requestSelectedConversation() { /* Reading/opening a panel is not a conversation-selection intent. */ }
    private void toggleDecisionOption(String optionId, Map<String, String> state) {
        if (optionId.isBlank()) {
            return;
        }
        if ("SINGLE".equals(state.get("selectionMode"))) {
            boolean wasSelected = decisionSelections.contains(optionId);
            decisionSelections.clear();
            if (!wasSelected) {
                decisionSelections.add(optionId);
            }
        } else if (!decisionSelections.remove(optionId)) {
            decisionSelections.add(optionId);
        }
    }

    private void sendDecisionCommand(String action, Map<String, String> state) {
        var values = new LinkedHashMap<String, String>();
        values.put("decisionId", state.getOrDefault("decisionId", ""));
        values.put("expectedRevision", state.getOrDefault("revision", "0"));
        if ("submit".equals(action)) {
            values.put("submissionId", UUID.randomUUID().toString());
            values.put("selectedCount", Integer.toString(decisionSelections.size()));
            int index = 0;
            for (String optionId : decisionSelections) {
                values.put("selected." + index++, optionId);
            }
            values.put("customText", decisionCustomDraft);
        }
        ClientPacketDistributor.sendToServer(new MineAgentPayloads.DecisionCommand(action, values));
    }

    private static int parseBoundedInt(String value, int minimum, int maximum, int fallback) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(value)));
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    private EditBox field(
            int x,
            int y,
            int width,
            String label,
            String value,
            java.util.function.Consumer<String> responder
    ) {
        addRenderableWidget(new StringWidget(x, y, width, 18, Component.literal(label), this.font));
        EditBox field = new EditBox(this.font, x, y + 20, width, 20, Component.literal(label));
        field.setMaxLength(label.contains("Workflow") ? 16_384 : 512);
        field.setValue(value);
        field.setResponder(responder);
        return field;
    }

    private void saveProviderSettings() {
        var values = new LinkedHashMap<String, String>();
        values.put("provider.openai.baseUrl", openAiBaseUrlDraft.strip());
        values.put("provider.openai.model", openAiModelDraft.strip());
        values.put("provider.ollama.baseUrl", ollamaBaseUrlDraft.strip());
        values.put("provider.ollama.model", ollamaModelDraft.strip());
        values.put("provider.comfyui.baseUrl", comfyUiBaseUrlDraft.strip());
        values.put("provider.comfyui.workflow", comfyUiWorkflowDraft.strip());
        if (!openAiApiKeyDraft.isBlank()) {
            addEncryptedApiKey(values);
        }
        if (!Boolean.parseBoolean(PanelSnapshotInbox.snapshot().values()
                .getOrDefault("runtime.initialized", "false"))) {
            values.put("runtime.initialized", "true");
        }
        if (this.minecraft.getConnection() != null) {
            ClientPacketDistributor.sendToServer(new MineAgentPayloads.ConfigPatch(
                    PanelSnapshotInbox.snapshot().revision(), values
            ));
        }
    }

    private void addEncryptedApiKey(LinkedHashMap<String, String> values) {
        try {
            String encodedKey = PanelSnapshotInbox.snapshot().values().get("security.secretTransportPublicKey");
            if (encodedKey == null) {
                return;
            }
            var serverKey = KeyFactory.getInstance("X25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)));
            var envelope = SecretChannel.seal(serverKey, openAiApiKeyDraft);
            var encoder = Base64.getEncoder();
            values.put("provider.openai.apiKey.encrypted.ephemeral", encoder.encodeToString(envelope.ephemeralPublicKey()));
            values.put("provider.openai.apiKey.encrypted.nonce", encoder.encodeToString(envelope.nonce()));
            values.put("provider.openai.apiKey.encrypted.ciphertext", encoder.encodeToString(envelope.ciphertext()));
            openAiApiKeyDraft = "";
        } catch (java.security.GeneralSecurityException | IllegalArgumentException ignored) {
            // The server will send a fresh transport key on the next panel snapshot.
        }
    }

    @Override
    public void added() {
        super.added();
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPacketDistributor.sendToServer(new MineAgentPayloads.PanelRequest());
        }
    }

    @Override
    public void tick() {
        super.tick();
        var snapshot = PanelSnapshotInbox.snapshot();
        if (PanelSnapshotInbox.generation() != observedSnapshotGeneration) {
            observedSnapshotGeneration = PanelSnapshotInbox.generation();
            openAiBaseUrlDraft = snapshot.values().getOrDefault("provider.openai.baseUrl", openAiBaseUrlDraft);
            openAiModelDraft = snapshot.values().getOrDefault("provider.openai.model", openAiModelDraft);
            ollamaBaseUrlDraft = snapshot.values().getOrDefault("provider.ollama.baseUrl", ollamaBaseUrlDraft);
            ollamaModelDraft = snapshot.values().getOrDefault("provider.ollama.model", ollamaModelDraft);
            comfyUiBaseUrlDraft = snapshot.values().getOrDefault("provider.comfyui.baseUrl", comfyUiBaseUrlDraft);
            comfyUiWorkflowDraft = snapshot.values().getOrDefault("provider.comfyui.workflow", comfyUiWorkflowDraft);
            mediaAllowedHostsDraft = snapshot.values().getOrDefault("media.allowedHosts", mediaAllowedHostsDraft);
            veinMiningLimitDraft = snapshot.values().getOrDefault(
                    "skill.veinMining.maxBlocks", veinMiningLimitDraft);
            if (agentPage == 1) {
                loadSelectedAgentVoice();
            }
            if (model.selectedSection() == PanelSection.APPEARANCE) {
                loadAppearanceDraft();
            }
            if (model.selectedSection() == PanelSection.CONVERSATIONS) {
                requestSelectedConversation();
            }
            rebuildWidgets();
        }
        var promptResult = PanelSnapshotInbox.promptResult();
        if (!promptResult.equals(observedPromptResult)) {
            observedPromptResult = promptResult;
            if (model.selectedSection() == PanelSection.CREATOR
                    || model.selectedSection() == PanelSection.PROVIDERS) {
                rebuildWidgets();
            }
        }
        var decisionState = PanelSnapshotInbox.decisionState();
        if (!decisionState.equals(observedDecisionState)) {
            observedDecisionState = decisionState;
            String decisionId = decisionState.values().getOrDefault("decisionId", "");
            if (!decisionId.equals(observedDecisionId)) {
                observedDecisionId = decisionId;
                decisionSelections.clear();
                decisionCustomDraft = "";
                decisionOptionOffset = 0;
            }
            if (model.selectedSection() == PanelSection.CONVERSATIONS) {
                rebuildWidgets();
            }
        }
        var conversationState = PanelSnapshotInbox.conversationState();
        if (!conversationState.equals(observedConversationState)) {
            observedConversationState = conversationState;
            if (model.selectedSection() == PanelSection.CONVERSATIONS && conversationPage == 0) {
                rebuildWidgets();
            }
        }
        if (PanelSnapshotInbox.conversationStreamGeneration() != observedConversationStreamGeneration) {
            observedConversationStreamGeneration = PanelSnapshotInbox.conversationStreamGeneration();
            if (model.selectedSection() == PanelSection.CONVERSATIONS && conversationPage == 0) {
                rebuildWidgets();
            }
        }
        var taskState = PanelSnapshotInbox.taskState();
        if (!taskState.equals(observedTaskState)) {
            observedTaskState = taskState;
            if (model.selectedSection() == PanelSection.TASKS) {
                rebuildWidgets();
            }
        }
        var codeState = PanelSnapshotInbox.codeState();
        if (!codeState.equals(observedCodeState)) {
            observedCodeState = codeState;
            if (model.selectedSection() == PanelSection.CODE_STUDIO) {
                rebuildWidgets();
            }
        }
        var memoryState = PanelSnapshotInbox.memoryState();
        if (!memoryState.equals(observedMemoryState)) {
            observedMemoryState = memoryState;
            if (memoryPage == 1) {
                loadSelectedMemory();
            }
            if (model.selectedSection() == PanelSection.MEMORY) {
                rebuildWidgets();
            }
        }
        var modKnowledgeState = PanelSnapshotInbox.modKnowledgeState();
        if (!modKnowledgeState.equals(observedModKnowledgeState)) {
            observedModKnowledgeState = modKnowledgeState;
            if (model.selectedSection() == PanelSection.MOD_KNOWLEDGE) {
                rebuildWidgets();
            }
        }
        var backupState = PanelSnapshotInbox.backupState();
        if (!backupState.equals(observedBackupState)) {
            observedBackupState = backupState;
            if (model.selectedSection() == PanelSection.BACKUPS) {
                rebuildWidgets();
            }
        }
        var mediaState = PanelSnapshotInbox.mediaState();
        if (!mediaState.equals(observedMediaState)) {
            observedMediaState = mediaState;
            if (model.selectedSection() == PanelSection.MEDIA) {
                rebuildWidgets();
            }
        }
        var appearanceState = PanelSnapshotInbox.appearanceState();
        if (!appearanceState.equals(observedAppearanceState)) {
            observedAppearanceState = appearanceState;
            if (model.selectedSection() == PanelSection.APPEARANCE) {
                rebuildWidgets();
            }
        }
        var packageState = PanelSnapshotInbox.packageState();
        if (!packageState.equals(observedPackageState)) {
            observedPackageState = packageState;
            String exported = packageState.values().getOrDefault("exportJson", "");
            if (!exported.isBlank()) {
                packageTransferDraft = exported;
                packagePage = 1;
            }
            if (model.selectedSection() == PanelSection.PACKAGES) {
                rebuildWidgets();
            }
        }
        var permissionState = PanelSnapshotInbox.permissionState();
        if (!permissionState.equals(observedPermissionState)) {
            observedPermissionState = permissionState;
            if (model.selectedSection() == PanelSection.PERMISSIONS && permissionPage == 1) {
                rebuildWidgets();
            }
        }
        var diagnosticsState = PanelSnapshotInbox.diagnosticsState();
        if (!diagnosticsState.equals(observedDiagnosticsState)) {
            observedDiagnosticsState = diagnosticsState;
            if (model.selectedSection() == PanelSection.DIAGNOSTICS) {
                rebuildWidgets();
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (model.selectedSection() != PanelSection.APPEARANCE || minecraft.level == null) {
            return;
        }
        String id = PanelSnapshotInbox.snapshot().values().get("agent." + appearanceAgentIndex + ".id");
        if (id == null) {
            return;
        }
        try {
            var entity = minecraft.level.getEntity(UUID.fromString(id));
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                int right = this.width - 20;
                net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsAngle(
                        graphics, right - 72, 30, right, 104, 28, 0f, 0f, 0f, living);
            }
        } catch (IllegalArgumentException ignored) {
            // A newer snapshot will replace an invalid/stale selection.
        }
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    public void runAllPageLayoutSmoke() {
        for (PanelSection section : model.sections()) {
            model.select(section);
            rebuildWidgets();
        }
        model.select(PanelSection.OVERVIEW);
        rebuildWidgets();
    }
}
