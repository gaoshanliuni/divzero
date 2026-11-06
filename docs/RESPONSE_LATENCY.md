# Response latency and per-AI model selection

This change targets Minecraft 26.1.2 / NeoForge. It does not change model reasoning settings or grant additional world permissions.

- Empty command forks now have a native command-queue completion marker. A drained queue without a callback returns `NO_RESULT` / `COMPLETED`, explicitly without claiming a successful world change. A queue that never completes remains unknown; unknown writes are never replayed.
- `scan_blocks` returns 1024 matches per page by default (previously 64), with optional `limit` 1–4096. `inspect_blocks` defaults to 16384 cells / 1024 runs, with optional cell `limit` 1–65536. Both preserve cursors, loaded-chunk boundaries and a 4096-cell per-tick work budget. Larger observation pages reach the model intact instead of being silently replaced by a short preview.
- Conversation history summaries run in a conversation-scoped background job. The main reply immediately uses the latest validated prefix and recent messages. Summary failure, delayed completion or unavailable coverage does not fail or restart the reply. Cancelled/disconnected/deleted conversations cannot accept late results.
- Execution history is archived locally in complete protocol groups, including required provider reasoning fields. The main loop continues after local archival; it does not await a model summary. Only assistant prose goes to the summarizer. Tool results and parameters stay in the execution archive, with a local receipt ledger and separate unresolved/unverified state in the active context. Pending prose remains available while the summary is being generated.
- Geometry errors include source field paths and expected values. JSON syntax errors include line/column. World collision errors include the block position and conflicting entity IDs/positions; stale block state errors include expected and observed states. Component plans retain their component/path through transaction rollback.
- The AI model picker always offers an editable model ID alongside catalog suggestions. Metadata refresh no longer hides the input or replaces its draft. Saving a custom global model is independent of an in-flight catalog refresh. Different AIs keep independent, persisted model overrides; a global chat-role model no longer overrides an AI's explicit chat model. Separate code/review/small-role settings retain their existing meaning.

## OpenCode reference

DivZero continues to execute the existing MIT-licensed, pinned OpenCode pure functions for atomic-group selection and summary prompts: commit `2fa3363c924c5c3e367b84a87ae478296a0ed59b` of [anomalyco/opencode](https://github.com/anomalyco/opencode).

The lifetime-bound background task pattern is informed by [processor.ts](https://github.com/anomalyco/opencode/blob/2fa3363c924c5c3e367b84a87ae478296a0ed59b/packages/opencode/src/session/processor.ts), which forks session-summary work with error isolation. That upstream session summary is a diff summary, not a claim that OpenCode's context compaction is inherently nonblocking. Assistant-only input and DivZero's background context-summary orchestration are application-specific adaptations. No secondary agents or upstream shell/permission runtime are enabled.

## Validation

Targeted regression tests cover assistant-only summary input, intact expanded observation pages, exact geometry field paths and syntax locations, and two unlisted per-AI models across persistence and global chat-role overrides. A localhost native fixture holds summary responses while the main reply completes, and exercises empty command branches, expanded scan cursors and geometry diagnostics without paid model calls.

Validation completed on 2026-11-06 for source `836a8da9264d03e4493d0e2d9ae45f70a5fa41ab`:

- [Actions 42214056512](https://github.com/gaoshanliuni/divzero/actions/runs/42214056512) built both Python editions and passed the included project tests and packaging checks. Java compilation was performed only in Actions.
- The isolated Minecraft 26.1.2 native fixture passed. Empty selector and false-condition commands returned `NO_RESULT / COMPLETED` in 68 ms and 31 ms; a matching command returned `APPLIED` in 51 ms. These are fixture observations, not general latency guarantees.
- A 1296-block scan completed as 1024 + 272 hits without a missing cursor page. A 6480-cell observation completed in one enlarged page while retaining the per-tick budget.
- Execution-summary and conversation-history-summary responses were deliberately held open by the localhost fixture. The corresponding main replies completed while the summaries were still blocked (three main rounds and one main round respectively).
- Two real AI identities saved unlisted model IDs through the server panel route and dispatched independent requests for `private/alpha-fixture` and `private/beta-fixture`.
- Invalid geometry returned `/parts/0/holes` with allowed fields. An actual collision returned block `[0,101,-4]` and the colliding entity's identity/position. Rejected plans reported no world modification.
- Execution archives contain backlinks, keeping older raw protocol records reachable after the active receipt list rolls forward. Shared changed native classes were byte-identical between the two edition JARs.
- The model endpoint was a controlled localhost test service; there were zero paid model calls. This verifies scheduling, native execution and model routing, not real-provider end-to-end speed or arbitrary custom model compatibility.

The initial typed-selector compile failure and two earlier native-fixture failures remain retained in local evidence. The latter involved an unretained distant scan region and a stale conversation revision after automatic title generation; the fixture now keeps its test chunks loaded and reads the current revision. Neither check was disabled.

The existing production installation, configuration and saves were left intact. This change does not automatically publish a Release.

Candidate JAR SHA-256:

- No Python: `b395a0acd90e59ea88c86f5da11b1873e08ae6832eaa59edd86c540828a950f8`
- Bundled Python: `61c2a6c7d28824190ac334fc3e630924099fac9eeabd903692652becde860bb6`
