# Erekir turrets and drills: what human players say (2026-10-19)

The question: what each Erekir turret and drill is good for, according to players, and what follows for the AI's defence planner and mining. Target version: v160.7.

Related files (this one does not repeat them):
- `erekir-late-spec.md` §5 (drills) and §8 (turrets) hold the exact source stats. Numbers below come from there or from `Blocks.java` directly.
- `meta-report.md` §2–3 has the unit-versus-turret counter table and the PvP view.
- `research-2026-10-18/human-play-en.md` / `-zh.md` hold the sector-by-sector advice.

## 0. How to read this

**Tags**
- **[Q]**: what a source says, quoted or closely paraphrased.
- **[I]**: my inference.
- **Source check**: a claim tested against v160.7 source (`/home/user/src-ref/mindustry/core/src/mindustry/`).
- *Rates I calculate* (items per second while firing, water and heat needs) come from source fields: ammoPerShot, ammoMultiplier, reload, reloadMultiplier, coolantMultiplier.

**Source shorthand**

| Tag | What it is |
|---|---|
| `FW/X` | Fandom unofficial wiki page `https://mindustry-unofficial.fandom.com/wiki/X`, raw wikitext through `api.php`. The date is the page's last edit. |
| `FWC/X` | Article comments on that page (`wikia.php?controller=ArticleCommentsController&method=getComments&title=X`), dated per comment. |
| `SG` | Steam guide "The Turrets of Erekir: A Defense Guide" by **Egui**, 2023-04-30, https://steamcommunity.com/sharedfiles/filedetails/?id=2968050682. Written in the v141–146 era, before the v147 ammo additions. |
| `SGC` | Comments on that guide. |
| `ST/<id>` | `https://steamcommunity.com/app/1127400/discussions/0/<id>/` |
| `YT/<id>` | `https://www.youtube.com/watch?v=<id>`. Descriptions and comments only; captions were blocked. |
| `BV…` / `cv…` | `https://www.bilibili.com/video/BV…` / `https://www.bilibili.com/read/cv…`. Descriptions plus the first three hot comments; anonymous access shows no more. |
| `ZH` | A quote already collected in `research-2026-10-18/human-play-zh.md`, cited there with its original BV or cv id. |
| `GH4913` | https://github.com/Anuken/Mindustry-Suggestions/issues/4913, 2024-03-17. |

**Version dates**
- Before 2025-04: v7 (b136–146).
- From 2025-04: the v8 beta (b147 added the alternative Erekir ammo).
- 2026-03: b156 rebased heat.
- From 2026-04: v8 stable (b157).

Old heat numbers ("6 electric heaters for 180 %", "Malign 90 heat") and "Disperse only uses tungsten" are pre-v8 and wrong now.

---

## 1. Summary table

Ranges are in tiles. Rates are per turret while it fires.

| Turret | Targets / pattern | Range | Stage players use it | Best ammo (why) | Main weakness / counter | Verdict for the AI |
|---|---|---|---|---|---|---|
| **Breach** 3×3 | air + ground, piercing single line | 23.75 (W 28.75, carbide 30.75) | Onset → Basin; "replace ASAP" | Be early; **W** when available (range, 4 pierce); carbide late (325 + frags) | overrun by massed T2; Averts cut its Be line; ammo hungry with water (Be 7.5/s) | First choke turret, watered, 2–3 per choke; keep later as cheap W/carbide filler |
| **Diffuse** 3×3 | air + ground, close cone, knockback | 15.6 | Lake → mid-game | graphite early (knockback); **oxide** when produced (damage, armor) | outranged by almost everything; eats ammo (graphite 6/s, 15/s watered) | Only at the choke mouth against leg mechs and short-range rushes, with a container buffer |
| **Sublimate** 3×3 | air + ground, armor-piercing flame, pierce | 16.25 (cyanogen 25) | Marsh → endgame (cyanogen) | **ozone** 15/s; **cyanogen** 18/s (2.2× dps, +8.7 range) | all T1/T2 ground units outrange ozone; gas lines explode | The main early/mid ground killer at chokes; cyanogen version is a late staple |
| **Titan** 4×4 | **ground only**, artillery splash | 48.75 (carbide 58.75) | Stronghold → late | Th all-round; **oxide** for swarms; carbide vs big units | no air; slow shells miss fast units; needs H₂ 5/s | Rear-line ground artillery near electrolyzers, always paired with AA |
| **Disperse** 4×4 | **air only** flak, also kills missiles | 38.75 (Th 23.75, surge 41.75) | Stronghold → endgame | **W** (cheap, 3 ammo/item); surge late; avoid Th (−15 range) | no ground (cannot hit Eludes); ammo drain from range edge; supply cut | The AA turret: two covering every point, fed by cargo drone or local W |
| **Afflict** 4×4 | air + ground, frag orb, kills missiles | 46 (+frags) | Ravine → endgame; "best Erekir turret" | power 300/s + heat 20 | dies when power/heat is cut; slow fire, swamped by 15–20 missile units; Obviate dives | Default generalist once surge exists; protect its heat and power |
| **Lustre** 4×4 | air + ground, slow point laser, targets strongest | 31.25 | Crevice → endgame | power 200/s + nitrogen 6/s (only while firing) | swarms, two-direction attacks, Collaris outranges it | Boss/heavy killer behind Sublimate/Titan; never alone |
| **Scathe** 4×4 | **ground only**, cruise missile, targets strongest | 168.75 | Siege+ niche | carbide (reliable); **phase** (shielded missile, cheap per shot); surge (burst) | missiles shot down or absorbed by walls and ducts; very expensive; low dps | One at most, as area denial / radar / core sniper; not a line defence |
| **Smite** 5×5 | air + ground, 10 piercing bolts + lightning | 37.5 | Karst → endgame | surge 2/salvo (1.2/s, 3/s watered) | expensive (phase, surge); single line; Quell/Disrupt outrange | Endgame line holder on a straight approach, watered, surge by cargo |
| **Malign** 5×5 | air + ground, homing flak + lasers, targets strongest | 51.25 | post-Karst, Origin | power 2400/s + heat 144 | huge heat; swarmed by ~20 Quells; heat source is the weak point | Only with phase-heater or neoplasia heat, against high-tier approaches |

| Drill | Mines | Players use it for | Boost worth it? | Verdict for the AI |
|---|---|---|---|---|
| **Plasma bore** 2×2 | wall Be, graphite, sand walls | all early Be/graphite | **yes**: H₂ 0.25/s → ×2.5; boosted beats an unboosted large bore per tile | Mass-build on wall faces, boost as soon as one electrolyzer runs |
| **Large plasma bore** 3×3 | wall Be, graphite, **W, Th** | the only source of wall W/Th | N₂ 3/s → ×2.5 when nitrogen is spare | Use for W/Th walls; for Be/graphite prefer small boosted bores |
| **Impact drill** 4×4 | floor Be (×2 speed), W, sand; not Th | first tungsten (Aegis) | ozone 3/s → ×1.75 saves power over a second drill | Floor W/Be when 10 water/s is available; cover 16 ore tiles |
| **Eruption drill** 5×5 | floor Be, W, **crystal Th**, sand | thorium floors, big W/Be patches | cyanogen 0.75/s → ×2, cheap once cyanogen exists | Thorium floors and big patches; defend it, it is a priority target |
| **Cliff crusher** 2×2 | sand from walls | sand for silicon | – | Put it next to the arc furnace; sand cannot be stored in the core |
| **Large cliff crusher** 3×3 | sand | late sand (phase 12 sand/s) | graphite ×1.6 | Build for phase/silicon scale-up |
| **Reinforced pump** 2×2 | liquid floor (slag, arkycite) | slag and arkycite for power, surge, heat | – | H₂ 1.5/s each; full 2×2 on the liquid |

---

## 2. Turrets

### 2.1 Breach (撕裂)

**Role.** Item turret, 3×3, air and ground. Fires piercing bullets over 23.75 tiles; 1.5 shots/s, ×2.5 with water 15/s.
- Be ammo: 63.75 damage, pierce 2.
- W ammo: 71.25 damage, +5 tiles range, pierce 4.
- Carbide ammo: 325 damage + 3 frags of 227, +7 tiles, but only 0.2× fire rate.
- Building damage is 0.3× with every ammo.

