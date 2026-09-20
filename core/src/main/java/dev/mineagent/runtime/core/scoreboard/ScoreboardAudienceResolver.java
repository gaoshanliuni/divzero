package dev.mineagent.runtime.core.scoreboard;

public final class ScoreboardAudienceResolver {
    public boolean visible(ScoreAudience audience, ScoreAudienceContext context) {
        if (audience == null || context == null || context.playerId() == null) {
            return false;
        }
        return switch (audience.kind()) {
            case PUBLIC -> true;
            case PLAYERS -> audience.members().contains(context.playerId().toString());
            case TEAM -> intersects(audience.members(), context.teams());
            case PERMISSION_GROUP -> intersects(audience.members(), context.permissionGroups());
            case SCENE -> intersects(audience.members(), context.scenes());
        };
    }

    private static boolean intersects(java.util.Set<String> left, java.util.Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }
}
