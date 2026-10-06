# Modern 26.x mobility and combat port

Updated 2026-10-06. Version **1.0.33 candidate**, built exclusively by public-main GitHub Actions. No Release, production installation, save/config replacement, or modern-model replacement occurred.

| Target | Build / native state | Delivery boundary |
| --- | --- | --- |
| 26.1.2 / NeoForge 26.1.2.106 | Both Python editions build; native movement, combat, UI/inventory lifecycle and targeted non-PvP regression verified below | Full existing Mod remains; candidate for isolated testing |
| 26.2 / NeoForge 26.2.0.88 | Full production source builds; native movement, combat and lifecycle pass | Experimental: official compatible KubeJS unavailable |
| 26.3 / NeoForge 26.3.0.51-beta | Full production source builds; native movement passes; lifecycle, combat and non-PvP work pass under the recorded JVM conditions | Experimental: official compatible KubeJS unavailable; default Windows native startup remains unstable |

Official LDLib2 dependencies are 26.1.2.41, 26.2.2.42 and 26.3.2.41, respectively. Exact official IDs/hashes are pinned in `gradle/modern-targets.json`. Neither the official KubeJS Maven nor Modrinth listed a 26.2/26.3 build when checked; the available modern build was 26.1.2-8.0.6. Built-in LDLib2 panels work independently. KubeJS-dependent dynamic interfaces retain their source and explicit dependency rejection, rather than being removed. Newer targets remain `probeOnly`; ordinary packaging refuses them and explicit compatibility/native probes produce clearly named experimental JARs.

## Reuse and requirements

The baseline was the existing modern Mod, with legacy189 .6/.8/.9/.10 code and native acceptance as references (see `legacy189/README.md`). Modern implementations were retained and extended:

| Requested area | Implementation and scope |
| --- | --- |
| Navigation | Retain SurfacePathfinder/TerrainPathSearch/NavigationRetry; slice/tick-invalidated state, floor and collision caches; collision-checked direct/diagonal paths; bounded sliced search and short retry; continuous collinear intermediate waypoints. Attack/mining/placement alignment remains precise. |
| Arena edges | Collision reads use loaded world geometry independently of editable wool bounds. Walk cells, protection and cleanup share arena constants; cleanup ends at the arena's editable height319, including in taller versions. Permanent map blocks remain protected. |
| Drops and recovery | Invoke the target's native calculateFallDamage, enchantment protection and resistance, then check health/absorption and real landing collision/fluids/enemy pockets. PvP alone permits one-way drops and native consumable wool columns. Non-PvP return/reusable-building checks remain. |
| Secondary equipment | Independent secondary hotbar slot3, supply slot2, wool and real offhand. Legacy six-slot profiles gain empty additions. No default golden apples. Native sword/bow selection, ammunition, shears and checked pearl trajectories/landing/consumption are retained. |
| Combat and animation | Actual health/absorption damage drives escape and counterattack; native sprint resets, S-tap/jump-tap and collision-constrained motion/projectile prediction. Short-horizon sub-Tick arrow candidates let a pursued archer return fire. Native use state synchronizes charge, release, cancellation and tracking re-entry. |
| Native diagonal sprint | Prepare exact +/-45-degree yaw with forward/strafe in the same Tick before native travel, only when terrain/collision/item/jump constraints permit. No added movement attribute, velocity, jump or knockback. |
| Lifecycle and inventory | Stop/equip/respawn clear use state and synchronize selected slot/full inventory. Native useItemOn owns placement and rollback; block and inventory correction occurs after return. Bounded cleanup is cancellable; round projectiles/inside drops are cleared and outside items survive. |

Modern attack cooldowns, shield/offhand mechanics, all non-PvP modules, neural decision participation, feature/model schema and modern weights remain. The legacy removal of attack cooldown was not ported. Existing optional enhancement functionality was not removed; PvP acceptance keeps boost disabled and AI base speed0.1/max health20.

## Measured 26.1.2 results

All automatic runs are isolated **FIXTURE_ONLY**, with zero provider requests. Test-opponent health80 gives a measurement window; AI health/attributes are not increased during fights. Independent scenes may reset starting health between scenes, never during pressure.

