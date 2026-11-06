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

Build and native validation results will be appended after execution. This source change is not an automatic Release or production installation.