**How people rate it**
- [Q] Holds "until tier 3 units begin showing up, just with Beryllium". It is "surprisingly expensive, so you should reinforce your mining before building many"; Stell and Elude "is usually a better option for the early-game". (FW/Breach, 2026-09-07)
- [Q] "Acceptable early game, but try and replace it with something better ASAP"; it "struggles with Merui and especially Cleroi unless supplied with tungsten". (SG)
- [Q] "Breach turrets are barely okay, initially, but mixing in some Sublimates will be your biggest punch… upgrading your Breach turret ammo to Tungsten will also help." (Kyrros, ST/3593338530537447476, 2022-11-27)
- [Q] One player defended Basin and Intersect "only of breach turrets fed by beryllium. 5-6 on intersect and 10-12 on basin" (MefistoSV, ST/3810657379793594901, 2024-02-11). Another answered that resource rates there are too low for that (same thread, 2026). [I] It works only on wave-light early maps.
- [Q] The YouTube tier-list author later moved Breach down to D: "it got carbide but thats no better; compared to other buffs this one just got worse." (JTM, YT/8nZIiMJ5vHs pinned comment, about 2025) Commenters disagree:
  - "If wasn't for water I would never used breach"
  - "The breach is so annoying on lake" (as an enemy turret).
- [Q] With carbide it is "able to take down a Quell by itself… also destroy groups of tier 3 units… with one single Surge-walled Carbide Breach". Carbide cost "is not really a worry unless the turret is constantly shooting". (FW/Breach)
- [Q] Chinese, 2022 (v136): "重要的是子弹和加水……撕裂炮配上钨作为子弹……敌人没来的时候可以用储液罐收集水，一个发电机的水就够两三个炮台". (ZH, BV1PV4y1s7ug) That is: ammo and water matter most, use tungsten, store water in tanks between waves, and one turbine's water covers 2–3 turrets.
- [Q] Chinese, Origin 2026: the air-defence line over the slag sea is "驱离+钨撕裂+T1/T2 飞机" (Disperse + tungsten Breach + T1/T2 ships). (ZH, cv44580757)

**Ammo**
- Be is the default.
- Switch to **W** as soon as tungsten flows: longer range, 4 pierce, and it shares ammo with Disperse. [Q] It catches units that hang back, such as Obviate and Anthicus (FW/Breach).
- Carbide turns it into a heavy single-target turret.
- Source check, rates while firing:
  - Be: 2 items per shot (ammoMultiplier 1, ammoPerShot 2) → **3 Be/s, 7.5 Be/s watered**.
  - W: 1 item per shot → 1.5/s, 3.75/s watered.
  - Carbide: 0.3/s, 0.75/s watered.
- [I] Watered beryllium Breaches compete hard with construction for beryllium. Tungsten ammo is actually cheaper per second.

**Weak against.**
- Massed T2 (Locus overruns Breaches, ST/3810657379793594901).
- Averts sniping its beryllium supply (same thread).
- Elude micro: [Q] "cheese the breach turrets with this. Manually control them and dodge" (FWC/Elude).

**Special uses**
- [Q] Turret creep. "Your Breach might take out an enemy Breach 1 tile out of range through AoE damage on their walls." Also: "placing some small walls down lets enemy turrets go low on ammo." (xBleacheDxSungasMx, ST/4136060426843421871, 2026-07-13)
- [Q] Chinese: "撕裂炮台可以被管子挡住，它不会主动攻击管子但子弹仍会打到管子上" (ZH, cv25184550). The Breach never aims at pipes, but its bullets still hit pipes in the way.
  - Source check: Breach, Diffuse, Sublimate and Scathe set `targetUnderBlocks = false`, so they never target ducts or conduits, but their projectiles still collide with them.
  - [I] Ducts in front of the AI's units soak Breach fire. Ducts in front of the AI's own walls are also safe from enemy Breaches' aim.

**Placement and support**
- [Q] "3 water-cooled Breaches and Large Beryllium Walls" at the choke (FW/Basin).
- [Q] Duct bridges chained as ammo buffers (KarinFysh, ST/3810657379793594901).
- [Q] "I had every section walled off with Breaches" (RiptheBlood, ST/601894472784113557, 2025-01).

**AI rule**
- Before thorium, defend a choke with 2–3 Breaches plus walls 1–3 tiles ahead.
- Water them only when water is spare: 15/s each, which is three turbine condensers' by-product, consumed only while reloading. Buffer that water in a tank.
- Feed tungsten once impact drills run.
- Do not build more than about one Breach per 2 Be/s of spare beryllium income.
- Never rely on Breaches against T2 swarms.

### 2.2 Diffuse (扩散)

**Role.** Item shotgun, 3×3, air and ground, 15.6 tiles. Fires 15 pellets ×2 salvos/s with strong knockback; ×2.5 with water.
- Graphite: 30.75 per pellet, knockback 4.
- Silicon: 26.25 per pellet, homing.
- Oxide: 67.5 per pellet, 2 ammo per item.
- Building damage 0.2×.

**How people rate it**
- [Q] "Best used in tight spaces where all enemies concentrate… making one-block notches in your own walls that funnel tanks and Eludes." "Merui and Cleroi get hard-countered by this turret in the campaign as their behavior forces them to walk directly over the turret." (FW/Diffuse, 2026-09-07)
- [Q] "Diffuse and Breach cover each other's weaknesses well… I find myself preferring Diffuse in most situations where I need to deal with some T1 and T2 enemies." (SG)
- [Q] "diffuse go brrrrrr (it pushes back annoying mechs)" (KarinFysh, ST/592895726921500459, 2025-05-03)
- [Q] "I've had 2 water cooled Diffuses absolutely shredding Locus'" (osen8063, YT/8nZIiMJ5vHs, about 2026-04).
- [Q] Negative: it "drains too much Graphite for ammo, crippling your building and production"; "almost completely useless in the early game, as there is almost never enough Graphite" (PLMMJ, ST/3810657379793594901, 2023).
- [Q] Chinese, about v155: "个人认为扩散和升华是E星前期最严厉的父亲" (ZH, BV1bGFZz8ED5, 2026-02). Diffuse and Sublimate are the harshest early-game turrets. A Chinese Stronghold plan: "T3 出来之前，用扩散 + 升华 + 再生投影防守" (before T3, defend with Diffuse + Sublimate + Regen Projector) (ZH).
- [Q] Knockback can hold "low numbers of Vanquish" off your blocks (FW/Diffuse). meta-report cites "3 watered Diffuses hold one off a double wall" (FW/Vanquish).

**Ammo**
- Graphite is the easiest to supply and has the most knockback.
- Silicon: [Q] homing but "struggle[s] against tanks with their high armor".
- Oxide: [Q] "no longer struggles against high-armor units and can rapidly kill anything gathered into a choke point". (FW/Diffuse) Oxide was nerfed in b151, 90 → 67.5.
- Source check, rates while firing:
  - Graphite: 3 per salvo → **6/s, 15/s watered**.
  - Oxide: 1.5 per salvo → 3/s, 7.5/s watered. One oxidation chamber makes 0.5/s.
- [I] Watered Diffuse fire is only sustainable from a stockpile. Size the buffer for a wave, not for continuous fire.

**Weak against**
- [Q] "Stells and Merui can outrange it"; so do Anthicus, Quell, Disrupt and Obviate.
- [Q] Cut its transport and "the fast firing rate will eat all of the ammo inside the block". (FW/Diffuse)

**Placement**
- In the choke mouth or a deliberate 1-tile notch, so leg mechs that step over walls land on it.
- Back it with a Breach or Disperse for range, and a Reinforced Container as buffer (FW/Diffuse).

**AI rule.** Build Diffuse only where the path passes within about 10 tiles of the turret: notch funnels, the inside face of walls, and the core's last ring. Pick it when the threat is leg mechs (Merui/Cleroi) or swarms of Stell/Elude, and give it a container. Prefer oxide once oxide income exceeds about 1/s.

### 2.3 Sublimate (升华)

**Role.** Liquid flamethrower, 3×3, air and ground, ignores armor.
- Ozone: 15/s while firing, 540 dps, pierce 2, 16.25 tiles.
- Cyanogen: 18/s while firing, 1170 dps, pierce 3, 25 tiles.
- Building damage 0.3×. It needs no items and no power.

**How people rate it**
- [Q] "Extremely powerful for a very minimal cost, as the 540 DPS from just ozone is enough to shred groups of tier 1 units… has enough damage to even handle the Precept if walled off." "Place them along areas where ground units accumulate in a line." It counters "large blobs of Cleroi, as the flame jet cannot be hit by point defense." (FW/Sublimate, 2026-03-01)
- [Q] With cyanogen it threatens higher tiers. It "easily melts unit missiles coming its way". It is outranged by "particularly the Obviate" less often, making "an ideal complement to the Disperse". It has none of the big turrets' wind-up or slow rotation, which "makes it easy to pair with the Lustre". (FW/Sublimate)
- [Q] "4 Sublimates at choke points with tungsten walls and regen projectors can hold back basically everything on that map" (Siege; Venorik Barra, ST/3810656958854938509, 2023-09-03).
- [Q] Karst strategy: "establish competent ozone Sublimate defenses on each of the ground paths… Add more Sublimates with Reinforced Liquid Tank ammo storage" (FW/Karst/Strategies, 2025-05-31). Karst video: "spamming cyanogen sublimates at four locations" (YT/3dxR-XQeqPo, in human-play-en).
- [Q] Crevice: "rush some Sublimates and place them near the enemy spawn point"; "many Sublimates and Titans" beat the four guardian Tectas (FW/Crevice, 2025-07-19). Chinese Crevice: "7个升华加4个泰坦" one-shots the T4 boss (ZH).
- [Q] SG is lukewarm: ozone "is very useful early game for power and Oxide, so I find myself only using it when it's convenient", near a vent with an electrolyzer.
- [Q] Chinese Ravine: "劫难？造价太高，还是普质一般的好，比如说升华" (ZH, BV13Q4y1j7E6, 2024-01). Afflict is too expensive there; plain Sublimate works.