| Scenario | Native result |
| --- | --- |
| 40-Tick straight / guarded diagonal pursuit | 10.88719 / 11.08090 blocks, +1.7792%; diagonal input used36/40 Ticks |
| 12-block left-edge route | First movement1 Tick; arrival44 Ticks; one checked direct plan |
| Four-block descent | Arrival21 Ticks; predicted and actual damage1 |
| Native column / shears | Three wool consumed,34–35 Ticks; wool sheared in5 Ticks |
| Narrow elevated ledge beside wall | Arrival27 Ticks, no fall |
| New obstacle inserted after movement began | Detour arrival45 Ticks; lethal/unloaded/lava/crowd rejection and cache invalidation checked |
| Bow tracking | Charge73 Ticks, tracking re-entry1, real arrow consumption, release and cancel return observer to idle |
| Original movement-window server time | Mean2.587 ms, P954.554 ms, max9.524 ms; scene-specific, not all-world latency |
| Latest full combat | Backup bow fires/hits, melee switch hits, approach pearl consumes1/does5 damage, pursued archer fires, actual pressure/counterattack, longest combo15, five observed tactical jumps |
| Pearl escape | One native pearl consumed,13.57390-block displacement,5 native damage |
| Moving/jumping opponent landing forecast | Latest maximum error .32401 blocks /1 Tick; earlier independent19-landing sample .60831 blocks /2 Ticks |
| High falling attacker | Earlier extended scene recorded2 early falling-contact evasions and actual jump-tap sprint resets |

Same-scene original1.0.31 A/B uses the mature door/slab/crouch/water/ladder fixture. Both execute138 steps,30 crouching,1 door,15 climbing and35 swimming steps. Original arrival was observed163 Ticks after command completion; candidate160 Ticks, with5-Tick polling. **Do not claim a successful-route speedup percentage from this small difference.** The original has no comparable internal search timer. Candidate internal metrics were first movement8 Ticks, arrival165 Ticks,31.416 ms total search and9.913 ms worst slice. These use a different server-side timing origin from client polling.

The sealed-route failure is a clear improvement: **65 ->15 Ticks**, with exactly three failed searches in both versions. Candidate search time was .312 ms. The first candidate run reached the traversable route but failed the historical >=60-Tick retry assertion; that failure remains. The new test requires12–80 Ticks AND three failures, not immediate unbounded retries.

Pressure outcomes vary. One earlier successful run ended at AI health .320005; another at8.880004, and an earlier candidate died. Do not advertise immunity to combo pressure or a measured human win-rate gain. The old contact-escape branch only struck blockers in front; allowing the in-range pursuer as a native counterattack target fixed its observed no-counter failure. Two later pearl-escape failures exposed extra ground/second-hit gates: native pearls may be thrown during knockback, and waiting for a second heavy hit can exhaust the health reserve. Current logic evaluates a collision-checked retreat after confirmed heavy contact while still retaining the conservative teleport damage reserve.

## UI, inventory and non-PvP acceptance

Actual LDLib2 press/release and server round trips select human pearls, AI shears, human golden apples and wool. Chinese/English screenshots were inspected at actual scale4 in26.1.2, scale3 in the initial smaller26.2/26.3 windows. The footer now says **Close**, while the wool toggle keeps **Off**. Item names follow Minecraft's language independently of the Mod UI setting.

The real-client lifecycle fixture performs four placements and three restarts. Successful placement is64->63 with wool visible on both sides; cleanup rejection is63->63 with air. A deliberately cancelled native EntityPlaceEvent verifies actual rollback64->64/air, then a subsequent successful placement. Cancellation during cleanup and reopening succeed. An inside dropped diamond is removed and a lobby diamond survives. Death while holding a native golden apple, followed by a real respawn, leaves both sides idle with matching selected slot and three unfinished apples retained.

Non-PvP native regression passes harvest/replant, live-entity following across the mixed traversal course, cancellation, pit/roof recovery, no excess pillaring, and known-player-terrain protection. A short original zombie-combo fixture completed its kill with combo3/sprint hits3/jump taps2 but failed its mandatory STAP counter because the target died first; preserve that failure. The extended durability fixture retains the original assertions and only raises the fixture opponent's health to80. Do not claim every historical native suite was already green.

## Version-specific adapters and limits

