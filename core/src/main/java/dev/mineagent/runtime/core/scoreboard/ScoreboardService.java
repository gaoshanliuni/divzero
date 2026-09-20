package dev.mineagent.runtime.core.scoreboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.scoreboard.ScoreboardCommand;
import dev.mineagent.runtime.api.scoreboard.ScoreboardCommandResult;
import dev.mineagent.runtime.api.scoreboard.ScoreboardMutationResult;
import dev.mineagent.runtime.api.scoreboard.ScoreboardPort;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ScoreboardService implements AutoCloseable {
    private static final String SOURCE_NAMESPACE = "score_sources";
    private static final String VIEW_NAMESPACE = "score_views";
    private static final String LEASE_NAMESPACE = "score_slot_leases";
    private static final String STATE_NAMESPACE = "score_state";
    private static final String LEDGER_NAMESPACE = "score_requests";
    private static final int MAX_LEDGER = 2048;

    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final ScoreboardPort port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, ScoreSourceBinding> sources = new LinkedHashMap<>();
    private final Map<UUID, ScoreView> views = new LinkedHashMap<>();
    private final Map<UUID, DisplaySlotLease> leases = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, LedgerEntry> ledger = new LinkedHashMap<>();
    private long revision;

    private ScoreboardService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            Clock clock,
            ScoreboardPort port
    ) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.port = port;
        for (var record : repository.list(worldId, SOURCE_NAMESPACE)) {
            ScoreSourceBinding source = mapper.readValue(record.payload(), ScoreSourceBinding.class);
            sources.put(source.sourceId(), source);
        }
        for (var record : repository.list(worldId, VIEW_NAMESPACE)) {
            ScoreView view = mapper.readValue(record.payload(), ScoreView.class);
            views.put(view.viewId(), view);
        }
        for (var record : repository.list(worldId, LEASE_NAMESPACE)) {
            DisplaySlotLease lease = mapper.readValue(record.payload(), DisplaySlotLease.class);
            leases.put(lease.viewId(), lease);
        }
        var state = repository.get(worldId, STATE_NAMESPACE, "state");
        if (state.isPresent()) {
            revision = mapper.readValue(state.get().payload(), ScoreboardServiceState.class).revision();
        }
        var ledgerRecords = new ArrayList<>(repository.list(worldId, LEDGER_NAMESPACE));
        java.util.Collections.reverse(ledgerRecords);
        for (var record : ledgerRecords) {
            ScoreboardLedgerRecord value = mapper.readValue(record.payload(), ScoreboardLedgerRecord.class);
            UUID requestId = UUID.fromString(record.recordId());
            ledger.put(requestId, new LedgerEntry(value.fingerprint(),
                    new ScoreboardCommandResult(requestId, value.accepted(), value.errorCode(),
                            value.resultRevision(), port.snapshot()), record.revision()));
        }
        trimLedger();
    }

    public static ScoreboardService open(Path database, UUID worldId, Clock clock, ScoreboardPort port)
            throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new ScoreboardService(repository, worldId, clock, port);
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized List<ScoreSourceBinding> refreshSources() throws Exception {
        for (var objective : port.snapshot().objectives()) {
            boolean known = sources.values().stream().anyMatch(source -> source.backend() == ScoreSourceBackend.VANILLA
                    && source.reference().equals(objective.name()));
            if (known) {
                continue;
            }
            UUID sourceId = UUID.nameUUIDFromBytes((worldId + "|vanilla|" + objective.name())
                    .getBytes(StandardCharsets.UTF_8));
            ScoreSourceBinding source = new ScoreSourceBinding(sourceId, ScoreSourceBackend.VANILLA,
                    objective.name(), ScoreSourceOwnership.EXTERNAL, ScoreAccessMode.READ_ONLY,
                    null, 1, clock.millis());
            saveNew(SOURCE_NAMESPACE, sourceId, source);
            sources.put(sourceId, source);
            bumpRevision();
        }
        return sources();
    }

    public synchronized List<ScoreSourceBinding> sources() {
        return sources.values().stream().sorted(Comparator.comparing(ScoreSourceBinding::reference)).toList();
    }

    public synchronized Optional<ScoreSourceBinding> source(UUID sourceId) {
        return Optional.ofNullable(sources.get(sourceId));
    }

    public synchronized ScoreSourceBinding grantExternalWrite(
            UUID sourceId,
            long expectedSourceRevision,
            boolean authorized
    ) throws Exception {
        if (!authorized) {
            throw new SecurityException("score source write grant requires authorization");
        }
        ScoreSourceBinding current = requireSource(sourceId);
        if (current.ownership() != ScoreSourceOwnership.EXTERNAL) {
            throw new IllegalArgumentException("source is not external");
        }
        if (current.revision() != expectedSourceRevision) {
            throw new IllegalStateException("stale score source revision");
        }
        ScoreSourceBinding next = new ScoreSourceBinding(current.sourceId(), current.backend(), current.reference(),
                current.ownership(), ScoreAccessMode.READ_WRITE, current.ownerPackageId(),
                current.revision() + 1, clock.millis());
        saveExisting(SOURCE_NAMESPACE, sourceId, current.revision(), next);
        sources.put(sourceId, next);
        bumpRevision();
        return next;
    }

    public synchronized ScoreboardCommandResult execute(ScoreboardCommand command, boolean allowed) throws Exception {
        String fingerprint = fingerprint(command);
        LedgerEntry previous = ledger.get(command.requestId());
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) {
                return result(command.requestId(), false, "REQUEST_ID_REUSED");
            }
            return previous.result();
        }
        if (!allowed) {
            return remember(fingerprint, command.requestId(), result(command.requestId(), false, "FORBIDDEN"));
        }
        if (command.expectedRevision() != revision) {
            return remember(fingerprint, command.requestId(), result(command.requestId(), false, "STALE_REVISION"));
        }
        ScoreSourceBinding source = requireSource(UUID.fromString(argument(command, "sourceId")));
        if (source.accessMode() != ScoreAccessMode.READ_WRITE) {
            return remember(fingerprint, command.requestId(), result(command.requestId(), false, "SOURCE_READ_ONLY"));
        }
        String holder = command.arguments().getOrDefault("holder", "");
        ScoreboardMutationResult mutation = switch (command.action()) {
            case "score.add" -> port.addScore(source.reference(), holder,
                    Integer.parseInt(argument(command, "value")));
            case "score.set" -> port.setScore(source.reference(), holder,
                    Integer.parseInt(argument(command, "value")));
            case "score.reset" -> port.resetScore(source.reference(), holder);
            case "holder.reset" -> port.resetHolder(holder);
            default -> ScoreboardMutationResult.rejected("UNSUPPORTED_ACTION", port.snapshot());
        };
        if (!mutation.accepted()) {
            return remember(fingerprint, command.requestId(),
                    result(command.requestId(), false, mutation.errorCode()));
        }
        bumpRevision();
        return remember(fingerprint, command.requestId(), result(command.requestId(), true, ""));
    }

    public synchronized ScoreView createView(
            UUID sourceId,
            ScoreViewKind kind,
            UUID ownerPackageId,
            ScoreAudience audience,
            Map<String, String> layout,
            ScoreViewTarget target
    ) throws Exception {
        return createView(UUID.randomUUID(), sourceId, kind, ownerPackageId, audience, layout, target);
    }
    public synchronized ScoreView createView(UUID viewId, UUID sourceId, ScoreViewKind kind, UUID ownerPackageId,
                                             ScoreAudience audience, Map<String, String> layout, ScoreViewTarget target) throws Exception {
        requireSource(sourceId);
        ScoreView previous = views.get(viewId);
        if (previous != null) {
            if (previous.sourceId().equals(sourceId) && previous.kind() == kind && previous.ownerPackageId().equals(ownerPackageId)
                    && previous.audience().equals(audience) && previous.layout().equals(layout) && previous.target().equals(target)) return previous;
            throw new IllegalStateException("VIEW_ID_REUSED");
        }
        ScoreView view = new ScoreView(viewId, sourceId, kind, ownerPackageId, audience,
                layout, target, true, 1, clock.millis());
        if(kind==ScoreViewKind.WORLD_BOARD&&views.values().stream().filter(v->v.kind()==kind).count()>=256)throw new IllegalStateException("WORLD_BOARD_BUDGET");
        saveNew(VIEW_NAMESPACE, view.viewId(), view);
        views.put(view.viewId(), view);
        bumpRevision();
        return view;
    }

    public synchronized List<ScoreView> views() {
        return List.copyOf(views.values());
    }
    public synchronized Optional<ScoreView> view(UUID viewId) { return Optional.ofNullable(views.get(viewId)); }
    public synchronized ScoreView placeWorldView(UUID id,UUID owner,long expected,ScoreViewTarget target)throws Exception{
        return placeWorldView(id,owner,expected,target,128);
    }
    public synchronized ScoreView placeWorldView(UUID id,UUID owner,long expected,ScoreViewTarget target,int viewDistance)throws Exception{
        if(!target.target().equals("WORLD")||!target.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||Math.abs(target.x())>30_000_000||Math.abs(target.z())>30_000_000||Math.abs(target.y())>4096)
            throw new IllegalArgumentException("WORLD_BOARD_TARGET");
        if(viewDistance<1||viewDistance>128)throw new IllegalArgumentException("WORLD_BOARD_DISTANCE");
        return presentationTarget(id,owner,expected,ScoreViewKind.WORLD_BOARD,target,Map.of("viewDistance",Integer.toString(viewDistance)));
    }
    public synchronized ScoreView detachWorldView(UUID id,UUID owner,long expected)throws Exception{
        return presentationTarget(id,owner,expected,ScoreViewKind.HUD,ScoreViewTarget.hud("TOP_LEFT"),Map.of());
    }
    private ScoreView presentationTarget(UUID id,UUID owner,long expected,ScoreViewKind kind,ScoreViewTarget target,Map<String,String> patch)throws Exception{
        var current=requireView(id);
        if(!current.ownerPackageId().equals(owner))throw new SecurityException("SCORE_VIEW_PACKAGE_MISMATCH");
        if(current.revision()!=expected)throw new IllegalStateException("STALE_VIEW_REVISION");
        if(!current.enabled())throw new IllegalStateException("SCORE_VIEW_DISABLED");
        var layout=new LinkedHashMap<>(current.layout());layout.putAll(patch);
        if(current.kind()==kind&&current.target().equals(target)&&current.layout().equals(layout))return current;
        if(kind==ScoreViewKind.WORLD_BOARD&&current.kind()!=kind&&views.values().stream().filter(v->v.kind()==kind).count()>=256)throw new IllegalStateException("WORLD_BOARD_BUDGET");
        var next=new ScoreView(id,current.sourceId(),kind,owner,current.audience(),layout,target,true,current.revision()+1,clock.millis());
        saveExisting(VIEW_NAMESPACE,id,current.revision(),next);views.put(id,next);bumpRevision();return next;
    }
    public synchronized List<dev.mineagent.runtime.api.scoreboard.WorldBoardFrame.Board> worldBoards(ScoreAudienceContext viewer,String dimension,double x,double y,double z){
        return worldBoardBatch(List.of(new BoardViewer(viewer,dimension,x,y,z))).get(viewer.playerId());
    }
    public record BoardViewer(ScoreAudienceContext audience,String dimension,double x,double y,double z){}
    public synchronized Map<UUID,List<dev.mineagent.runtime.api.scoreboard.WorldBoardFrame.Board>> worldBoardBatch(List<BoardViewer> viewers){
        var projection=new WorldBoardProjection();var candidates=new LinkedHashMap<UUID,List<ScoreView>>();
        for(var q:viewers)candidates.put(q.audience().playerId(),views.values().stream().filter(v->projection.eligible(v,q.audience(),q.dimension(),q.x(),q.y(),q.z())).toList());
        boolean any=candidates.values().stream().anyMatch(v->!v.isEmpty());var result=new LinkedHashMap<UUID,List<dev.mineagent.runtime.api.scoreboard.WorldBoardFrame.Board>>();
        var data=any?port.snapshot():null;
        for(var q:viewers)result.put(q.audience().playerId(),candidates.get(q.audience().playerId()).isEmpty()?List.of():projection.project(candidates.get(q.audience().playerId()),sources,data,q.audience(),q.dimension(),q.x(),q.y(),q.z()));
        return Map.copyOf(result);
    }
    public record ViewPage(List<ScoreView> views,int page,int totalPages,int totalCount){}
    public synchronized ViewPage viewPage(java.util.function.Predicate<ScoreView> authorized,int page,int size){
        if(page<0||size<1||size>64)throw new IllegalArgumentException("VIEW_PAGE_ARGUMENTS");
        var eligible=views.values().stream().filter(authorized).sorted(Comparator.comparingLong(ScoreView::updatedAtEpochMillis).reversed().thenComparing(v->v.viewId().toString())).toList();
        int pages=Math.max(1,(eligible.size()+size-1)/size),actual=Math.min(page,pages-1),start=actual*size;
        return new ViewPage(List.copyOf(eligible.subList(start,Math.min(eligible.size(),start+size))),actual,pages,eligible.size());
    }

    /** Presentation-only CAS. Does not call a scoreboard mutation or replace the source/audience/target. */
    public synchronized ScoreView patchLayout(UUID viewId, UUID ownerPackageId, long expectedRevision,
                                              Map<String, String> patch) throws Exception {
        ScoreView current = requireView(viewId);
        if (!current.ownerPackageId().equals(ownerPackageId)) throw new SecurityException("SCORE_VIEW_PACKAGE_MISMATCH");
        if (current.revision() != expectedRevision) throw new IllegalStateException("STALE_VIEW_REVISION");
        if (patch == null || patch.isEmpty() || patch.size() > 128) throw new IllegalArgumentException("INVALID_LAYOUT_PATCH");
        if(patch.containsKey("viewDistance")){try{int distance=Integer.parseInt(patch.get("viewDistance"));if(distance<1||distance>128)throw new IllegalArgumentException("WORLD_BOARD_DISTANCE");}catch(NumberFormatException invalid){throw new IllegalArgumentException("WORLD_BOARD_DISTANCE");}}
        var layout = new LinkedHashMap<>(current.layout()); layout.putAll(patch);
        var next = new ScoreView(current.viewId(), current.sourceId(), current.kind(), current.ownerPackageId(), current.audience(),
                layout, current.target(), current.enabled(), current.revision() + 1, clock.millis());
        if (current.layout().equals(next.layout())) return current;
        saveExisting(VIEW_NAMESPACE, viewId, current.revision(), next);
        views.put(viewId, next); bumpRevision();
        return next;
    }
    public synchronized dev.mineagent.runtime.api.scoreboard.ScoreViewSnapshot project(UUID viewId) {
        ScoreView view = requireView(viewId);
        if (!view.enabled()) throw new IllegalStateException("SCORE_VIEW_DISABLED");
        return new ScoreboardViewProjector().project(view, requireSource(view.sourceId()), port.snapshot(), Math.max(1, revision));
    }

    public synchronized ScoreboardMutationResult claimDisplaySlot(
            UUID viewId,
            String slot,
            boolean takeoverAuthorized
    ) throws Exception {
        ScoreView view = requireView(viewId);
        String objective = requireSource(view.sourceId()).reference();
        String current = port.snapshot().displaySlots().getOrDefault(slot, "");
        if (!current.isEmpty() && !current.equals(objective) && !takeoverAuthorized) {
            return ScoreboardMutationResult.rejected("SLOT_OCCUPIED", port.snapshot());
        }
        var mutation = port.setDisplaySlot(slot, objective);
        if (!mutation.accepted()) {
            return mutation;
        }
        DisplaySlotLease old = leases.get(viewId);
        DisplaySlotLease lease = new DisplaySlotLease(viewId, slot, current, objective, true,
                old == null ? 1 : old.revision() + 1, clock.millis());
        if (old == null) {
            saveNew(LEASE_NAMESPACE, viewId, lease);
        } else {
            saveExisting(LEASE_NAMESPACE, viewId, old.revision(), lease);
        }
        leases.put(viewId, lease);
        bumpRevision();
        return mutation;
    }

    public synchronized void releaseDisplaySlot(UUID viewId) throws Exception {
        DisplaySlotLease current = leases.get(viewId);
        if (current == null || !current.active()) {
            return;
        }
        String actual = port.snapshot().displaySlots().getOrDefault(current.slot(), "");
        if (actual.equals(current.claimedObjective())) {
            port.setDisplaySlot(current.slot(), current.previousObjective());
        }
        DisplaySlotLease inactive = new DisplaySlotLease(current.viewId(), current.slot(),
                current.previousObjective(), current.claimedObjective(), false,
                current.revision() + 1, clock.millis());
        saveExisting(LEASE_NAMESPACE, viewId, current.revision(), inactive);
        leases.put(viewId, inactive);
        bumpRevision();
    }

    public synchronized void deleteView(UUID viewId) throws Exception {
        ScoreView view = views.get(viewId);
        if (view == null) {
            return;
        }
        releaseDisplaySlot(viewId);
        var deleted = repository.delete(worldId, VIEW_NAMESPACE, viewId.toString(),
                view.revision(), clock.millis());
        if (!deleted.accepted()) {
            throw new IllegalStateException("score view CAS conflict");
        }
        views.remove(viewId);
        bumpRevision();
    }

    public synchronized void unloadPackage(UUID packageId) throws Exception {
        for (UUID viewId : views.values().stream()
                .filter(view -> view.ownerPackageId().equals(packageId))
                .map(ScoreView::viewId).toList()) {
            deleteView(viewId);
        }
    }

    private ScoreboardCommandResult result(UUID requestId, boolean accepted, String errorCode) {
        return new ScoreboardCommandResult(requestId, accepted, errorCode, revision, port.snapshot());
    }

    private ScoreboardCommandResult remember(
            String fingerprint,
            UUID requestId,
            ScoreboardCommandResult result
    ) throws Exception {
        ScoreboardLedgerRecord record = new ScoreboardLedgerRecord(fingerprint, result.accepted(),
                result.errorCode(), result.revision(), clock.millis());
        var saved = repository.compareAndSet(worldId, LEDGER_NAMESPACE, requestId.toString(), 0,
                mapper.writeValueAsString(record), record.completedAtEpochMillis());
        if (!saved.accepted()) {
            throw new IllegalStateException("scoreboard request ledger conflict");
        }
        ledger.put(requestId, new LedgerEntry(fingerprint, result, saved.record().revision()));
        trimLedger();
        return result;
    }

    private void trimLedger() throws Exception {
        while (ledger.size() > MAX_LEDGER) {
            UUID oldest = ledger.keySet().iterator().next();
            LedgerEntry removed = ledger.remove(oldest);
            repository.delete(worldId, LEDGER_NAMESPACE, oldest.toString(),
                    removed.recordRevision(), clock.millis());
        }
    }

    private void bumpRevision() throws Exception {
        long previous = revision;
        ScoreboardServiceState next = new ScoreboardServiceState(previous + 1, clock.millis());
        var saved = repository.compareAndSet(worldId, STATE_NAMESPACE, "state", previous,
                mapper.writeValueAsString(next), next.updatedAtEpochMillis());
        if (!saved.accepted()) {
            throw new IllegalStateException("scoreboard service revision conflict");
        }
        revision = next.revision();
    }

    private void saveNew(String namespace, UUID id, Object value) throws Exception {
        var saved = repository.compareAndSet(worldId, namespace, id.toString(), 0,
                mapper.writeValueAsString(value), clock.millis());
        if (!saved.accepted()) {
            throw new IllegalStateException("scoreboard record already exists");
        }
    }

    private void saveExisting(String namespace, UUID id, long expectedRevision, Object value) throws Exception {
        var saved = repository.compareAndSet(worldId, namespace, id.toString(), expectedRevision,
                mapper.writeValueAsString(value), clock.millis());
        if (!saved.accepted()) {
            throw new IllegalStateException("scoreboard record revision conflict");
        }
    }

    private ScoreSourceBinding requireSource(UUID sourceId) {
        ScoreSourceBinding source = sources.get(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("unknown score source");
        }
        return source;
    }

    private ScoreView requireView(UUID viewId) {
        ScoreView view = views.get(viewId);
        if (view == null) {
            throw new IllegalArgumentException("unknown score view");
        }
        return view;
    }

    private static String argument(ScoreboardCommand command, String name) {
        String value = command.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing scoreboard command argument " + name);
        }
        return value;
    }

    private static String fingerprint(ScoreboardCommand command) {
        var values = new ArrayList<String>();
        command.arguments().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.add(entry.getKey() + "=" + entry.getValue()));
        return command.expectedRevision() + "|" + command.action() + "|" + String.join("&", values);
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }

    private record LedgerEntry(String fingerprint, ScoreboardCommandResult result, long recordRevision) {
    }
}