**Ammo and logistics**
- Source check: ozone 15/s while firing equals 3.75 electrolyzers per continuously firing Sublimate.
- [I] Real defences fire in bursts, so the working ratio is 1 electrolyzer per 1–2 Sublimates plus a liquid tank per pair.
- Cyanogen at 18/s is 1.5 synthesizers per Sublimate under continuous fire.
- [Q] "Both gases are highly volatile with disastrous explosions if any Reinforced Liquid Container or Tank is destroyed. Use Reinforced Bridge Conduits so fires do not quickly jump across lines." (FW/Sublimate)

**Weak against**
- [Q] "All tier 1 units outrange an ozone Sublimate." Locus "have a longer range than sublimate" (YT/hrqr1RUsH2I comment). Chinese Basin: "用 T2 坦克和升华拼射程" (outrange it with T2 tanks) (ZH).
- [Q] Surround it ("the AI has to waste some time locking onto each target"); knock out conduits "and hope for a chain reaction".
- [Q] The cyanogen version is outranged only by Tecta (by about 3 tiles), Conquer and Collaris. (FW/Sublimate)
- Source check: the Avert's 11.25-tile range cannot outrange even ozone.

**AI rule**
- At every ground choke from Marsh onward, place 2+ Sublimates where the path is a straight line through them, with walls 2–4 tiles ahead.
- Put a liquid tank behind them, fed by the electrolyzer that also feeds the regen projector.
- Move to cyanogen when the enemy fields Locus-range units (≥ 20 tiles) or missile mechs.
- Never chain gas tanks next to each other at the front.

### 2.4 Titan (泰坦)

**Role.** Artillery, 4×4, **ground only**, 48.75 tiles. Fires 0.43 shots/s, ×1.75 with water 30/s. Always needs hydrogen 5/s.
- Thorium: 262.5 + 350 splash.
- Carbide: 525 + 750 splash + frags, +10 tiles range.
- Oxide: 225 + 180 splash over a radius of 110 plus a lingering field.

**How people rate it**
- [Q] "Similar purpose to the Ripple… Tier 1 and 2 units are weakened severely after just a few shots… the area damage also filters out weaker units for the Lustre." Its shells "will not get absorbed by Anthicus missiles, becoming an effective counter". (FW/Titan, 2026-09-27)
- [Q] PvP: Locus rush works "provided that the enemy does not tech up to Thorium and begin getting Titans" (FW/PvP).
- [Q] "Overall a great turret for ground defense, but I find it somewhat situational given its 2 resource requirements… you cant transport Hydrogen by Unit Cargo Drones like you can Thorium." Slow shells "tend to miss… Eludes". (SG)
- [Q] Siege weapon: "titan has bigger range than most of the turrets, you can just place it in the right position near enemy wall and destroy the turrets behind the walls. And BELIVE its much faster and cheaper than using units" (_Dark_skill_, YT/8nZIiMJ5vHs, about 2025).
- [Q] Stronghold final core: "build two Titans… The Titans will destroy the walls and turrets" (FW/Stronghold). Crossroads 2022: "bombard the lower outer corner with titans and afflicts" (Happyhafer, ST/3714936444653765872).
- [Q] PvP: "a titan… should cover a 2 core wide choke point… and you should full wall with tungsten" (YT/SE6zBVjIKH0 comment, in human-play-en).
- [Q] Crevice: "a few of them with water can tear many ground units apart" (FW/Crevice).
- [Q] "Ripple but more single target based"; "Titan with oxide is just a half thorium reactor explosion radius" (RockstarLoic, FWC/Titan, 2025).

**Ammo**
- [Q] Oxide has "a colossal area damage radius… extremely useful against swarms".
- [Q] Carbide deals "around 1300 damage each shot", and its range catches "parked Collaris".
- [Q] Thorium is the "all-rounder". (FW/Titan)
- [Q] "Four Electrolyzers and eight Oxidation Chambers can continuously support five Titans with hydrogen and two with ammo… while also producing 40 heat." (FW/Titan)
- Source check:
  - Hydrogen: 24 H₂/s from four electrolyzers against 25 needed by five Titans.
  - Targeting: thorium and oxide shells use `UnitSorts.grouped` (they aim at the densest cluster); carbide uses `strongest`.
  - Ammo per second while firing: Th 1.74/s (3.0 watered); oxide 1.22/s; carbide 1.39/s.

**Weak against**
- No air at all. [Q] "commonly paired with the Disperse… try and sever the input to that turret first — once cut off, your ships are free to sweep in" (FW/Titan).
- [Q] "Split a unit to the front of your army to minimise how many targets get hit." (FW/Titan) [Q] "As of Build 154 all Titans are using Thorium ammunition in the Campaign" (map-placed enemy Titans).
- [Q] Chinese Stronghold: "需要使用几个 T1 飞船反复横跳拆掉泰坦供弹" (ZH, BV1Uh4y1D75p). Use a few T1 ships, dodging back and forth, to cut the Titan's ammo supply.

**AI rule**
- Build Titans 6–15 tiles behind the wall line, where approaching ground units bunch up.
- Place them next to an electrolyzer cluster (hydrogen cannot ride cargo drones) and next to thorium or oxide supply.
- Always cover them with a Disperse or Afflict.
- Use oxide against swarms and carbide against T4/T5. Also use Titan offensively, as a creep turret placed just outside enemy range.

### 2.5 Disperse (驱离)

**Role.** Flak, 4×4, **air only**, 38.75 tiles. Fires 6.7 salvos/s of 4 bullets, ×1.83 with water 20/s. It also shoots down missile units.

| Ammo | Damage | Ammo per item | Range | Fire rate |
|---|---|---|---|---|
| W | 48.75 | 3 | 38.75 | 1× |
| Th | 67.5, pierce 2 | 1 | **23.75** (−15 tiles) | 0.85× |
| Si | 27.75, homing | 4 | 38.75 | 1× |
| Surge | 48.75 + lightning | 3 | 41.75 (+3 tiles) | 0.75× |

**How people rate it**
- [Q] "The only dedicated anti-air turret on Erekir, but in return is extremely effective… can make large parts of your territory a no-go-zone for Averts and Obviates… can shoot down most missiles as they count as air units", against Anthicus, Scathe, Quell and Disrupt. It "consumes ammo very quickly… additional storage is recommended". (FW/Disperse, 2026-06-07)
- [Q] SG: "the quintessential anti-air turret". A long unprotected duct line "can risk cutting them off of their ammo". It is "solid… [but] can get outclassed in the later game" by Afflict.
- [Q] Crevice: "your entire base should receive coverage from at least two at any point" (FW/Crevice). Karst: "Four Disperses with water cooling will handle all ships from that spawn point" (FW/Karst/Strategies). Ravine: "use 3-4 Disperse protected by walls to guard that flank" (Kyrros, ST/3593338530537447476).
- [Q] Chinese Origin: "每隔十格左右放两个驱离做好防空……驱离一定要两个两个放，不然容易被后期四级飞机攻破" (ZH, cv44580757, 2026-01). Place two Disperses every ten tiles or so, always in pairs, or late T4 aircraft break them.
- [Q] "One requester pad to two disperse turrets is usually enough to shut down any air unit harassment for effectively no infrastructure cost." (Birrihappyface, YT/8nZIiMJ5vHs, about 2025)
- [Q] "Awesome turret with the only downside being it cost which is quite expensive for early on" (RockstarLoic, FWC/Disperse, 2025-10-13).
- [Q] Stronghold: "Once you mine thorium your first priority is to save up and research the Disperse… crucial for what enemies come next" (FW/Stronghold).

**Ammo**
- Tungsten is the standard.
- [Q] Thorium Disperses "surprisingly struggle against amassed missiles" (FW/Titan). Source check: thorium cuts range by 15 tiles.
- [Q] Silicon "is typically not worth the reduced damage" (FW/Disperse).
- Surge adds range and lightning.
- Source check, rates while firing: W **2.2/s, 4.1/s watered**; surge 1.67/s.