`gradle/modern-api.gradle` generates target source without rewriting26.1.2 originals. Explicit26.2/26.3 overlays cover changed registry/color APIs, HUD/screens, native capture/rendering, resource-pack suppliers, entity interpolation, item/brewing/block-transform APIs and SDL input/dialogs.26.3 native brewing replaces removed accessor bindings; ore JSON uses the new data-driven feature codec. These are full production builds, not reduced PvP modules.

26.2 independently measured the same40-Tick diagonal result (+1.7792%), edge44, drop1, column3/35, shears5, and bow73/re-entry1. Full lifecycle passes. Latest combat independently passes combo14, seven tactical jumps, escape pearl13.95113 blocks/5 damage and landing error .32401 blocks. These results do not certify KubeJS or every unrelated Mod integration.

26.3 independently passes the complete movement/geometry suite through phase14, including the same +1.7792% sample, narrow ledge27 and changing terrain45. The first client placement test failed because partialTick0 used the previous rotation; native action rays now use current Tick1, preserving camera interpolation and actual native collision. The complete lifecycle subsequently passes with **Temurin25.0.4.1, -XX:ActiveProcessorCount=4 and -Xcheck:jni** in its receipt. With only -XX:ActiveProcessorCount=4 (without JNI checking), non-PvP harvest/replant/follow/cancel also passes in `build/mc263/runs/c6b2b0ce`, and full combat passes in `c21fd053`: combo15, seven tactical jumps, escape pearl12.89593 blocks/5 damage and landing error .89711 blocks. These conditional results do not certify default launch stability.

Several default26.3 Windows startups exit0xc0000005 during resource loading. Windows reports an unknown module and no usable Java fault stack; the same symptom occurs with official25.0.1 and25.0.4.1 runtimes. A dependency-only control without DivZero reached GUI startup, but a single successful control cannot assign root cause. Diagnostic JVM arguments are confined to isolated runs and were not installed into production.26.3 stays experimental until this default-startup issue and official KubeJS availability are resolved.

## Evidence index

Local paths are relative to the implementation repository; logs/JARs/saves remain ignored and are not exported to GitHub.

| Evidence | Local run |
| --- | --- |
| Original1.0.31 navigation | `build/native-ui-runtime/ab60d737-1209-4bd6-ad85-f9a1bdebafd0` |
| Candidate navigation | `build/native-ui-runtime/637e4dda-483a-452a-91ac-703c8e409f49` |
| Extended movement/geometry | `build/native-ui-runtime/d3bb2d17-3410-4f93-a46b-af7b74fbefb8` |
| Full latest26.1.2 combat | `build/native-ui-runtime/f9999139-a210-4b23-b87e-823b8d50b160` |
| Independent19-landing sample | `build/native-ui-runtime/e8218fd5-1a5a-428a-9a5c-29883ae58f7b` |
| Full26.1.2 lifecycle | `build/native-ui-runtime/ebdf416b-8280-421a-bc6a-d093cabda9f2` |
| Non-PvP work / recovery | `build/native-ui-runtime/1ae9bf3c-014c-487f-8519-81187058c484` / `26854f9b-6402-4be6-8ffc-2da9da42b014` |
|26.2 movement / lifecycle / combat | `build/mc262/runs/e9bc9e02` / `e710d2bc` / `e79c5d16` |
|26.3 default movement / diagnostic lifecycle | `build/mc263/runs/44e755bc` / `1ff74b82` |
|26.3 native exit / dependency control | `build/mc263/runs/e01d83b0` and `e7a5c5f9` / `a129a08d` |

Successful code build9491209: [both Python editions](https://github.com/gaoshanliuni/divzero/actions/runs/37435012351), [both target probes](https://github.com/gaoshanliuni/divzero/actions/runs/37435012923). Follow-up fixture-only validation uses Actions37436641023/37436641417; native absorption-buff validation is still being completed. All public synchronization uses allowlisted committed blobs and DivZero's independent main history; no private history push, secrets, local runtime outputs, or replacement of public release documents.

Preserved failure history additionally includes the first column drift timeout, close-arrow intercept failure, pressure death, each API/mixin/resource migration failure, the fixture Zombie-package compile error, incomplete-food input cancellation, and previous escape failures. The old public-export Python suite still has two historical browser/suffix expectations; the actual guarded export succeeded, but that old suite is not presented as green. The two pre-existing PackageAssetSmoke files remain outside this task's commits.
