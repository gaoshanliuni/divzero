package dev.mineagent.runtime.api.scoreboard;
import java.util.*;
/** Recipient-specific native presentation only. Contains no complete scoreboard, code, URI or authority grant. */
public record WorldBoardFrame(UUID serverInstanceId,UUID worldId,UUID viewerId,long sequence,String dimension,List<Board> boards) {
    public WorldBoardFrame {
        Objects.requireNonNull(serverInstanceId);Objects.requireNonNull(worldId);Objects.requireNonNull(viewerId);
        if(sequence<1||dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]{1,100}"))throw new IllegalArgumentException("WORLD_BOARD_FRAME");
        boards=List.copyOf(boards);
        if(boards.size()>16||boards.stream().mapToInt(b->b.text().length()).sum()>8192||boards.stream().map(Board::viewId).distinct().count()!=boards.size())throw new IllegalArgumentException("WORLD_BOARD_BUDGET");
    }
    public record Board(UUID viewId,UUID packageId,long revision,double x,double y,double z,float yaw,float scale,String text) {
        public Board {
            Objects.requireNonNull(viewId);Objects.requireNonNull(packageId);
            if(revision<1||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000||Math.abs(y)>4096
                    ||!Float.isFinite(yaw)||!Float.isFinite(scale)||scale<=0||scale>32||text==null||text.length()>4096)throw new IllegalArgumentException("WORLD_BOARD_VALUE");
        }
    }
}