**Weak against**
- [Q] "Will shoot at units at the edge of its range but cannot hit them unless Silicon ammo is used. Due to its high fire rate, its ammo can be drained quickly." It fires in one direction at a time, so a second group "from the side" opens gaps. A core unit holding any payload becomes a target and a distraction. (FW/Disperse) The same appears in Chinese: "核心机上面挂个东西试探更方便" (BV1MG411d7Sg comment, 2023-08-28, 10 likes); probe with the core unit carrying a block.
- [Q] Origin: "a gap… only covered by Disperse, which Eludes can't be targeted by" (FW/Origin). Source check: Elude is a ground (hover) target.
- [Q] Attack "any vulnerable tungsten-carrying blocks, to disable Disperses" (FW/Attack). "around 30 averts to disable the disperse's ammo supply" (FWC/Stronghold).

**AI rule**
- Every economic zone and every front gets Disperses in pairs, overlapping so that any tile is covered by two.
- Feed them by local tungsten or a cargo-drone unload point, with a container buffer of at least 100 W.
- Use tungsten, then surge; never thorium on the long-range AA ring.
- Disperse does not stop Eludes. Cover hover routes with a ground turret.

### 2.6 Afflict (劫难)

**Role.** Power turret, 4×4, air and ground, 46 tiles, one orb every 50 ticks.
- The orb deals 180 damage, then releases 20 frags of 35 plus interval bullets that home onto targets.
- Needs 300 power/s plus 20 heat; extra heat does nothing.
- Building damage 0.5× (orb) and 0.3× (frags).

**How people rate it**
- [Q] "Afflict is likely the best turret in all of Erekir… taking down enemies and their projectiles at the same time over very long range… anywhere near Slag pools are great places to build Afflict… its siege capability, out-ranging many other turrets and piercing through enemy walls." (SG)
- [Q] "Best Erekir turret" / "Yes" (FWC/Afflict, 2024-07 / 2025-12). "Afflict is really op so i just rush it instead of building breach" (STAR_POPGD, YT/8nZIiMJ5vHs). "spam afflict when def siege and it shred t3 like a wet paper" (YT/8nZIiMJ5vHs, about 2026-07).
- [Q] "Invest in surge alloy. It lets you build afflict, a very good powerful turret that rips through flying units." (KarinFysh, ST/590650717026394794, 2025-07-12)
- [Q] Sector use:
  - Siege: "rush your way to afflict and disperse construction" (Vice, ST/3810656958854938509, 2023-09-22).
  - Crevice: "rush surge, built afflicts, and ignore carbide" (YT/iizNPSnqgOU).
  - Caldera: "a single afflict and a slag heater" (FW/Caldera).
  - Ravine: "get an Afflict built 3 minutes before the first wave" (FW/Ravine).
  - Origin: "Construct 1 Lustre on both sides and 2 Afflicts on each side"; retaliation waves "can be dangerous unless you have a lot of Afflicts" (FW/Origin).
- [Q] Missiles: "Afflict is able to absorb them better than others" (FW/PvP). "The frag and pierce also makes the Afflict an excellent anti-missile turret." (FW/Afflict)
- [Q] Chinese:
  - "劫难清小怪，拿光辉防大怪" (ZH, BV1PN4y1d7qt, 2023-08): Afflict clears small units, Lustre stops big ones.
  - Karst: "主力用群伤炮塔（劫难最好）" (ZH): the main turrets should be area-damage ones, Afflict best.
  - Turret creep: "劫难射程远直接推图就行了……推开了下一个劫难过后，上一个劫难可以直接拆" (ZH, BV1jz4y137sj). Afflict outranges, so creep it forward and remove the one behind.
  - Range probing: "用铍墙试探敌人炮塔的攻击范围……然后放一个劫难和两个电热机并连上电" (ZH, cv44580757, 2026-01). Find the enemy's turret range with beryllium walls, then place one Afflict plus two electric heaters there.
  - Against: "造价太高" at Ravine (too expensive, ZH, 2024).

**Heat and power**
- [Q] Outdated: "180% (6 Electric Heaters)". The page itself flags this as obsolete after b155. (FW/Afflict)
- Source check: 20 heat for 100 %, with a cap of 100 %. Ways to supply it:
  - 7 electric heaters (700 power/s, so about 1000/s in total);
  - **4 oxidation chambers** (free heat, but heat stops if their oxide backs up);
  - 3 slag heaters (needs a slag pump).
- [Q] Enemy designs: "A 200% efficiency Afflict on Oxidation Chamber heating guards the core" (FW/Stronghold). Karst plan: "8 Oxidation Chambers going to the core, which can include the starting two heating the Afflict" (FW/Karst/Strategies).
- [Q] Chinese schematic series "氧化热劫难系列" (Afflict heated by oxidation chambers; BV1PqVq6SEyQ, 2026-06-02). Comments:
  - "战役里面还是尽量将后勤和前线分开…氧化物尽量和合金碳化物相织布这种结合在一起" (拖鞋板大爷, 6 likes): keep logistics apart from the front and pair oxide heat with surge/carbide/phase crafters.
  - "实战下来不如这几个 / 省去臭氧制作问题还能提供氢" (Tu_Fox): in practice other heat designs did better, since they skip ozone production and also yield hydrogen.

**Weak against**
- [Q] "Vulnerable to large groups of 15-20 Anthicus or Quell"; "turned off as soon as all nodes or heating are cut — target them"; "A hit squad of 10 Obviates can dive in past walls and one-shot the tower." (FW/Afflict)
- [Q] Crossroads: "Avoid engaging with the Afflicts, as they kill many Averts in just a few shots" (FW/Crossroads/Strategies).
- [Q] b148: frags "no longer infinitely pierce, effectively relegating it to a purely single-target turret" (FW/Afflict); Chinese "削了无限穿透" (ZH). [I] Players still rank it top in 2025–26, so treat it as the generalist anyway.
- [Q] Old v7 claim: "1 200% afflict can easily beat 15 (uncoordinated) anthicus" (FWC/Afflict, 2024-02-23). This predates the b156 heat rebase, when 200 % is no longer possible.

**AI rule.** From the first 100 surge, build one Afflict per front at the choke, positioned to hit both the ground path and the air lane.
- Heat it with oxidation chambers whose oxide drains to the core, or slag heaters next to a slag pool.
- Give it two independent power paths (two beam nodes from different sides).
- Place heaters behind it, not in front.
- Use it as the creep turret when attacking a fixed enemy line.

### 2.7 Lustre (光辉)

**Role.** Continuous point laser, 4×4, air and ground, 31.25 tiles, 1890 dps on a single target.
- Targets the **strongest** unit (`UnitSorts.strongest`).
- Slow rotation (0.9); shootCone 360.
- Needs 200 power/s + nitrogen 6/s while firing.

**How people rate it**
- [Q] "Requiring no item input, just Electric Heaters for the nitrogen ammo and some power… They can also siphon off Prime Refabricators… as both blocks only consume their stated nitrogen if continuously running." But it is "nowhere near as flexible as the Afflict… extremely vulnerable to swarms, and the targeting priority is likely to only focus on tanks shielding the backline mechs… completely vulnerable to the Collaris and does poorly against unit missiles… It may be useful to prematurely fire the Lustre so that it points toward the enemy path." (FW/Lustre, 2025-08-04)
- [Q] SG: "possibly the easiest turret on Erekir to supply… lowest maintenance". But "its very slow… It so often lags just behind the enemy… a bit situational."
- [Q] Origin: "Lustres are ridiculously effective at killing single targets for how easy it is to get nitrogen"; "A pair of Lustres and a Regen Projector will work" (FW/Origin). Crevice: "extremely efficient at eliminating them [Tecta guardians], and one Atmospheric Concentrator can supply many of them by stockpiling using liquid containers" (FW/Crevice).
- [Q] Chinese: "光辉伤害很高，但只是单点攻击，不太适合防守类关卡" (ZH, BV1iP411W7h3): high damage but single target only, not suited to defence sectors. Crossroads on Eradication: "左边防线2个光辉1个劫难加合金墙刚好能守" (ZH, BV1yDKn6nECU, 2026-07): two Lustres, one Afflict and surge walls just hold the left line.
- [Q] Crossroads 2022: "the luster beam is not blocked by the shield!" (Happyhafer, ST/3714936444653765872). FW/Shield_Projector agrees that Sublimate and Lustre work through the map shields.
- [Q] Recent: "Lustre not in S is criminal" (YT/eFMl3-88POU comment, 2026-10). "Lustre is single-target monster" (YT/MUpuKWYANo0, about 2025).

