# Native UI installation

The native workspace uses **LDLib2 with its MC theme**. F2 does not require KubeJS. AI-created interfaces additionally use the DivZero KubeJS bridge. There is no browser renderer fallback.

| Artifact | Purpose |
| --- | --- |
| DivZero main JAR | Runtime, permissions, synchronization and built-in workspace |
| LDLib2 `26.1.2.41` | Required client UI and HUD renderer |
| KubeJS `26.1.2-8.0.6` | AI-created scripted native interfaces |
| Better Advanced Tooltips `2601.1.0-build.9` | Required by the pinned KubeJS version |

Rhino is already embedded in DivZero. Do not install MCEF or WebGUI for this version. Older browser packages require an explicit native-interface rewrite; the package migration workflow preserves unrelated resources, identity and gameplay code.

First entry into a world/server presents Enable and Disable chat actions. Enable takes effect without leaving the world. F2 opens the native workspace; right-clicking an AI opens its profile. Dynamic HUDs are passive until an explicit interaction action.

Dependency sources and licenses are recorded in `DEPENDENCIES.json` in the build artifacts. [LDLib2](https://github.com/Low-Drag-MC/LDLib2) and [KubeJS](https://github.com/KubeJS-Mods/KubeJS) use LGPL-3.0-only; [Better Advanced Tooltips](https://github.com/latvian-dev/better-advanced-tooltips) uses MIT. Exact downloads are hash-verified during packaging.

This installation description does not certify every migration scenario. The [current capability and acceptance status](NATIVE_UI_MIGRATION_STATUS.md) summarizes the migration; exact evidence and historical failures remain in [the migration record](NATIVE_UI_BUILDING_MIGRATION.md). Existing release documents describe their own historical versions.
