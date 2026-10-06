# Modern 26.x mobility and combat port

Status at the 2026-10-06 interruption: **partially implemented and verified; not a completed delivery**. The 26.1.2 candidate builds, but sustained melee pressure still fails. The 26.2 full production source compiles and produces an explicitly experimental artifact; native startup/gameplay remain unverified. The 26.3 build still fails. No Release or production installation was performed.

The user selected official versions and explicitly asked to mark capabilities that are not ready. KubeJS remains unavailable for the newer targets; its functionality has not been removed.

## Actual results and remaining work

Latest implementation build: [Actions 37412906352](https://github.com/gaoshanliuni/divzero/actions/runs/37412906352), successful 26.1.2 builds/tests/packaging for both Python editions. [Target checks 37412906804](https://github.com/gaoshanliuni/divzero/actions/runs/37412906804) produced the 26.2 experimental JAR; the 26.3 job failed. Compiler logs are retained locally as well as in Actions artifacts. Compilation alone does not validate mixin injection or gameplay.

The 26.1.2 native mobility fixture passed on public source `df70cec`, and the extended observer fixture passed on `1476fb7` (including the bundled-Python edition):

| Scenario | Observed result |
| --- | --- |
| 40-Tick straight / diagonal pursuit | 10.88719 / 11.08090 blocks; +1.7792%, with diagonal input used on 36 of the 40 Ticks |
| Left arena edge, 12-block route | First movement after 1 Tick; fixture arrival after 44 Ticks; one checked direct plan |
| Four-block drop | First movement after 1 Tick; reached the lower target after 21 Ticks; predicted and actual damage both 1 |
| Vertical column | Three actual wool blocks consumed; 34–35 Ticks to stand three blocks higher |
| Native shears | Correct tool selected; wool broken in 5 Ticks |
| Bow / tracking | Native charge and one-arrow consumption; observer saw 73 charge Ticks, one tracking re-entry, release and cancel returning to idle |
| Inventory / protection | Selected slot reset to 0 on re-equipping; server-native wool placement consumed one; permanent floor break rejected |
| Movement fixture server timing | Mean 2.587 ms, P95 4.554 ms, maximum 9.524 ms, including the fixture's movement window |

These are scene-specific measurements. There is not yet a same-scene original-26.1.2 A/B baseline for navigation latency; do not present the legacy measurements or the new absolute timings as a measured navigation speedup percentage. The sprint number is the observed guarded pursuit behavior, not a promise of the same gain on every terrain or game version.

The actual combat-controller fixture on `1476fb7` confirmed two backup-bow arrows and native damage, switching back to the sword at close range, and one consumed pearl teleporting approximately 13.67 blocks with 5 native damage. It then failed because a pursued archer repeatedly cancelled charging without shooting. Sub-Tick ballistic candidates and fractional target interpolation were added in `7c9f3e0`; its native rerun progressed through the pursued-archer phase into sustained melee pressure. **The AI then died in the pressure phase, so this combat suite did not pass.** Keep its round archive and missing overall PASS visible; do not claim the combo/escape objective has been met.

Still required: sustained-pressure escape/counterattack repair; independent combo, jump-tap and landing-prediction accuracy measurements; real-client repeated restart/respawn/denied-placement prediction tests; actual secondary/supply menu clicks and bilingual scaling checks; non-PvP native regression; native 26.2/26.3 measurements. Modern feature/model weights were not replaced with legacy weights.

Preserved failures include the initial missing 1.0.33 packaging note, a subsequent local-variable compile error, first column timeout (fixed by releasing sprint and letting native momentum settle before jumping), pursued-archer failure, pressure-phase death, and each target-API compile failure. The historical `test-public-snapshot.py` also still contains two stale browser/suffix expectations; the guarded actual export succeeded, but this old Python suite must not be reported as green.

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

## Acceptance scope

- Actions: both Python editions and all exported tests have passed on the 26.1.2 candidate; this does not certify the pending native scenarios.
- Native: same-version straight/diagonal 40-Tick distances, edge/drop start and arrival time, search time and worst slice; narrow ledges and changing terrain.
- Native: wool column, native shears, secondary switching, pearl consumption/landing/damage, bow remote animation and tracking re-entry.
- Native: cooldown-aware combos, sustained real damage, bounded ranged retreat, restart/respawn/client placement prediction, cleanup and unrelated non-PvP regression.
- All automatic fixtures are FIXTURE_ONLY. Failures must remain recorded; builds alone do not constitute native acceptance.

## 26.2 and 26.3 dependency check (2026-10-06)

Mojang lists both game versions as releases. NeoForge Maven lists 26.2.0.88 and 26.3.0.51-beta. Modrinth lists LDLib2 FiGmJmpx for 26.2 and CcNvp4yE for 26.3. Neither Modrinth nor the KubeJS official releases Maven lists a 26.2/26.3 KubeJS build; its modern build is still 26.1.2-8.0.6. Full dynamic-interface compatibility cannot be claimed without a compatible dependency and native verification.

`gradle/modern-targets.json` pins the actual loader and UI hashes. `gradle/modern-api.gradle` generates target sources from the shared implementation, with explicit capture/rendering adapters; it does not rewrite the 26.1.2 sources or exclude non-PvP features. The 26.3 adapter uses native SDL key codes and RenderPearl types; the remaining build errors include resource-pack and brewing API changes. Both newer targets remain `probeOnly`: ordinary packaging refuses them. The explicitly requested `compatibilityProbe` + `nativeProbe` path creates a clearly named experimental artifact for isolated acceptance only.