**Source check.** One YouTube commenter says "Ray damaging not only at the tip. Whole ray deals damage" (YT/MUpuKWYANo0). That is wrong: `PointLaserBulletType` calls `Damage.collidePoint` at the aim point only. The Chinese wiki line "穿透单位（无次数上限）" (pierces without limit) is a generated field and means only that it can hit everything at that point.

**AI rule**
- Add one or two Lustres per front once nitrogen is available and the enemy sends T3+ units or guardians.
- Always place them behind or next to splash or line turrets (Sublimate, Titan, Afflict) so swarms are handled by others.
- Keep a nitrogen tank on the concentrator line.
- Do not use Lustre as the only turret against massed T1/T2 or missiles.

### 2.8 Scathe (创伤)

**Role.** Cruise-missile launcher, 4×4, **ground only**, 168.75 tiles, one shot per 10 s (×2.5 with water).
- Targets the **strongest** unit.
- Fog reveal is 40 % of range = 67.5 tiles.
- Building damage 0.1×.

| Ammo | Cost per shot | Effect | Fire rate |
|---|---|---|---|
| Carbide | 15 items | 1000 splash + 7 sub-bursts | 1× |
| Phase | 3 items | 320 splash over the widest radius; the missile carries a 3000-hp shield | 0.8× |
| Surge | 15 items | 1800 splash + 5 split missiles | 0.9× |

**How people rate it**
- [Q] "Running a single Scathe is a very daunting task for what appears to be little reward… However… incredible range can make it very effective against all ground armies, and snipe any Collaris that hang at the very back… the missile can still catch ducts in its blast." (FW/Scathe, 2025-12-21; tips flagged as pre-b148)
- [Q] SG, 2023: "Scathe is useful in only 2 situations: as a glorified radar, or for preventing the enemy from building… If you build walls closer to the Scathe… it will fire at the walls." "Utterly useless turret that you almost should always build one of, just so you can carry it around with an air unit and quickly reveal the map."
- [Q] JTM tier list: D, then "Scathe becomes C tier (Cheaper ammo…)" (YT/8nZIiMJ5vHs, pinned). Commenters disagree:
  - "the new V8 update probably boosted scathe to a good low A tier… The phase missiles also get a massive shield, removing scathe's biggest downside" (Lemi, about 2025);
  - "scathe only really makes sense for stuff like siege sector to snipe one of the enemy cores to steal some thorium" (lool8421, about 2026);
  - "scathe actively refuses to fire at anything important and misses units sometimes" (GHOST, about 2026);
  - "I used Scathe under siege and broke their first core" (MartPeace).
- [Q] "Big risk high reward… you either waste tons of ressources… or destroy the core from super far"; "it also prioritise higher hp units" (RockstarLoic, FWC/Scathe, 2025–26). "This turret somehow destroyed the avert-producing base with its core in Origin" (WantechFan, FWC/Scathe, 2025-07-31).
- [Q] Chinese 2022 (v7): "创伤的Cd非常的长，建议用水加强开火速率，打群伤可以，单体不太值" (by清林, BV17W4y1Y7Vk, 18 likes). The cooldown is very long, so water it; fine against groups, poor value against single units.
- [Q] Chinese, how to beat it: "创伤优先瞄准血量上限最高的单位" (ZH): Scathe aims at the unit with the highest max health. "创伤导弹可以被管子拦截……记得沿着路边造管子抗导弹" (ZH, cv25184550): ducts intercept Scathe missiles, so lay ducts along the roadside. Bait it with one T2 tank plus a regen projector (ZH).
- Source check: `unitSort = UnitSorts.strongest` confirms it targets the highest max health.

