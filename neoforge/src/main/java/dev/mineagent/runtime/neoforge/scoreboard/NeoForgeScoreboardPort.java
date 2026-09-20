package dev.mineagent.runtime.neoforge.scoreboard;

import dev.mineagent.runtime.api.scoreboard.NumberFormatKind;
import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.api.scoreboard.ScoreEntrySnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreObjectiveSnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreOperation;
import dev.mineagent.runtime.api.scoreboard.ScoreboardMutationResult;
import dev.mineagent.runtime.api.scoreboard.ScoreboardPort;
import dev.mineagent.runtime.api.scoreboard.ScoreboardSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class NeoForgeScoreboardPort implements ScoreboardPort {
    private final Scoreboard scoreboard;
    private final BooleanSupplier serverThread;

    public NeoForgeScoreboardPort(MinecraftServer server) {
        this(server.getScoreboard(), server::isSameThread);
    }

    public NeoForgeScoreboardPort(Scoreboard scoreboard, BooleanSupplier serverThread) {
        this.scoreboard = Objects.requireNonNull(scoreboard, "scoreboard");
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
    }

    @Override
    public ScoreboardSnapshot snapshot() {
        var objectives = scoreboard.getObjectives().stream()
                .map(objective -> new ScoreObjectiveSnapshot(objective.getName(), objective.getCriteria().getName(),
                        objective.getCriteria().isReadOnly(), objective.getDisplayName().getString(),
                        objective.getRenderType().name(), objective.displayAutoUpdate(),
                        fromNative(objective.numberFormat())))
                .sorted(java.util.Comparator.comparing(ScoreObjectiveSnapshot::name))
                .toList();
        var entries = new ArrayList<ScoreEntrySnapshot>();
        for (Objective objective : scoreboard.getObjectives()) {
            scoreboard.listPlayerScores(objective).stream()
                    .map(entry -> new ScoreEntrySnapshot(objective.getName(), entry.owner(), entry.value(),
                            entry.display() == null ? "" : entry.display().getString(),
                            fromNative(entry.numberFormatOverride())))
                    .sorted(java.util.Comparator.comparing(ScoreEntrySnapshot::holder))
                    .forEach(entries::add);
        }
        var slots = new LinkedHashMap<String, String>();
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective != null) {
                slots.put(slot.getSerializedName(), objective.getName());
            }
        }
        var teams = scoreboard.getPlayerTeams().stream()
                .map(team -> new dev.mineagent.runtime.api.scoreboard.ScoreTeamSnapshot(
                        team.getName(), team.getDisplayName().getString(), team.getColor().getName(),
                        java.util.Set.copyOf(team.getPlayers())))
                .sorted(java.util.Comparator.comparing(dev.mineagent.runtime.api.scoreboard.ScoreTeamSnapshot::name))
                .toList();
        return new ScoreboardSnapshot(objectives, entries, slots, teams);
    }

    @Override
    public ScoreboardMutationResult createObjective(
            String name,
            String criteria,
            String displayName,
            String renderType,
            boolean autoUpdate,
            NumberFormatSpec numberFormat
    ) {
        return mutate(() -> {
            if (scoreboard.getObjective(name) != null) {
                return "OBJECTIVE_EXISTS";
            }
            ObjectiveCriteria parsedCriteria = ObjectiveCriteria.byName(criteria).orElse(null);
            if (parsedCriteria == null) {
                return "CRITERIA_UNKNOWN";
            }
            scoreboard.addObjective(name, parsedCriteria, Component.literal(displayName),
                    parseRenderType(renderType), autoUpdate, toNative(numberFormat));
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult removeObjective(String name) {
        return mutate(() -> {
            Objective objective = scoreboard.getObjective(name);
            if (objective == null) {
                return "OBJECTIVE_MISSING";
            }
            scoreboard.removeObjective(objective);
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult updateObjective(
            String name,
            String displayName,
            String renderType,
            boolean autoUpdate,
            NumberFormatSpec numberFormat
    ) {
        return mutate(() -> {
            Objective objective = scoreboard.getObjective(name);
            if (objective == null) {
                return "OBJECTIVE_MISSING";
            }
            objective.setDisplayName(Component.literal(displayName));
            objective.setRenderType(parseRenderType(renderType));
            objective.setDisplayAutoUpdate(autoUpdate);
            objective.setNumberFormat(toNative(numberFormat));
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult setScore(String objectiveName, String holder, int value) {
        return mutateWritable(objectiveName, objective -> {
            scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(holder), objective).set(value);
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult addScore(String objectiveName, String holder, int delta) {
        return mutateWritable(objectiveName, objective -> {
            scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(holder), objective).add(delta);
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult resetScore(String objectiveName, String holder) {
        return mutateWritable(objectiveName, objective -> {
            scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(holder), objective);
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult resetHolder(String holder) {
        return mutate(() -> {
            for (var score : scoreboard.listPlayerScores(ScoreHolder.forNameOnly(holder)).keySet()) {
                if (score.getCriteria().isReadOnly()) {
                    return "OBJECTIVE_READ_ONLY";
                }
            }
            scoreboard.resetAllPlayerScores(ScoreHolder.forNameOnly(holder));
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult setScoreDisplay(
            String objectiveName,
            String holder,
            String displayName,
            NumberFormatSpec numberFormat
    ) {
        return mutate(() -> {
            Objective objective = scoreboard.getObjective(objectiveName);
            if (objective == null) {
                return "OBJECTIVE_MISSING";
            }
            ScoreHolder scoreHolder = ScoreHolder.forNameOnly(holder);
            if (objective.getCriteria().isReadOnly()
                    && scoreboard.getPlayerScoreInfo(scoreHolder, objective) == null) {
                return "SCORE_MISSING";
            }
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(scoreHolder, objective);
            score.display(displayName == null || displayName.isEmpty() ? null : Component.literal(displayName));
            score.numberFormatOverride(toNative(numberFormat));
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult operate(
            String targetObjective,
            String targetHolder,
            ScoreOperation operation,
            String sourceObjective,
            String sourceHolder
    ) {
        return mutateWritable(targetObjective, target -> {
            Objective source = scoreboard.getObjective(sourceObjective);
            if (source == null) {
                return "SOURCE_OBJECTIVE_MISSING";
            }
            var sourceInfo = scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly(sourceHolder), source);
            if (sourceInfo == null) {
                return "SOURCE_SCORE_MISSING";
            }
            ScoreAccess targetScore = scoreboard.getOrCreatePlayerScore(
                    ScoreHolder.forNameOnly(targetHolder), target);
            int left = targetScore.get();
            int right = sourceInfo.value();
            if ((operation == ScoreOperation.DIVIDE || operation == ScoreOperation.MOD) && right == 0) {
                return "DIVISION_BY_ZERO";
            }
            if (operation == ScoreOperation.SWAP) {
                if (source.getCriteria().isReadOnly()) {
                    return "OBJECTIVE_READ_ONLY";
                }
                targetScore.set(right);
                scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(sourceHolder), source).set(left);
                return "";
            }
            int result = switch (operation) {
                case ASSIGN -> right;
                case ADD -> Math.addExact(left, right);
                case SUBTRACT -> Math.subtractExact(left, right);
                case MULTIPLY -> Math.multiplyExact(left, right);
                case DIVIDE -> Math.floorDiv(left, right);
                case MOD -> Math.floorMod(left, right);
                case MIN -> Math.min(left, right);
                case MAX -> Math.max(left, right);
                case SWAP -> throw new IllegalStateException("handled above");
            };
            targetScore.set(result);
            return "";
        });
    }

    @Override
    public ScoreboardMutationResult setDisplaySlot(String slot, String objectiveName) {
        return mutate(() -> {
            DisplaySlot parsed = java.util.Arrays.stream(DisplaySlot.values())
                    .filter(value -> value.getSerializedName().equals(slot)).findFirst().orElse(null);
            if (parsed == null) {
                return "DISPLAY_SLOT_UNKNOWN";
            }
            Objective objective = objectiveName == null || objectiveName.isBlank()
                    ? null : scoreboard.getObjective(objectiveName);
            if (objectiveName != null && !objectiveName.isBlank() && objective == null) {
                return "OBJECTIVE_MISSING";
            }
            scoreboard.setDisplayObjective(parsed, objective);
            return "";
        });
    }

    private ScoreboardMutationResult mutateWritable(String objectiveName, ObjectiveMutation operation) {
        return mutate(() -> {
            Objective objective = scoreboard.getObjective(objectiveName);
            if (objective == null) {
                return "OBJECTIVE_MISSING";
            }
            if (objective.getCriteria().isReadOnly()) {
                return "OBJECTIVE_READ_ONLY";
            }
            return operation.apply(objective);
        });
    }

    private ScoreboardMutationResult mutate(Mutation mutation) {
        if (!serverThread.getAsBoolean()) {
            return ScoreboardMutationResult.rejected("WRONG_THREAD", snapshot());
        }
        try {
            String error = mutation.apply();
            return error.isEmpty()
                    ? ScoreboardMutationResult.accepted(snapshot())
                    : ScoreboardMutationResult.rejected(error, snapshot());
        } catch (ArithmeticException overflow) {
            return ScoreboardMutationResult.rejected("SCORE_OVERFLOW", snapshot());
        } catch (IllegalArgumentException invalid) {
            return ScoreboardMutationResult.rejected("INVALID_ARGUMENT", snapshot());
        } catch (IllegalStateException invalidState) {
            return ScoreboardMutationResult.rejected("MUTATION_REJECTED", snapshot());
        }
    }

    private static ObjectiveCriteria.RenderType parseRenderType(String renderType) {
        return ObjectiveCriteria.RenderType.valueOf(renderType.toUpperCase(java.util.Locale.ROOT));
    }

    private static NumberFormat toNative(NumberFormatSpec format) {
        return switch (format.kind()) {
            case DEFAULT -> null;
            case BLANK -> BlankFormat.INSTANCE;
            case FIXED -> new FixedFormat(Component.literal(format.value()));
            case STYLED -> {
                Style style;
                if ("reset".equalsIgnoreCase(format.value())) {
                    style = Style.EMPTY;
                } else {
                    TextColor color = TextColor.parseColor(format.value()).result()
                            .orElseThrow(() -> new IllegalArgumentException("invalid style color"));
                    style = Style.EMPTY.withColor(color);
                }
                yield new StyledFormat(style);
            }
        };
    }

    private static NumberFormatSpec fromNative(NumberFormat format) {
        if (format == null) {
            return NumberFormatSpec.defaultFormat();
        }
        if (format instanceof BlankFormat) {
            return new NumberFormatSpec(NumberFormatKind.BLANK, "");
        }
        if (format instanceof FixedFormat fixed) {
            return new NumberFormatSpec(NumberFormatKind.FIXED, fixed.value().getString());
        }
        if (format instanceof StyledFormat styled) {
            String color = styled.style().getColor() == null ? "reset" : styled.style().getColor().serialize();
            return new NumberFormatSpec(NumberFormatKind.STYLED, color);
        }
        return new NumberFormatSpec(NumberFormatKind.FIXED, format.format(0).getString());
    }

    @FunctionalInterface
    private interface Mutation {
        String apply();
    }

    @FunctionalInterface
    private interface ObjectiveMutation {
        String apply(Objective objective);
    }
}
