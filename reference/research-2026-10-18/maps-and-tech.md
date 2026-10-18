# The 17 Erekir campaign maps and the Erekir tech tree, read from the game files (v160.7)

Static analysis of `build/test-maps/maps/erekir/*.msav` and the content classes in `Mindustry.jar`. No games were run. Everything here comes from programs in `maptools/` in the scratchpad (sources in `maptools/src/maptools/`, raw output in `maptools/out/`):

- `MapScan` (new): rules, objectives, waves, cores, resources by distance, routes for ground, hover and legs with their narrowest points, buildings of each team, an overview picture.
- `TechDump` (new): the Erekir tech tree in tree order, with every objective resolved (`reference/content.txt` prints them as `???`).
- `Ranges` (new): turret, driver, bridge and node ranges, and unit traits.
- The repo's own `xtest.Study` (list/walls/feeds), `xtest.CampaignInfo` and `xtest.Procs` (the world processor code).

How the numbers were measured:

- **Vents** are distinct 3x3 spots where a turbine condenser fits (all 9 tiles are vent floor, no rock; the game's own `canPlaceOn` check). They are chosen greedily so they do not overlap. A vent condenser needs the same full 3x3 vent.
- **Wall ores and sand walls** count only rock tiles that touch open ground (exposed faces), because that is all a bore or crusher can reach.
- **Ground** is what tanks can cross: no static rock and no tile whose 4 neighbours are all deep liquid. This follows the game's `allDeep` test. **Hover** (elude) only needs no rock. **Legs** (merui and the other mechs) only stop at `Tile.legSolid()`, which is deep inside rock. Air goes anywhere.
- **Walk** is the shortest 8-direction ground path from the player core.
- **Route width** is how wide the widest ground route is at its narrowest point. It is measured from the distance to the nearest rock or deep tile (a 3-4 chamfer distance), ignoring boulder-sized lumps of 12 tiles or fewer. The scanner then blocks that narrowest point and looks for the next widest route, three times. "No other route" means that point is a single choke.
- **Gap** is the number of rock or deep tiles between the player's ground region and a region it cannot walk into. For scale: duct and conduit bridges reach 4 tiles and the payload mass driver 87.5 tiles.
- Every distance is in tiles, measured from the player core's centre tile.

Rules worth knowing before reading the maps (read from the bytecode):

- **Planet rules in campaign.** The Erekir planet applies `ruleSetter` on top of every map's own rules: `waveTeam=malis`, `placeRangeCheck=false`, `fog=true`, `staticFog=true`, `lighting=false`, `coreDestroyClear=true`, `onlyDepositCore=true`. The planet's `enemyBuildSpeedMultiplier` is 0.4.
  - Three map files have `placeRangeCheck=true` (onset, basin, caldera) and ravine has `fog=false`. The campaign overrides both.
- **Starting items.** Erekir has `allowLaunchLoadout=false`, so `FileMapGenerator` clears the player core on landing and fills it with `rules.loadout`. The "start items" below are therefore the loadout, not what the map file's core holds. For example, Ravine's file core has 400 tungsten that the player never gets, and Karst's file core is empty but the player starts with the full loadout.
- **First wave time.** `Logic.play()` sets it to `initialWaveSpacing` if that is positive, otherwise `2 × waveSpacing`, multiplied by the difficulty's `waveTimeMultiplier` (hard 0.8, eradication 0.6). `SectorPreset.startWaveTimeMultiplier` (2.0 everywhere) is set but not read in v160.7. Objective timers are scaled by the same difficulty factor (`objectiveTimerMultiplier`).
- **Capture wave.** `SectorPreset.rules` copies `captureWave` into `winWave`: intersect 9, ravine 24, crevice 46, karst 10. Intersect also has `attackAfterWaves`.
- **No-build radius.** `enemyCoreBuildRadius` is in world units (8 per tile): usually 400, which is 50 tiles. Exceptions: onset 430 (54), aegis 280 (35), intersect 160 (20), origin 496 (62). This is the radius around the other side's cores where neither side may build.
- **Unit cap.** `unitCap` is 0 except caldera (5) and origin (15). `unitCapVariable` adds each core's own cap on top.


## 1. Summary across the 17 maps

Distances are in tiles, straight line from the player core unless marked. Vents = distinct 3x3 spots a turbine/vent condenser fits on, counted within 30/60/100 tiles (map total in brackets). Reach to the nearest enemy core: G = tanks can drive, H = hover (elude), L = legs (mechs), "air only" = nothing on the ground gets there. "Enemy" counts are the pre-built malis base.

| # | map | size | mode | player core | enemy cores (nearest: straight / walk) | reach | first pressure | vents ≤30/60/100 | nearest tungsten floor / wall | arkycite / slag nearest | enemy turrets / walls / unit blocks | enemy free resources | start items |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | onset | 512×512 | attack + tasks | bastion 276,107 | 2 (2 bastion); 90 / 105 | GHL | scripted 2 stell 30 s after the 6-wall task | 2/4/8 (13) | — / — | — / — | 4 / 75 / 2 | no | nothing |
| 2 | aegis | 400×400 | attack + tasks | bastion 176,108 | 1 (1 bastion); 148 / 159 | GHL | enemy tank fab switched on at 9 min (or when 100 W reached) | 3/5/8 (14) | 13 / — | — / — | 3 / 43 / 2 | no | graphite=300 beryllium=300 |
| 3 | lake | 320×320 | attack + tasks | bastion 236,83 | 1 (1 bastion); 159 / — | HL | enemy ship fab runs from the start (RTS squads, min 1) | 3/4/5 (11) | — / — | — / 17 | 8 / 21 / 1 | yes | graphite=200 beryllium=200 |
| 4 | intersect | 484×484 | waves | bastion 271,321 | 1 (1 bastion); 71 / — | L | wave 1 at 8.3 min (3 stell), then every 3 min | 3/8/9 (14) | 33 (not walkable) / — | 55 / 27 | 2 / 0 / 0 | no | graphite=200 beryllium=300 |
| 5 | atlas | 586×586 | attack | bastion 278,517 | 4 (3 bastion, 1 citadel); 179 / 191 | GHL | RTS squads ≤5 from the start; furnace on at 4 min | 2/4/8 (27) | 89 / — | — / 136 | 23 / 154 / 2 | yes | graphite=300 beryllium=300 |
| 6 | split | 600×600 | attack + tasks | bastion 278,398 | 1 (1 bastion); 128 / — | air only | none: enemy has no factory | 1/6/10 (15) | 87 (not walkable) / — | — / 39 | 1 / 27 / 0 | yes | graphite=200 beryllium=200 |
| 7 | basin | 586×586 | attack + tasks | bastion 337,376 | 6 (2 citadel, 4 bastion); 87 / 93 | GHL | 6 enemy fabs on at 6 min; furnace at 3 min | 1/4/6 (44) | 104 / — | 198 / 95 | 38 / 413 / 17 | no | graphite=200 beryllium=300 |
| 8 | marsh | 586×586 | attack + tasks | bastion 371,232 | 1 (1 bastion); 232 / 255 | GHL | map opens 8 min after the first chem. chamber | 2/4/5 (10) | 7 / — | 22 / — | 14 / 166 / 3 | yes | graphite=600 beryllium=600 tungsten=50 |
| 9 | peaks | 310×390 | attack + tasks | bastion 210,88 | 2 (2 bastion); 125 / — | air only | enemy ship fabs on 3 min after your first avert | 3/4/6 (17) | 107 (not walkable) / — | 100 / 198 | 8 / 38 / 4 | yes | graphite=100 silicon=100 beryllium=100 tungsten=650 |
| 10 | ravine | 494×494 | waves | bastion 192,63 | none; 3 ground spawn(s), nearest 314 / 401 | GHL (spawn) | wave 1 at 11 min (1 locus), every 1.8 min | 3/7/13 (20) | 43 / — | 68 / 38 | 0 / 0 / 0 | yes | graphite=1000 silicon=600 beryllium=1000 |
| 11 | caldera-erekir | 450×450 | attack | bastion 86,68 | 1 (1 bastion); 257 / — | HL | enemy ship fab on at 5 min | 1/6/7 (40) | 31 / 172 (not walkable) | 18 / 23 | 23 / 481 / 1 | no | graphite=200 silicon=200 surge-alloy=50 beryllium=200 |
| 12 | stronghold | 586×586 | attack + tasks | citadel 382,514 | 10 (8 bastion, 1 citadel, 1 acropolis); 185 / 192 | GHL | flag units1 at 3 min (mech fab) | 1/7/8 (91) | 161 / 124 (walk 228) | 212 / — | 106 / 325 / 21 | yes | graphite=700 silicon=500 beryllium=1000 tungsten=500 oxide=200 |
| 13 | crevice | 494×494 | waves | bastion 205,113 | none; 1 ground spawn(s), nearest 326 / 450 | GHL (spawn) | wave 1 at 6 min, every 2 min | 2/3/8 (24) | 96 (not walkable) / 32 | 36 / 71 | 0 / 0 / 0 | yes | graphite=500 thorium=100 silicon=800 beryllium=500 tungsten=300 oxide=200 |
| 14 | siege | 400×600 | attack + tasks | bastion 124,52 | 4 (3 bastion, 1 citadel); 202 / 227 | GHL | map opens at 10 min; refabs at 20/30/37 min | 5/7/12 (51) | 80 (walk 201) / — | 115 / 86 | 89 / 1195 / 24 | yes | graphite=1000 thorium=700 silicon=800 beryllium=1000 tungsten=550 oxide=300 carbide=200 |
| 15 | crossroads | 600×420 | attack + tasks | bastion 332,248 | 3 (2 bastion, 1 citadel); 151 / — | air only | refab/assembler flags from 10 min | 3/6/11 (43) | 12 / 49 | 31 / 43 | 50 / 177 / 12 | yes | graphite=1000 thorium=400 silicon=800 beryllium=1000 tungsten=400 oxide=300 carbide=100 |
| 16 | karst | 600×600 | waves | citadel 170,446 | none; 2 ground spawn(s), nearest 246 / 500 | GHL (spawn) | wave 1 at 11 min (10 merui + 6 elude), every 8 min | 3/4/11 (46) | 70 / 31 | 29 / 39 | 0 / 0 / 0 | yes | graphite=1000 thorium=500 silicon=500 beryllium=1000 tungsten=600 oxide=300 carbide=300 |
| 17 | origin | 600×600 | attack + tasks | acropolis 160,377 | 9 (3 bastion, 4 acropolis, 2 citadel); 110 / 179 | GHL | fabs on at 10 min; waves when the 355,306 citadel falls | 5/10/15 (90) | 62 (walk 172) / 76 (walk 167) | 85 / 21 | 189 / 1886 / 29 | no | graphite=5000 thorium=2000 silicon=3000 surge-alloy=1000 beryllium=5000 tungsten=2000 oxide=2000 carbide=1000 |

What the table says at a glance:

- **Who can reach the enemy.** On 6 maps tanks cannot reach the nearest enemy core:
  - lake: slag sea, 60-tile gap; hover can cross.
  - intersect: 1-2-tile rock seams; legs only.
  - split: 9-tile rock gap; nothing walks or hovers over it.
  - peaks: air only.
  - caldera: slag; hover takes 540 tiles, legs 284.
  - crossroads: its nearest core is air only; another is legs only.

  On 3 more maps some cores are cut off: atlas 2 of 4, stronghold 2 of 10, origin 1 of 9. On ravine the air spawn is a sealed pocket. In each case the unit class just unlocked is the answer: ships at lake, mechs at intersect, payload mass drivers at split, avert at peaks.
- **Free resources.** The enemy has free resources (`infiniteResources`) on 11 maps. It does not on onset, aegis, intersect, basin, caldera and origin; on those, its core holds a copy of the loadout and its pre-built economy has to feed it.
- **Waves.** Wave maps are intersect, ravine, crevice and karst. On origin the wave groups are fired by script. Everywhere else, pressure comes from the enemy's own factories, which world processors and objective timers switch on.


## 2. Map by map

Each section: the facts the scanner read (rules, cores, waves, resources, routes, pre-built bases), then notes. Raw output per map: `maptools/out/scan-<map>.txt`; base layout statistics: `maptools/out/study.txt`.

### 2.1 onset (512×512)

**Rules.** attack; start area limited to x 224+89, y 67+79. Enemy: RTS AI true (squads 2..50), free resources false, unit build speed ×1.0. No-build radius around cores 54 tiles; unit cap 0. Start items: nothing.

**Objectives:** 0 Item: item=beryllium amount=60; 1 BuildCount (after 0): block=turbine-condenser count=1; 2 BuildCount (after 1): block=plasma-bore count=1; 3 BuildCount (after 2): block=beam-node count=1; 4 CoreItem (after 3): item=beryllium amount=5; 5 CoreItem (after 4): item=beryllium amount=200; 6 CoreItem (after 5): item=graphite amount=100; 7 Research (after 6): content=silicon-arc-furnace; 8 CoreItem (after 7): item=silicon amount=50; 9 BuildCount (after 8): block=tank-fabricator count=1; 10 UnitCount (after 9): unit=stell count=1; 11 CommandMode (after 10); 12 BuildCount (after 11): block=breach count=1; 13 BuildCount (after 19): block=beryllium-wall count=6; 14 Timer (after 13): enemiesapproaching duration=30s flags+[defStart]; 15 DestroyUnits (after 14): count=2 flags+[defDone]; 16 DestroyBlock (after 15): pos=(288, 198) team=malis block=core-bastion; 17 BuildCount (after 16): block=core-bastion count=1; 18 Timer (after 17): text=@onset.defenses duration=2.0min flags+[openMap]; 19 Flag (after 12): flag=breachAmmo text=@gz.supplyturret

**Cores.** Player core-bastion at 276,107. Enemy: bastion@287,197 (90), bastion@293,288 (181).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 2 | 4 | 8 | 13 | 3 | 15 | 15 |
| sand wall (exposed) | 127 | 324 | 455 | 1035 | 239 | 13 | 12 |
| wall ore beryllium (exposed) | 12 | 17 | 24 | 36 | 12 | 14 | 13 |
| wall ore graphite (exposed) | 4 | 11 | 24 | 40 | 11 | 28 | 29 |

Vents: nearest 6 at 289,116(15) 272,86(21) 243,121(35) 264,156(50) 294,178(73,malis) 255,186(81) (distance, and the team whose block already stands on it). Already built on at the start: 8 of them {malis:turbine-condenser=8}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@287,197 | 90 | 105 | 105 | 101 | ~7 wide at 288,140 (path 105); ~6 wide at 262,163 (path 105); no other route |
| enemy bastion@293,288 | 181 | 198 | 198 | 194 | ~7 wide at 288,140 (path 198); ~6 wide at 262,163 (path 198); no other route |

Separate ground regions of 300+ tiles: 1; player's region 14592 tiles (99% of open ground); vents in player's ground region 13/13.

**Enemy base**, 737 blocks: turret 4 (breach×4); wall 75 (beryllium-wall×69 beryllium-wall-large×6); units 2 (tank-fabricator×2); drill 34 (plasma-bore×25 cliff-crusher×9); crafter 2 (silicon-arc-furnace×2); power 59 (beam-node×51 turbine-condenser×8); ducts/bridges/routers 494; conduits/pumps/tanks 65.
Turrets and unit blocks by nearest core (60-tile radius): 287,197 breach×1 / 293,288 breach×3 units×2.
Layout (xtest.Study): 4 breach in 3 groups; 75 Be walls in 5 pieces (U-shapes) 3-6 tiles from a turret, 23 in front / 39 at the sides / 4 behind; every breach fed by a duct line ending in a duct router, 25-33 ducts long.

**Player's pre-built blocks** (1): none besides the core.

**World processors:** Spawns 2 stell at 290,147 when `defStart` is set (30 s after the 6-wall task). Opens the map in two steps (`defDone` → 119×144 rect, `openMap` → 119×293). Sets `breachAmmo` once the player's breach has ammo. Ramps `unitBuildSpeed` for team crux, which does nothing here because the enemy is malis.

**Notes.**

- Tutorial. The player starts with **nothing** (empty loadout) and the first task is to dig 60 beryllium with the core unit. The enemy also has no free resources and an empty core, so this is the one map where both sides start at zero.
- The start area is 89×79 tiles and opens in two scripted steps. Both enemy bastions are outside it at the start. Everything is ground-connected through one corridor (~7 wide at 288,140, then ~6 at 262,163, then no other route), so an attack on the player has exactly one way in.
- Rock covers 94% of the map. Resources near the player are tiny: 12 exposed beryllium and 4 graphite wall tiles within 30, 2 vents within 30 (4 within 60). Sand walls are plentiful (127 within 30). No tungsten anywhere.
- The enemy base is a full tier-1 economy (25 plasma bores, 9 crushers, 2 arc furnaces, 8 turbines, 476 ducts) with 4 breach and 2 tank fabricators. The 8 turbines sit on the vents next to its cores.

**Overview.** (x 68..448, y 30..348; 1 column = 5 tiles, 1 row = 10 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 340|#############################################################################|
 330|#########################################ee..e#.eee...#######################|
 320|########################################bee....#eeee.########################|
 310|#########################################eeeee...ee.#########################|
 300|########################################....eeeY.eee..#######################|
 290|#######################################......eeeeeeeee#######################|
 280|######################################..eeeeeXeeeeeeee#######################|
 270|####################################....G=..e..=e=.e.########################|
 260|###################################........=G..=G=...g#######################|
 250|####################################.................g#######################|
 240|####################################g..............##########################|
 230|####################################g#.......################################|
 220|###################################.........#################################|
 210|###################################........##################################|
 200|###################################.......##..ee#e##ee#######################|
 190|####################################.......Xeeeeeeeeee#######################|
 180|####################################.:....eee..eeeeeeeg######################|
 170|#####################################....eeeeeeee..eeeg######################|
 160|######################################==beb##==G=############################|
 150|######################################.:.####..##############################|
 140|######################################   ###  ###############################|
 130|######################################       ################################|
 120|###################################:    #####################################|
 110|###################################         :bb##############################|
 100|##################################g      O  bb###############################|
  90|##################################ggg       #################################|
  80|#####################################   :####################################|
  70|#############################################################################|
  60|#############################################################################|
  50|#############################################################################|
  40|#############################################################################|
  30|#############################################################################|
```

### 2.2 aegis (400×400)

**Rules.** attack; start area limited to x 0+400, y 0+171. Enemy: RTS AI true (squads 2..50), free resources false, unit build speed ×0.5. No-build radius around cores 35 tiles; unit cap 0. Start items: graphite=300 beryllium=300.

**Objectives:** 0 CoreItem: item=tungsten amount=100 flags+[beginBuild]

**Cores.** Player core-bastion at 176,108. Enemy: bastion@103,237 (148).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 3 | 5 | 8 | 14 | 8 | 22 | 22 |
| floor ore tungsten | 4 | 29 | 58 | 58 | 58 | 13 | 13 |
| sand wall (exposed) | 48 | 323 | 603 | 935 | 513 | 21 | 22 |
| wall ore beryllium (exposed) | 1 | 27 | 41 | 58 | 41 | 29 | 28 |
| wall ore graphite (exposed) | 4 | 22 | 22 | 35 | 22 | 23 | 23 |

Vents: nearest 6 at 169,129(22) 191,127(24) 156,86(29,sharded) 135,115(41,sharded) 209,73(48,sharded) 127,145(61) (distance, and the team whose block already stands on it). Already built on at the start: 9 of them {malis:turbine-condenser=6, sharded:turbine-condenser=3}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@103,237 | 148 | 159 | 159 | 159 | ~23 wide at 169,145 (path 159); ~10 wide at 150,142 (path 162); no other route |

Separate ground regions of 300+ tiles: 1; player's region 14905 tiles (99% of open ground); vents in player's ground region 14/14.

**Enemy base**, 397 blocks: turret 3 (breach×3); wall 43 (beryllium-wall×34 beryllium-wall-large×9); units 2 (tank-fabricator×2); drill 23 (plasma-bore×19 cliff-crusher×4); crafter 1 (silicon-arc-furnace×1); power 42 (beam-node×36 turbine-condenser×6); ducts/bridges/routers 280; payload blocks 2.
Layout (xtest.Study): 3 breach in 2 groups; 43 Be walls in 2 pieces (8 and 62 tiles), 18 in front / 21 sides / 1 behind; fed by duct lines of 5-17.

**Player's pre-built blocks** (177): turret 1 (breach×1); drill 11 (plasma-bore×11); power (nodes+generators) 20; ducts/bridges/routers 114; conduits/pumps/tanks 30.

**World processors:** Switches on the enemy tank fabricator at 93,231 at minute 9, or when the 100-tungsten task ends (`beginBuild`). That task also opens the whole 400×400 map.

**Notes.**

- This is the map that introduces tungsten: 58 floor-tungsten tiles, 4 within 30 tiles and the nearest 13 away, all walkable. The first task is 100 tungsten in the core, delivered by blocks (PLAN 18.7: a core unit carrying it does not count), and finishing it opens the north half.
- The enemy core is 148 straight / 159 walking. The route is wide (~23 tiles at 169,145) with a second one (~10 wide) if that is closed: the most open approach of the early maps.
- The enemy is slow by rule (`unitBuildSpeed` 0.5) and its tank fabricator is off until minute 9. The enemy has no free resources: it starts with 300 Gr + 300 Be and its pre-built economy (19 bores, 4 crushers, 1 furnace, 6 turbines).
- The player gets 177 pre-built blocks: 11 bores, 3 turbines on the nearest vents, 1 breach and a conduit line. 3 of the 5 vents within 60 tiles are already taken by the player's turbines.

**Overview.** (x 61..392, y 62..399; 1 column = 5 tiles, 1 row = 10 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 392|###################################################################|
 382|###################################################################|
 372|###################################################################|
 362|###################################################################|
 352|###################################################################|
 342|###################################################################|
 332|###################################################################|
 322|###################################################################|
 312|###################################################################|
 302|###################################################################|
 292|###################################################################|
 282|###################################################################|
 272|###################################################################|
 262|#########be.e.e####################################################|
 252|###eeeegeee.e..####################################################|
 242|###.eeeeeee.ee#####################################################|
 232|###eee..X.e.eeb####################################################|
 222|###eeeYY.eeeeeb####################################################|
 212|####.eeeeee..###..#################################################|
 202|####===GeGeG==.....################################################|
 192|#....................##############################################|
 182|#....................##############################################|
 172|#####....###.........##############################################|
 162|########bb####        #############################################|
 152|#######   b###          ###########################################|
 142|######   :   :  bb      b #########################################|
 132|######    tt       b        g######################################|
 122|#####b     tt      # :    : g######################################|
 112|#####         p   t t         g####################################|
 102|#####   :  b ppppppppppOpppppppp###################################|
  92|######## ##bbbbbp p  p         ppg#################################|
  82|#################  ppp gg##b p p ##################################|
  72|##################   ####### p   ##################################|
  62|#############################  ####################################|
```

### 2.3 lake (320×320)

**Rules.** attack; whole map open. Enemy: RTS AI true (squads 1..50), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=200 beryllium=200.

**Objectives:** 0 BuildCount: block=ship-fabricator count=1; 1 UnitCount (after 0): unit=elude count=1

**Cores.** Player core-bastion at 236,83. Enemy: bastion@149,217 (159).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 3 | 4 | 5 | 11 | 11 | 11 | 10 |
| liquid slag (deep) | 934 | 8660 | 25557 | 94647 | 94647 | 17 | 16 |
| sand wall (exposed) | 49 | 49 | 80 | 220 | 220 | 10 | 9 |
| wall ore beryllium (exposed) | 4 | 4 | 12 | 22 | 22 | 13 | 12 |
| wall ore graphite (exposed) | 4 | 4 | 12 | 26 | 26 | 14 | 14 |

Vents: nearest 6 at 246,88(11,sharded) 230,69(15,sharded) 224,106(25) 182,88(54) 174,94(62) 107,157(148) (distance, and the team whose block already stands on it). Already built on at the start: 6 of them {malis:turbine-condenser=4, sharded:turbine-condenser=2}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@149,217 | 159 | — | 170 | 170 | — |

Separate ground regions of 300+ tiles: 3; player's region 2227 tiles (27% of open ground); vents in player's ground region 3/11; enemy bastion@149,217 is on another ground region (4588 tiles), narrowest gap from the player's region 60 tiles.

**Enemy base**, 277 blocks: turret 8 (breach×8); wall 21 (beryllium-wall×21); units 1 (ship-fabricator×1); drill 14 (plasma-bore×11 cliff-crusher×3); crafter 1 (silicon-arc-furnace×1); power 26 (beam-node×22 turbine-condenser×4); ducts/bridges/routers 205.
Layout (xtest.Study): 8 breach spread singly over 7 squares; 21 Be walls as 3 straight rows of 7, all within 3 tiles of a turret; duct lines 5-25.

**Player's pre-built blocks** (169): turret 3 (breach×3); drill 7 (plasma-bore×5 cliff-crusher×2); crafter 1 (silicon-arc-furnace×1); power (nodes+generators) 14; ducts/bridges/routers 143.

**World processors:** "Enable furnace1 at minute 4", but the link points outside the map, so it does nothing.

**Notes.**

- Deep slag covers 91% of the map. The player and enemy ground regions are 60 tiles apart, so ground units never meet: the enemy can only be reached by hover/ship (elude: 170 tiles) or air. The task is to build a ship fabricator and an elude.
- The player's island is small (2227 walkable tiles). It has 3 vents (2 already have the player's turbines), 49 exposed sand walls and 4 beryllium and 4 graphite wall tiles within 30. No tungsten, no arkycite. The economy is capped by space and power, not by ore.
- The enemy has free resources, 8 breach spread singly round its island and a ship fabricator, and attacks with RTS squads (min 1). A defence AI only has to watch the shore that faces the enemy island.

**Overview.** (x 0..319, y 0..319; 1 column = 4 tiles, 1 row = 8 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 312|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 304|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 296|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 288|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 280|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 272|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 264|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 256|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 248|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 240|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 232|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~   ~~ ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 224|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~  eeGeeeeeGeeeG= ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 216|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~eeeeeeXeeeebe     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 208|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ e#geeeYeeeeee     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 200|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~   =G eG   G=  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 192|~~~~~~~~~~~~~ ~~~~~~~~~~~~~~~~~~   ~~~~ ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 184|~~~~~~~~~~~~~#g     ~~~~         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 176|~~~~~~~~~~~~~~g   :  ~~    #bb  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 168|~~~~~~~~~~~~~~~~~~~~       eeG~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 160|~~~~~~~~~~~~~~~~~~~~~~~~    G   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 152|~~~~~~~~~~~~~~~~~~~~~~~~~ :          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 144|~~~~~~~~~~~~~~~~~~~~~~~~~~~~      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 136|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~   ~~~~~~~ ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 128|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~   ~~~~~~    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 120|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ ~~~~~~~~~~   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
 112|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~   ~~~~~~p ~~~~~~~~~~~~~~~~~~~~~~~|
 104|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~  ~~~~  p:     ~~~~~~~~~~~~~~~~~~|
  96|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~    ~~  ~    ppgg#bpp ~~~~~~~~~~~~~~~~~|
  88|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~bb  : :   ~    pppppppp  ~~~~~~~~~~~~~~~~|
  80|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~  bbgg     ~   ppp  pOpppp~~~~~~~~~~~~~~~~|
  72|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~    g  ~~~~~~    pppppppp ~~~~~~~~~~~~~~~~|
  64|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~p  pp ~~~~~~~~~~~~~~~~~|
  56|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~  ~~~~~~~~~~~~~~~~~~~~|
  48|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
  40|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
  32|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
  24|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
  16|~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~|
```

### 2.4 intersect (484×484)

**Rules.** no attack, waves; whole map open. Enemy: RTS AI false (squads 2..50), free resources false, unit build speed ×1.0. No-build radius around cores 20 tiles; unit cap 0. Capture at wave 9. Start items: graphite=200 beryllium=300.

**Cores.** Player core-bastion at 271,321. Enemy: bastion@208,287 (71). Ground spawns: 170,240 (129), 362,271 (103), 242,367 (54).

**Waves.** first wave after 8.3min (normal difficulty; ×0.8 on hard), then every 3.0min. Spawn groups (wave numbers 1-based), then totals per wave:

```
group stell begin=1 end=9 spacing=1 amount=3 scaling=4.0 spawn=242,367
group merui begin=2 end=9 spacing=1 amount=2 scaling=4.0 spawn=242,367
group merui begin=4 end=9 spacing=1 amount=3 scaling=4.0 spawn=362,271
group merui begin=5 end=9 spacing=1 amount=2 scaling=4.0 spawn=362,271
group merui begin=7 end=9 spacing=1 amount=4 scaling=1.0 spawn=170,240
group avert(air) begin=8 end=9 spacing=1 amount=1 scaling=1.0
wave 1 @8.3min: 3 units (0 air) hp~2550 {stell=3}
wave 2 @11.3min: 5 units (0 air) hp~3910 {stell=3, merui=2}
wave 3 @14.3min: 5 units (0 air) hp~3910 {stell=3, merui=2}
wave 4 @17.3min: 8 units (0 air) hp~5950 {stell=3, merui=5}
wave 5 @20.3min: 11 units (0 air) hp~8160 {stell=4, merui=7}
wave 6 @23.3min: 12 units (0 air) hp~8840 {stell=4, merui=8}
wave 7 @26.3min: 16 units (0 air) hp~11560 {stell=4, merui=12}
wave 8 @29.3min: 21 units (3 air) hp~16220 {stell=4, avert=3, merui=14}
wave 9 @32.3min: 27 units (6 air) hp~21730 {stell=5, avert=6, merui=16}
```

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 3 | 8 | 9 | 14 | 14 | 7 | 6 |
| floor ore tungsten | 0 | 25 | 50 | 50 | 50 | 33 | not walkable |
| liquid arkycite (deep) | 0 | 23 | 58 | 200 | 200 | 55 | not walkable |
| liquid slag (deep) | 10 | 73 | 1433 | 2113 | 2113 | 27 | 28 |
| sand wall (exposed) | 197 | 482 | 1159 | 2091 | 2091 | 9 | 8 |
| wall ore beryllium (exposed) | 18 | 22 | 31 | 31 | 31 | 9 | 8 |
| wall ore graphite (exposed) | 9 | 16 | 25 | 25 | 25 | 11 | 11 |

Vents: nearest 6 at 267,315(7) 278,310(13,sharded) 282,342(23) 297,294(37) 277,281(40) 297,282(46,malis) (distance, and the team whose block already stands on it). Already built on at the start: 4 of them {malis:turbine-condenser=2, malis:vent-condenser=1, sharded:turbine-condenser=1}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@208,287 | 71 | — | — | 95 | — |
| spawn@170,240 | 129 | — | — | 152 | — |
| spawn@362,271 | 103 | — | — | 116 | — |
| spawn@242,367 | 54 | 58 | 58 | 58 | ~13 wide at 260,337 (path 58); no other route |

Separate ground regions of 300+ tiles: 3; player's region 14365 tiles (36% of open ground); vents in player's ground region 5/14; enemy bastion@208,287 is on another ground region (13385 tiles), narrowest gap from the player's region 2 tiles; spawn@170,240 is on another ground region (13385 tiles), narrowest gap from the player's region 2 tiles; spawn@362,271 is on another ground region (11987 tiles), narrowest gap from the player's region 1 tiles.

**Enemy base**, 116 blocks: turret 2 (breach×2); drill 6 (plasma-bore×5 impact-drill×1); crafter 2 (vent-condenser×1 electrolyzer×1); power 15 (beam-node×12 turbine-condenser×2 chemical-combustion-chamber×1); ducts/bridges/routers 51; conduits/pumps/tanks 38; other 1 (shield-projector×1).
Layout (xtest.Study): 2 breach, no walls, duct lines 2 and 11; a shield projector and the only chemical combustion chamber on an early map.

**Player's pre-built blocks** (31): drill 5 (plasma-bore×5); power (nodes+generators) 6; ducts/bridges/routers 19.

**World processors:** After wave 1, an explosion (500 damage, radius 7) at 293,285 takes out the enemy shield projector it links to.

**Notes.**

- Waves: the first at 8.3 min (3 stell), then every 3 min, capture at wave 9 (32 min), then attack the core. Wave 9 is 27 units, ~21.7k HP, 6 of them avert. From wave 2 most units are merui (legs).
- Of the 3 spawns, only 242,367 (54 tiles north) connects to the player by ground (~13 wide). The other two spawns and the enemy core are behind 1-2-tile rock seams that only legs cross. **Rock seams do not stop mechs**: they walk over them, so turret cover has to be all-round, not only on the tank lane.
- Within 30 tiles there are 3 vents, 18 beryllium and 9 graphite wall tiles and 197 sand walls. The 50 floor-tungsten tiles are all on the far side of a rock seam: not walkable, but 1-2 tiles of rock is within duct-bridge range.
- The enemy base is minimal: 2 breach, an impact drill, an electrolyzer, a chemical combustion chamber and a shield projector (which the script blows up after wave 1). The danger is the waves, not the base.

**Overview.** (x 0..421, y 168..438; 1 column = 6 tiles, 1 row = 12 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 432|#######################################################################|
 420|############################################        ~   ###############|
 408|#######################################               :     ~ #########|
 396|#####################################        ~~~~~         :  #########|
 384|###################################          ~~~         ##   #########|
 372|###################################              #       ##############|
 360|##################################      S       #    ~   ##############|
 348|#########################     ###             g##       ###############|
 336|#######################                      bg:#          ############|
 324|#####################       #             #    g## #     ~    #########|
 312|######################      ###   #########bpOpg###  ############# ####|
 300|#####################      ##  ###g  e######pppbbb#          # :    ###|
 288|######################          ##geeeG       ttee##                 ##|
 276|######################          #eXeeeG     # :t e        ~~         ##|
 264|#########################          eee#########g:###        S        ##|
 252|#########################        ##############gg###                 ##|
 240|#####################       S         ###########                     #|
 228|####################                     ########                   ###|
 216|####################                :   #########           ##  #######|
 204|####################            :      ###########         ############|
 192| #######################              ############# ## ################|
 180|##################################   ##################################|
 168|#######################################################################|
```

### 2.5 atlas (586×586)

**Rules.** attack; whole map open. Enemy: RTS AI true (squads 1..5), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=300 beryllium=300.

**Cores.** Player core-bastion at 278,517. Enemy: bastion@407,251 (295), bastion@339,289 (236), citadel@239,342 (179), bastion@442,371 (219). Ground spawns: 307,68 (449).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 2 | 4 | 8 | 27 | 27 | 16 | 16 |
| floor ore tungsten | 0 | 0 | 44 | 44 | 44 | 89 | 87 |
| liquid slag (deep) | 0 | 0 | 0 | 4067 | 4067 | 136 | 146 |
| sand wall (exposed) | 115 | 272 | 520 | 2173 | 2173 | 20 | 19 |
| wall ore beryllium (exposed) | 19 | 27 | 66 | 132 | 132 | 20 | 19 |
| wall ore graphite (exposed) | 11 | 20 | 30 | 108 | 108 | 26 | 25 |

Vents: nearest 6 at 274,533(16,sharded) 258,517(20,sharded) 254,496(31) 286,477(40) 257,455(65) 341,481(72) (distance, and the team whose block already stands on it). Already built on at the start: 16 of them {malis:turbine-condenser=14, sharded:turbine-condenser=2}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@407,251 | 295 | — | 346 | 319 | — |
| enemy bastion@339,289 | 236 | 302 | 302 | 253 | ~7 wide at 296,294 (path 302); ~1 wide at 352,315 (then only a diagonal squeeze); ~1 wide at 339,320 (then only a diagonal squeeze) |
| enemy citadel@239,342 | 179 | 191 | 191 | 191 | ~14 wide at 336,424 (path 191); ~13 wide at 260,436 (path 191); ~6 wide at 293,426 (path 213) |
| enemy bastion@442,371 | 219 | — | 242 | 239 | — |
| spawn@307,68 | 449 | — | — | — | — |

Separate ground regions of 300+ tiles: 3; player's region 21674 tiles (79% of open ground); vents in player's ground region 20/27; enemy bastion@407,251 is on another ground region (3399 tiles), touching the player's region only at a diagonal (gap 0); enemy bastion@442,371 is on another ground region (1898 tiles), narrowest gap from the player's region 12 tiles.

**Enemy base**, 1462 blocks: turret 23 (breach×20 diffuse×3); wall 154 (beryllium-wall×135 beryllium-wall-large×19); units 2 (mech-fabricator×1 ship-fabricator×1); drill 65 (plasma-bore×61 cliff-crusher×4); crafter 2 (electrolyzer×1 silicon-arc-furnace×1); power 114 (beam-node×100 turbine-condenser×14); ducts/bridges/routers 1027; conduits/pumps/tanks 71.
Turrets and unit blocks by nearest core (60-tile radius): 407,251 breach×3 units×2 / 339,289 breach×5 / 239,342 breach×8 diffuse×2 / 442,371 breach×4 diffuse×1.
Layout (xtest.Study): 23 turrets in groups of 1-3; 154 walls in 22 pieces (13 of them rows ≤2 thick), 137 within 3 tiles of a turret; all 23 fed by ducts, median line 13, longest 66.

**Player's pre-built blocks** (199): turret 3 (breach×3); wall 14; drill 12 (plasma-bore×8 cliff-crusher×4); crafter 1 (silicon-arc-furnace×1); power (nodes+generators) 20; ducts/bridges/routers 148.

**World processors:** Turns on the enemy silicon furnace at 405,238 at minute 4.

**Notes.**

- 4 enemy cores. Two are reachable by ground: the citadel at 239,342 (walk 191, routes ~14/~13/~6 wide) and the bastion at 339,289 (walk 302, ~7 wide). The bastions at 407,251 and 442,371 sit on separate ground (gaps of 0, i.e. touching only diagonally, and 12 tiles), so hover or legs must take those.
- The player has 2 vents within 30 and 4 within 60 (2 already have the player's turbines). The nearest tungsten is 87 tiles walk (44 floor tiles in one patch at 341,454). PLAN 18.23 measured the consequence: only 4 vents within 40, so power is the hard cap.
- The enemy has free resources, 23 turrets (20 breach, 3 diffuse) in groups of 1-3 with walls 0-3 tiles in front, and 1 mech + 1 ship fabricator, both at 407,251 next to the southeast core. Its RTS squads are capped at 5.

**Overview.** (x 208..473, y 206..541; 1 column = 4 tiles, 1 row = 8 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 534|###############pppbbbb#############################################|
 526|###########g  ppp  p pp############################################|
 518|##########gppppppp   pppb##########################################|
 510|##########g p    Opppppb###########################################|
 502|#########gg    pppppppp ###########################################|
 494|########## :   p     pp   #########################################|
 486|###########   pp   #        #######################################|
 478|#############                    : ################################|
 470|############       :               b###############################|
 462|##########         b#bbb#        : b b#############################|
 454|##########  :      gg #bb        tt  b#############################|
 446|##########     ##gg#bb          ttt g##############################|
 438|#######bb     g########  :         bg##############################|
 430|########b      g#####b            #################################|
 422|########b#b     ####  bb  bb      ############## ~~~~~~~###########|
 414|##########            ########       #####gg g#g ~~~~~~~~~~~~~~####|
 406|##########           ####               : # :gg    ~~~~~~~~~~~~~~##|
 398|##########                                       ~~~~~~~~~~~~~~~~##|
 390|###########=              :          #      ~~~~~~~~~~ G=  ~~~~  g#|
 382|######## eeG=     ##           ~~###########~~~~~~~~~~~=eeeeGe eee#|
 374|#####    eeeG=:  #########     ~ ############~~~~~~~~~=Geeeeee eg##|
 366|##    eeeee  ###############     ##############~~~#~=G  eeXeeeee###|
 358|##  e e G=e################      ###################=eeeeeeeeebb###|
 350|eeeeeeeeeeeeee#############        ###################  e ebb######|
 342|#ee=GeeXeeeGeeg############         ################## =G  ########|
 334|## e eeeee==eeeg#########g           ################# ~~##########|
 326|#eeeeeeeGeee  eeg######     #        #################  ~~  #######|
 318|##eeeeebeeeeeee   #####    =G  #      ##############~~~~~~  #######|
 310|#########gggeeeeee#####g    e  == #    #############~~~~~~~########|
 302|#############g GGG####gg    eeeeGeeeeG=   #########== ==  #########|
 294|##############=====   g    eee e  eee  eeeG  #####  G  G###########|
 286|################          eeee# X eeeeeee =  ####  eeeeeb##########|
 278|#################    #    ee######bb## eG      eeeee eeeb##########|
 270|########################bb##############=   =Geeee e eeee##########|
 262|############################################    e YeYeee###########|
 254|############################################  e e eeeeee###########|
 246|###########################################~   e Xeeeeeb###########|
 238|############################################     eeee  ############|
 230|##############################################eeeeeeeee############|
 222|###############################################eee  ee#############|
 214|###################################################################|
 206|###################################################################|
```

### 2.6 split (600×600)

**Rules.** attack; whole map open. Enemy: RTS AI false (squads 4..50), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=200 beryllium=200.

**Objectives:** 0 CoreItem: item=tungsten amount=100; 1 BuildCount (after 0): block=payload-mass-driver count=2; 2 DestroyCore (after 1)

**Cores.** Player core-bastion at 278,398. Enemy: bastion@406,388 (128).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 1 | 6 | 10 | 15 | 15 | 25 | 26 |
| floor ore tungsten | 0 | 0 | 55 | 64 | 64 | 87 | not walkable |
| liquid slag (deep) | 0 | 534 | 1097 | 1097 | 1097 | 39 | 40 |
| sand wall (exposed) | 142 | 272 | 535 | 740 | 740 | 12 | 11 |
| wall ore beryllium (exposed) | 20 | 27 | 27 | 35 | 35 | 12 | 11 |
| wall ore graphite (exposed) | 18 | 25 | 25 | 25 | 25 | 17 | 17 |

Vents: nearest 6 at 285,423(25,sharded) 280,431(33) 252,423(36,sharded) 262,436(41,sharded) 290,445(48,sharded) 281,453(55,sharded) (distance, and the team whose block already stands on it). Already built on at the start: 12 of them {malis:turbine-condenser=2, sharded:turbine-condenser=9, sharded:vent-condenser=1}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@406,388 | 128 | — | — | — | — |

Separate ground regions of 300+ tiles: 2; player's region 4176 tiles (42% of open ground); vents in player's ground region 6/15; enemy bastion@406,388 is on another ground region (5504 tiles), narrowest gap from the player's region 9 tiles.

**Enemy base**, 114 blocks: turret 1 (breach×1); wall 27 (beryllium-wall×27); drill 6 (plasma-bore×6); crafter 1 (electrolyzer×1); power 7 (beam-node×5 turbine-condenser×2); ducts/bridges/routers 36; conduits/pumps/tanks 35.
Layout (xtest.Study): 1 breach inside one 27-wall piece, fed through an overflow duct (12 ducts).

**Player's pre-built blocks** (394): drill 28 (plasma-bore×23 cliff-crusher×3 impact-drill×2); crafter 2 (vent-condenser×1 silicon-arc-furnace×1); power (nodes+generators) 68; ducts/bridges/routers 222; conduits/pumps/tanks 64; containers 1; payload blocks 6 (payload-mass-driver×4 payload-loader×1 payload-unloader×1); other 2 (radar×2).

**World processors:** None.

**Notes.**

- The enemy core is on a separate rock-ringed region 9 tiles from the player's. No ground, hover or leg route exists. The tasks are 100 tungsten → 2 payload mass drivers (range 87.5 tiles) → destroy the core: units have to be fired across.
- The only floor tungsten (64 tiles at ~350,447) is **not walkable** from the player core, as PLAN 18.7 found; the player's pre-built mass drivers (4 drivers, a loader and an unloader) are there for that.
- The player gets a large pre-built base (393 blocks: 23 bores, 2 impact drills, 9 turbines, a vent condenser, 2 radars). The enemy base is tiny (1 breach, 6 bores, 1 electrolyzer) and has free resources but no factory.

**Overview.** (x 228..422, y 371..482; 1 column = 3 tiles, 1 row = 6 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 479|#################################################################|
 473|#####################################    ### ####################|
 467|############# ~~~~~~#################    ppppp###################|
 461|##########~~~~~~~~~~~  #############       p p  #################|
 455|##  #  ~# ~~~~~         #########      p ptttpp    ##############|
 449|#      ~~     p  p  ~   b#######  #      tppppppp   #############|
 443|#~~   ~~~~ p  p  ~p p   bb##########    ttt pp        ###########|
 437|###~~ ~~~                 ###########   p   pp  p      ##########|
 431|###~~    p p     :       p p  #######  p ppppp  p      ##########|
 425|###~~    p  gg          pppp  ######## pppp              ########|
 419|####    pp  g#     p    pp     #######                       ####|
 413|#####ppppppppp  p  p             ####                        ####|
 407|#####gg#pg#ppp  pppppppppppp##  #####                         ###|
 401|####### ppppppppp    #    pp###########                      ####|
 395|########p# p p  Oppp ##################    :          =G=====####|
 389|############ppppppppp #################               = eeeeeeee#|
 383|############bbpp ppppb#################        :     ## eeeXeeee#|
 377|###########################################      ###########eeb##|
 371|#################################################################|
```

### 2.7 basin (586×586)

**Rules.** attack; start area limited to x 0+586, y 333+253. Enemy: RTS AI true (squads 2..50), free resources false, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=200 beryllium=300.

**Objectives:** 0 DestroyBlocks: positions=[(290, 501), (158, 496)] team=malis block=core-bastion flags+[nukeannounce]; 1 Timer (after 0): nuclearlaunch duration=6.0min flags+[nuke1]

**Cores.** Player core-bastion at 337,376. Enemy: citadel@184,87 (327), citadel@85,182 (318), bastion@361,292 (87), bastion@105,338 (235), bastion@157,496 (216), bastion@289,501 (133).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 1 | 4 | 6 | 44 | 25 | 14 | 13 |
| floor ore tungsten | 0 | 0 | 0 | 269 | 125 | 104 | 116 |
| liquid arkycite (deep) | 0 | 0 | 0 | 574 | 26 | 198 | 213 |
| liquid slag (deep) | 0 | 0 | 17 | 182 | 92 | 95 | 113 |
| sand wall (exposed) | 80 | 415 | 798 | 4404 | 1948 | 13 | 13 |
| wall ore beryllium (exposed) | 4 | 20 | 28 | 164 | 60 | 13 | 13 |
| wall ore graphite (exposed) | 0 | 6 | 18 | 91 | 29 | 49 | 48 |

Vents: nearest 6 at 334,390(14) 369,372(32,sharded) 355,408(36,sharded) 279,380(58) 382,334(61) 354,279(98,malis) (distance, and the team whose block already stands on it). Already built on at the start: 27 of them {malis:turbine-condenser=12, malis:vent-condenser=13, sharded:turbine-condenser=2}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy citadel@184,87 | 327 | 371 | 365 | 363 | ~9 wide at 351,344 (path 371); no other route |
| enemy citadel@85,182 | 318 | 373 | 373 | 367 | ~7 wide at 100,188 (path 373); ~5 wide at 82,198 (path 385); no other route |
| enemy bastion@361,292 | 87 | 93 | 93 | 93 | ~9 wide at 351,344 (path 93); no other route |
| enemy bastion@105,338 | 235 | 340 | 340 | 328 | ~9 wide at 351,344 (path 340); no other route |
| enemy bastion@157,496 | 216 | 235 | 235 | 230 | ~14 wide at 184,488 (path 235); no other route |
| enemy bastion@289,501 | 133 | 201 | 201 | 196 | ~16 wide at 289,501 (path 201) (at least this wide all the way) |

Separate ground regions of 300+ tiles: 1; player's region 84820 tiles (99% of open ground); vents in player's ground region 44/44.

**Enemy base**, 5121 blocks: turret 38 (breach×32 sublimate×6); wall 413 (beryllium-wall×241 beryllium-wall-large×172); units 17 (tank-fabricator×6 mech-fabricator×3 tank-refabricator×3 mech-refabricator×3 ship-refabricator×1 ship-fabricator×1); drill 113 (plasma-bore×83 cliff-crusher×21 impact-drill×9); crafter 29 (vent-condenser×13 electrolyzer×11 silicon-arc-furnace×5); power 332 (beam-node×305 turbine-condenser×12 chemical-combustion-chamber×11 beam-tower×2 beam-link×2); ducts/bridges/routers 2850; conduits/pumps/tanks 1279; containers 7; payload blocks 37.
Turrets and unit blocks by nearest core (60-tile radius): 184,87 (no turrets) / 85,182 units×5 / 361,292 units×4 breach×3 sublimate×1 / 105,338 breach×6 / 157,496 units×2 / 289,501 (no turrets) / far from any core breach×23 units×6 sublimate×5.
Layout (xtest.Study): 38 turrets (32 breach, 6 sublimate) in groups of 2-4; 413 walls in 28 pieces (median ~22 tiles), 97 front / 173 sides / 80 behind; 32 item turrets all fed (22 by a duct, 10 straight from a router), median line 5.

**Player's pre-built blocks** (240): drill 17 (plasma-bore×12 cliff-crusher×5); crafter 1 (silicon-arc-furnace×1); power (nodes+generators) 34; ducts/bridges/routers 187.

**World processors:** Turns on 6 enemy fabricators (3 mech at 254,168-174, 3 tank at 121-127,177) at minute 6, and the furnace at 177,488 at minute 3. When both north cores (290,501 and 158,496) are gone, a 6-minute timer runs and then a missile nukes the player's start core at 338,377 (radius 52, 5000 damage). After that the map opens, and malis `rtsMinWeight` (0.85 + minutes/35) and `unitBuildSpeed` (half of that) grow with time. The details are in PLAN 18.23.

**Notes.**

- 6 enemy cores, all ground-connected through one big region (99% of open ground). From the start area every route leaves through one choke, ~9 wide at 351,344, the only way out to the south cores. The north cores (289,501 at walk 201 and 157,496 at 235) are the objective, and destroying them starts the nuke countdown (see processors).
- The enemy has no free resources here. It has 6 tank, 3 mech and 1 ship fabricators plus 3 tank, 3 mech and 1 ship refabricators. 38 turrets cover a base of 5121 blocks, 2850 of them ducts and 1265 conduits. Its economy includes 9 impact drills, 13 vent condensers, 11 electrolyzers and 11 chemical combustion chambers.
- The player has 1 vent within 30 and 4 within 60. Tungsten is ≥104 tiles away (walk 116). There are 4 beryllium wall tiles within 30 and no graphite until 49. The start is poor; the resources are out in the contested middle.

**Overview.** (x 26..421, y 42..516; 1 column = 5 tiles, 1 row = 10 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 512|##################################################gg############################|
 502|########################eeeeb#g#################eeee  :#########################|
 492|#######################eeeXeeeeeeee#############eeeeX :#########################|
 482|####################ee#eeeYYeeeeeeeeee######### eeeeeeb#########################|
 472|###################geeeeeeeeeee#:  eeee =####= eeeee# ##########################|
 462|#########################eee#######   eeG #    e   :############################|
 452|##########################e #######   GG=   =Geet ##############################|
 442|########################eeeeee######        ===  ###############################|
 432|####################### eeee## ###              ################################|
 422|########################:eeG=           :      #################################|
 412|########################=====   :               ################################|
 402|############################     g##g           #  ##############p bb pp########|
 392|###################  ## ## :#####g###               #       ####  pp pppb#######|
 382|#################            #####g#######                   :bb  pppppb########|
 372|###############====##                    ###   #  :           Opppppp  #########|
 362|##############e GG########                 ######         pppppp pp   ##########|
 352|#############eeeee########          :        ######   p pppp ppp   #############|
 342|#############beee#########   #       b##      g ######ppp pp####  ##############|
 332|############# eXe########=G=###                     g##pp######    #=  :  ######|
 322|#############...e#########eee===......................g.###....==GGG=.......eb##|
 312|#############b..e.########eeeeeG......................G==#......eeeeeeeeeeeeeee#|
 302|##############.GGG.#######=Geee#........G==G..........=eeeeeeeeeeeeeeee....eeb##|
 292|##########.##.======...#...==#####......====.....:....Gee=.eeeeeee.Xeeeeee.eee##|
 282|########ee=...................######.....................###...eeeeeeYY.e....###|
 272|######eeGG=.....................#####...................g.##..eeeee.ee.ee..#####|
 262|##...ee.GG=.......................##...........:...........##..eeeeeeeeee.######|
 252|#eee.e..ee########..#......................................###...eeeee.#########|
 242|#eeeeeeee#.eee.e#ee#####==.................................#.....G=...##########|
 232|##..e..e##.ete....ee#.##.GG=##.#####.......................................#####|
 222|##.eeeeeeeeeee.bbbeeeeeeeee.#######...........................................##|
 212|###.eeee..b#bee##eee......e...##############..##..........................b.:.##|
 202|#####..eeeeeeeeeee.e......e.#######.##....########.........#..........#####bb###|
 192|########.bb.b#eeeeee.eeeeeeeeeee##eeeeeee...eeee####......###.........##########|
 182|#########..X=.bbbee...eeeeeeeeee...eeeeeeeeeeYYee....G=.G=###..........#########|
 172|#########..==.bbbeeYYYYe.eeeeee#eee.e#beee..eYee.....e=.Ge####==GGG=G=##########|
 162|################.eeeeeee.....##eee####beeeeeeYee.....e=.e#####..eeeee###########|
 152|#################..##bee.##....teeee##b.bbe.ee.e..G..e..e####...eee.############|
 142|###########################gggggeeee#b.e.....e.e..eeeGeee####...eeb#############|
 132|################gee##############.eeeeee~~~~.e.e..e.ee.....#....eee#############|
 122|###############eeee..#############.eee.e...eee.e..eeeeeeeeeeeeeee###############|
 112|###############eeeeeeee.###########eeeeeeeeeeeeeeeeeeeeeee.e.e##################|
 102|#####################eee.###....e.........e.e.....eeeeeeee.e.###################|
  92|######################eeeee#eeeeeeeeeeeeeee.e..#..e.e.e.e...####################|
  82|########################.eeeee.X......############....##########################|
  72|###########################eeeeeee##############.....b##########################|
  62|##############################e################.....b###########################|
  52|#############################################......#############################|
  42|###############################################.#.##############################|
```

### 2.8 marsh (586×586)

**Rules.** attack; start area limited to x 240+210, y 185+145. Enemy: RTS AI true (squads 2..6), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=600 beryllium=600 tungsten=50.

**Objectives:** 0 Research: content=oxidation-chamber; 1 BuildCount (after 0): block=oxidation-chamber count=1; 2 Produce (after 0): content=oxide; 3 Research (after 5): content=chemical-combustion-chamber; 4 Research (after 1,2): content=reinforced-pump; 5 Research (after 4): content=arkycite; 6 BuildCount (after 3): block=chemical-combustion-chamber count=1; 7 Timer (after 6): enemiesapproaching duration=8.0min flags+[setupComplete]

**Cores.** Player core-bastion at 371,232. Enemy: bastion@357,464 (232).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 2 | 4 | 5 | 10 | 5 | 14 | 13 |
| floor ore beryllium | 82 | 103 | 103 | 264 | 103 | 11 | 9 |
| floor ore tungsten | 26 | 26 | 49 | 149 | 49 | 7 | 5 |
| liquid arkycite (deep) | 60 | 519 | 718 | 1070 | 718 | 22 | 23 |
| sand wall (exposed) | 39 | 122 | 165 | 529 | 153 | 16 | 16 |
| wall ore beryllium (exposed) | 0 | 4 | 4 | 14 | 4 | 42 | 44 |
| wall ore graphite (exposed) | 14 | 14 | 14 | 62 | 14 | 20 | 20 |

Vents: nearest 6 at 382,222(14,sharded) 372,252(20) 386,258(30,sharded) 352,257(31) 372,312(80) 292,350(142,malis) (distance, and the team whose block already stands on it). Already built on at the start: 7 of them {malis:turbine-condenser=1, malis:vent-condenser=4, sharded:turbine-condenser=2}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@357,464 | 232 | 255 | 255 | 250 | ~7 wide at 390,331 (path 255); no other route |

Separate ground regions of 300+ tiles: 1; player's region 15079 tiles (98% of open ground); vents in player's ground region 10/10.

**Enemy base**, 1103 blocks: turret 14 (breach×12 sublimate×2); wall 166 (beryllium-wall×133 beryllium-wall-large×31 blast-door×2); units 3 (mech-fabricator×1 tank-refabricator×1 tank-fabricator×1); drill 16 (impact-drill×6 plasma-bore×6 cliff-crusher×4); crafter 8 (vent-condenser×4 electrolyzer×3 silicon-arc-furnace×1); power 38 (beam-node×33 chemical-combustion-chamber×4 turbine-condenser×1); ducts/bridges/routers 509; conduits/pumps/tanks 348.
Layout (xtest.Study): 14 turrets; 166 walls in 7 pieces, one 152-tile and one 73-tile; 2 blast doors in the wall line; 12 item turrets fed, median line 2.

**Player's pre-built blocks** (152): drill 13 (plasma-bore×8 cliff-crusher×5); crafter 1 (silicon-arc-furnace×1); power (nodes+generators) 18; ducts/bridges/routers 119.

**World processors:** Opens the whole map when `setupComplete` is set (8 min after the first chemical combustion chamber).

**Notes.**

- The start area is 210×145 and the map opens 8 min after the first chemical combustion chamber. The objectives are a research-and-build chain: oxidation chamber → oxide → reinforced pump → arkycite → chemical combustion chamber.
- The richest start of the campaign: 26 floor-tungsten tiles within 30 (nearest 7 tiles), 82 floor beryllium within 30 (impact drill), deep arkycite 22 tiles away (60 tiles of it within 30), 2 vents within 30. Rock-wall ore is scarce (no beryllium wall within 30).
- One enemy core, walk 255, one ~7-wide choke at 390,331 with no alternative. The enemy has free resources, 14 turrets (12 breach, 2 sublimate) and 166 walls with 2 blast doors, and 12 of its 14 turrets stand more than 60 tiles from its core, as outposts along the approach.

**Overview.** (x 114..546, y 40..531; 1 column = 6 tiles, 1 row = 12 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 520|#########################################################################|
 508|#########################################################################|
 496|#########################################################################|
 484|#########################################################################|
 472|#######################################ee#b##############################|
 460|######################################.GXee##############################|
 448|####################################eeeeeG.##############################|
 436|#####################################eeeeb###############################|
 424|###################################eeeeee.###############################|
 412|################################.eeeeYYe.################################|
 400|##############################.eeeeeeeGG#################################|
 388|##########################....~eeg#geeeeeeGggggg.~#######################|
 376|##########################.....eeg#g=GeG=G=.......#######################|
 364|#############################bbeee##g.tt.t.......########################|
 352|#############################eeeeee###........###########################|
 340|#############################eeeeGG=...........##########################|
 328|###############################e=GG=..###b###..##########################|
 316|###########################################     #########################|
 304|########################################## :tt    #######################|
 292|##########################################   t   ########################|
 280|#########################################      ##########################|
 268|######################################   b  ~  ##########################|
 256|#######################################: b   p pp########################|
 244|######################################  bbb:   ppb#######################|
 232|#####################################~  bbOppppp#########################|
 220|######################################   ttppppp#########################|
 208|##########################################    g##########################|
 196|#########################################################################|
 184|#########################################################################|
 172|#########################################################################|
 160|#########################################################################|
 148|#########################################################################|
 136|#########################################################################|
 124|#########################################################################|
 112|#########################################################################|
 100|#########################################################################|
  88|#########################################################################|
  76|#########################################################################|
  64|#########################################################################|
  52|#########################################################################|
  40|#########################################################################|
```

### 2.9 peaks (310×390)

**Rules.** attack; start area limited to x 53+224, y 37+114. Enemy: RTS AI true (squads 1..2), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=100 silicon=100 beryllium=100 tungsten=650.

**Objectives:** 0 Research (after 2): content=beam-tower; 1 BuildCount (after 3): block=chemical-combustion-chamber count=1; 2 CoreItem: item=tungsten amount=50; 3 BuildCount (after 0): block=beam-tower count=2; 4 Timer (after 5): enemiesapproaching duration=3.0min flags+[setupFinished]; 5 UnitCount (after 1): unit=avert count=1 flags+[openMap]

**Cores.** Player core-bastion at 210,88. Enemy: bastion@183,211 (125), bastion@143,304 (226).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 3 | 4 | 6 | 17 | 7 | 19 | 18 |
| floor ore beryllium | 57 | 82 | 82 | 82 | 82 | 12 | 11 |
| floor ore tungsten | 0 | 0 | 0 | 149 | 51 | 107 | not walkable |
| liquid arkycite (deep) | 0 | 0 | 0 | 317 | 176 | 100 | not walkable |
| liquid slag (deep) | 0 | 0 | 0 | 25 | 0 | 198 | not walkable |
| sand wall (exposed) | 135 | 370 | 711 | 1571 | 550 | 11 | 10 |
| wall ore beryllium (exposed) | 0 | 0 | 40 | 96 | 31 | 76 | not walkable |
| wall ore graphite (exposed) | 33 | 96 | 96 | 162 | 96 | 12 | 11 |

Vents: nearest 6 at 224,102(19,sharded) 194,103(21,sharded) 187,74(26) 229,64(30,sharded) 198,172(84,malis) 220,184(96,malis) (distance, and the team whose block already stands on it). Already built on at the start: 14 of them {malis:turbine-condenser=9, malis:vent-condenser=1, sharded:turbine-condenser=4}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@183,211 | 125 | — | — | — | — |
| enemy bastion@143,304 | 226 | — | — | — | — |

Separate ground regions of 300+ tiles: 4; player's region 3925 tiles (32% of open ground); vents in player's ground region 4/17; enemy bastion@183,211 is on another ground region (3577 tiles), narrowest gap from the player's region 31 tiles; enemy bastion@143,304 is on another ground region (2698 tiles), narrowest gap from the player's region 152 tiles.

**Enemy base**, 1018 blocks: turret 8 (breach×5 disperse×2 diffuse×1); wall 38 (beryllium-wall×38); units 4 (ship-refabricator×2 ship-fabricator×2); drill 44 (plasma-bore×36 cliff-crusher×6 impact-drill×2); crafter 5 (electrolyzer×2 silicon-arc-furnace×2 vent-condenser×1); power 83 (beam-node×72 turbine-condenser×9 chemical-combustion-chamber×2); ducts/bridges/routers 557; conduits/pumps/tanks 277.
Turrets and unit blocks by nearest core (60-tile radius): 183,211 breach×5 units×2 diffuse×1 / 143,304 disperse×2 units×2.
Layout (xtest.Study): 8 turrets; 38 walls in 3 pieces, 20 of them more than 12 tiles from any turret; median line 4.

**Player's pre-built blocks** (192): turret 2 (breach×2); drill 9 (cliff-crusher×4 plasma-bore×4 impact-drill×1); crafter 1 (silicon-arc-furnace×1); power (nodes+generators) 22; ducts/bridges/routers 124; conduits/pumps/tanks 32; other 1 (radar×1).

**World processors:** Opens the map after the first avert (`openMap`). Switches on the enemy ship fabricators at 182,191 and 135,305 at `setupFinished` (3 min later).

**Notes.**

- **Air only**: neither enemy core can be reached by ground, hover or legs (31- and 152-tile gaps). The tasks are 50 tungsten → research beam tower → 2 beam towers → chemical combustion chamber → an avert. That opens the map; 3 min later the enemy ship fabricators start.
- Start items include 650 tungsten. Floor tungsten is 107+ tiles away and **not walkable**, as are arkycite and the beryllium walls. Within 60 there are 82 floor-beryllium tiles, 96 graphite wall tiles and 4 vents (3 already have the player's turbines). Power comes from turbines plus whatever arkycite can be piped. The beam tower (range 23) is there to carry power over the gaps.
- The enemy has free resources, 8 turrets including the first 2 disperse (anti-air), and 2 ship fabricators + 2 ship refabricators (avert vs avert).

**Overview.** (x 67..251, y 48..361; 1 column = 3 tiles, 1 row = 6 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 360|##############################################################|
 354|#################g.g##########################################|
 348|##################ggg...g.g###################################|
 342|####################.g..ttteee################################|
 336|#####################gggtteeee.gg#############################|
 330|########################.#e.ee.eeeeg##########################|
 324|##############.##########.e.eeeeeeeee#########################|
 318|###############...#####eeeeeee####ggeg########################|
 312|###############....#..#eeeeee##.##############################|
 306|###############.....eeeeeeeeeee...############################|
 300|###############b...G.eYe=X=eeeeeeeee~~########################|
 294|################b....eeeeeeeeeeGeee#~~~#######################|
 288|#################b.........bbb################################|
 282|##################b.b.......b#################################|
 276|###################....bbbbb##################################|
 270|######################.#######################################|
 264|##############################################################|
 258|##############################################################|
 252|##############################################################|
 246|##############################################################|
 240|##############################################################|
 234|##############################################################|
 228|##############################################################|
 222|###################################ee.#.####.#################|
 216|###################################eeeeeG####..bb#############|
 210|###########################g########eeX=ee#.bb.Ge#############|
 204|########################ggg...####..#e==.ee..beeee############|
 198|########################g.ggg...eeeGeeeeeeeeeee.##############|
 192|####################.####b..g...e.eeeeeeeee.eeeg#g############|
 186|#####################.eeeee...eeeeeeeeYYeeeeeeeeeeg###########|
 180|######################beee..eeeeeeeeeeeeeGeeeGeeeeee.#########|
 174|##################....##eeeGeeeeeb#..#bee.eeee#######.########|
 168|###################.........e.eeb######....e...###############|
 162|########################......eeeb######..#...################|
 156|###########################.##eeeb############################|
 150|##############################################################|
 144|##############################################################|
 138|##############################################################|
 132|###~##########################################################|
 126|##    ########################################################|
 120|###    b####b  #  ######################### ##################|
 114|####     bb b      b############# g# g ggg#g g##g  gggggg#####|
 108|#####  :     ~      b############         pppp bgg      gg####|
 102|###     ttt p         b#####      #g      ppppppb   p p  ppg##|
  96|####    ttttp ## #b   b######       g       ppbbppppp  ppppp##|
  90|######         b#####b  ######     bbb       ppppppppppp   gg#|
  84|######~~  :    b###############gg   b     gg p Op  ppgggg#g###|
  78|##########  #    ###############g         g#gp  pppppppp######|
  72|############     b############## gg  g  :  g##g# g  p pp######|
  66|############## #   b##############g##g#g        ##g p   ######|
  60|################ b  ###################gg     ## ##gp p ######|
  54|##################b#b#################### ggg ########gg######|
  48|##############################################################|
```

### 2.10 ravine (494×494)

**Rules.** no attack, waves; whole map open. Enemy: RTS AI false (squads 2..50), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Capture at wave 24. Start items: graphite=1000 silicon=600 beryllium=1000.

**Cores.** Player core-bastion at 192,63. Enemy: none. Ground spawns: 442,254 (314), 300,429 (381), 304,429 (382). Units on the map: {malis:locus=1}.

**Waves.** first wave after 11.0min (normal difficulty; ×0.8 on hard), then every 1.8min. Spawn groups (wave numbers 1-based), then totals per wave:

```
group locus begin=1 end=25 spacing=2 amount=1 scaling=1.6666666 max=7 spawn=442,254
group stell begin=2 end=20 spacing=2 amount=6 scaling=1.0 spawn=442,254
group merui begin=5 end=25 spacing=3 amount=3 scaling=0.9090909 max=8 spawn=442,254
group locus begin=6 end=25 spacing=2 amount=1 scaling=2.5 spawn=442,254
group avert(air) begin=10 end=25 spacing=2 amount=3 scaling=0.5 max=10 spawn=300,429
group cleroi begin=14 end=25 spacing=4 amount=3 scaling=1.0 spawn=442,254
group merui begin=15 end=25 spacing=2 amount=5 scaling=10.0 spawn=442,254
group elude begin=16 end=25 spacing=2 amount=10 scaling=none spawn=442,254
group obviate(air) begin=21 end=25 spacing=2 amount=2 scaling=0.33333334 spawn=300,429
group anthicus begin=22 end=24 spacing=2 amount=5 scaling=0.5 spawn=437,256
wave 1 @11.0min: 1 units (0 air) hp~2100 {locus=1}
wave 2 @12.8min: 6 units (0 air) hp~5100 {stell=6}
wave 3 @14.7min: 1 units (0 air) hp~2100 {locus=1}
wave 4 @16.5min: 7 units (0 air) hp~5950 {stell=7}
wave 5 @18.3min: 5 units (0 air) hp~6240 {locus=2, merui=3}
wave 6 @20.2min: 9 units (0 air) hp~8900 {locus=1, stell=8}
wave 7 @22.0min: 2 units (0 air) hp~4200 {locus=2}
wave 8 @23.8min: 14 units (0 air) hp~12470 {locus=1, stell=9, merui=4}
wave 9 @25.7min: 3 units (0 air) hp~6300 {locus=3}
wave 10 @27.5min: 14 units (3 air) hp~13900 {locus=1, stell=10, avert=3}
wave 11 @29.3min: 9 units (0 air) hp~11800 {locus=4, merui=5}
wave 12 @31.2min: 18 units (5 air) hp~19050 {locus=2, stell=11, avert=5}
wave 13 @33.0min: 4 units (0 air) hp~8400 {locus=4}
wave 14 @34.8min: 30 units (7 air) hp~29480 {locus=2, stell=12, avert=7, merui=6, cleroi=3}
wave 15 @36.7min: 10 units (0 air) hp~13900 {locus=5, merui=5}
wave 20 @45.8min: 46 units (10 air) hp~41490 {locus=3, stell=15, avert=10, merui=8, elude=10}
wave 25 @55.0min: 20 units (8 air) hp~36500 {locus=7, obviate=8, merui=5}
```

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 3 | 7 | 13 | 20 | 20 | 13 | 12 |
| floor ore beryllium | 0 | 0 | 47 | 47 | 47 | 82 | 103 |
| floor ore tungsten | 0 | 44 | 94 | 154 | 154 | 43 | 42 |
| liquid arkycite (deep) | 0 | 0 | 80 | 80 | 80 | 68 | 71 |
| liquid slag (deep) | 0 | 26 | 26 | 26 | 26 | 38 | 38 |
| sand wall (exposed) | 129 | 445 | 1103 | 2583 | 2583 | 8 | 7 |
| wall ore beryllium (exposed) | 0 | 13 | 23 | 54 | 54 | 41 | 43 |
| wall ore graphite (exposed) | 0 | 10 | 36 | 53 | 53 | 33 | 34 |

Vents: nearest 6 at 205,64(13,sharded) 173,69(19,sharded) 204,82(22,sharded) 159,66(33) 226,70(34,sharded) 231,52(40,sharded) (distance, and the team whose block already stands on it). Already built on at the start: 6 of them {sharded:turbine-condenser=5, sharded:vent-condenser=1}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| spawn@442,254 | 314 | 401 | 401 | 378 | ~3 wide at 434,143 (path 401); no other route |
| spawn@300,429 | 381 | — | — | — | — |
| spawn@304,429 | 382 | — | — | — | — |

Separate ground regions of 300+ tiles: 1; player's region 25212 tiles (99% of open ground); vents in player's ground region 20/20; spawn@300,429 is on another ground region (190 tiles), narrowest gap from the player's region 143 tiles; spawn@304,429 is on another ground region (190 tiles), narrowest gap from the player's region 143 tiles.

**Enemy base:** none.

**Player's pre-built blocks** (281): turret 2 (breach×2); wall 10; drill 15 (plasma-bore×9 cliff-crusher×5 impact-drill×1); crafter 2 (vent-condenser×1 silicon-arc-furnace×1); power (nodes+generators) 34; ducts/bridges/routers 208; conduits/pumps/tanks 9.

**World processors:** None.

**Notes.**

- A wave survival map with no enemy base. Waves start at 11 min and come every 1.8 min; capture at wave 24 (~53 min). One locus is on the map from the start. Ground waves come from 442,254 (walk 401) through a single choke **~3 wide at 434,143**: a natural kill zone 100+ tiles from the core.
- Air (avert from wave 10, obviate from 21) spawns at 300,429-304,429, a 190-tile pocket unreachable by ground. Defence there must be anti-air at the core. The waves mix ground, legs (merui, cleroi, anthicus), hover (elude from wave 16) and air; wave 20 is 46 units and ~41k HP.
- Start items are 1000 Gr, 600 Si and 1000 Be. Within 60 there are 7 vents (6 already have the player's turbines) and 44 floor tungsten (nearest 43). The research unlocked here (surge crucible, heat redirector, electric heater, afflict) depends on the slag 38 tiles away and arkycite 68 tiles away.

**Overview.** (x 92..489, y 8..439; 1 column = 5 tiles, 1 row = 10 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 438|################################################################################|
 428|######################################## SS ####################################|
 418|################################################################################|
 408|################################################################################|
 398|################################################################################|
 388|################################################################################|
 378|################################################################################|
 368|################################################################################|
 358|################################################################################|
 348|################################################################################|
 338|################################################################################|
 328|################################################################################|
 318|#########################################################################bb#####|
 308|##########################################################################b ####|
 298|##########################################################################    ##|
 288|#########################################################################   ####|
 278|#########################################################################   ####|
 268|########################################################################gg   ###|
 258|####################################################################    g  :  ##|
 248|###################################################################   S       ##|
 238|####################################################################           #|
 228|#####################################################################        ###|
 218|#######################################################################     ####|
 208|########################################################################   #####|
 198|#########################################################################  #####|
 188|#####################################################################       ####|
 178|########################################################## ####  ###        ####|
 168|####################################################### #                  #####|
 158|#####################################################  :  ttt :    ##     ######|
 148|##############  #   ###################################bbb tt     ###    #######|
 138|##########bb:       ##################################               ###########|
 128|######    bb  :      #####  ########################                 ###########|
 118|###### gg##  ##### t  #      ####################        ####        ###########|
 108|####b  g##########ppp #     ####### #######                ###b   ##############|
  98|# ##   ###########  p  #   bb####:    b###    :  ####         b ################|
  88|#      ##########   p      #        bb          ######          ################|
  78|#  : tt  ######pppp p p       pp  :           #######    :   : #################|
  68|###   tt   gppppppppp p ppp ppppp           #########bbbb    ###################|
  58|###          pppppppO p p   p   p  ######g:  ############ ######################|
  48|######     pppp####    ### p  ##  #########g ###################################|
  38|###########pp  #############      ##############################################|
  28|##############################    g#############################################|
  18|###############################   :#############################################|
```

### 2.11 caldera-erekir (450×450)

**Rules.** attack; whole map open. Enemy: RTS AI true (squads 1..3), free resources false, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 5. Start items: graphite=200 silicon=200 surge-alloy=50 beryllium=200.

**Cores.** Player core-bastion at 86,68. Enemy: bastion@301,209 (257).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 1 | 6 | 7 | 40 | 40 | 11 | 10 |
| floor ore beryllium | 0 | 0 | 0 | 34 | 34 | 159 | not walkable |
| floor ore tungsten | 0 | 9 | 9 | 147 | 147 | 31 | 32 |
| liquid arkycite (deep) | 6 | 6 | 6 | 76 | 76 | 18 | 18 |
| liquid slag (deep) | 151 | 5470 | 13661 | 92788 | 92788 | 23 | 22 |
| sand wall (exposed) | 113 | 324 | 870 | 6004 | 6004 | 7 | 6 |
| wall ore beryllium (exposed) | 3 | 3 | 3 | 97 | 97 | 10 | 9 |
| wall ore graphite (exposed) | 6 | 6 | 6 | 91 | 91 | 20 | 19 |
| wall ore tungsten (exposed) | 0 | 0 | 0 | 47 | 47 | 172 | not walkable |

Vents: nearest 6 at 97,67(11,sharded) 102,38(34,sharded) 110,100(40) 113,35(42) 43,96(51) 26,68(60) (distance, and the team whose block already stands on it). Already built on at the start: 12 of them {malis:turbine-condenser=6, malis:vent-condenser=4, sharded:turbine-condenser=2}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@301,209 | 257 | — | 540 | 284 | — |

Separate ground regions of 300+ tiles: 11; player's region 5018 tiles (16% of open ground); vents in player's ground region 6/40; enemy bastion@301,209 is on another ground region (5640 tiles), narrowest gap from the player's region 120 tiles.

**Enemy base**, 1543 blocks: turret 23 (breach×18 diffuse×3 sublimate×2); wall 481 (tungsten-wall×462 tungsten-wall-large×17 blast-door×2); units 1 (ship-fabricator×1); drill 50 (plasma-bore×37 cliff-crusher×9 impact-drill×4); crafter 19 (electrolyzer×6 vent-condenser×4 oxidation-chamber×4 heat-redirector×3 surge-crucible×1 silicon-arc-furnace×1); power 115 (beam-node×103 chemical-combustion-chamber×6 turbine-condenser×6); ducts/bridges/routers 593; conduits/pumps/tanks 253; containers 5; other 2 (regen-projector×2).
Layout (xtest.Study): 23 turrets; 481 walls, mostly 1x1 tungsten, in 103 small pieces (median 3, 62 rows); feeders include duct unloaders on reinforced containers and a vent condenser (water); median line 5.

**Player's pre-built blocks** (186): units 1 (ship-fabricator×1); drill 9 (plasma-bore×6 cliff-crusher×3); crafter 3 (electrolyzer×1 oxidation-chamber×1 silicon-arc-furnace×1); power (nodes+generators) 22; ducts/bridges/routers 117; conduits/pumps/tanks 33.

**World processors:** Switches on the enemy ship fabricator at 309,184 at minute 5. A second processor would spawn eludes but starts with `end`.

**Notes.**

- Slag covers 42% of the map. The player's ground region is only 5018 tiles (16% of ground) and the enemy core is 120 tiles of slag away: hover 540, legs 284. The player has a ship fabricator, so this is an elude/legs map.
- Tungsten: 9 floor tiles within 60 (nearest 31, walkable). The 47 wall-tungsten tiles (large plasma bore) are 172+ tiles away and not walkable. 6 of the 40 vents are on the player's ground. The map has `unitCap 5`.
- The enemy is the first with oxidation chambers (4), a surge crucible, heat redirectors and regen projectors. Its 481 walls are mostly 1×1 tungsten in small rows. It has no free resources and one ship fabricator, switched on at 5 min.

**Overview.** (x 11..437, y 1..432; 1 column = 6 tiles, 1 row = 12 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 421|#############################################~#####~####################|
 409|################################################~~~~~~~~~~~~~###########|
 397|##########################~~~~~~~~~~~~~~######~~~~~~~~~~~~~~~~~#########|
 385|#####################~~~~~~~~~~~~~~~~~~~~~####~~~~~~~~#~~~~~~~~~~~~#####|
 373|###################~~~~~:~~~  ~~ ~ ~~~~~~~#~~~~~~~~####~~~~~~~~~~~~#####|
 361|##################~~~~~~~~~ : :    ~~~~~~~#~~~~~~~~~~~~~~~  :   ~~~~~~##|
 349|#############~~~~##~~~~~~~ bg  g    ~~~~~~~~~~~~~~~~  :            ~~~##|
 337|#######~~~~~~~~~~~#~~~~~~~ bggg ~~~~~~~~~~#~~~~~~~ :TTT     bb     ~~~##|
 325|######~~~~~gg ~~~~~#~~~~~~:    :~~~~~~~~~~##~~~~~~~   ~~ :   b  : ~~~~##|
 313|##~##~~~~~~gg : ~~~~~~~~~      ~~~#~~~~~~~~#~~~~~~~~~~~~        : ~~~~##|
 301|##~##~~~~~~~~~~~~~##~~~~~~~ ~~ ~~~~~~~~~~~~##~~~~~~~~~~~bTT      ~~~~~##|
 289|##~~###~~~~~~~~~~###~~~~~~   T  ~~~~~~~~~~##~~~~~~~~~~~~~ ~~~~  ~~~~~###|
 277|##~~#######~####~#~~#~~~~:   T#  ~~~~~~~#~#~~~~~~~~~~~~~~~~~~~~~~~~##~##|
 265|#########~~~~~~~~~~~~~~ :gg  T :~~~~~~~#####~~~~~~~~~~~~~~~:~~~bb~~~####|
 253|########~~~~~~~~~~~~~~  ##ggT  ~~~~~##########~#~ ~~~~~~~~~b~~~   ~~####|
 241|######~~~~#~~~~~~~~:    gg#   ~~###~~~#########~# ~#~#~~~~~~~~~~  ~~####|
 229|#######~~~~~~~~~~~~~~      ~###~~~~~~~~~##~~~~~~==~~~###~~~~~~~ ~~######|
 217|########~~~~~~~~~~~~ ~~~:~~## b b~~G=~~==~~~~==GG===~~~#~#~~~ ~~~#######|
 205|#########~~~~~~~~~~~~~~~~~~#~:   ~eee~e=eee ee==XeeG=~~~~~###~~~########|
 193|#########~~~~~~~~~~~~~~~~##~~    eeeG~~~~=Geeeeeeee==~~~~~~~~###########|
 181|###############~~~~~~~~~#~~~~~ ~=GG~~~~~~  GeeeeeYeG=~~  ==~~~~~~#######|
 169|###############~~~~~#~###~~~~~~~~~~~~~~~~~G=eeeeeeee== ~ e ~~~~~~~######|
 157|##########~~~~~~~~~####~##~~~~~~~~~~~~~~~==GeeG eeeee===Ge=G~~~~~~######|
 145|########~~~~~~~~~~~~~~##########~~~~~~~~ =e=eeGeeeeeG===eeeG=~~~~#######|
 133|#######~~~   : ~~~~~~~~~~~########~~~~~~~~e==GeGe=~==ee=Ge~~~~~~########|
 121|######~~~~    ~~~~~~~~~~~~###########~~~~~~~~  ~~~~=G= ~~~~~~~~~~#######|
 109|####~~~~~     ~~~~~~~~~~~~####~~#~~~~~~~#~#~    ~~~~~~~~~~~~~~~~~#######|
  97|###~~     tt    :~~~~~~~~~###~~~~~~~~~~~~~~~   ~~~~~#~~~~~~~~~~~~#######|
  85|##~~~:###g p      ~~~~~~~~###~~~~~~~~~~~  T  : ~~~~~~~##~~~~~:bb########|
  73|##~    ##pppppp   ~~~~~~~~##~~~~~~~~~~         ~~~~~~~~~###~~~##########|
  61|#~: ~~ ##pppOppp ~~~~~~~~~~~~~~~~~~~~~~:  tgTTb ~~~~~~~~~##~############|
  49|#~~~~~ #####pppp ~##~~~~~~~#~~~~~~~~bb bbTgg###b ~~~~#~~~###############|
  37|##~~~~~~~###g  p ~~~~~~~~~##~~~~~~~~~  ##T     :~~~~~~~~################|
  25|#~##~~~~~~~~ ~~  : ~~~~~~~###~~~~~~~~~~ ~~~~~~~~~~~~~~~~################|
  13|#######~~~~~~~~~~ ~~~~~~~#######~#~##~~~~~~~~~~#########~~##############|
```

### 2.12 stronghold (586×586)

**Rules.** attack; start area limited to x 284+299, y 268+306. Enemy: RTS AI true (squads 1..20), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=700 silicon=500 beryllium=1000 tungsten=500 oxide=200.

**Objectives:** 0 Timer: enemiesapproaching duration=3.0min flags+[units1]; 1 Timer (after 4): enemyescalating duration=3.0min flags+[units2]; 2 Timer (after 3): enemyairunits duration=7.5min flags+[beginAirProduction]; 3 Timer (after 1): enemyescalating duration=10.0min flags+[units3]; 4 Timer (after 7): enemyescalating duration=6.0min flags+[expandMap]; 5 Timer (after 2): enemyescalating duration=10.0min flags+[units4]; 6 Timer (after 5): enemyescalating duration=8.3min flags+[units5]; 7 DestroyBlocks (after 0): positions=[(520, 389), (335, 297)] team=malis block=core-bastion

**Cores.** Player core-citadel at 382,514. Enemy: bastion@290,57 (466), bastion@434,102 (415), bastion@304,190 (333), bastion@529,219 (329), bastion@56,256 (415), bastion@335,296 (223), citadel@248,345 (215), bastion@519,389 (185), acropolis@34,478 (349), bastion@189,515 (193).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 1 | 7 | 8 | 91 | 23 | 18 | 17 |
| floor ore beryllium | 0 | 0 | 0 | 264 | 120 | 171 | 219 |
| floor ore thorium | 0 | 0 | 0 | 363 | 1 | 102 | 109 |
| floor ore tungsten | 0 | 0 | 0 | 326 | 37 | 161 | 205 |
| liquid arkycite (deep) | 0 | 0 | 0 | 6528 | 452 | 212 | 275 |
| sand wall (exposed) | 10 | 96 | 120 | 1290 | 304 | 26 | 32 |
| wall ore beryllium (exposed) | 8 | 8 | 18 | 342 | 84 | 19 | 19 |
| wall ore graphite (exposed) | 0 | 18 | 23 | 482 | 91 | 30 | 107 |
| wall ore thorium (exposed) | 0 | 0 | 0 | 41 | 12 | 172 | 270 |
| wall ore tungsten (exposed) | 0 | 0 | 0 | 72 | 33 | 124 | 228 |

Vents: nearest 6 at 381,496(18) 412,501(32,sharded) 415,488(42,sharded) 340,498(44,sharded) 342,487(48,sharded) 407,470(50) (distance, and the team whose block already stands on it). Already built on at the start: 67 of them {malis:turbine-condenser=51, malis:vent-condenser=12, sharded:turbine-condenser=4}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@290,57 | 466 | 809 | 546 | 533 | ~4 wide at 313,274 (path 809); no other route |
| enemy bastion@434,102 | 415 | — | 473 | 456 | — |
| enemy bastion@304,190 | 333 | 600 | 600 | 574 | ~4 wide at 313,274 (path 600); no other route |
| enemy bastion@529,219 | 329 | — | 443 | 425 | — |
| enemy bastion@56,256 | 415 | 601 | 601 | 588 | ~4 wide at 313,274 (path 601); no other route |
| enemy bastion@335,296 | 223 | 279 | 279 | 271 | ~12 wide at 348,284 (path 279); ~12 wide at 359,306 (path 279); ~6 wide at 349,345 (path 279) |
| enemy citadel@248,345 | 215 | 549 | 549 | 528 | ~4 wide at 313,274 (path 549); no other route |
| enemy bastion@519,389 | 185 | 192 | 192 | 188 | ~12 wide at 456,427 (path 192); no other route |
| enemy acropolis@34,478 | 349 | 736 | 736 | 716 | ~4 wide at 313,274 (path 736); no other route |
| enemy bastion@189,515 | 193 | 676 | 676 | 659 | ~4 wide at 313,274 (path 676); no other route |

Separate ground regions of 300+ tiles: 7; player's region 85145 tiles (89% of open ground); vents in player's ground region 76/91; enemy bastion@434,102 is on another ground region (6541 tiles), narrowest gap from the player's region 11 tiles; enemy bastion@529,219 is on another ground region (6541 tiles), narrowest gap from the player's region 11 tiles.

**Enemy base**, 8346 blocks: turret 106 (breach×61 diffuse×18 disperse×10 sublimate×8 titan×7 afflict×2); wall 325 (beryllium-wall×130 beryllium-wall-large×114 blast-door×39 reinforced-surge-wall-large×18 tungsten-wall×14 tungsten-wall-large×8 reinforced-surge-wall×2); units 21 (ship-fabricator×5 prime-refabricator×4 mech-fabricator×3 tank-refabricator×3 tank-fabricator×3 mech-refabricator×2 ship-refabricator×1); drill 194 (plasma-bore×107 cliff-crusher×42 large-plasma-bore×34 impact-drill×9 eruption-drill×2); crafter 61 (electrolyzer×15 vent-condenser×12 electric-heater×11 silicon-arc-furnace×9 heat-redirector×6 oxidation-chamber×4 atmospheric-concentrator×4); power 454 (beam-node×389 turbine-condenser×51 chemical-combustion-chamber×12 beam-tower×2); ducts/bridges/routers 4004; conduits/pumps/tanks 3161; containers 4; payload blocks 6.
Layout (xtest.Study): 106 turrets (61 breach, 18 diffuse, 10 disperse, 8 sublimate, 7 titan, 2 afflict) in groups up to 6; 325 walls in 26 pieces plus 39 blast doors; 96 item turrets fed (78 by a duct, 15 by a router), median line 5.

**Player's pre-built blocks** (307): turret 1 (breach×1); wall 9; drill 18 (plasma-bore×14 cliff-crusher×4); crafter 1 (silicon-arc-furnace×1); power (nodes+generators) 41; ducts/bridges/routers 230; payload blocks 6 (payload-mass-driver×4 payload-loader×1 payload-unloader×1).

**World processors:** A chain of timers sets flags that switch on enemy factories one by one. `units1` (3 min) starts the mech fabricator at 502,400. Destroying the cores at 520,389 and 335,297 starts a 6-minute timer to `expandMap`. Then come `units2` (ship fabs), `units3` (tank fab at 224,363), `beginAirProduction`, `units4` and `units5` (prime refabricators in the west).

**Notes.**

- 10 enemy cores (8 bastion, 1 citadel, 1 acropolis). The player starts with a citadel. The start area is 299×306 in the southeast, and the first objective is to destroy the cores at 520,389 (walk 192, one ~12-wide choke) and 335,296 (walk 279, ~12/~12/~6 wide). That opens the map 6 min later.
- Every route to the western and northern cores goes through one choke **~4 wide at 313,274**, with walks of 549-809 tiles (≥5-wide units cannot pass). Two northeast cores (434,102 and 529,219) are on separate ground 11 tiles away.
- Nothing is near the player: tungsten floor is 161+ tiles away (walk 205), thorium 102+, arkycite 212+. Within 30 there are 8 beryllium wall tiles and 1 vent; within 60, 7 vents (4 already have the player's turbines). The loadout (700 Gr, 500 Si, 1000 Be, 500 W, 200 Ox) has to last a long way.
- The enemy has free resources, 106 turrets including 7 titan and 2 afflict, 21 unit blocks (4 prime refabricators) and 8346 blocks in all. Factories switch on by timer flags.

**Overview.** (x 16..546, y 7..543; 1 column = 7 tiles, 1 row = 14 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 539|########TT##################################################################|
 525|#######eeee####################################### p########################|
 511|eeeeeeeGeeeG#########eeeXeeee#################g###ppOppp####################|
 497|eeGeeeeGeeYYee#######eeeeYee#b####T##b######pppppppppppppp##################|
 483|eeeG##eeeeYYeeeG##.##eGGeeGe.#####.:.bb#####gpppg## : pppp :  b#############|
 469|#GXeeeeeeeeeeGGG.....===.=G=########################## ppppp     hg#########|
 455|#####hheHe##eGGG......ttt...b###########################           #########|
 441|##############=.......:tt..gb###########################bb    :    #########|
 427|#############...........tt.g###########################bb     =Ge  ##### bbb|
 413|############.....##.......=Ge#########################     ## =Ge   ###    b|
 399|###########b....##.....###=Ge####ee##################      #g##eeeeeeYe  :bb|
 385|##########.t.:.###bb.hh.GegeeeeGeeee#############         #Geegb   eeeeX   #|
 371|######bbbb.ttgg#####hh:.GGeGeeeeeeT############b           GeeeeTeT  eee   #|
 357|######.:...gg####b.......=GeYYeeee##############G=       #  eeeeeeeeeeee   #|
 343|####g.tgg#gg####.........=GeeeGeeX############eeG=tt#b   ##Geeeeeeeeeeee  ##|
 329|####g..gg#ggg...h######..=######==########geeeee####eG   ####TTeeeeeeeeegg##|
 315|###...ggg......########....bb############geeeeeee# eeG   ##########g#####bg#|
 301|##:.####.....###########.g..b############geee GeeeeGeG=##################b:#|
 287|##..####...##############g....############ eGXeeeeeeebb############e bb#~~~#|
 273|#####=G=GG=###########g=#......########### eeeeeeee eee############eeGeeg b#|
 259|#####Geeeee##########eG=...b.......:..#h#.#####TT####~~######gggg##egegeg###|
 245|###b.Xeeeeb######g=G.......bbb:.......#h############~~~~~~~~~~:.~~GGeYYeee##|
 231|####bb.eeGb######=eG........###bbbb#b################~~~~~#.~~~~~~~GGeeeeee#|
 217|#####..eeG=..........####...:######e.....###########~~~~~.#..#:~~~eGGeeeeXe#|
 203|#######b####....b#######bb..####..TeeeeeeYee#######~~~~~~~.~~~~~~..Geeeeeee#|
 189|############...#########b....###beeee#eeeXeeee#####..~~~~.:..~.....##ee#####|
 175|############..:############...b=Gee..##eeeYeee####=....~##....##############|
 161|############...############....=Geeeeeeeeeeee#gggeGGGG=##g=GG=##############|
 147|###########...b#######bb:#b#bb.=Gee..#..ee.e##eeeeeeee###g.eeg##############|
 133|#############.#######.............############eeeg########.Gee##############|
 119|#############.#####...####....:.#############gee###########eGeb#############|
 105|############..####....####:....############eeeee##########eeGeb#############|
  91|##############.##b..##########b##########geeeeee.#########YXee##############|
  77|####################..###################ggeeeee############################|
  63|######################....:.########ggg##eeeeee#############################|
  49|########################....:#######g.GXeeeeeeet.###########################|
  35|#########################.########=.GeeeYeeeeeeeT###########################|
  21|##########################.##....#=.eeeeeeeeeee#############################|
```

### 2.13 crevice (494×494)

**Rules.** no attack, waves; whole map open. Enemy: RTS AI false (squads 4..50), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Capture at wave 46. Start items: graphite=500 thorium=100 silicon=800 beryllium=500 tungsten=300 oxide=200.

**Cores.** Player core-bastion at 205,113. Enemy: none. Ground spawns: 358,401 (326). Units on the map: {malis:stell=1, malis:merui=1}.

**Waves.** first wave after 6.0min (normal difficulty; ×0.8 on hard), then every 2.0min. Spawn groups (wave numbers 1-based), then totals per wave:

```
group stell begin=1 end=25 spacing=1 amount=1 scaling=1.0 shields=0.0+20.0/w
group merui begin=1 end=30 spacing=1 amount=1 scaling=1.3333334 shields=0.0+20.0/w
group elude begin=4 end=20 spacing=2 amount=1 scaling=2.0 shields=0.0+25.0/w
group locus begin=15 end=25 spacing=2 amount=1 scaling=0.6666667 shields=100.0+65.0/w effect=overclock
group avert(air) begin=15 end=20 spacing=2 amount=1 scaling=1.3333334 shields=100.0+45.0/w
group avert(air) begin=20 end=40 spacing=2 amount=1 scaling=1.3333334 shields=100.0+65.0/w effect=overclock
group locus begin=25 end=40 spacing=2 amount=2 scaling=0.5 shields=400.0+65.0/w effect=overdrive
group cleroi begin=30 end=40 spacing=2 amount=1 scaling=0.6666667 shields=100.0+65.0/w effect=overclock
group precept begin=40 end=44 spacing=2 amount=3 scaling=0.5 shields=750.0+100.0/w effect=overclock
group obviate(air) begin=40 end=44 spacing=2 amount=2 scaling=0.8 shields=750.0+100.0/w effect=overclock
group anthicus begin=41 end=43 spacing=2 amount=4 scaling=0.5 shields=600.0+100.0/w effect=overclock
group tecta begin=45 end=45 spacing=1 amount=4 scaling=none shields=1500.0+0.0/w effect=boss
wave 1 @6.0min: 2 units (0 air) hp~1530 {stell=1, merui=1}
wave 2 @8.0min: 3 units (0 air) hp~2440 {stell=2, merui=1}
wave 3 @10.0min: 5 units (0 air) hp~4110 {stell=3, merui=2}
wave 4 @12.0min: 8 units (0 air) hp~6460 {stell=4, merui=3, elude=1}
wave 5 @14.0min: 9 units (0 air) hp~7690 {stell=5, merui=4}
wave 6 @16.0min: 11 units (0 air) hp~9470 {stell=6, merui=4, elude=1}
wave 7 @18.0min: 12 units (0 air) hp~10790 {stell=7, merui=5}
wave 8 @20.0min: 16 units (0 air) hp~14240 {stell=8, merui=6, elude=2}
wave 9 @22.0min: 16 units (0 air) hp~14970 {stell=9, merui=7}
wave 10 @24.0min: 19 units (0 air) hp~17820 {stell=10, merui=7, elude=2}
wave 11 @26.0min: 19 units (0 air) hp~18590 {stell=11, merui=8}
wave 12 @28.0min: 24 units (0 air) hp~23340 {stell=12, merui=9, elude=3}
wave 13 @30.0min: 23 units (0 air) hp~23370 {stell=13, merui=10}
wave 14 @32.0min: 27 units (0 air) hp~27490 {stell=14, merui=10, elude=3}
wave 15 @34.0min: 28 units (1 air) hp~30910 {locus=1, stell=15, avert=1, merui=11}
wave 20 @44.0min: 41 units (1 air) hp~46700 {stell=20, avert=1, merui=15, elude=5}
wave 25 @54.0min: 54 units (0 air) hp~83090 {locus=10, stell=25, merui=19}
wave 30 @64.0min: 27 units (4 air) hp~36320 {avert=4, merui=22, cleroi=1}
```

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 2 | 3 | 8 | 24 | 24 | 10 | 9 |
| floor ore beryllium | 0 | 0 | 62 | 118 | 118 | 91 | not walkable |
| floor ore thorium | 0 | 18 | 121 | 151 | 151 | 48 | 49 |
| floor ore tungsten | 0 | 0 | 9 | 84 | 84 | 96 | not walkable |
| liquid arkycite (deep) | 0 | 239 | 323 | 555 | 555 | 36 | 37 |
| liquid slag (deep) | 0 | 0 | 629 | 1280 | 1280 | 71 | 75 |
| sand wall (exposed) | 21 | 21 | 92 | 1819 | 1819 | 18 | 18 |
| wall ore beryllium (exposed) | 4 | 4 | 18 | 117 | 117 | 15 | 14 |
| wall ore graphite (exposed) | 11 | 27 | 30 | 253 | 253 | 24 | 28 |
| wall ore thorium (exposed) | 0 | 1 | 11 | 11 | 11 | 59 | 62 |
| wall ore tungsten (exposed) | 0 | 2 | 2 | 2 | 2 | 32 | 34 |

Vents: nearest 6 at 195,111(10,sharded) 217,136(25,sharded) 197,170(57) 251,160(65) 285,137(83,sharded) 252,187(87) (distance, and the team whose block already stands on it). Already built on at the start: 4 of them {sharded:turbine-condenser=4}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| spawn@358,401 | 326 | 450 | 450 | 447 | ~14 wide at 206,222 (path 450); ~10 wide at 278,192 (path 502); no other route |

Separate ground regions of 300+ tiles: 4; player's region 34124 tiles (83% of open ground); vents in player's ground region 14/24.

**Enemy base:** none.

**Player's pre-built blocks** (404): drill 16 (plasma-bore×11 cliff-crusher×4 large-plasma-bore×1); crafter 3 (electrolyzer×1 oxidation-chamber×1 silicon-arc-furnace×1); power (nodes+generators) 42; ducts/bridges/routers 247; conduits/pumps/tanks 90; payload blocks 4 (payload-mass-driver×2 small-deconstructor×1 constructor×1); other 1 (radar×1).

**World processors:** None.

**Notes.**

- A wave survival map with no enemy base (one stell and one merui are on the map at the start). Waves start at 6 min and come every 2 min; capture at wave 46 (~96 min). Shields grow per wave (stell/merui +20/wave, later groups +65 to +100) and status effects are applied (overclock, overdrive, boss at wave 45 with 4 tecta).
- One ground spawn, 358,401, walk 450, with two routes (~14 wide at 206,222 and ~10 at 278,192, then nothing). Two lanes to hold, a long way out.
- Thorium floor 48 tiles away (18 within 60) is what this map is about (carbide crucible, pyrolysis). Floor tungsten (84 tiles) and floor beryllium are **not walkable**, and there are only 2 wall-tungsten tiles. Arkycite is 36 tiles away. The player gets a large plasma bore, an oxidation chamber and payload blocks pre-built.

**Overview.** (x 111..386, y 84..468; 1 column = 4 tiles, 1 row = 8 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 468|#####################################################################|
 460|#####################################  g#############################|
 452|#############################          g#############################|
 444|###########################             ###########g      b##########|
 436|########################               ##  #######gg           ### ##|
 428|#####################                        #ggggg                 #|
 420|####################                          :               h    ##|
 412|#####################ggg      ##                      h      hh   ###|
 404|######################g       ##  ##bb                           ####|
 396|###############bb#            b######                     #  S   ####|
 388|#######  #####bb             bb#####b      b####        ####    #####|
 380|###~                        bb#######        b##    :   #############|
 372|###~           #          bb##########                ggg############|
 364|####           #  :          #########                  g############|
 356|####  :        ~##            #########            :    #############|
 348|#####           #          g   ########              gg##############|
 340|########                 ggg##    : ####          ###################|
 332|########bbb              gg####    #  ###        ####################|
 324|##############            ggg###      ######b    ####################|
 316|###########   ##            gg###    ######### ######################|
 308|######:       #####         g#####   ################################|
 300|#####b     ##########         ###    ################################|
 292|#####bbb   g#########  :              ###############################|
 284|#### bb# :gg#########                      b#########################|
 276|####################g       ########       ##########################|
 268|###################        #########          #######################|
 260|############ ##~#g        ############         ######################|
 252|###########     ~~  :     #############g#      ######################|
 244|###########:      ~     gg##############     hh #####################|
 236|##########gg      ~~    gg##############       ######################|
 228|#########g  ttt     ~    gg#############gg     ######################|
 220|####### ggg  ttt     ~   gg##############g       ####################|
 212|#######     :    :   ~   gg#############gg       ####################|
 204|######                    gg   ##########g     ~ #bb#################|
 196|###   tt :    ~~~ ~-      :        ######g    ~ : bb#################|
 188|##    ttt      ~~~         ###       #       ~  b####################|
 180|#:     tt     ~~           ##H hh  :     #### bbb####################|
 172|#######  ~~~              ##HH hhh      ####  bb ####################|
 164|##########           :     HH hhhhh   ####      b####################|
 156|###########                 h hh   : ####  p     bb##################|
 148|###################           h       ##   p bbb  ###################|
 140|###################Tppppp                  ppbbb   ##################|
 132|##################### p ppp         ~~  pp pppppppb##################|
 124|##################### pp pppp     ~~~  pppppp# bpp###################|
 116|#################### pppppppp  p p#~pp p bbb#########################|
 108|#################   pp Oppppppppggg##################################|
 100|##################  pppppppppppg#####################################|
  92|######################  pppp#g#######################################|
  84|#####################################################################|
```

### 2.14 siege (400×600)

**Rules.** attack; start area limited to x 0+400, y 0+143. Enemy: RTS AI true (squads 2..7), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=1000 thorium=700 silicon=800 beryllium=1000 tungsten=550 oxide=300 carbide=200.

**Objectives:** 0 Timer: enemiesapproaching duration=10.0min flags+[def]; 1 Timer (after 0): enemyescalating duration=10.0min flags+[u1]; 2 Timer (after 1): enemyescalating duration=10.0min flags+[u2]; 3 Timer (after 2): enemyescalating duration=6.7min flags+[u3]

**Cores.** Player core-bastion at 124,52. Enemy: bastion@132,254 (202), bastion@331,370 (379), bastion@138,386 (334), citadel@248,484 (449).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 5 | 7 | 12 | 51 | 15 | 11 | 10 |
| floor ore beryllium | 40 | 40 | 40 | 159 | 40 | 9 | 8 |
| floor ore thorium | 0 | 0 | 0 | 214 | 0 | 218 | 252 |
| floor ore tungsten | 0 | 0 | 103 | 367 | 108 | 80 | 201 |
| liquid arkycite (deep) | 0 | 0 | 0 | 599 | 29 | 115 | 512 |
| liquid slag (deep) | 0 | 0 | 144 | 350 | 97 | 86 | 99 |
| sand wall (exposed) | 118 | 294 | 668 | 3406 | 802 | 19 | 17 |
| wall ore beryllium (exposed) | 20 | 27 | 31 | 111 | 54 | 19 | 19 |
| wall ore graphite (exposed) | 11 | 22 | 88 | 206 | 92 | 24 | 24 |
| wall ore thorium (exposed) | 0 | 0 | 0 | 30 | 0 | 214 | 248 |

Vents: nearest 6 at 135,51(11) 112,38(18) 142,58(18,sharded) 105,56(19) 128,32(20) 126,82(30,sharded) (distance, and the team whose block already stands on it). Already built on at the start: 39 of them {malis:turbine-condenser=16, malis:vent-condenser=19, sharded:turbine-condenser=4}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@132,254 | 202 | 227 | 227 | 219 | ~4 wide at 105,185 (path 227); no other route |
| enemy bastion@331,370 | 379 | 623 | 623 | 597 | ~4 wide at 105,185 (path 623); no other route |
| enemy bastion@138,386 | 334 | 374 | 374 | 362 | ~4 wide at 105,185 (path 374); no other route |
| enemy citadel@248,484 | 449 | 556 | 556 | 541 | ~4 wide at 105,185 (path 556); no other route |

Separate ground regions of 300+ tiles: 1; player's region 55589 tiles (99% of open ground); vents in player's ground region 51/51.

**Enemy base**, 7764 blocks: turret 89 (breach×47 sublimate×17 diffuse×14 afflict×6 titan×3 scathe×2); wall 1195 (tungsten-wall×549 reinforced-surge-wall×355 beryllium-wall×171 reinforced-surge-wall-large×57 tungsten-wall-large×41 blast-door×17 beryllium-wall-large×5); units 24 (mech-fabricator×7 tank-fabricator×5 mech-refabricator×5 ship-fabricator×4 tank-refabricator×2 prime-refabricator×1); drill 177 (plasma-bore×89 cliff-crusher×64 impact-drill×20 large-plasma-bore×3 eruption-drill×1); crafter 140 (electric-heater×46 electrolyzer×35 vent-condenser×19 silicon-arc-furnace×16 heat-redirector×11 oxidation-chamber×7 surge-crucible×2 carbide-crucible×2 slag-heater×1 atmospheric-concentrator×1); power 572 (beam-node×513 chemical-combustion-chamber×37 turbine-condenser×16 beam-tower×6); ducts/bridges/routers 3020; conduits/pumps/tanks 2484; containers 10; payload blocks 24; other 25 (regen-projector×23 unit-repair-tower×2).
Turrets and unit blocks by nearest core (60-tile radius): 132,254 breach×6 diffuse×5 units×2 sublimate×1 titan×1 / 331,370 breach×6 units×5 afflict×1 diffuse×1 / 138,386 breach×4 sublimate×2 afflict×2 units×1 scathe×1 / 248,484 units×14 breach×8 sublimate×6 diffuse×4 afflict×3 titan×2 scathe×1 / far from any core breach×23 sublimate×8 diffuse×4 units×2.
Layout (xtest.Study): 89 turrets; 1195 walls (549 tungsten, 412 reinforced surge) in 235 pieces, median 3 tiles, 163 rows; 66 item turrets fed (37 duct, 22 router, 5 unloader), median 5.

**Player's pre-built blocks** (269): turret 1 (diffuse×1); wall 46; drill 11 (plasma-bore×7 cliff-crusher×4); crafter 1 (silicon-arc-furnace×1); power (nodes+generators) 32; ducts/bridges/routers 147; conduits/pumps/tanks 20; containers 3; payload blocks 6 (payload-mass-driver×4 payload-loader×1 payload-unloader×1); other 1 (radar×1).

**World processors:** `def` (10 min) opens the whole map. `u1`/`u2`/`u3` (20/30/36.7 min) switch on 2 mech refabs, then 4 more mech and tank refabs, then the prime refab.

**Notes.**

- The start area is 400×143 (the south strip) and opens at 10 min. 4 enemy cores; **every ground route goes through one ~4-wide choke at 105,185** with blast doors behind it. Walks are 227-623 tiles, and ≥5-wide units have no ground route at all (vanquish, at 3.5 tiles across, is borderline).
- The richest close start after marsh: 5 vents within 30 and 40 floor beryllium within 30. But tungsten floor is 80 straight and **201 by walking** (through the choke), and thorium is 218+ tiles away.
- The enemy has free resources, 89 turrets (including 2 scathe, range 169 tiles, and 3 titan) and 1195 walls (tungsten and reinforced surge in short rows). Refabricators switch on at 20/30/37 min. This is the first map with the enemy fielding carbide (2 carbide crucibles).

**Overview.** (x 46..388, y 21..544; 1 column = 5 tiles, 1 row = 10 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 541|#####################################################################|
 531|###########################HH#######bb###.ee.########################|
 521|#######################H#heehee..#eeeee=eYeeeee#ee###################|
 511|#######################Hheeeeee.eeeeeeGeeYee=eeeeee##gg##############|
 501|#######################.eeeeeeeeeeeeee##.eeeeGeeeeeeeeeg#############|
 491|#########################H...e.eeeeee..###ee===YYYeeeeee#############|
 481|###########################..ee=G=e=eee=X=e#e=Geeeeeee=##############|
 471|###########################...eeeeeeeeG=e=eeeee.GeeGee.##############|
 461|#############################.eeeeeeeGeGeeeeeee.eG=Gee###############|
 451|############################eeeeeeeeeee=eee=G=eeee=.#################|
 441|############################eeeeeGeGeGYYeGe==.eeee..#################|
 431|############################eeee==G=eeeeeG=#GeGG=GGG#.###############|
 421|###############.###############ee==##=Geee.###.........ee############|
 411|###############.###########b##########GGGG#####........e.############|
 401|###############...###.....eeeee######...e.e###e.eeeeeeeeeeb##########|
 391|###############...eeeeeeeeeeeee####.e.e.e.######.eeeeeeeeeee#########|
 381|################eeX=eeeeeeee.=G.##e.e..#########.eeeee.eeee=.########|
 371|###############eeGeeeeYeeeeGe==eeGe==e=##########eeeee.GeeG=..#######|
 361|#############e=eeeeeeeg=eee###..eeeG=############eeeGeeeeX......#####|
 351|############=G=GGeG=####G=#######GGGe##########.eeeeeee=eeeG=.#######|
 341|##########..e..##########.#####################.eeee..#.Ge...e#######|
 331|#########......################################eeeeG..#eeeeeee#######|
 321|#########.....#################################==eeeeeeeG=ee.########|
 311|########......##############################=eeeeeeeGeeYYYe..########|
 301|######.........#############################GeGGGeee..====.##########|
 291|###.........###############.#############.......#Gee.......##########|
 281|##....eeee....###ee######..#######.#HHHh......#######################|
 271|####..eeee.e.e.eeeeeeeehhh.##########hhh..###########################|
 261|####eeeeeGeeeGeeee.G=Geee.############eeeeeg#########################|
 251|#####ee..e.e...G=XeeGG..ee############ee.eee#########################|
 241|########.eee...#ee#eeeeeeeee####e.e.e.eeeeee.########################|
 231|#########.eeeeeeeeeee=e=ee.####.....eeeGe~=G.########################|
 221|#########eeGeeeeeeeeGGeee##########.e.eeeeee#########################|
 211|############...GeeGY==e############=G##e==ee.#....###################|
 201|############=======.e.#########..##==GeGeeeeeeee#####################|
 191|##.#########e.Gee===e##########...Geeee=eeGee########################|
 181|###...####e.e.Geee############....=GGe=GYYeeee#######################|
 171|#####.=ee.e..###############.........#####G=..#######################|
 161|#####..eeee..############.........###################################|
 151|####.eeeeeGG=#..########..........###################################|
 141|######..eG=.....########......:....##################################|
 131|##########         ######bbb         ################################|
 121|############     :   # ###        :   ## :###########################|
 111|################   :    ####          bb#############################|
 101|###############bbpp     ##### gg :    b##############################|
  91|################b p   g###### gg tt     #############################|
  81|###############ppppppppg#####g   tt  ################################|
  71|###############ppppppppg###ggg tt: g#################################|
  61|############pppppp pp######ppp ttggg#################################|
  51|###########:bb Opp p p#p#p pp p: g ##################################|
  41|##########ggbb pppppp###    #  ggggg#################################|
  31|############ :  :bb###########gg#####################################|
  21|#####################################################################|
```

### 2.15 crossroads (600×420)

**Rules.** attack; whole map open. Enemy: RTS AI true (squads 2..50), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Start items: graphite=1000 thorium=400 silicon=800 beryllium=1000 tungsten=400 oxide=300 carbide=100.

**Objectives:** 0 Timer: enemiesapproaching duration=10.0min flags+[u2]; 1 Timer (after 0): enemyescalating duration=8.3min flags+[u1]; 2 Timer (after 1): enemyairunits duration=3.0min flags+[u3]; 3 Timer (after 2): enemyescalating duration=16.7min flags+[u4]

**Cores.** Player core-bastion at 332,248. Enemy: bastion@326,97 (151), bastion@541,233 (209), citadel@89,273 (244).

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 3 | 6 | 11 | 43 | 43 | 17 | 17 |
| floor ore beryllium | 0 | 0 | 0 | 171 | 171 | 202 | 440 |
| floor ore tungsten | 13 | 53 | 72 | 315 | 315 | 12 | 12 |
| liquid arkycite (deep) | 0 | 217 | 296 | 629 | 629 | 31 | 32 |
| liquid slag (deep) | 0 | 79 | 189 | 5076 | 5076 | 43 | 47 |
| sand wall (exposed) | 99 | 410 | 1359 | 4877 | 4877 | 13 | 12 |
| wall ore beryllium (exposed) | 30 | 60 | 115 | 274 | 274 | 17 | 16 |
| wall ore graphite (exposed) | 0 | 20 | 53 | 286 | 286 | 53 | 56 |
| wall ore thorium (exposed) | 0 | 15 | 25 | 49 | 49 | 41 | not walkable |
| wall ore tungsten (exposed) | 0 | 11 | 11 | 23 | 23 | 49 | 50 |

Vents: nearest 6 at 321,234(17,sharded) 344,225(25,sharded) 355,266(29,sharded) 316,214(37,sharded) 346,287(41) 285,224(52,sharded) (distance, and the team whose block already stands on it). Already built on at the start: 35 of them {malis:turbine-condenser=26, malis:vent-condenser=4, sharded:turbine-condenser=4, sharded:vent-condenser=1}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@326,97 | 151 | — | — | — | — |
| enemy bastion@541,233 | 209 | — | — | 284 | — |
| enemy citadel@89,273 | 244 | 449 | 449 | 430 | ~2 wide at 319,141 (path 449); no other route |

Separate ground regions of 300+ tiles: 4; player's region 38065 tiles (66% of open ground); vents in player's ground region 19/43; enemy bastion@326,97 is on another ground region (2400 tiles), narrowest gap from the player's region 5 tiles; enemy bastion@541,233 is on another ground region (15714 tiles), narrowest gap from the player's region 1 tiles.

**Enemy base**, 4552 blocks: turret 50 (breach×25 diffuse×12 disperse×6 afflict×5 smite×2); wall 177 (tungsten-wall×50 tungsten-wall-large×50 reinforced-surge-wall-large×30 blast-door×24 beryllium-wall-large×18 reinforced-surge-wall×3 beryllium-wall×2); units 12 (tank-fabricator×3 prime-refabricator×2 mech-fabricator×2 mech-refabricator×2 tank-assembler×1 ship-refabricator×1 ship-fabricator×1); drill 124 (plasma-bore×72 cliff-crusher×30 impact-drill×11 large-plasma-bore×11); crafter 111 (electric-heater×38 heat-redirector×30 slag-heater×15 electrolyzer×11 silicon-arc-furnace×7 vent-condenser×5 atmospheric-concentrator×2 cyanogen-synthesizer×2 surge-crucible×1); power 315 (beam-node×268 turbine-condenser×26 beam-link×12 pyrolysis-generator×6 chemical-combustion-chamber×2 flux-reactor×1); ducts/bridges/routers 2049; conduits/pumps/tanks 1702; containers 3; payload blocks 2; other 4 (large-shield-projector×3 shield-projector×1).
Turrets and unit blocks by nearest core (60-tile radius): 326,97 breach×5 units×3 / 541,233 breach×6 afflict×3 units×3 disperse×2 / 89,273 units×4 disperse×2 / far from any core breach×14 diffuse×12 disperse×2 afflict×2 smite×2 units×2.
Layout (xtest.Study): 50 turrets; 177 walls in 14 pieces (2-79 tiles) and 24 blast doors; 3 large shield projectors; 45 item turrets fed, median 4, longest 73.

**Player's pre-built blocks** (536): drill 26 (plasma-bore×21 cliff-crusher×4 impact-drill×1); crafter 4 (vent-condenser×1 electrolyzer×1 oxidation-chamber×1 silicon-arc-furnace×1); power (nodes+generators) 44; ducts/bridges/routers 268; conduits/pumps/tanks 193.

**World processors:** `u2` (10 min), `u1` (18.3), `u3` (21.3) and `u4` (38) switch on 3 refabricators and the tank assembler at 108,273, but only while those blocks still belong to malis.

**Notes.**

- 3 enemy cores. The one at 326,97 (151 straight) is on separate ground, 5 tiles of rock away, that nothing walks to. The one at 541,233 is reachable only by legs (gap 1). The citadel at 89,273 is reachable on the ground through a **~2-wide** squeeze at 319,141 (walk 449).
- Good start: 13 floor-tungsten tiles within 30 (nearest 12), 3 vents within 30 (all already have the player's turbines), deep arkycite 31 tiles away, slag 43. Wall tungsten 49 and thorium 41 (wall) are close.
- The enemy has free resources, 50 turrets including 2 smite and 5 afflict, 3 large shield projectors, the first flux reactor and pyrolysis generators, and 24 blast doors.

**Overview.** (x 52..580, y 33..396; 1 column = 7 tiles, 1 row = 14 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 383|############################################################################|
 369|###############################################g############################|
 355|############################################gg  gg gg#######################|
 341|##################################    #bbb###g     ggg######################|
 327|############         bb   ####               :       g######################|
 313|###########===G=      b        b b#####   t: t      G===# g#################|
 299|#########eeeeee=#####  bb#     b####HHHHH# # ####   GGeeeeee eG#############|
 285|#~###g#eeeeeeeg##########   ######### #HH :##########beeeeeeeeG ############|
 271|#eeeeXeYYeGeeee=b#bb#####     ######bbppbp## #############geeeG##===########|
 257|#eeGeeeeeeeeeee# b bb         ###~~   pppppp    #############HeeeeeGeeee####|
 243|##eeeee#TeeeeGG             # ####ppppppOppp      bb##########eeYYeeeeeee###|
 229|###########      ######     ####gppppppppp#      : b##########eeee#eeXeeee##|
 215|##########bb   b###############gppppppp ppppp     #########  =GeeeeeeeGeeee#|
 201|#########bb   bb################gg Ttp     b    : ####### ~~~=GGeeeeeeeebe##|
 187|##########    ###################gg####tbb bbb   b####### ~~  ==bb  eee ee##|
 173|##########    #####gggg# ##############     bb   ######    ~~     =GGGGee###|
 159|#############  ####eeeeG#  ############    : ##   #####   ~ t      =====####|
 145|###############  gggeeG=      ######     g#########    ~    t:  ~~ ~ #~#####|
 131|##################   e####       #       ##Gee####  ~~         ~~~~ ########|
 117|#############################bbb    ###H#eeGee ######=GG  #     ############|
 103|####################################eGeGeGeeeeee#####eee####=GGGGe##########|
  89|#####################################eeXYYeeege#####eeeGeeeeeeeee ##########|
  75|####################################################eeee eeGeYee~~##########|
  61|#########################################################~eeeeee~###########|
  47|############################################################eeeee###########|
  33|##############################################################eee###########|
```

### 2.16 karst (600×600)

**Rules.** no attack, waves; whole map open. Enemy: RTS AI false (squads 4..50), free resources true, unit build speed ×1.0. No-build radius around cores 50 tiles; unit cap 0. Capture at wave 10. Start items: graphite=1000 thorium=500 silicon=500 beryllium=1000 tungsten=600 oxide=300 carbide=300.

**Cores.** Player core-citadel at 170,446. Enemy: none. Ground spawns: 172,84 (362), 341,269 (246).

**Waves.** first wave after 11.0min (normal difficulty; ×0.8 on hard), then every 8.0min. Spawn groups (wave numbers 1-based), then totals per wave:

```
group merui begin=1 end=5 spacing=2 amount=10 scaling=0.14285715 shields=0.0+20.0/w spawn=172,84
group elude begin=1 end=3 spacing=2 amount=6 scaling=0.16666667 shields=0.0+10.0/w spawn=172,84
group locus begin=2 end=- spacing=2 amount=5 scaling=0.2 spawn=172,84
group avert(air) begin=2 end=- spacing=2 amount=4 scaling=0.25 shields=0.0+10.0/w spawn=341,269
group elude begin=3 end=9 spacing=3 amount=15 scaling=0.1
group obviate(air) begin=3 end=- spacing=3 amount=2 scaling=0.33333334 spawn=172,84
group precept begin=4 end=- spacing=2 amount=4 scaling=0.33333334 spawn=341,269
group cleroi begin=4 end=4 spacing=3 amount=6 scaling=0.33333334 spawn=341,269
group avert(air) begin=4 end=- spacing=2 amount=3 scaling=0.33333334 spawn=172,84
group locus begin=5 end=- spacing=2 amount=4 scaling=0.2 spawn=341,269
group anthicus begin=5 end=- spacing=2 amount=3 scaling=0.33333334
group quell(air) begin=5 end=- spacing=2 amount=2 scaling=1.0 spawn=172,84
group vanquish begin=6 end=10 spacing=2 amount=1 scaling=none spawn=172,84
group cleroi begin=6 end=- spacing=2 amount=14 scaling=0.16666667 spawn=341,269
group tecta begin=7 end=- spacing=2 amount=1 scaling=1.0 shields=0.0+400.0/w spawn=172,84
group disrupt(air) begin=8 end=- spacing=10 amount=1 scaling=none spawn=172,84
group collaris begin=9 end=- spacing=10 amount=1 scaling=none effect=boss spawn=172,84
wave 1 @11.0min: 16 units (0 air) hp~10400 {merui=10, elude=6}
wave 2 @19.0min: 9 units (4 air) hp~14900 {locus=5, avert=4}
wave 3 @27.0min: 60 units (2 air) hp~41560 {obviate=2, merui=16, elude=42}
wave 4 @35.0min: 31 units (11 air) hp~59860 {locus=10, precept=4, avert=11, cleroi=6}
wave 5 @43.0min: 35 units (2 air) hp~54080 {quell=2, locus=4, merui=23, anthicus=6}
wave 6 @51.0min: 110 units (23 air) hp~154680 {locus=15, precept=7, avert=18, obviate=5, vanquish=1, elude=50, cleroi=14}
wave 7 @59.0min: 25 units (3 air) hp~75800 {quell=3, locus=9, anthicus=12, tecta=1}
wave 8 @67.0min: 77 units (26 air) hp~165460 {locus=20, precept=10, avert=25, disrupt=1, vanquish=1, cleroi=20}
wave 9 @75.0min: 117 units (12 air) hp~195000 {quell=4, locus=14, obviate=8, collaris=1, anthicus=18, elude=70, tecta=2}
wave 10 @83.0min: 97 units (32 air) hp~193900 {locus=25, precept=13, avert=32, vanquish=1, cleroi=26}
wave 11 @91.0min: 51 units (5 air) hp~159000 {quell=5, locus=19, anthicus=24, tecta=3}
wave 12 @99.0min: 128 units (50 air) hp~248800 {locus=30, precept=16, avert=39, obviate=11, cleroi=32}
wave 13 @107.0min: 64 units (6 air) hp~203000 {quell=6, locus=24, anthicus=30, tecta=4}
wave 14 @115.0min: 138 units (46 air) hp~264260 {locus=35, precept=19, avert=46, cleroi=38}
wave 15 @123.0min: 91 units (21 air) hp~280800 {quell=7, locus=29, obviate=14, anthicus=36, tecta=5}
wave 20 @163.0min: 175 units (67 air) hp~348900 {locus=40, precept=28, avert=67, cleroi=40}
wave 25 @203.0min: 128 units (12 air) hp~471200 {quell=12, locus=40, anthicus=66, tecta=10}
wave 30 @243.0min: 229 units (109 air) hp~493900 {locus=40, precept=40, avert=80, obviate=29, cleroi=40}
```

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 3 | 4 | 11 | 46 | 46 | 14 | 14 |
| floor ore beryllium | 50 | 102 | 141 | 367 | 367 | 19 | 19 |
| floor ore thorium | 0 | 0 | 0 | 574 | 574 | 145 | 153 |
| floor ore tungsten | 0 | 0 | 152 | 527 | 527 | 70 | 72 |
| liquid arkycite (deep) | 3 | 674 | 1763 | 3007 | 3007 | 29 | 28 |
| liquid slag (deep) | 0 | 320 | 558 | 1941 | 1941 | 39 | 43 |
| sand wall (exposed) | 29 | 202 | 601 | 4142 | 4142 | 24 | 24 |
| wall ore beryllium (exposed) | 4 | 24 | 110 | 222 | 222 | 28 | 28 |
| wall ore graphite (exposed) | 0 | 23 | 23 | 361 | 361 | 33 | 33 |
| wall ore thorium (exposed) | 0 | 0 | 0 | 86 | 86 | 115 | 126 |
| wall ore tungsten (exposed) | 0 | 15 | 38 | 59 | 59 | 31 | 32 |

Vents: nearest 6 at 163,433(14,sharded) 155,449(15,sharded) 186,431(21,sharded) 203,454(33) 130,397(63) 156,517(72) (distance, and the team whose block already stands on it). Already built on at the start: 3 of them {sharded:turbine-condenser=2, sharded:vent-condenser=1}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| spawn@172,84 | 362 | 449 | 447 | 439 | ~9 wide at 176,171 (path 449); ~9 wide at 172,327 (path 456); ~7 wide at 194,338 (path 462) |
| spawn@341,269 | 246 | 500 | 314 | 305 | ~7 wide at 368,274 (path 500); no other route |

Separate ground regions of 300+ tiles: 1; player's region 76833 tiles (99% of open ground); vents in player's ground region 46/46.

**Enemy base:** none.

**Player's pre-built blocks** (470): turret 1 (afflict×1); drill 29 (plasma-bore×21 cliff-crusher×6 large-plasma-bore×2); crafter 9 (heat-redirector×2 electrolyzer×2 oxidation-chamber×2 silicon-arc-furnace×2 vent-condenser×1); power (nodes+generators) 44; ducts/bridges/routers 274; conduits/pumps/tanks 112.

**World processors:** None.

**Notes.**

- A wave survival map with no enemy base: the first wave at 11 min, then every 8 min, capture at wave 10 (~83 min). The waves are large from the start: wave 1 has 16 units, wave 3 has 60 (42 elude), wave 6 has 110 units and ~155k HP including a vanquish, wave 9 has 117 units with a boss collaris.
- Two spawns. 172,84 (walk 449) has three ~7-9-wide routes, so it cannot be held at one choke. 341,269 is walk 500 but only 314 for hover and 305 for legs, so elude and mech waves arrive much sooner than tanks.
- The player starts with a citadel and an afflict. Within 30: 3 vents and 50 floor beryllium. Wall tungsten is 31 away and floor tungsten 70; thorium 115+ (wall) and 145+ (floor). Arkycite starts 29 tiles away and is plentiful (674 tiles within 60).

**Overview.** (x 23..522, y 54..563; 1 column = 7 tiles, 1 row = 14 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 558|########################################################################|
 544|###################       ##~~######gggg ###############################|
 530|################bbb   :bb #~~~~#####g     ##############################|
 516|################b  :   ~~~ ##~#####     :  #############################|
 502|##################   ~~~~ ######T## ttt : gg####HHhh ggg################|
 488|###################~~bb ~  #####T ###    gg#####HHhh :   hh ############|
 474|################### pbbb~  ###b tt####   ##########      hhgg###########|
 460|##############pgppppppbb    ##b tttt    #### #### ##     :   ###########|
 446|#########TT#  pppppppO bb:  bbb  :                   :ggg    H##########|
 432|#########T:   ppppppppp             #           bb: :gg#g#g   ##########|
 418|########      ~~ pppp  p #####           :h  :  bbb     :#gg   HH#######|
 404|########    tt    ppp######bb          g hh #gg##    :    ### :    #####|
 390|#######     tt :   TT######b    # ##HHggg#######  tt gg     :     :hh gg|
 376|###gg     bb       T#######   b############## #   ttgg#####     :  hhg##|
 362|###g       bb   ~~: bb#####  #b##############################         g#|
 348|##g                :  b##   ~###############################H    : hhh##|
 334|gg   g###bb    ### :           ###########################gg  ttt Hhh ##|
 320|g ggg###      ######  ######  : ##b####bbb###############gg  tt TT######|
 306|#######tt :  ########   #####     bbbb  :   ##############gg :    # ####|
 292|####### tt  ##########   ####  ~ ##bb#    # ##############gg   :hh #####|
 278|#######bbt   ############   bb  :########## ##########    ### HH  ######|
 264|####### #b ~#####:##ggg######b#   ########## S          ####HH##########|
 250|####### :  :~###      gg######    :#####################################|
 236|#######     bb#      : gg#####     #####################################|
 222|#######        bbb:ggg  gg######  # ####################################|
 208|########     bbb#####g   ######     ####################################|
 194|######    : #########   #######   ######################################|
 180|#######~~~~~~########   ###### bb ######################################|
 166|########~~##########HH #########   #####################################|
 152|################gghhh gg######  :  #####################################|
 138|#################g     g#H###        ###################################|
 124|#################g#    : HH  tt :  #####################################|
 110|################# #         :     ######################################|
  96|###############         ##       b######################################|
  82|############### #    S   ##TTTT:   #####################################|
  68|####################    #######bb#######################################|
  54|#################### ###################################################|
```

### 2.17 origin (600×600)

**Rules.** attack; whole map open. Enemy: RTS AI true (squads 2..20), free resources false, unit build speed ×1.0. No-build radius around cores 62 tiles; unit cap 15. Start items: graphite=5000 thorium=2000 silicon=3000 surge-alloy=1000 beryllium=5000 tungsten=2000 oxide=2000 carbide=1000.

**Objectives:** 0 Timer: enemyescalating duration=10.0min flags+[u1]; 1 Timer (after 0): enemyescalating duration=20.0min flags+[u2]; 2 Timer (after 1): enemyescalating duration=30.0min flags+[u3]; 3 Timer (after 2): enemyescalating duration=30.0min flags+[u4]; 4 Timer (after 3): enemyescalating duration=20.0min flags+[u5]

**Cores.** Player core-acropolis at 160,377. Enemy: bastion@110,83 (298), acropolis@186,146 (232), acropolis@396,155 (324), acropolis@485,177 (381), bastion@112,248 (137), citadel@355,306 (207), acropolis@528,403 (368), citadel@384,483 (247), bastion@156,487 (110).

**Waves.** `waves=false`, so no timed waves; these groups are only used when a world processor calls `spawnwave` (the times below would apply only if waves were on). Spawn groups, then totals per wave:

```
group precept begin=1 end=1 spacing=1 amount=5 scaling=none
group anthicus begin=1 end=1 spacing=1 amount=5 scaling=none
group collaris begin=2 end=2 spacing=1 amount=1 scaling=none
group precept begin=2 end=3 spacing=1 amount=10 scaling=none
group anthicus begin=2 end=3 spacing=1 amount=10 scaling=none
group quell(air) begin=2 end=3 spacing=1 amount=3 scaling=none
group disrupt(air) begin=2 end=2 spacing=1 amount=1 scaling=none
wave 1 @4.0min: 10 units (0 air) hp~38500 {precept=5, anthicus=5}
wave 2 @6.0min: 25 units (4 air) hp~125000 {quell=3, precept=10, disrupt=1, collaris=1, anthicus=10}
wave 3 @8.0min: 23 units (3 air) hp~95000 {quell=3, precept=10, anthicus=10}
```

**Resources** (tiles within 30/60/100 of the player core; walk = shortest ground path to the tile or the open ground beside it).

| resource | ≤30 | ≤60 | ≤100 | map total | in start area | nearest | nearest walk |
|---|---|---|---|---|---|---|---|
| vent 3x3 (turbine fits) | 5 | 10 | 15 | 90 | 90 | 7 | 6 |
| floor ore beryllium | 33 | 36 | 98 | 584 | 584 | 17 | 17 |
| floor ore thorium | 0 | 0 | 0 | 700 | 700 | 230 | 270 |
| floor ore tungsten | 0 | 0 | 18 | 576 | 576 | 62 | 172 |
| liquid arkycite (deep) | 0 | 0 | 18 | 1801 | 1801 | 85 | 123 |
| liquid slag (deep) | 184 | 2474 | 4745 | 10388 | 10388 | 21 | 20 |
| sand wall (exposed) | 75 | 334 | 1215 | 5472 | 5472 | 12 | 11 |
| wall ore beryllium (exposed) | 2 | 15 | 52 | 307 | 307 | 29 | 30 |
| wall ore graphite (exposed) | 12 | 53 | 66 | 489 | 489 | 19 | 18 |
| wall ore thorium (exposed) | 0 | 0 | 0 | 54 | 54 | 206 | 320 |
| wall ore tungsten (exposed) | 0 | 0 | 6 | 87 | 87 | 76 | 167 |

Vents: nearest 6 at 161,384(7,sharded) 151,370(11,sharded) 162,365(12,sharded) 172,395(21,sharded) 188,375(28) 196,356(41,sharded) (distance, and the team whose block already stands on it). Already built on at the start: 75 of them {malis:turbine-condenser=40, malis:vent-condenser=27, sharded:turbine-condenser=8}.

**Routes from the player core** (walk/hover/legs = path lengths; routes = widest ground route first with its narrowest width, then the next one after closing that point).

| target | straight | walk | hover | legs | ground routes, widest first |
|---|---|---|---|---|---|
| enemy bastion@110,83 | 298 | 411 | 345 | 342 | ~9 wide at 364,202 (path 411); ~7 wide at 256,211 (path 411); ~1 wide at 82,197 (path 472) |
| enemy acropolis@186,146 | 232 | 309 | 309 | 302 | ~9 wide at 364,202 (path 309); ~7 wide at 256,211 (path 309); ~1 wide at 82,197 (path 553) |
| enemy acropolis@396,155 | 324 | 388 | 388 | 379 | ~9 wide at 364,202 (path 388); ~7 wide at 256,211 (path 393); ~1 wide at 82,197 (path 801) |
| enemy acropolis@485,177 | 381 | 421 | 421 | 415 | ~10 wide at 326,283 (path 421); ~8 wide at 254,341 (path 421); ~7 wide at 256,211 (path 483) |
| enemy bastion@112,248 | 137 | 273 | 204 | 197 | ~5 wide at 209,254 (path 273); ~1 wide at 82,197 (path 599); no other route |
| enemy citadel@355,306 | 207 | 224 | 224 | 224 | ~10 wide at 326,283 (path 224); ~8 wide at 254,341 (path 224); ~7 wide at 256,211 (path 286) |
| enemy acropolis@528,403 | 368 | 535 | 532 | 521 | ~10 wide at 326,283 (path 535); ~8 wide at 254,341 (path 535); ~7 wide at 256,211 (path 598) |
| enemy citadel@384,483 | 247 | — | — | 267 | — |
| enemy bastion@156,487 | 110 | 179 | 164 | 159 | ~8 wide at 223,424 (path 179); no other route |

Separate ground regions of 300+ tiles: 6; player's region 82847 tiles (87% of open ground); vents in player's ground region 72/90; enemy citadel@384,483 is on another ground region (5000 tiles), narrowest gap from the player's region 16 tiles.

**Enemy base**, 17434 blocks: turret 189 (disperse×91 sublimate×36 breach×33 afflict×11 smite×6 titan×3 lustre×3 diffuse×3 malign×2 scathe×1); wall 1886 (carbide-wall-large×551 shielded-wall×412 carbide-wall×398 tungsten-wall-large×217 tungsten-wall×186 reinforced-surge-wall-large×45 beryllium-wall×23 reinforced-surge-wall×23 beryllium-wall-large×18 blast-door×13); units 29 (ship-fabricator×6 tank-fabricator×4 basic-assembler-module×3 mech-fabricator×3 prime-refabricator×2 ship-refabricator×2 tank-refabricator×2 ship-assembler×2 mech-refabricator×2 mech-assembler×2 tank-assembler×1); drill 318 (cliff-crusher×126 plasma-bore×121 large-plasma-bore×47 eruption-drill×18 impact-drill×6); crafter 401 (electric-heater×144 heat-redirector×68 electrolyzer×68 phase-heater×27 vent-condenser×27 atmospheric-concentrator×18 silicon-arc-furnace×13 cyanogen-synthesizer×9 oxidation-chamber×8 phase-synthesizer×8 carbide-crucible×6 surge-crucible×3 heat-router×2); power 970 (beam-node×889 turbine-condenser×40 chemical-combustion-chamber×15 beam-tower×8 pyrolysis-generator×7 beam-link×6 neoplasia-reactor×5); ducts/bridges/routers 7338; conduits/pumps/tanks 6097; containers 19; payload blocks 114; other 64 (regen-projector×36 build-tower×15 shockwave-tower×6 unit-cargo-unload-point×4 unit-cargo-loader×2 unit-repair-tower×1).
Layout (xtest.Study): 189 turrets (91 disperse) + 15 build towers; 1886 walls in 161 pieces (median 16, largest 380 tiles), mostly carbide/shielded; 137 item turrets fed, half straight from a duct router, median line 5.

**Player's pre-built blocks** (576): turret 5 (breach×4 diffuse×1); wall 38; units 1 (ship-fabricator×1); drill 18 (plasma-bore×12 cliff-crusher×5 eruption-drill×1); crafter 7 (heat-redirector×2 oxidation-chamber×2 electrolyzer×1 atmospheric-concentrator×1 silicon-arc-furnace×1); power (nodes+generators) 50; ducts/bridges/routers 350; conduits/pumps/tanks 104; other 2 (unit-cargo-unload-point×1 radar×1).

**World processors:** `u1`..`u5` (10/30/60/90/110 min) switch on fabricators, then refabricators, prime refabs and assemblers. When the citadel at 355,306 is gone, an explosion and a wave come from 480,250. When malis has 1 core left, waves come from there every minute.

**Notes.**

- The final map. The player has an acropolis and a huge loadout (5000 Gr, 3000 Si, 5000 Be, 2000 W, 2000 Th, 2000 Ox, 1000 surge, 1000 carbide). The enemy has 9 cores (4 acropolis) and 17434 blocks, but **no free resources**. Its turrets: 91 disperse, 36 sublimate, 33 breach, 11 afflict, 6 smite, 3 titan, 3 lustre, 2 malign, 1 scathe.
- Most cores are walkable through a few corridors 5-10 wide (364,202; 256,211; 326,283; 254,341). The nearest core, the bastion at 156,487 (walk 179, ~8 wide at 223,424), is the obvious first target. The citadel at 384,483 is legs only.
- Close by: 5 vents within 30 (with siege the most of any map; the player already has turbines on 8 vents), 33 floor beryllium within 30 and slag 21 tiles away. Tungsten and thorium are far (floor tungsten walk 172, thorium 230+).
- Scripted escalation: factories at 10 min, then refabs (30), prime refabs (60) and assemblers (90, 110). Killing the 355,306 citadel triggers a wave at 480,250, and with 1 malis core left waves repeat every minute.

**Overview.** (x 15..585, y 18..546; 1 column = 8 tiles, 1 row = 16 tiles; north up)

```
O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  S spawn
: 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag (shallow)  ~ deep liquid  - shallow liquid  # rock  . outside start area
 546|########################################################################|
 530|####################################=eee################################|
 514|###############e#ee# ############## G=eGe###############################|
 498|##############eeeeeeeee#ggg#########=eeee#==G=e#########################|
 482|############b=GeeXYeeeeeeGeee#######eGeeYY#=eeX==##############Ge#######|
 466|###########~#==eeGeGGeebb#GGe##########GGYe=GeG=##############=Gee######|
 450|################ee=# eeGee===##########==gg== ==############g=GeeGe#####|
 434|######################==GGG###:bb##### #####################gGeeeeG~####|
 418|#################:##:gg##  ##    ###  #######################=Geee=#####|
 402|#######~####~~   b pppppp         gggT#########G==##########gGGYXeG=####|
 386|######~ T: ~~~ bb ppppppp ####################=ee==#########g=GeeG==####|
 370|###### #ppt~~~ bppOpp:pppbp #################=ee==G###########==Gee#####|
 354|######## ~~~~~~  pppppppppp  ###########eeG=G=ee###############HGGe#####|
 338|############~~~~~#bb## ppp ##  :#bbbb GGGGeeeeeeG=#########=e=heee==####|
 322|#############~##########   ###   b  : GGeeeeeeeee=########=GG=eeeeG=####|
 306|############~~###########  : ####b#### geGXeeYeeG=########GeeeeeGe######|
 290|G=G=###~:~~~~~###GGGG#####    ######  g==GeGYeeG#########=GeGGGG==######|
 274|eYGG##~~~ #===###eee#######  b ### :  g=Ge==############g  ====#########|
 258|#eGe=G~~##GGG=G##eb#####ggg      tt ######gg  ~    # # gg bb ###########|
 242|##### ~GGeeYXeeeeGG     g :   :#tt######### eb: bb      :  :############|
 226|####==~=eeeeeeGeGee######b b    ##########  etttt b         ######GG==##|
 210|######~~GeGGee#############b#  ##g ####bb##bet   TT    =====######GYYeG#|
 194|#######~ bb###############==GG==#g     b====e##########=GGGGGe####=YYeeg|
 178|#######~############gg####=eGee==G=###=GGGG=G=####=G=g#=GeeeeG#==G=eee##|
 162|#######~##########eeGGG==G=eeeeeeee=###GeeeeeG==G==eee##YeX=YY==eee#####|
 146|#################=GeeXeGeeeeeeeeeee==###GGg##eGXeeeGeeeGeeeeeeeeeGhg####|
 130|##########gg####h=eeeeee ##eGGGGe==G=###=GeeeeeeGeee=GGegG=#eeGeG=#g####|
 114|######## #G=ee##=Geeee##G=##===ee#########=eGeGGe#####g######=GG==######|
  98|#####T ~=GGeeeG=GeeeYeYYeG################# ==ee###############== ######|
  82|#### e~=GeeXeeeeeeeeeeee==##############################################|
  66|####~~~=eGeeeeeeeeee ###################################################|
  50|######~~=GeGeGeeeeeGee==G=eG=###########################################|
  34|##############eeGGe=G==GGeeeG###########################################|
  18|################ee######=GeGG###########################################|
```

## 3. The Erekir tech tree

Read from `Planets.erekir.techTree` (152 nodes) with every objective resolved. The game adds a "research item X" objective for each item in a node's cost; those are left out, as is the parent (which must be researched first). "On landing at X" is `Objectives.OnSector` (the sector has been landed on); "after completing X" is `SectorComplete`. The gate shown is the latest of: the node's own sector objective, its ancestors' gates, and the first map where each material in its research cost can be produced (tungsten aegis, oxide marsh, surge ravine, thorium stronghold, carbide crevice, phase karst). In other words, it is the first point in the campaign where the node can be researched. Costs use Be beryllium, Gr graphite, Si silicon, W tungsten, Th thorium, Ox oxide, Sg surge alloy, Cb carbide, Ph phase fabric.

### 3.1 Sector order

| sector | unlocked by |
|---|---|
| onset | always |
| aegis | complete onset, research duct-router, research duct-bridge |
| lake | complete aegis |
| intersect | complete aegis, complete lake, research vent-condenser, research ship-fabricator |
| atlas | complete intersect, research mech-fabricator |
| split | complete atlas, research reinforced-payload-conveyor, research reinforced-container |
| basin | complete atlas |
| marsh | complete basin |
| ravine | complete marsh, research slag |
| caldera-erekir | complete peaks, complete ravine, research heat-redirector |
| stronghold | complete caldera-erekir, research core-citadel |
| crevice | complete stronghold |
| siege | complete crevice |
| crossroads | complete siege |
| karst | complete crossroads, research core-acropolis |
| origin | complete karst, research core-acropolis, research vanquish, research disrupt, research collaris, research malign, research basic-assembler-module, research neoplasia-reactor |
| peaks | complete marsh, complete split |

So the order is: onset → aegis → lake → intersect (needs aegis and lake) → atlas → {split, basin} → marsh (after basin) → ravine (after marsh) and peaks (after marsh and split) → caldera (after peaks and ravine) → stronghold → crevice → siege → crossroads → karst → origin.

### 3.2 Blocks and units by the point they become available

| available | node | parent | other prerequisites | research cost | build cost |
|---|---|---|---|---|---|
| from the start (onset) | core-bastion | - | — | Gr2900 Si2900 Be2300 | Gr1000 Si1000 Be800 |
| from the start (onset) | duct | core-bastion | — | Be4 | Be1 |
| from the start (onset) | plasma-bore | core-bastion | — | Be9 | Be40 |
| from the start (onset) | turbine-condenser | core-bastion | — | Be13 | Be60 |
| from the start (onset) | breach | core-bastion | research silicon-arc-furnace, research tank-fabricator | Gr180 Si180 Be180 | Gr125 Si150 Be150 |
| from the start (onset) | tank-fabricator | core-bastion | research silicon-arc-furnace, research plasma-bore, research turbine-condenser | Be180 Gr72 Si72 | Si200 Be150 |
| from the start (onset) | duct-router | duct | — | Be27 | Be10 |
| from the start (onset) | beam-node | turbine-condenser | — | Be4 | Be8 |
| from the start (onset) | cliff-crusher | turbine-condenser | — | Be90 Gr36 | Gr25 Be20 |
| from the start (onset) | beryllium-wall | breach | — | Be180 | Be6 |
| from the start (onset) | stell (unit) | tank-fabricator | — | — | Si50 Be40 |
| from the start (onset) | duct-bridge | duct-router | — | Be90 | Be15 |
| from the start (onset) | silicon-arc-furnace | cliff-crusher | research sand | Be135 Gr45 | Gr80 Be70 |
| from the start (onset) | beryllium-wall-large | beryllium-wall | — | Be630 | Be24 |
| on landing at aegis | impact-drill | plasma-bore | — | Gr810 Si990 Be1350 | Gr60 Si70 Be90 |
| on landing at aegis | reinforced-conduit | turbine-condenser | — | Be270 | Be2 |
| on landing at aegis | radar | breach | research beryllium, research beam-node, research turbine-condenser, research tank-fabricator | Si63 Gr63 | Gr50 Si60 Be10 |
| on landing at aegis | overflow-duct | duct-router | — | Gr270 Be270 | Gr8 Be8 |
| on landing at aegis | reinforced-message | duct-router | — | Gr270 Be90 | Gr10 Be5 |
| on landing at aegis | vent-condenser | beam-node | — | Gr540 Be1710 | Gr20 Be60 |
| on landing at aegis | reinforced-liquid-junction | reinforced-conduit | — | Gr90 Be180 | Gr4 Be8 |
| on landing at aegis | tungsten-wall | beryllium-wall | — | W180 | W6 |
| on landing at aegis | armored-duct | duct-bridge | — | Be270 W90 | Be2 W1 |
| on landing at aegis | underflow-duct | overflow-duct | — | Gr270 Be270 | Gr8 Be8 |
| on landing at aegis | reinforced-container | overflow-duct | — | Gr1080 W810 | Gr40 W30 |
| on landing at aegis | canvas | reinforced-message | — | Si270 Be270 | Si10 Be10 |
| on landing at aegis | reinforced-bridge-conduit | reinforced-liquid-junction | — | Gr180 Be540 | Gr8 Be20 |
| on landing at aegis | reinforced-liquid-router | reinforced-liquid-junction | — | Gr630 Be360 | Gr8 Be4 |
| on landing at aegis | tungsten-wall-large | tungsten-wall | — | W630 | W24 |
| on landing at aegis | duct-unloader | reinforced-container | — | Gr540 Si540 W270 | Gr20 Si20 W10 |
| on landing at aegis | reinforced-liquid-container | reinforced-liquid-router | — | Be1710 W1080 | Be16 W10 |
| on landing at aegis | blast-door | tungsten-wall-large | — | Si630 W630 | Si24 W24 |
| on landing at lake | diffuse | breach | — | Gr6480 Si6480 Be4680 W1350 | Gr200 Si200 Be150 W50 |
| on landing at lake | ship-fabricator | tank-fabricator | — | Si4140 Be3240 | Si250 Be200 |
| on landing at lake | elude (unit) | ship-fabricator | — | — | Gr50 Si70 |
| on landing at intersect | mech-fabricator | ship-fabricator | — | Si4140 Be5400 W180 | Si200 Be250 W10 |
| on landing at intersect | merui (unit) | mech-fabricator | — | — | Si70 Be50 |
| after completing intersect | reinforced-liquid-tank | reinforced-liquid-container | — | Be1350 W1080 | Be50 W40 |
| on landing at atlas | reinforced-payload-conveyor | duct | — | W1080 | W10 |
| on landing at atlas | reinforced-payload-router | reinforced-payload-conveyor | — | W1620 | W15 |
| on landing at atlas | electrolyzer | silicon-arc-furnace | — | Gr1350 Si1710 Be4860 W2790 | Gr40 Si50 Be130 W80 |
| on landing at atlas | tank-refabricator | mech-fabricator | — | Si2250 Be4860 W1710 | Si100 Be200 W80 |
| on landing at atlas | locus (unit) | tank-refabricator | — | — | Si90 Be40 W30 |
| on landing at split | payload-mass-driver | reinforced-payload-conveyor | research silicon-arc-furnace | Gr540 Si1350 W1080 | Gr20 Si50 W40 |
| on landing at split | payload-loader | payload-mass-driver | — | Gr2340 Si5040 W2700 | Gr80 Si160 W90 |
| on landing at split | constructor | payload-mass-driver | — | Si1350 Be2160 W1080 | Si50 Be75 W40 |
| on landing at split | payload-unloader | payload-loader | — | Gr4320 Si7200 W5760 | Gr140 Si220 W180 |
| on landing at basin | reinforced-pump | reinforced-conduit | — | Si540 Be1080 W810 | Si20 Be40 W30 |
| on landing at basin | slag-incinerator | electrolyzer | — | W360 | W15 |
| on landing at basin | mech-refabricator | tank-refabricator | — | Si3510 Be6210 W4140 | Si150 Be250 W175 |
| on landing at basin | cleroi (unit) | mech-refabricator | — | Si450 W360 | Si120 Be50 W40 |
| on landing at marsh | sublimate | diffuse | — | W360 Si360 Ox40 Be720 | Si200 Be400 W150 Ox40 |
| on landing at marsh | chemical-combustion-chamber | vent-condenser | — | Gr1800 W900 Ox5 Si1350 | Gr40 Si30 W20 Ox40 |
| on landing at marsh | oxidation-chamber | electrolyzer | research tank-refabricator | Gr2610 Si3330 Be4050 W4050 | Gr80 Si100 Be120 W120 |
| on landing at peaks | beam-tower | beam-node | — | Si270 Be810 Ox150 | Si10 Be30 Ox10 |
| on landing at peaks | regen-projector | beam-node | — | Si2340 Be2340 W1710 Ox600 | Si80 Be80 W60 Ox40 |
| on landing at peaks | small-deconstructor | constructor | — | Gr1350 Si1980 Be1980 Ox350 | Gr50 Si70 Be70 Ox25 |
| on landing at peaks | ship-refabricator | mech-refabricator | — | Be450 W180 Si270 Ox40 | Si150 Be250 W120 Ox15 |
| on landing at peaks | avert (unit) | ship-refabricator | — | Si540 W360 | Gr50 Si130 W40 |
| after completing peaks | core-citadel | core-bastion | — | Si14400 Be29700 W21600 Ox3600 | Si4000 Be4000 W3000 Ox1000 |
| on landing at ravine | unit-repair-tower | tank-fabricator | research mech-refabricator | Gr2700 Si2700 W2340 | Gr90 Si90 W80 |
| on landing at ravine | unit-cargo-loader | duct-bridge | — | Si2250 Sg14 Ox15 | Si80 Sg50 Ox20 |
| on landing at ravine | reinforced-surge-wall | tungsten-wall | — | Sg14 W90 | Sg6 W2 |
| on landing at ravine | afflict | sublimate | — | Gr270 Si180 Sg70 Ox25 | Gr250 Si200 Sg100 Ox40 |
| on landing at ravine | surge-conveyor | armored-duct | — | Sg21 W72 | Sg1 W1 |
| on landing at ravine | unit-cargo-unload-point | unit-cargo-loader | research tungsten | Si2700 Ox10 | Si60 W60 |
| on landing at ravine | large-canvas | canvas | — | Si360 Sg70 Be360 | Si15 Sg5 Be15 |
| on landing at ravine | reinforced-surge-wall-large | reinforced-surge-wall | — | Sg28 W180 | Sg24 W8 |
| on landing at ravine | surge-router | surge-conveyor | — | Sg70 W72 | Sg5 W1 |
| on landing at ravine | surge-crucible | oxidation-chamber | — | Gr2340 Si2970 W2340 Ox1300 | Gr80 Si100 W80 Ox80 |
| on landing at ravine | heat-redirector | oxidation-chamber | — | Gr2790 W2790 | Gr10 W10 |
| on landing at ravine | electric-heater | heat-redirector | research afflict | Be3330 W3330 Ox1850 | Be30 W30 Ox30 |
| on landing at ravine | heat-router | electric-heater | — | Gr2790 W4140 | Gr10 W15 |
| on landing at ravine | small-heat-redirector | heat-router | — | Gr2610 Sg140 | Gr8 Sg3 |
| on landing at caldera-erekir | large-plasma-bore | impact-drill | — | Si1350 Ox100 Be2700 W1080 | Si100 Be100 W70 Ox25 |
| on landing at caldera-erekir | slag-heater | electric-heater | — | W1080 Ox450 Be2160 | Be20 W50 Ox20 |
| on landing at caldera-erekir | atmospheric-concentrator | electric-heater | — | Si1800 Ox450 Be2160 | Si150 Be180 Ox60 |
| on landing at stronghold | disperse | diffuse | — | Th1350 Si6480 Be11700 Ox750 | Th50 Si200 Be350 Ox50 |
| on landing at stronghold | eruption-drill | large-plasma-bore | — | Th4680 Si9900 W8280 Ox300 | Th150 Si300 W250 Ox20 |
| on landing at stronghold | large-cliff-crusher | large-plasma-bore | — | Si2340 Sg280 Be2970 W1350 | Si80 Sg15 Be100 W50 |
| on landing at stronghold | build-tower | regen-projector | — | Th1710 Si4680 Ox600 | Th60 Si150 Ox40 |
| on landing at stronghold | reinforced-vault | reinforced-container | — | Th1980 Be2970 W3870 | Th70 Be100 W125 |
| on landing at stronghold | titan | afflict | — | Th13500 Si9900 W8280 | Th400 Si300 W250 |
| on landing at stronghold | prime-refabricator | ship-refabricator | — | Th1620 Si13500 W6480 Ox3600 | Th250 Si400 W200 Ox200 |
| on landing at stronghold | precept (unit) | prime-refabricator | — | — | Th80 Si190 Be40 W30 |
| on landing at stronghold | anthicus (unit) | prime-refabricator | — | Th720 Si900 | Th80 Si220 Be50 W40 |
| on landing at stronghold | obviate (unit) | prime-refabricator | — | Th720 Si900 | Gr50 Th80 Si230 W40 |
| on landing at crevice | carbide-wall | tungsten-wall | — | Th180 Cb60 | Th6 Cb6 |
| on landing at crevice | pyrolysis-generator | chemical-combustion-chamber | — | Gr1170 Si1170 Ox350 Cb210 | Gr100 Si100 Ox60 Cb60 |
| on landing at crevice | carbide-wall-large | carbide-wall | — | Th630 Cb210 | Th24 Cb24 |
| on landing at crevice | lustre | titan | — | Gr6480 Si8280 Ox750 Cb900 | Gr200 Si250 Ox50 Cb90 |
| on landing at crevice | carbide-crucible | electric-heater | research graphite | Th4680 W3330 Ox950 | Th150 W110 Ox60 |
| on landing at siege | scathe | disperse | — | Gr13500 Si15300 W17100 Ox1650 Cb2160 | Gr400 Si450 W500 Ox100 Cb200 |
| on landing at siege | shockwave-tower | build-tower | — | Si4680 Sg1050 W2970 Ox450 | Si150 Sg50 W100 Ox30 |
| on landing at siege | large-constructor | small-deconstructor | — | Th2340 Si4680 W6480 Ox1650 | Th80 Si150 W200 Ox100 |
| on landing at siege | deconstructor | small-deconstructor | — | Si8280 Be8280 Ox1650 Cb450 | Si250 Be250 Ox100 Cb50 |
| on landing at siege | tank-assembler | ship-refabricator | research constructor, research atmospheric-concentrator | Th7110 Si9000 Ox1050 Cb300 | Th500 Si650 Ox150 Cb80 |
| on landing at siege | vanquish (unit) | tank-assembler | — | Si1800 Be1440 W2160 | Si200 Be160 W240 |
| on landing at siege | cyanogen-synthesizer | atmospheric-concentrator | research graphite | Si3690 Be4320 Ox600 Cb780 | Si120 Be140 Ox40 Cb80 |
| after completing siege | core-acropolis | core-citadel | — | Si9000 Be27900 W22500 Ox7000 Cb4200 | Si5000 Be6000 W5000 Ox3000 Cb3000 |
| on landing at crossroads | flux-reactor | pyrolysis-generator | research cyanogen-synthesizer | Gr7920 Si16200 Sg2870 Ox1300 Cb570 | Gr240 Si480 Sg120 Ox80 Cb60 |
| on landing at crossroads | ship-assembler | tank-assembler | — | Th13500 Si34200 W19800 Ox3600 Cb990 | Th400 Si900 W550 Ox200 Cb100 |
| on landing at crossroads | mech-assembler | tank-assembler | — | Th21600 Si37800 W19800 Ox3600 Cb2160 | Th600 Si1000 W550 Ox200 Cb200 |
| on landing at crossroads | quell (unit) | ship-assembler | — | — | Gr200 Si280 Be288 |
| on landing at crossroads | tecta (unit) | mech-assembler | — | — | Si350 Be250 W288 |
| on landing at karst | beam-link | beam-tower | — | Si8280 Ph480 Sg1680 Be8280 Ox2600 Cb720 | Si250 Ph75 Sg75 Be250 Ox150 Cb75 |
| on landing at karst | large-payload-mass-driver | payload-unloader | — | Gr1710 Si4680 Ph60 W3690 Ox450 | Gr60 Si150 Ph10 W120 Ox30 |
| on landing at karst | shielded-wall | reinforced-surge-wall-large | — | Ph120 Sg210 Be270 | Ph20 Sg12 Be12 |
| on landing at karst | neoplasia-reactor | flux-reactor | — | Si17100 Ph1040 Sg5040 W27900 Ox2600 Cb3300 | Si500 Ph150 Sg200 W750 Ox150 Cb300 |
| on landing at karst | smite | lustre | — | Si29700 Ph2200 Sg10500 Ox3600 Cb5700 | Si800 Ph300 Sg400 Ox200 Cb500 |
| on landing at karst | phase-synthesizer | carbide-crucible | research sand | Th2970 Si2970 W6480 Cb900 | Th100 Si100 W200 Cb90 |
| on landing at karst | conquer (unit) | vanquish | — | Th4320 Si4860 Be2160 W1620 Cb1440 | Th480 Si540 Be240 W180 Cb480 |
| on landing at karst | phase-heater | phase-synthesizer | research phase-fabric | Be810 Ox450 Cb270 | Be30 Ox30 Cb30 |
| on landing at karst | disrupt (unit) | quell | — | Gr2700 Th4320 Si7020 W2160 Cb1440 | Gr300 Th480 Si780 W240 Cb480 |
| on landing at karst | collaris (unit) | tecta | — | Th4320 Si6480 Be2700 W2160 Cb1440 | Th480 Si720 Be300 W240 Cb480 |
| after completing karst | malign | scathe | — | Gr17100 Si17100 Ph1440 Be37800 Cb2160 | Gr500 Si500 Ph200 Be1000 Cb200 |
| after completing karst | basic-assembler-module | tank-assembler | — | Th12600 Ph2200 Ox3450 Cb2520 | Th500 Ph400 Ox250 Cb300 |

### 3.3 Materials

Items and liquids are research nodes that unlock when first produced: beryllium → sand → silicon → oxide; beryllium → water → ozone → hydrogen → {nitrogen, cyanogen → neoplasm}; beryllium → graphite → tungsten → {slag, arkycite, thorium → carbide, surge alloy → phase fabric}. Ravine also requires slag to have been produced (`R:slag`), and Marsh's tasks walk the player through oxide and arkycite.

### 3.4 Cross-check with reference/meta-report.md section 1.7

The stage table in section 1.7 matches the dump for every block it names and for the sector that gates it. Corrections and additions from the dump:

- **Sector order.**
  - lake and intersect are not parallel: intersect needs *both* aegis and lake completed, plus vent condenser and ship fabricator researched.
  - peaks needs split completed as well as marsh.
  - ravine needs slag to have been produced.
  - caldera needs peaks and ravine completed and the heat redirector researched.
  - stronghold needs the citadel core researched, and karst the acropolis.
  - origin needs acropolis, vanquish, disrupt, collaris, malign, basic assembler module and neoplasia reactor researched.
- **Split** gates the payload mass driver and constructor (`OnSector split`). The reinforced payload conveyor is gated at atlas. Section 1.7 leaves split out.
- **Gates section 1.7 does not list:**
  - aegis also gates overflow/underflow duct and reinforced message. The reinforced liquid tank needs intersect *completed*.
  - basin also gates the slag incinerator.
  - peaks also gates the small deconstructor.
  - ravine also gates the unit repair tower, which also needs the mech refabricator.
  - stronghold also gates the large cliff crusher.
  - siege also gates the large constructor and deconstructor.
  - crossroads also gates the beam link.
- **Extra research prerequisites** besides the parent:
  - oxidation chamber: tank refabricator.
  - electric heater: afflict.
  - carbide crucible and cyanogen synthesizer: graphite.
  - tank assembler: constructor and atmospheric concentrator.
  - flux reactor: cyanogen synthesizer.
  - breach and tank fabricator: arc furnace (and bore and turbine).
- **Unit research costs:**
  - cleroi Si450 W360.
  - avert Si540 W360.
  - anthicus and obviate Th720 Si900 each.
  - vanquish Si1800 Be1440 W2160.
  - conquer, disrupt and collaris need Th4320 + Cb1440 and On:karst.
  - stell, elude, merui, locus, precept, quell and tecta cost nothing beyond their factory.
- **Material order** in section 1.7 (tungsten → oxide → surge → nitrogen → thorium → carbide → cyanogen → phase) is consistent with the gates: oxide at marsh, surge at ravine, nitrogen at caldera, thorium at stronghold, carbide at crevice, cyanogen at siege, phase at karst.



## 4. Cross-map patterns and implications for the AI

1. **Vents are the economy's bottleneck, and water competes with power for them.**
   - Near the player core: 1-5 full vents within 30 tiles (median 3), 3-10 within 60, 5-15 within 100. Map totals run from 10 (marsh) to 91 (stronghold), but most are in the enemy half.
   - On 16 of 17 maps (all but onset) the player starts with turbines on 1-9 of the nearest vents.
   - **No Erekir map has a water floor.** Water only comes from vent condensers, which need the same full 3x3 vent.
   - So every water-cooled turret, impact drill (water) or electrolyzer chain costs a vent that would otherwise make 180/s of power. The AI's vent allocation has to trade power against water explicitly, and vents within ~60 tiles of each core are the scarcest tiles on the map.
2. **Tungsten is rarely close and often not walkable.**
   - Floor tungsten (impact drill) exists on 15 maps (not onset or lake). It is within 30 tiles only on aegis (13), marsh (7) and crossroads (12); typically it is 30-110 tiles away.
   - On 4 maps no floor tungsten can be reached on the ground from the player core (intersect, split, peaks, crevice), and on caldera the wall tungsten cannot.
   - Walking distance can be 2.5-2.8× the straight line: siege 80 → 201, origin 62 → 172.
   - Wall tungsten (large plasma bore) only appears from caldera on and is scarce (2-87 exposed tiles per map).
   - The AI must plan tungsten as a remote, defended outpost with a long line, or use payload/cargo logistics. "Mine what is next to the core" will not do.
3. **Rock ore is thin; sand is everywhere.** Within 30 tiles there are 0-30 exposed beryllium rock-wall tiles and 0-33 graphite ones, against 10-197 sand-wall tiles (median ~100). Any plan that assumes many bores on rock ore near the core fails on most maps. From marsh on, floor beryllium (impact drill) takes over: marsh 82 within 30, siege 40, karst 50, origin 33.
4. **Plan reachability per unit class and per target.**
   - On 6 of 17 maps the nearest enemy core cannot be reached by tanks: lake, caldera, intersect, crossroads, split and peaks. On lake and caldera hover or legs can get there; intersect is legs only; on split and peaks nothing on the ground can (mass driver or air).
   - On multi-core maps, cores of the same map differ: atlas 2 of 4, crossroads 2 of 3, stronghold 2 of 10 and origin 1 of 9 are cut off from tanks.
   - The AI should compute G/H/L/A reachability for each target, choose which factory to build from that, and treat thin rock seams (1-2 tiles) as passable for legs and crossable for duct/conduit bridges (range 4).
5. **Lanes are few and often single chokes.**
   - The widest route from the player to the enemy is usually 4-14 tiles wide at its narrowest. Very often there is no second route once that point is closed: onset, basin, marsh, ravine, stronghold, siege, crossroads, and two of the origin cores.
   - A ground army has one predictable lane, and so does a ground defence. Most maps have a natural kill zone, so the AI should find chokes on the way to the opponent and use them in both directions: hold them, and avoid feeding units through them piecemeal.
   - Walks are mostly 1.05-1.9× the straight line, up to 3.5× (stronghold's 189,515 core: 193 straight, 676 walk).
6. **Legs and hover arrive by different, shorter paths.** Karst's east spawn is a 500-tile walk for tanks but 314 for hover and 305 for legs. On intersect most waves are merui that come over rock. A defence that only covers the tank lane will be outflanked, so the AI's threat map has to be per movement class.
7. **The start area is limited on 7 maps** (onset, aegis, basin, marsh, peaks, stronghold, siege), and the map opens on a script flag or task: 2-10 min, or "after X is built or destroyed". The enemy's bases and the contested resources lie outside it. The AI has to read `rules.limitMapArea` and treat the area outside as unbuildable until it opens, which the game's own placement check already enforces.
8. **Pre-built enemy turrets start empty and are fed only by ducts.**
   - Every item turret on every map has an adjacent duct or duct router, and no turret starts with item ammo.
   - Lines behind turrets are short (median 2-15 ducts, 30 on onset), and routers often sit right next to the turret.
   - Walls are rows or U-shapes 0-6 tiles from the turrets; more stand at the sides than in front, few behind (Study: front ~30%, sides ~50%, behind ~20%).
   - This confirms PLAN section 2 for all 17 maps, and the rewrite can use it as its template.
9. **Free resources are the map's default; the mod turns them off.** 11 maps set `infiniteResources=true` for the enemy, and onset, aegis, intersect, basin, caldera and origin do not. Under the mod's rules the enemy's starting wealth is the loadout copy in its core (onset: nothing) plus whatever its pre-built economy delivers. On the free-resource maps that economy was never meant to sustain the base, so the AI should expect to fix or extend it in the first minutes.
10. **Enemy production is gated by world processors and timers.**
    - Aegis 9 min, atlas furnace 4 min, basin 6 min, caldera 5 min, peaks after the first avert, and staged flags on stronghold, siege, crossroads and origin.
    - These processors toggle specific linked blocks by name (`fabricator1`...). If the AI deconstructs or replaces a linked block, the script loses it, and if it builds its own factories, the map's pacing no longer holds.
    - The AI should keep linked blocks in place (PLAN rule: script-linked blocks are left alone) and treat the timer schedule as a ceiling to stay under, not as its own plan.
11. **Wave maps (intersect, ravine, crevice, karst) are a different game.**
    - The first wave comes at 6-11 min and the next every 1.8-8 min. First waves are small (2-16 units, ≤10k HP), but karst reaches 110 units and ~155k HP by wave 6.
    - Air arrives from wave 8-15, sometimes from a separate spawn (ravine) that ground units cannot reach.
    - When the enemy has a core there (intersect), the AI's base acts as an extra source of units on top of the waves. Without a core (ravine, crevice, karst) there is nothing for the base-building AI to run.
12. **The player always starts with a pre-built base** on 16 of 17 maps (31-537 blocks, usually bores, turbines on the near vents, a furnace and sometimes a breach), so the player's half is never empty. For the enemy AI this means the player has defences early, and the 50-tile no-build radius (`enemyCoreBuildRadius` 400 world units) is the area it can never use.
13. **Ranges set the early fights.** Breach (23.75 tiles) outranges stell and locus (19.5) and merui (16.75), so any line of fed breach stops tier 1-2 ground units. Diffuse (15.6) and sublimate (16.25) are short. Air threats (avert 10.75) are short-ranged but ignore lanes. The late turrets out-range everything that walks: titan 48.75, afflict 46, malign 51, scathe 169. Siege onwards needs units with real range (anthicus 41, collaris 47.6, quell 45, disrupt 58) or mass.
14. **Base size scales hard.** Enemy blocks: 114-1103 on onset, aegis, lake, intersect, split, marsh and peaks; 1.5-5k on atlas, caldera, crossroads and basin; 7.8-17.4k on siege, stronghold and origin. Ducts plus conduits are 60-80% of every base. An AI at that scale needs logistics that stay routable (PLAN 18.23's maze problem) more than it needs more block types.
15. **Planet rules override the map file.** Fog is always on in campaign, `placeRangeCheck` is always off (onset, basin and caldera files have it on), cores only accept deposits by block (`onlyDepositCore`), and `coreDestroyClear` wipes a destroyed core's area. Test harnesses that load the `.msav` directly must apply `Planets.erekir.applyRules` and put `rules.loadout` in the player core, or they test a different game.