**Defending against Scathe** (also applies to the AI's own bases)
- [Q] "Lone Scathes can be handled with a Regen Projector on any block, but Shielded Walls in particular also soak up the area damage… the Afflict, cyanogen Sublimate, or surge alloy Disperse can reliably stop missiles." (FW/Scathe)
- [Q] Siege: "Move a single, bulky unit forwards" (FW/Siege).

**AI rule**
- Do not count Scathe as defence.
- Build at most one, after carbide, phase or surge production is stable. Uses:
  1. area denial over a resource the enemy is contesting;
  2. sniping enemy cores, drills (e.g. an Eruption drill) or factories from outside their turret range;
  3. scouting through fog.
- Prefer phase ammo (3 items per shot, shielded) once phase exists.
- Expect humans to bait it with walls, ducts and one tanky unit.

### 2.9 Smite (天谴)

**Role.** Item turret, 5×5, air and ground, 37.5 tiles. Each salvo is 10 piercing bolts of 187.5 (pierce 4) plus lightning, every 100 ticks; ×2.5 with water. Ammo: 2 surge per salvo.

**How people rate it**
- [Q] "Effective damage per round… around 2000… enough to four-shot Quells… best used against single-targets such as the tier 5 units in Karst (particularly the Collaris guardian), although it is also good at killing groups of lower-tier units due to the pierce and lightning… Cooling it is very easy with Reinforced Liquid Containers with water." (FW/Smite, 2026-08-25)
- [Q] SG: "the one that you'd be more likely to build… it only needs Surge Alloy, and you'll probably deliver it via unit cargo loaders… so it can go pretty much wherever." It struggles with "higher tier air units" (range) and "multiple targets if they aren't in a line".
- [Q] "I honestly like the smite more… you can just place a watered smite and work at its max efficiency with just a duct of surge alloys… you can also spam smites" (_Ryyl, YT/_UGKLaXqa7o, 35 likes, 2022). "Malign is better for longer range and heavy units while smite… can easily fend off crowds" (same video).
- [Q] "So good… the only bad thing is that it is extremely expensive" (FWC/Smite, 2026-03-17).
- [Q] Crossroads: the enemy Smite "blocks distractions" (ST/3714936444653765872). Chinese: decoy beam nodes spammed beside an enemy Smite let a Scathe through: "帮创伤吸引天谴火力在天谴旁边乱点了一堆激光节点，它炸我就修" (BV1MG411d7Sg comment, 2023-08).

**Source check, rates.** Surge 1.2/s while firing, 3/s watered. One surge crucible makes 1.33/s. Quell's 45-tile range outranges Smite's 37.5. FW/Crossroads says Quell fires safely against "all turrets other than the Smite and Afflict"; by range alone only Afflict (46) and Malign (51) outrange Quell.

**AI rule.** In the endgame, place Smites where the approach is a straight corridor at least 15 tiles long. Water them from a container and feed surge by cargo drone. Pair them with Disperse or Afflict against Quell and Disrupt.

### 2.10 Malign (魔灵)

**Role.** Power turret, 5×5, air and ground, 51.25 tiles.
- Fires homing flak (70) that ends in lightning and piercing lasers, reload 3.5 ticks.
- Targets the **strongest** unit.
- Needs 2400 power/s + **144 heat**, capped at 100 %.

**How people rate it**
- [Q] "Less practical than the Smite, as it uses nine times the heat an Afflict uses… can be easily overwhelmed, especially by a swarm of units such as Quell." (FW/Malign, tips "not updated since build 147.1")
- [Q] "This guy can handle a onslaught of 80 conquers… [but] would not be able to handle 80 stells… use it at a position where only high tier enemies would come" (Crecent Moon, FWC/Malign, 2024-08-30). "Malign can annihilate tier 5 enemys but fails to kill 80 small tonks" (YT/MUpuKWYANo0, 143 likes, 2022).
- [Q] "Just placing one of this turret make the defence impenetrable (if you managed to heat it up)" (RockstarLoic, FWC/Malign, 2025-10-04). "Nowdays, malign is just too op" (YT/_UGKLaXqa7o, about 2026-04).
- [Q] v8 change: "will charge up it attack in advance before any unit come in range and no longer just reset it attack as soon as it target dies… handle EXTREMELY well in hordes" (FWC/Malign, 2025-04-17). Source check: `warmupMaintainTime = 120` keeps it warm for 2 s between targets. The v7 complaints ("stops shooting just to target another unit", YT/MUpuKWYANo0) are outdated.
- [Q] Heat: "a single phase factory can make nearly enough heat for a single malign because phase heaters are so efficient. Or you could use neoplasia" (Underscorecow, ST/3819657908520025752, 2023-04-20).
- [Q] Chinese 2026: "14魔灵基本就是同宽度下合理尺寸的上限了…因为真的没有实用性所以也没完全做完" (BV17afLBXEpj, 2026-02-20, on 14 electric-heated Maligns). Fourteen is the practical width limit; the build has no real use, so it was never finished. A commenter on the v8 turret test: "热量传输机传输热量不损耗…魔灵是可以考虑密铺的" (BV1Digw6QEZe, 2026-08-27): heat redirectors lose nothing, so Maligns can be packed densely.
- [Q] Origin: "A group of around 20 [Quells] is enough to completely negate a Malign's bullets"; "the eastern Malign must be disabled by destroying the Neoplasia Reactor heating it"; "You will be bogged down by the Maligns, however, if there are any Regen Projectors." (FW/Origin)

**Source check**
- The wiki says "72 heat… 200% (144)" and SG says "90 heat". Both are outdated: source is 144 for 100 %.
- 144 heat = 10 phase heaters, 18 slag heaters, 48 electric heaters (4800 power/s), or 3 neoplasia reactors.
- [Q] SG says it lacks range for T4/T5 air. Source: Malign 51.25 tiles outranges Quell (45) but not Disrupt (about 58.6).

**AI rule**
- Build only post-Karst with phase-heater or neoplasia heat.
- Place it where T4/T5 come (core ring, guardian lane), never as the only turret against swarms.
- Protect the heat source more than the turret.
- Against an enemy Malign: kill the heat, or bring about 20 missile units.

### 2.11 Walls and support blocks

Wall stats from source (health per tile, armor):

| Block | HP | Armor | Cost | Notes |
|---|---|---|---|---|
| Beryllium wall | 520 | 2 | Be 6 | cheapest |
| Tungsten wall | 720 | 14 | W 6 | |
| Reinforced surge wall | 1000 | 20 | surge 6, W 2 | 5 % chance of 30-damage lightning when hit |
| Carbide wall | 1080 | 16 | Th 6, carbide 6 | most HP per tile |
| Blast door (2×2) | 2800 | 14 | W 24, Si 24 | opens for own ground units |
| Shielded wall (2×2) | 4160 | 15 | phase 20, surge 12, Be 12 | needs 3 power/s; deflects; shield |

- Damage after armor is `max(dmg − armor, dmg × minArmorDamage)`.

**Beryllium walls**
- [Q] "Extremely cheap… for temporary defense or for emptying the ammo from disconnected enemy turrets" (FW/Beryllium_Wall).
- [I] The AI should use beryllium walls as decoys and ammo drains when attacking, and as the first layer in Onset/Aegis.

**Tungsten walls**
- [Q] "The flat reduction on tungsten walls reduces locus damage to around 60%" (YT/SE6zBVjIKH0 comment). Source check: 36 − 14 = 22, i.e. 61 %. Correct.
- [Q] "Each barricade requires a triple-layer of Large Tungsten Walls" (FW/Karst/Strategies).
- Tungsten is the AI's default wall from Aegis onward.

**Reinforced surge walls vs carbide walls**
- [Q] Carbide has "the most raw health per tile, at 1080… it may be recommended to use the Reinforced Surge Wall instead depending on if you wish for more damage or survivability" (FW/Carbide_Wall).
- [Q] Enemy bases with "Afflict and Reinforced Surge Walls guarding the cores" are the hard ones (FW/Stronghold).
- [I] Use surge walls where surge is plentiful and the threat is many small hits (armor 20 erases T1/T2 shots). Use carbide against big single hits.

**Blast door**
- [Q] Opens for own ground units "(including the Elude, but not anything with large legs)"; bullets pass while it is open (FW/Blast_Door).
- [Q] Chinese PvP lesson: tanks blocked by their own walls (ZH).
- [I] Every wall line that the AI's own tanks must cross gets a blast-door gate.

**Shielded wall.** [Q] Soaks Scathe area damage (FW/Scathe). Unit missiles are never reflected (FW/Shielded_Wall).

**Regen projector**
- Source: hydrogen 1/s and 60 power/s. It heals 4 % of max HP per second (25 s from empty to full), ×2 with phase, over a 28×28-tile square.
- [Q] It "only consumes power and hydrogen if it is actively repairing a block" and "does not stack with itself" (FW/Regen_Projector). Chinese 2026: "再生投影不能叠加" (13 likes, ZH).
- [Q] Turret clusters in sector guides almost always include one: Siege "4 Sublimates… tungsten walls and regen projectors"; Karst "Add Regen Projector and Build Tower support to each area".
- [I] One per defensive cluster, never two overlapping.

**Build tower**
- Source: 150 Si, 40 oxide, 60 Th. Range 25 tiles, nitrogen 3/s, 180 power/s. It rebuilds destroyed blocks.
- [Q] "Place Build Towers first to save time on later Sublimates — at least two is recommended" (FW/Karst/Strategies). Build Towers placed ahead shorten turret build times when air units appear (FW/Obviate). "Watch out for any Build Towers, as they will not hesitate to put those blocks back" (FW/Attack).
- [Q] One player (2025) complains a turret can be "1 shot… during it's mid-build phase" (DestinedLight, ST/592895726921500459).
- [I] Build towers are the answer to that.

**Shockwave tower.** Cyanogen 1.5/s, range 21 tiles. [Q] Destroys projectiles like Cleroi point defence, "which excludes any unit missiles" (FW/Shockwave_Tower). It stops Collaris shells (meta-report).

**Shield projector.** `BuildVisibility.editorOnly`: map-placed only (Intersect, Crossroads). [Q] "Piercing laser projectiles… Malign… Sublimate and Lustre also work" through it (FW/Shield_Projector).

**Core unit**
- [Q] Untargetable unless it carries a payload; repairs blocks at 186/s (FW/Evoke).
- Players use it as an Erekir mender, and with a payload as a Disperse decoy.

---

## 3. Drills and miners

### 3.1 Plasma bore (等离子钻机)

Source: 2×2, Be 40, 9 power/s, tier 3. Mines wall Be and graphite (no W or Th). 0.75/s on 2 tiles; with H₂ 0.25/s → ×2.5 = 1.875/s.

**What players say**
- [Q] "Boosted Plasma Bores are faster per tile than an unmodified Large Plasma Bore despite consuming half the hydrogen. It is recommended to use boosted Plasma Bores for mining beryllium, as it can run out very quickly when constantly building logistics blocks." (FW/Plasma_Bore, 2025-07-10) The same point from a commenter: "Large plasma bores with only hydrogen (3 tiles, 1.80) are actually slower than plasma bores with hydrogen (2 tiles, 1.87)" (Martian076, FWC/Advanced_Plasma_Bore, 2024-06-09).
- [Q] "8 boosted Plasma Bores (16 tiles) will effectively reach a duct's maximum throughput of 15 items/sec. It is recommended to run either the liquid or conveyor line under the space where the lasers would hit." (FW/Plasma_Bore) Lasers pass over ducts and conduits but are stopped by solid blocks such as beam nodes (same page).
- [Q] Chinese: "管道不会阻碍等离子钻机的等离子束，因此在挖掘墙矿时可以留出一格距离，方便连接物品和流体导管" (ZH, BV1UJ64BREAS, 2026-02-20). Pipes do not block the beam, so leave a one-tile gap at wall ores for item and liquid ducts.
- [Q] Several bores can drill the same wall section from different sides, almost doubling extraction on "diagonal" wall sections (Steam b136 announcement comments, https://steamcommunity.com/app/1127400/eventcomments/5788831477029818913/?ctp=6, 2022; read only through a search summary). Source check: lasers do not reserve tiles, so this is consistent.
- [Q] Chinese, "几个等离子钻机的放置技巧" (BV1aNHAzAEpL, 2025-10-02). The creator's tricks save space. Comments push back on cost:
  - "挺好的，就是造价太贵，纯e比较考验对资源消耗的控制，所以开局发育还是像评论区那种省材料的摆法比较好" (4metaphor): fine, but too expensive; pure Erekir tests resource control, so open with a material-saving layout.
  - "小矿像上面这样开 省铍" (你好perper): mine small deposits this way to save beryllium.
  - "大墙钻常用摆法" (彩色末影, 7 likes): the common layout for large wall drills.
  - Creator: "小矿的我基本能不用就换大的" (I swap small-deposit bores for big ones whenever possible).
- [Q] Ravine: "two Oxidation Chambers and one Electrolyzer… resulting in a number of boosted bores to fix beryllium spending" (FW/Ravine).
- [Q] Chinese Stronghold opening: "先扩大铍和钨的产量：左侧墙矿钻机加氢气，右侧地钻加水" (ZH). First raise Be and W output: hydrogen on the left wall bores, water on the right floor drills.

**Pitfalls**
- No tungsten or thorium.
- Output stops when the 10-item buffer is full.
- Any solid block in the laser path (beam node, wall, turret) cuts output.
- Source check, hydrogen budget: one electrolyzer (6 H₂/s) boosts 24 bores. The wiki pages say 12 and 15 (see meta-report §1.5).

**AI rule**
- Line every reachable Be/graphite wall face with bores, leaving a 1-tile duct lane in the laser gap.
- At most 8 boosted bores per duct.
- When the first electrolyzer runs, boost existing bores before adding more. Each boost is +1.1 items/s for 0.25 H₂/s and no build cost.

### 3.2 Large plasma bore (大型等离子钻机; Fandom calls it "Advanced Plasma Bore")

Source:
- Cost: Si 100, oxide 25, Be 100, **W 70**. FW lists W 100; the source is 70.
- 3×3, tier 5, 48 power/s, **H₂ 0.5/s required**.
- 1.8/s on 3 tiles; N₂ 3/s → ×2.5 = 4.5/s.
- Unlocked at Caldera.

**What players say**
- The only way to get **wall** tungsten and thorium.
- [Q] Karst: "Build Large Plasma Bores over the wall tungsten to the south, close to the fog-of-war" (FW/Karst/Strategies). Crevice: "You are given a Large Plasma Bore mining 2 tiles of Tungsten — this will be the only source of tungsten from walls" (FW/Crevice). Stronghold: "Boost any bores mining thorium if needed to quickly research the Disperse" (FW/Stronghold).
- [Q] Before the b147 drill buffs, a suggestion argued that even "the Eruption Drill even at max coverage still has less throughoutput than Large Plasma Bore", so floor drills were "only used when it is forced via limited wall ores" (SomeonesShade, GH4913, 2024-03-17). Source check: since then the eruption drill makes 5.33/s (10.67 boosted), which beats an LPB on 3 tiles (4.5 boosted).

**AI rule**
- Use LPBs for wall W and Th.
- Use the nitrogen boost only when a concentrator already runs for Lustre or build towers; nitrogen is only consumed while drilling.
- Do not use LPBs on Be/graphite walls; boosted small bores are better per tile.

### 3.3 Impact drill (冲击钻头)

Source:
- 4×4, tier 6, 160 power/s, **water 10/s required**.
- 1.33/s on 16 tiles; Be floor 2× (2.67/s).
- Ozone 3/s → ×1.75.
- Thorium is hard-blocked.
- Output comes in bursts of `dominantItems`. Sand is low priority.

**What players say**
- [Q] Aegis/Intersect: first tungsten; "Impact Drill in wave 1" (FW/Intersect).
- [Q] Water source: "It is much safer to use the trace quantities of Water obtained from Turbine Condensers to operate blocks like the Impact Drill" than to replace a turbine with a vent condenser (FW/Power, 2025-08-28). Source check: 10 water/s = two turbine condensers' by-product.
- [Q] Chinese: "地钻的水可以取自热解发生器" (ZH): take the floor drills' water from the pyrolysis generator, which outputs 20 water/s.
- [Q] Ozone boost: "it does save power. 60 power/s for boost and 120 power/s for a new drill"; "ozone boosting often saves even more power and heck even water"; and "it WOULD have an impact on pre oxide maps like basin" (jehosula, GH4913, 2024-03-17). This suggestion is where the current 1.75× ozone boost and 10/s water come from.
- [Q] Complaints: "Bro really had to be hard coded to not mine thorium" (FWC/Impact_Drill, 2026-04-15).

**AI rule**
- Use for floor tungsten (and floor Be when walls are exhausted) from Aegis on.
- Place so all 16 tiles are the target ore; rate scales with ore tile count.
- Pipe turbine by-product water to it before building vent condensers.
- Add ozone when an electrolyzer has spare ozone: cheaper than a second drill.
- It stops when its 40-item buffer is full, so always give it an output.

### 3.4 Eruption drill (爆裂钻头 / 喷发钻头)

Source:
- Cost: Si 300, oxide 20, W 250, Th 150. 5×5, tier 7, 360 power/s, **H₂ 4/s required**.
- 5.33/s on 25 tiles; Be 10.67/s.
- Cyanogen 0.75/s → ×2.
- Mines crystal thorium floors.

**What players say**
- [Q] "SLOW at mining other thing then berylium… add a coolant… cyanogen to boost it", followed weeks later by "They did it woah" (RockstarLoic, FWC/Eruption_Drill, 2025-04-10 / 2025-05-08). The v8 beta added the cyanogen boost.
- [Q] Crevice: "build Eruption Drills on the tungsten in northwest area separated by a river of Slag" (FW/Crevice). Karst: "Place an Eruption Drill on one of the nearby ground beryllium patches, and connect it to the core" (FW/Karst/Strategies).
- [Q] It is a target: "place an Avert to constantly snipe the eruption drill at the bottom of the main base, stopping all tier 3 enemy unit production" (FWC/Stronghold, 2025-10-17).

**AI rule**
- Use for crystal thorium (the only floor-thorium miner) and for the largest W/Be floor patches.
- Budget 4 H₂/s: two-thirds of an electrolyzer.
- Add cyanogen as soon as a synthesizer exists: 0.75/s doubles the output.
- Cover the drill with a Disperse; it is a known raid target.

### 3.5 Cliff crusher and large cliff crusher (岩壁粉碎机 / 大型岩壁粉碎机)

Source:
- Cliff crusher: 2×2, 11 power/s. Output scales with the sand attribute of the 2 wall tiles it faces: about 1.09/s on two attribute-1 walls (sand walls 2.18/s).
- Large crusher ("Advanced Cliff Crusher" on Fandom): 3×3, 60 power/s, H₂ 1/s, surge 15 to build. 3.75/s on attribute-1 walls; ×1.6 with graphite 0.75/s.
- Source check: **sand put into an Erekir core is deleted.**

**What players say**
- Little opinion beyond "it is how you get sand".
- [Q] The large crusher "previously used ozone to run and tungsten as a booster in the Bleeding Edge versions leading to Build 147"; some translations still say so (FW/Advanced_Cliff_Crusher). Old guides may describe the wrong inputs.
- Layout: the community production cell ("on G") feeds sand from crushers and graphite from bores straight into the silicon furnace (human-play.md §6). The phase line needs 12 sand/s, which is 4 large crushers (spec §10).

**AI rule**
- Place crushers touching the arc furnace or a short dedicated duct to it, never routed to the core.
- Prefer sand walls (attribute 2).
- Switch to large crushers with graphite boost for the phase-fabric stage.

### 3.6 Reinforced pump (强化泵)

Source:
- 2×2, H₂ 1.5/s, no power.
- 20/s per covered liquid tile (80/s full).
- [Q] "the only pump available on the planet" (FW/Reinforced_Pump).
- None of the 17 Erekir maps has water floors (human-play.md §2), so the pump is for slag and arkycite.

**What players say**
- [Q] Arkycite lines are prime raid targets: "every power generator besides the Turbine Condenser needs it in some form" (FW/Attack). Siege: "The eastern pass base ships arkycite to the western pass, and destroying it will cripple the first base's power supply" (FW/Siege).
- [Q] Chinese blueprint guide: one electrolyzer's hydrogen supplies "3个芳油泵和2个矿渣泵" (3 arkycite pumps and 2 slag pumps) for power (7723.cn strategy page, https://3g.7723.cn/strategy/68623.html, read only as a search summary). Source check: 5 pumps × 1.5 = 7.5 H₂/s against 6 produced, so slightly over budget.

**AI rule**
- Put pumps fully on the liquid (4 tiles).
- Count 1.5 H₂/s each in the hydrogen budget.
- Defend arkycite and slag pumps like power plants.

### 3.7 Core unit and unit mining

Source (`UnitTypes.java`, `MinerComp.java`):
- Evoke, Incite and Emanate mine wall Be and graphite only (`mineWalls`, tier 3), at mineSpeed 6/8/9.
- One item per 65/mineSpeed ticks: about **5.5 / 7.4 / 8.3 items/s**. That is several boosted plasma bores' worth.
- The item goes straight into the core only when the unit is player-controlled and within 27.5 tiles of the core (`offloadImmediately()` returns `isPlayer()`).
- An AI-controlled core unit fills its 60/90/110-item hold and must fly back to unload.
- Erekir has no dedicated miner units. Cargo drones only move items.

**Players.** No human source discusses core-unit mining rates. Players mention the core unit for building, repair (186/s), payload carrying (ozone-container bombs, Disperse decoy) and early graphite (meta-report §1.6).

**AI rule** [I]
- In the first minutes, keep the core unit mining the nearest Be/graphite wall within about 27 tiles of the core whenever it is not building.
- The mod already does this in `src/expansion/econ/Hand.java`.
- Prefer graphite when graphite is short: silicon and Diffuse ammo both depend on it, and there is no floor graphite.

---

## 4. Implications for the AI's defence planner and mining

### 4.1 Threat → turret (what to build where)

| Observed enemy | Build (in this order) | Where | Support | Source |
|---|---|---|---|---|
| T1/T2 tanks (Stell, Locus), early | 2–3 watered Breaches (W ammo when available) + 2 layers of walls | choke nearest the core | duct-bridge or container ammo buffer; water tank | FW/Basin, FW/Breach, Kyrros |
| Leg mechs (Merui, Cleroi) walking over walls | Diffuse in the choke mouth or a notch; Sublimate behind | path within about 10 tiles of the turret | container buffer (graphite 6/s while firing) | FW/Diffuse, SG, KarinFysh |
| Massed T1–T3 ground in lines | ozone Sublimates ×2–4, cyanogen later | straight segment of the path | liquid tank, regen projector, tungsten walls | Venorik Barra, FW/Karst |
| Units that outrange Sublimate (Locus 20, Anthicus 42, Precept 24.5) | add Titan behind, or move to cyanogen Sublimate (25) | 6–15 tiles behind the wall line | H₂ 5/s next to electrolyzers | FW/Titan, YT/hrqr1RUsH2I |
| Air (Avert, Obviate, Quell, Disrupt) | Disperse pairs (W); Afflict once surge exists | every economic zone and core; pairs every ~10 tiles along supply lines | container ≥ 100 W or cargo unload point | FW/Crevice, cv44580757, YT/8nZIiMJ5vHs |
| Eludes (hover = ground) | Breach/Diffuse/Sublimate; **not** Disperse | slag/liquid crossings and the core | – | FW/Origin |
| Missile units / Scathe | Disperse, Afflict, cyanogen Sublimate; cheap walls/ducts in front; regen projector or shielded walls | in the missiles' approach line | – | FW/Disperse, FW/Afflict, FW/Scathe |
| T4/T5 heavies and guardians | Lustre ×2 behind Sublimate/Titan; carbide Titan; Smite on a straight lane; Malign at the core ring | behind the main line | nitrogen tank; surge cargo; phase or neoplasia heat | FW/Origin, FW/Crevice, FW/Smite |
| Long-range sieges (Collaris 48, Quell 45, Disrupt 58.6) | Afflict (46 + frags), Malign (51), carbide Titan (58.75 vs ground); units for Disrupt | – | – | FW/Afflict, FW/Malign, source ranges |

Rules:
1. **Units first, then the cheapest turret that answers the threat.** Every source agrees on this order. Turrets are fixed answers at 1–3 chokes, plus AA pairs in the economy.
2. **Range class decides placement.**
   - 15–25 tiles (Diffuse, ozone Sublimate, Breach): at the wall line.
   - 30–50 tiles (Lustre, Smite, Disperse, Afflict, Titan, Malign): 5–15 tiles behind it.
   - Scathe: anywhere.
3. **Pair ground-only with air-only.** Titan and Scathe never stand without Disperse or Afflict cover, and Disperse never stands without ground cover. Players attack exactly the missing half.
4. **Ammo sits next to the turret.**
   - Put a container or tank within 3 tiles, sized for about 60 s of fire: Breach Be 180, W 90; Diffuse graphite 360; Disperse W 130; Titan Th 100; Sublimate ozone ≥ 900 = one tank.
   - Never rely on one long exposed duct (SG, FW/Attack).
   - From cargo-drone tech on, feed Disperse and Smite by unload point.
5. **Water only from stockpiles.** Coolant is consumed only while reloading. Fill a tank between waves rather than dedicating a vent ([Q] ZH 2022: "敌人没来的时候可以用储液罐收集水", collect water in tanks while no enemy is attacking). Water priority: impact drills > electrolyzers > turret coolant.
6. **Protect the non-item turrets' inputs.** Afflict, Lustre and Malign die when power or heat is cut. Give them two power paths, put the heaters behind the turret, and never put oxidation-chamber heat where its oxide output can back up.
7. **Support package per cluster.**
   - 1 regen projector (non-stacking), 2–3 wall layers 1–4 tiles in front.
   - 1 build tower from Stronghold tech.
   - Blast-door gates where own tanks pass.
   - Tungsten walls are the default; surge or carbide on the main approach late.
8. **Plan against the targeting rules humans exploit** (source-verified). Lustre, Scathe and Malign target the strongest unit. Titan (Th/oxide) targets the densest group. Breach, Diffuse, Sublimate and Scathe never target ducts. So expect:
   - one tanky decoy, spread formations, decoy beam nodes or walls (BV1MG411d7Sg);
   - edge-of-range ammo draining against Disperse;
   - Obviate dives on Afflict.

   Counter by mixing turret types at each choke and keeping a mobile reserve.
9. **Offense with turrets** (players do this): creep an Afflict or Titan forward just outside enemy turret range, with beryllium walls as range probes, and remove the rear one after moving forward (ZH, YT/8nZIiMJ5vHs). All Erekir turrets do 0.1–0.5× building damage, so creeping is slower than units but cheap.

### 4.2 Which drill when

1. **Start**
   - Core unit mines the nearest wall Be or graphite.
   - Plasma bores on every Be/graphite wall face with a 1-tile duct gap; cliff crushers touching the arc furnace.
   - Everything else waits for power.
2. **First electrolyzer**
   - Boost existing plasma bores with H₂ (0.25/s each, ×2.5) before adding new bores.
   - One electrolyzer covers 24 bores, or the regen projector plus 12 bores.
3. **Tungsten (Aegis+)**
   - Impact drill on full 4×4 floor W, watered by turbine by-product water (two turbines per drill).
   - Ozone boost when ozone is spare.
   - Wall W must wait for the large plasma bore (Caldera).
4. **Oxide era**
   - Large plasma bores on wall W/Th (H₂ 0.5/s each).
   - Not on Be/graphite; boosted small bores stay better there.
5. **Thorium (Stronghold+)**
   - Eruption drill on crystal-thorium floors (the only floor-Th miner, H₂ 4/s), defended by a Disperse.
   - Boost Th bores when racing to Disperse (FW/Stronghold).
6. **Cyanogen**
   - Cyanogen boost on eruption drills (×2 for 0.75/s): the best extraction gain per input late.
   - Nitrogen boost on large bores only when a concentrator already exists.
7. **Phase**
   - 4 large cliff crushers per phase synthesizer (12 sand/s), graphite-boosted.
8. **Liquids**
   - Reinforced pumps fully on slag/arkycite; 1.5 H₂/s each.
   - Defend them like power plants.

**Hydrogen budget** (one electrolyzer = 6 H₂/s), all consumers:

| Consumer | H₂ per second |
|---|---|
| Plasma bore boost | 0.25 |
| Large plasma bore | 0.5 |
| Large cliff crusher | 1 |
| Regen projector (while healing) | 1 |
| Reinforced pump | 1.5 |
| Refabricator | 3 |
| Eruption drill | 4 |
| Titan | 5 |

Plan electrolyzers from this sum before placing the consumer.

**Pitfalls to encode**
- Plasma bores cannot mine W/Th; impact drills cannot mine Th.
- Solid blocks in a bore's laser path cut output.
- Burst drills stop when their buffer is near full.
- Sand to the core is deleted.
- Oxidation-chamber heat stops when its oxide backs up.
- Older guides list wrong heat and inputs (Malign 72/90 heat, Afflict "6 heaters for 180 %", large crusher on ozone). Use the spec numbers.

---

## 5. Sources reached and not reached

**Read**
- Fandom wikitext: all 10 turret pages, 7 extractor pages, walls, regen projector, build tower, shockwave tower, shield projector, core units, and the Attack, PvP, Siege, Stronghold, Crevice, Origin and Karst/Strategies pages, among others.
- Fandom article comments: turret and drill pages.
- The Steam turret guide (SG) and its comments.
- About 20 Steam threads.
- YouTube descriptions and comments: JTM tier list (8nZIiMJ5vHs), Michaelwhomightdraw tier list (eFMl3-88POU), Fagerst stell test (MUpuKWYANo0), Malign vs Smite (_UGKLaXqa7o).
- Bilibili: descriptions and top comments of the v8 turret test BV1Digw6QEZe (海洋之心Seaheart, 2026-08-14; T5-Conquer pressure test of every ground turret, results only in the video), BV17W4y1Y7Vk, BV1PqVq6SEyQ, BV1MG411d7Sg, BV1aNHAzAEpL, BV1yuYd6JER6 and BV17afLBXEpj.
- GitHub suggestion #4913 (via WebFetch).
- The Chinese wiki mdtwiki.top (v160.7 data). Its "用途" sections are templated text, not player opinion, so it is not cited.

**Not reached**
- **Reddit**: every route returned 403 or a bot check (old/www/api.reddit, redlib instances); WebSearch refuses reddit.com.
- **YouTube captions**, so the actual tier placements in the tier-list videos are unknown; only the pinned author notes are visible.
- **Bilibili** comments beyond the top three, the v8 test's results, and some search queries (HTTP 412 rate limit).
- **Baidu Tieba** (403 security check); **Zhihu** and **NGA** not reachable (as in the 2026-10-18 pass).
- **Steam** discussion search, rate-limited after about 20 queries; no thread focused on drills was found.
- **GitHub search API**, blocked by session policy.
- The official wiki (mindustrygame.github.io) is reachable but holds only generated stats.
