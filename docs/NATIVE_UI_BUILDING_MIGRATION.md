# Native UI and component construction migration

Status: implementation in progress. This document is not a release or an assertion of feature parity.

## Accepted target (2026-09-28)

- One LDLib2 UI system replaces the original Ctrl+M panels and F2 web workspace. Both shortcuts can remain.
- **F2 built-in screens are constructed directly by DivZero with LDLib2 and do not depend on KubeJS.** KubeJS supplies script construction and interaction for AI-created interfaces. Both paths share native controls and the DivZero data/authority layer.
- Conversation history/streaming/cancellation, AI management, provider/model/secret settings, file import, content packages, model/structure previews, permission operations, language selection and the other existing workspace capabilities remain required.
- AI-generated interfaces are arbitrary widget trees with styles, data bindings and interactions, not a choice of fixed templates. Data updates are incremental. Structural replacement builds and validates a candidate first, preserves matching input drafts and retains the previous version on error. Normal updates must not require restart, world reentry, script copying or global reload.
- AI overlays default to passive LDLib2 ModularUI HUDs. Independent world/owner/agent/view identities isolate tasks. Interaction is an explicit mode; a passive HUD never captures movement, camera or pointer input.
- MCEF, browser bridges, native downloads, offline packaging and repair flows are removed after their corresponding functions are migrated. They are not a fallback renderer in the final system.
- Construction uses stable component IDs, reusable templates and block-state-aware transformations. Revisions compute local differences; hollow generation does not implicitly clear the world. Step history, pause/resume and conflict-checked undo/redo remain required.
- Completion requires actual block/state and semantic checks bound to the construction ID, affected scope and current revision. Unrelated observations and old screenshots cannot clear pending verification. Intentional floating/overhanging/open structures are supported through explicit plan requirements.

## Upstream versions actually inspected

Minecraft 26.1.2 / NeoForge 26.1.2.106:

| Library | Release ID | Version |
| --- | --- | --- |
| LDLib2 | `15aCZh6V` | `26.1.2.41` |
| KubeJS | `FzLyIIBB` | `26.1.2-8.0.6` |
| Rhino | `SqkDvOLG` | `2101.2.8-build.91` |

The LDLib2 26.1 release's KubeJS plugin source is commented out. Documentation for another branch is not evidence that the release provides working automatic bindings. DivZero therefore supplies its own KubeJS builder bridge using the release's actual native widget APIs. The bundled trusted script consumes JSON data; AI text is never directly evaluated as client JavaScript. The direct built-in renderer does not import KubeJS classes.

References: [KubeJS UI support](https://low-drag-mc.github.io/LowDragMC-Doc/en/ldlib2/ui/kjs_support.html), [HUD](https://low-drag-mc.github.io/LowDragMC-Doc/en/ldlib2/ui/hud.html), [ArchItect](https://github.com/MatrixEcho-AI/ArchItect). ArchItect's agent loop, inspection lint and state transformations were inspected for design ideas. No upstream implementation was copied into this change.

## Implemented foundations

- World geometry: disjoint box boundary spans preserve the original coordinate emission order/material/transform semantics; no interior volume scan in walls/shell mode.
- Polygon geometry: `mode`, `thickness`, `holes`, `cap_top`, `cap_bottom`; concave outlines, ring validation and no implicit air writes. Hole boundaries form walls and strict hole interiors remain empty. Thickness uses center-to-edge planar distance `< thickness`; caps use the same thickness. Existing polygon default remains solid for compatibility; house design should explicitly choose walls/shell.
- Strict native interface data contract: stable widget IDs, arbitrary nesting, LSS styles, scoped resource IDs, data bindings and declarative event intents.
- Scoped interface session: optimistic revision checks, candidate replacement, stable input draft preservation, stale callback rejection and passive HUD interaction state.
- Shared LDLib2 widget builder plus isolated KubeJS construction program, without global reload. Invalid LSS declarations fail the candidate instead of silently being dropped by LDLib2's permissive parser.

## Pending integration and acceptance

The foundations above do not yet replace production F2/Ctrl+M or remove MCEF. Required remaining work includes complete workspace port, server lifecycle/persistence/synchronization and application action routing, native HUD registration/restoration, migration of generated HTML content and tools, preview/IME/accessibility parity, MCEF removal and packaging cleanup, persistent construction components/diffs/history, semantic verification gating, and actual in-game acceptance.

Core tests cover geometry equivalence, holes/caps/rejections, failed replacement, retained input, incremental data updates, scope/revision rejection and passive interaction. Tests must run on public GitHub Actions under the repository's no-local-compilation rule; native rendering and KubeJS execution require separate in-game evidence. Until such evidence exists, this is not a usable replacement build.
