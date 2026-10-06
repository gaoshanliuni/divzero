# Modern 26.x mobility and combat port

Status: candidate implementation; Actions and native acceptance pending. This page does not certify a supported 26.2/26.3 release.

## Reuse and version boundaries

- Source baseline: modern 1.0.32, with the completed legacy189 .6/.8/.9/.10 acceptance recorded in legacy189/README.md.
- Keep SurfacePathfinder, TerrainPathSearch, NavigationRetry, native ranged adapters, combat footwork, model inference and all non-PvP modules. Do not copy legacy mappings, packets, slot indices or the legacy removal of attack cooldown.
- New traversal cache entries expire at each slice/tick. Routes execute against fresh collisions; loaded collision queries are independent of the wool editing boundary. Direct checked routes and diagonal graph edges reuse the legacy traversal approach.
- Damage planning invokes the current game's calculateFallDamage, then reads native enchantment protection and resistance. No planning call applies damage. Real health/absorption and collision/landing checks bound one-way drops.
- Only marked-map active PvP participants may use the new one-way recovery branch; ordinary-world reusable-building checks remain.
- Secondary slot 3 and supply slot 2 are independent of main hand, wool and real offhand. Old six-slot profiles add empty choices. Supplies are not given by default. Bow/crossbow ammunition is supplied only when selected.
- Native useItemOn owns placement/rollback/consumption. Authoritative inventory, selected slot and block updates are sent after return. No speculative manual stack decrement or refund.
- AI PvP movement uses native forward/strafe input before vanilla travel. Exact +/-45 yaw preserves world heading. No movement attribute, jump impulse or knockback multiplier is increased. The existing non-PvP controller remains available to its existing tasks.
- Remote item-use state supplements native equipment tracking with elapsed charge, cancel and tracking-entry state. It does not fire or consume items on the observing client.
- Predictions use observed acceleration and the native swept-box collision routine. Recent real damage marks the impulse transition uncertain. Neural feature schema and modern weights remain unchanged; no 1.8.9 weights are installed.

## Acceptance to complete

- Actions: both Python editions and all exported tests.
- Native: same-version straight/diagonal 40-Tick distances, edge/drop start and arrival time, search time and worst slice; narrow ledges and changing terrain.
- Native: wool column, native shears, secondary switching, pearl consumption/landing/damage, bow remote animation and tracking re-entry.
- Native: cooldown-aware combos, sustained real damage, bounded ranged retreat, restart/respawn/client placement prediction, cleanup and unrelated non-PvP regression.
- All automatic fixtures are FIXTURE_ONLY. Failures must remain recorded; builds alone do not constitute native acceptance.

## 26.2 and 26.3 dependency check (2026-10-06)

Mojang lists both game versions as releases. NeoForge Maven lists 26.2.0.88 and 26.3.0.51-beta. Modrinth lists LDLib2 FiGmJmpx for 26.2 and CcNvp4yE for 26.3. Neither Modrinth nor the KubeJS official releases Maven lists a 26.2/26.3 KubeJS build; its modern build is still 26.1.2-8.0.6. Full dynamic-interface compatibility cannot be claimed without a compatible dependency and native verification. Keep that capability and the existing version constraint; do not relabel the 26.1.2 package as compatible.
