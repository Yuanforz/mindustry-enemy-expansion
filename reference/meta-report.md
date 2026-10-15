# Erekir meta report: how humans play, turret roles, prior art for base-building AI

Research date: 2026-10-11. Target game version: Mindustry v8 build 160.7 (tag `v160.7`, released 2026-10-08, https://github.com/Anuken/Mindustry/releases/tag/v160.7).

## 0. How to read this

Every claim carries one of three tags.

- **[S]** the cited source states it (wiki text, forum post, or a value in the game source at tag `v160.7`).
- **[D]** derived by me by arithmetic from [S] numbers (no judgment involved; recheck the arithmetic if it matters).
- **[I]** my own inference or judgment.

Source shorthand: `GH` = `https://github.com/Anuken/Mindustry/blob/v160.7`. `FW` = `https://mindustry-unofficial.fandom.com/wiki` (Fandom "Mindustry Unofficial Wiki"; I read the raw wikitext through its API, so quotes are exact; the last-edit date is given per page).

Method and limits:

- Game source was read as text from `raw.githubusercontent.com` at tag `v160.7` and filtered with grep/awk. Nothing was saved, built or run. All rates below are per second (source values are per tick; 60 ticks = 1 s).
- Could not be read: Reddit (search API returns a block page), the Mindustry Discord, the Steam guide "The Turrets of Erekir" (HTTP 429), speedrun.com web pages (403; its public API worked), YouTube captions (empty response). The fetch tool cached one PDF (the Game AI Pro chapter cited in section 5) in the session's tool-results folder.
- GitHub's unauthenticated API quota ran out during the prior-art search; the searches that did complete are listed in 4.3.

---

## 1. How good players build on Erekir

### 1.1 Three version traps that affect every number

1. **Heat crafters were rebased between v154 and v156.** [S] In v146-v154 the Carbide Crucible had `heatRequirement = 10`, `craftTime = 60*2.25` and the default `maxEfficiency = 4`; from v156 through v160.7 it has `heatRequirement = 40`, `craftTime = 60*2.25/4`, `maxEfficiency = 1` (`GH/core/src/mindustry/content/Blocks.java#L1537`, compared against the same file at tags `v146`, `v154`, `v156`). The same change applies to Atmospheric Concentrator (6 -> 24 heat, 4 -> 16 nitrogen/s), Surge Crucible (10 -> 40, slag 40 -> 160/s), Cyanogen Synthesizer (5 -> 20) and Phase Synthesizer (8 -> 32). [D] The absolute heat-to-output mapping is unchanged (old "400% at 40 heat" = new "100% at 40 heat"), and below the requirement output scales linearly with heat (`efficiencyScale()` in `GH/core/src/mindustry/world/blocks/production/HeatCrafter.java`). What changed is that there is no longer any overdrive above the listed rate, and every percentage in wiki tables is now off by a factor of 4.
2. **Wikis are mixed-version.** [S] Fandom's Carbide Crucible infobox shows "10 heat units" together with "0.562 seconds" (`FW/Carbide_Crucible`); Miraheze shows 10 heat, 2.25 s, "up to 400% Efficiency" (https://mindustry.miraheze.org/wiki/Carbide_Crucible, edited 2026-03-29). Fandom's Malign shows "72 heat units ... 200% (144 heat units)" and range 47.5 blocks (`FW/Malign`, edited 2026-08-25); source v160.7 has `heatRequirement = 144f`, `maxHeatEfficiency = 1f`, `range = 410` (= 51.25 tiles) (`GH/core/src/mindustry/content/Blocks.java#L5959`). Where they disagree, the tables below use the v160.7 source.
3. **Erekir damage values in source are multiplied by 0.75 at load.** [S] `ErekirTechTree.rebalance()` multiplies `bullet.damage` by 0.75 for every `ErekirUnitType` weapon and every turret whose build cost contains a non-Serpulo item (`GH/core/src/mindustry/content/ErekirTechTree.java`; `TankUnitType extends ErekirUnitType`, `GH/core/src/mindustry/type/unit/TankUnitType.java`). Example: Breach beryllium bullet is 85 in `Blocks.java` and 63.75 on the wiki (`FW/Breach`).

### 1.2 Extraction, power and logistics (source v160.7)

All rows: `GH/core/src/mindustry/content/Blocks.java` (plasma bore L3057, large bore L3071, impact drill L3089, eruption drill L3112, cliff crusher L3021, vent condenser L3000, turbine condenser L2704, combustion chamber L2729, pyrolysis L2751, flux L2781, neoplasia L2817, pump L2404, duct L2179, beam node L2667). Rate formulas: `BeamDrill.java`, `BurstDrill.java`, `WallCrafter.java`, `ThermalGenerator.java`, `Duct.java` under `GH/core/src/mindustry/world/blocks/`.

| Block | Size | Build cost | Consumes | Produces [S values, D rates] | Limits |
|---|---|---|---|---|---|
| Plasma Bore | 2x2 | 40 Be | 9 power/s; optional hydrogen 0.25/s gives x2.5 | 1 item per ore tile hit per 160 ticks = 0.375/s per tile, 0.75/s with both beams on ore, 1.875/s boosted | tier 3: beryllium and graphite wall ore only (Be hardness 3, tungsten 5, thorium 4 in `Items.java`); beam range 5 tiles |
| Large Plasma Bore | 3x3 | 100 Si, 25 oxide, 100 Be, 70 W | 48 power/s; hydrogen 0.5/s (required); optional nitrogen 3/s gives x2.5 | 0.6/s per tile, 1.8/s max, 4.5/s boosted | tier 5: adds tungsten and thorium wall ore; range 6 |
| Impact Drill | 4x4 | 70 Si, 90 Be, 60 graphite | 160 power/s; water 10/s (required); optional ozone 3/s gives x1.75 | one burst of N items every 12 s (6 s for beryllium), N = ore tiles covered, max 16: 1.33/s tungsten, 2.67/s beryllium | tier 6 floor ore; `blockedItem = thorium` |
| Eruption Drill | 5x5 | 300 Si, 20 oxide, 250 W, 150 Th | 360 power/s; hydrogen 4/s; optional cyanogen 0.75/s gives x2 | N items every 4.69 s, max 25 tiles: 5.33/s | tier 7 (thorium floor ore) |
| Cliff Crusher | 2x2 | 25 graphite, 20 Be | 11 power/s | sand at 0.545/s x (sum of the "sand" attribute of the 2 wall tiles it faces): 1.09/s on attribute-1.0 walls | wall attribute: stone/regolith/rhyolite 1.0, yellow stone/red stone 1.5, beryllic stone 1.2, carbon 0.7, ferric stone 0.5, dune/sand 2.0 (`Blocks.java#L597-L676`) |
| Large Cliff Crusher | 3x3 | 80 Si, 15 surge, 100 Be, 50 W | 60 power/s; hydrogen 1/s; optional graphite boost | 1.25/s per attribute point, 3.75/s on three 1.0 tiles | |
| Turbine Condenser | 3x3 on a vent | 60 Be | nothing | 180 power/s and 5 water/s | needs all 9 vent tiles (`minEfficiency = 9`) |
| Vent Condenser | 3x3 on a vent | 20 graphite, 60 Be | 30 power/s | 30 water/s | same vent requirement |
| Chemical Combustion Chamber | 3x3 | 40 graphite, 20 W, 40 oxide, 30 Si | ozone 2/s + arkycite 40/s | 550 power/s | |
| Pyrolysis Generator | 3x3 | 100 graphite, 60 carbide, 60 oxide, 100 Si | slag 20/s + arkycite 40/s | 1400 power/s + 20 water/s | |
| Flux Reactor | 5x5 | 240 graphite, 60 carbide, 80 oxide, 480 Si, 120 surge | cyanogen 9/s, heat (`maxHeat = 150`) | up to 18000 power/s | explodes (`explosionRadius = 17`) |
| Neoplasia Reactor | 5x5 | 750 W, 300 carbide, 150 oxide, 500 Si, 150 phase, 200 surge | arkycite 80/s, water 10/s, 1 phase per 3 s | 8400 power/s, 60 heat, neoplasm 20/s | `explodeOnFull = true` |
| Reinforced Pump | 2x2 | 40 Be, 30 W, 20 Si | hydrogen 1.5/s | 20 liquid/s per tile, 80/s on 4 tiles | |
| Duct | 1x1 | 1 Be | | 15 items/s (`speed = 4`, stat `60/speed`) | |
| Beam Node / Beam Tower | 1x1 / 3x3 | 8 Be / 30 Be, 10 oxide, 10 Si | | range 10 / 23 tiles; power buffer 1000 / 40000 | links only along the 4 cardinal directions (`Geometry.d4` loop in `GH/core/src/mindustry/world/blocks/power/BeamNode.java`) |

Cores [S] (`Blocks.java#L3185-L3240`): Bastion costs 1000 graphite, 1000 Si, 800 Be, stores 2000 per item, `unitCapModifier = 15`, `requiresCoreZone = true`, core unit Evoke; Citadel 4000 Si, 4000 Be, 3000 W, 1000 oxide, stores 3000; Acropolis 6000 Be, 5000 Si, 5000 W, 3000 carbide, 3000 oxide, stores 4000. Core units mine wall ore only (`mineWalls = true`, `mineFloor = false`, `mineTier = 3`), carry 2x2 block payloads and are untargetable unless carrying one (`targetable = false`, `vulnerableWithPayloads = true`) (`GH/core/src/mindustry/content/UnitTypes.java#L4354`).

### 1.3 Crafting recipes (source v160.7)

`GH/core/src/mindustry/content/Blocks.java#L1335-L1690`. Rates [D] = per-craft amounts divided by `craftTime`.

| Block (3x3 unless noted) | Build cost | Inputs per second | Heat | Power/s | Output per second |
|---|---|---|---|---|---|
| Silicon Arc Furnace | 70 Be, 80 graphite | sand 4.8, graphite 1.2 | | 300 | silicon 4.8 |
| Electrolyzer | 50 Si, 40 graphite, 130 Be, 80 W | water 10 | | 60 | ozone 4 and hydrogen 6, on opposite sides (`liquidOutputDirections = {1, 3}`) |
| Oxidation Chamber | 120 W, 80 graphite, 100 Si, 120 Be | beryllium 0.5, ozone 2 | emits 5 | 30 | oxide 0.5 |
| Electric Heater (2x2) | 30 W, 30 oxide, 30 Be | | emits 3 | 100 | |
| Slag Heater | 50 W, 20 oxide, 20 Be | slag 40 | emits 8 | 0 | |
| Phase Heater (2x2) | 30 oxide, 30 carbide, 30 Be | 1 phase fabric per 8 s | emits 15 | 0 | |
| Atmospheric Concentrator | 60 oxide, 180 Be, 150 Si | | needs 24 | 120 | nitrogen 16 |
| Carbide Crucible | 110 W, 150 Th, 60 oxide | tungsten 3.56, graphite 5.33 | needs 40 | 120 | carbide 1.78 |
| Surge Crucible | 100 Si, 80 graphite, 80 W, 80 oxide | silicon 4, slag 160 | needs 40 | 90 | surge alloy 1.33 |
| Cyanogen Synthesizer | 80 carbide, 120 Si, 140 Be, 40 oxide | arkycite 160, graphite 3 | needs 20 | 120 | cyanogen 12 |
| Phase Synthesizer | 90 carbide, 100 Si, 100 Th, 200 W | thorium 4, sand 12, ozone 8 | needs 32 | 480 | phase fabric 2 |

Slag Centrifuge and Heat Reactor are `BuildVisibility.debugOnly` [S] and can be ignored.

### 1.4 Unit production (source v160.7)

`GH/core/src/mindustry/content/Blocks.java#L6448-L6620`.

| Building | Build cost | Per unit | Time | Running inputs | Result |
|---|---|---|---|---|---|
| Tank Fabricator | 200 Si, 150 Be | 40 Be, 50 Si | 35 s | 90 power/s | Stell |
| Ship Fabricator | 250 Si, 200 Be | 50 graphite, 70 Si | 40 s | 90 power/s | Elude |
| Mech Fabricator | 200 Si, 250 Be, 10 W | 50 Be, 70 Si | 40 s | 90 power/s | Merui |
| Tank Refabricator | 200 Be, 80 W, 100 Si | 40 Si, 30 W | 30 s | 180 power/s, hydrogen 3/s | Stell -> Locus |
| Ship Refabricator | 250 Be, 120 W, 150 Si, 15 oxide | 60 Si, 40 W | 50 s | 150 power/s, hydrogen 3/s | Elude -> Avert |
| Mech Refabricator | 250 Be, 175 W, 150 Si | 50 Si, 40 W | 45 s | 150 power/s, hydrogen 3/s | Merui -> Cleroi |
| Prime Refabricator (5x5) | 250 Th, 200 oxide, 200 W, 400 Si | 80 Th, 100 Si | 60 s | 270 power/s, nitrogen 10/s | Locus -> Precept, Cleroi -> Anthicus, Avert -> Obviate |
| Tank Assembler (5x5, 13x13 work area) | 500 Th, 150 oxide, 80 carbide, 650 Si | Vanquish: 4 Stell + 10 large tungsten walls; Conquer: 6 Locus + 20 large carbide walls | 50 s; 180 s | 150 power/s, cyanogen 9/s | Vanquish, Conquer |
| Ship Assembler | 100 carbide, 200 oxide, 550 W, 900 Si, 400 Th | Quell: 4 Elude + 12 large beryllium walls; Disrupt: 6 Avert + 20 large carbide walls | 60 s; 180 s | 150 power/s, cyanogen 12/s | Quell, Disrupt |
| Mech Assembler | 200 carbide, 600 Th, 200 oxide, 550 W, 1000 Si | Tecta: 5 Merui + 12 large tungsten walls; Collaris: 6 Cleroi + 20 large carbide walls | 70 s; 180 s | 180 power/s, cyanogen 12/s | Tecta, Collaris |
| Basic Assembler Module (5x5) | 300 carbide, 500 Th, 250 oxide, 400 phase | | | 210 power/s | sets the assembler's tier: with no module it builds its first plan (tier 4 unit), with one module attached it builds the second plan (tier 5 unit) (`plan()` in `GH/core/src/mindustry/world/blocks/units/UnitAssembler.java`) |

Assemblers consume their cyanogen only while a unit is being built (comment in `UnitAssembler.shouldConsume`: "liquid is only consumed when building is being done") [S].

Unit stats (source, before the 0.75 damage multiplier) `GH/core/src/mindustry/content/UnitTypes.java#L2654-L4300`:

| Unit | Line / tier | Health | Armor | Speed | Movement | Notes from source |
|---|---|---|---|---|---|---|
| Stell | tank 1 | 850 | 6 | 0.75 | treads | targets air and ground |
| Locus | tank 2 | 2100 | 8 | 0.70 | treads | rail (hitscan, piercing) weapon, reload 18 ticks |
| Precept | tank 3 | 5000 | 11 | 0.64 | treads | |
| Vanquish | tank 4 | 11000 | 20 | 0.63 | treads | `crushDamage` |
| Conquer | tank 5 | 22000 | 26 | 0.48 | treads | `crushDamage` |
| Merui | mech 1 | 680 | 4 | 0.72 | legs, `hovering` | artillery; `targetAir = false` |
| Cleroi | mech 2 | 1100 | 5 | 0.60 | legs | has a point-defense weapon |
| Anthicus | mech 3 | 2700 | 7 | 0.65 | legs | fires missile units |
| Tecta | mech 4 | 6500 | 5 | 0.60 | legs | `ShieldArcAbility` |
| Collaris | mech 5 | 18000 | 9 | 1.10 | legs | artillery; `targetAir = false` |
| Elude | ship 1 | 600 | 1 | 1.8 | `hovering`, not flying | |
| Avert | ship 2 | 1100 | 3 | 2.0 | flying | |
| Obviate | ship 3 | 2300 | 6 | 1.8 | flying | lightning sub-bullet does not hit air |
| Quell | ship 4 | 6000 | 4 | 1.1 | flying | missile units; `targetAir = false`; payload 4x4 |
| Disrupt | ship 5 | 12000 | 9 | 1.0 | flying | missile units; `targetAir = false`; payload 6x6 |

### 1.5 Ratios: what the wiki quotes and what v160.7 arithmetic gives

| Ratio | Wiki statement [S] | v160.7 arithmetic [D] |
|---|---|---|
| Graphite for 1 Silicon Arc Furnace | "2 non-boosted Plasma Bores (0.75/s each, 0.3/s extra)" or 1 boosted bore (`FW/Silicon_Arc_Furnace`, edited 2025-07-10) | 1.2/s needed; 1.6 bores at full coverage. Agrees. |
| Sand for 1 furnace | "5 Cliff Crushers (1.09/s)", 7 at 0.76/s, 4 at 1.30/s, 3 at 1.63/s (same page) | 4.8/s needed; 4.4 crushers on attribute-1.0 walls. Agrees. |
| Power for 1 furnace | "2 Turbine Condensers", with the note "Power ratios have not been updated for b149" (same page) | furnace 300 (was 360 in v146) + 5 crushers 55 + 2 bores 18 = 373 vs 360 from 2 turbines: about 3.5% short. |
| Furnace output | 1 furnace feeds "2x Tank Fabricator + Fabricator of choice", or 2 fabricators + 1 refabricator, or 1 fabricator + refabricator + Prime Refabricator (same page); "1 Silicon Arc Furnace for 3 Fabricators, 0.5/sec excess" (`FW/Tank_Fabricator`) | Stell uses 1.43 Si/s and 1.14 Be/s; 4.8 / 1.43 = 3.4 tank fabricators. Agrees. |
| Beryllium for 1 Tank Fabricator | "3 tiles with unboosted Plasma Bores for 1 Fabricator, 0.015/sec deficit" (`FW/Tank_Fabricator`) | 3 x 0.375 = 1.125/s vs 1.143 needed. Agrees. |
| One duct | "8 boosted Plasma Bores (16 tiles) will effectively reach a duct's maximum throughput of 15 items/sec" (`FW/Plasma_Bore`) | 8 x 1.875 = 15. Agrees. |
| Electrolyzer water | 1 Turbine Condenser (5/s) runs 0.5 Electrolyzer; 1 Vent Condenser (30/s) runs 3 (`FW/Electrolyzer`, edited 2025-07-08) | 10 water/s each. Agrees. |
| Electrolyzer ozone | 4/s = "2 Oxidation Chambers" or "2 Chemical Combustion Chambers" (`FW/Electrolyzer`) | 2/s each. Agrees. Phase Synthesizer now takes 8/s = 2 electrolyzers (wiki's "200% Phase Synthesizer" per electrolyzer is the old scale). |
| Electrolyzer hydrogen | 6/s = "2 Refabricators of choice", "4 Reinforced Pumps", "12 Large Plasma Bores" (`FW/Electrolyzer`). Boosted bores: `FW/Electrolyzer` says 15 per 3/s, `FW/Turbine_Condenser` says 12 per 3/s (the two pages disagree) | refabricator 3/s, pump 1.5/s, large bore 0.5/s, bore boost 0.25/s -> 12 boosted bores per 3/s. Titan 5/s, Eruption Drill 4/s. |
| Electrolyzer + oxide + Titan | "Four Electrolyzers and eight Oxidation Chambers can continuously support five Titans with hydrogen and two with ammo ... while also producing 40 heat" (`FW/Titan`, edited 2026-09-27) | 24 hydrogen/s vs 25 needed; 4 oxide/s; 40 heat = exactly one Carbide or Surge Crucible at 100%. |
| Heat for nitrogen | 2 electric heaters = 4 N/s; 5 = 10 N/s "for Prime Refabricator"; 3 Slag Heaters (24) = 16 N/s (`FW/Electric_Heater`, `FW/Atmospheric_Concentrator`; percentages there are pre-v156) | 24 heat = 16 N/s; linear below. Prime Refabricator's 10 N/s needs 15 heat = 5 electric heaters (500 power/s), 3 oxidation chambers or 2 slag heaters. |
| Heat for Carbide / Surge Crucible | "10 [heaters] = 30 heat = 300%", "14 = 42 = 400% (2 units wasted)" (`FW/Electric_Heater`, edited 2025-08-14); slag heater "can cleanly reach 40 heat" (`FW/Oxidation_Chamber`) | 40 heat = 100% now: 14 electric heaters (1400 power/s), or 5 slag heaters (200 slag/s), or 8 oxidation chambers, or 3 phase heaters. |
| Heat for Cyanogen Synthesizer / Afflict | Afflict infobox: "20 heat units ... 100%" (`FW/Afflict`); its tip "180% (6 Electric Heaters)" is flagged outdated on the page itself | 20 heat: 7 electric heaters, 4 oxidation chambers or 3 slag heaters. |
| Heat for Phase Synthesizer | slag heater "can cleanly reach 32 heat" (`FW/Oxidation_Chamber`) | 32 heat: 4 slag heaters, 11 electric, 7 oxidation chambers or 3 phase heaters. |
| Heat for Malign | "60 Electric Heaters, 22.5 Slag Heaters, 12 Phase Heaters, or 3 Neoplasia Reactors" for 200% (`FW/Malign`, flagged not updated since b147.1) | 144 heat: 48 electric, 18 slag, 10 phase, or 3 Neoplasia Reactors (180). |
| Arkycite power | "One Electrolyzer can supply 2 Chemical Combustion Chambers with ozone, while also fuelling 4 Reinforced Pumps, each of which provide 80 arkycite per second" (`FW/Chemical_Combustion_Chamber`) | 2 chambers = 1100 power/s for 80 arkycite/s (one pump) and 4 ozone/s. |
| Fabricator : refabricator | Refabricator 30 s vs fabricator 35 s, so 1:1 costs "only a 5 second delay"; exact match is 7:6 (`FW/Tank_Refabricator`) | Agrees with source times. |
| Surge slag | not found in wiki | 160 slag/s = 2 reinforced pumps on 4 slag tiles each. |
| Sublimate ozone | "Ozone (15/sec)", "Cyanogen (18/sec)" (`FW/Sublimate`) | one electrolyzer's 4 ozone/s sustains about 27% firing duty; needs tanks as a buffer. |

### 1.6 Opening: what sources state

- [S] The Onset's scripted objective order is the nearest thing to an official opening: obtain 60 beryllium -> Turbine Condenser -> Plasma Bore -> Beam Node -> beryllium into the core by duct -> 200 beryllium -> 60 graphite -> 50 silicon -> Tank Fabricator -> build one Stell -> command units; enemy production starts only after that (`FW/The_Onset`, edited 2026-03-04).
- [S] "The basic resources are Beryllium and Graphite, the former of which is generally spent on building Turbine Condensers to power Plasma Bores for automated mining ... Vent numbers are more discrete ... meaning that power generation and spending are tighter in the early game." (`FW/Erekir`, edited 2026-10-02)
- [S] Campaign research prerequisites: Tank Fabricator needs Silicon Arc Furnace + Plasma Bore + Turbine Condenser; Breach needs Silicon Arc Furnace + Tank Fabricator (`GH/core/src/mindustry/content/ErekirTechTree.java`). Custom games and PvP are not bound by this.
- [S] "just build a tank factory AS SOON as you spawn in" because beryllium and silicon are near the core on most maps; spam beam nodes in a corner to store excess power, then run silicon furnaces for a jumpstart; "Coolant on Erekir actually does A LOT unlike serpulo" so water Breaches and Diffuses; chain duct bridges as ammo buffers (user KarinFysh, Steam, https://steamcommunity.com/app/1127400/discussions/0/3810657379793594901/).
- [S] Breach "are surprisingly expensive, so you should reinforce your mining before building many of them. Using other units like the Stell and Elude to defend is usually a better option for the early-game." (`FW/Breach`, edited 2026-09-07)
- [S] Tungsten step: "it is safer to pipe two turbines' water output to one Impact Drill for a tungsten source" than to replace turbines with Vent Condensers, which "effectively costs 210 power/sec" (`FW/Turbine_Condenser`, edited 2025-07-10).
- [S] First defence in a real sector: "at least a Breach and a Diffuse" on the nearest approach, Impact Drill for tungsten in the first wave, no early second core (`FW/Intersect`); "3 water-cooled Breaches and Large Beryllium Walls" at the choke (`FW/Basin`, edited 2026-05-24).

[D] Cost of the smallest honest "first Stell" economy: 2 Turbine Condensers (120 Be), 2 bores on beryllium + 2 on graphite (160 Be), 5 Cliff Crushers (125 graphite, 100 Be), Silicon Arc Furnace (70 Be, 80 graphite), Tank Fabricator (150 Be, 200 Si), first Stell (40 Be, 50 Si): about 640 Be + 205 graphite + 250 Si, plus ducts and nodes. Load with everything running is 300 + 55 + 36 + 90 = 481 power/s, so 3 vents are needed for full speed; with 2 vents (360) everything runs at about 75%. Once the furnace is fed, 250 silicon takes about 52 s and the Stell 35 s.

[I] Opening order consistent with all of the above (for a custom/PvP game with no research gating):

1. Turbine Condenser on the nearest vent.
2. Plasma Bores on beryllium wall ore, ducted to the core; then bores on graphite.
3. Second and third vents; 4-5 Cliff Crushers on the best sand wall in reach; Silicon Arc Furnace; silicon ducted to the core.
4. Tank Fabricator (or Ship Fabricator if the map is liquid-split) fed by splitting the core-bound beryllium and silicon lines; add fabricators before turrets (up to 3 per furnace).
5. Walls at chokes; one or two watered Breaches only where units cannot cover.
6. Impact Drill on tungsten floor ore using the water of two turbines; then Electrolyzer (needs 80 W) -> hydrogen to a Tank Refabricator (Locus) and bore boosting, ozone to an Oxidation Chamber.

### 1.7 Mid and late game progression

[S] Order in which the campaign tech tree unlocks things (`GH/core/src/mindustry/content/ErekirTechTree.java`; sector order onset -> aegis -> lake/intersect -> atlas -> split/basin -> marsh -> ravine/peaks -> caldera -> stronghold -> crevice -> siege -> crossroads -> karst -> origin):

| Stage | Unlock (sector that gates it) | New material or capability |
|---|---|---|
| 1 | Plasma Bore, Turbine Condenser, Beam Node, Cliff Crusher, Silicon Arc Furnace, Tank Fabricator, Breach (Onset) | beryllium, graphite, sand, silicon; Stell |
| 2 | Impact Drill, Vent Condenser, conduits, Radar (Aegis); Ship Fabricator, Diffuse (Lake); Mech Fabricator (Intersect) | tungsten, water; Elude, Merui |
| 3 | Electrolyzer, Tank Refabricator (Atlas); Mech Refabricator, Reinforced Pump, Chemical Combustion Chamber (Basin) | hydrogen, ozone, arkycite power; Locus, Cleroi |
| 4 | Oxidation Chamber, Sublimate (Marsh); Ship Refabricator, Regen Projector, Beam Tower (Peaks); Core: Citadel (after Peaks) | oxide; Avert |
| 5 | Surge Crucible, Heat Redirector, Electric Heater, Afflict (Ravine) | slag, heat, surge alloy |
| 6 | Slag Heater, Atmospheric Concentrator, Large Plasma Bore (Caldera) | nitrogen; tungsten and thorium wall ore |
| 7 | Eruption Drill, Titan, Disperse, Prime Refabricator, Build Tower (Stronghold) | thorium; Precept, Anthicus, Obviate |
| 8 | Carbide Crucible, Pyrolysis Generator, Lustre (Crevice) | carbide (the crucible itself costs 150 thorium, so thorium must come first) |
| 9 | Cyanogen Synthesizer, Scathe, Tank Assembler, Shockwave Tower (Siege); Core: Acropolis (after Siege) | cyanogen; Vanquish |
| 10 | Ship and Mech Assembler, Flux Reactor (Crossroads) | Quell, Tecta |
| 11 | Phase Synthesizer, Phase Heater, Smite, Neoplasia Reactor, tier 5 units (Karst); Malign and Assembler Module (after Karst) | phase fabric; Conquer, Disrupt, Collaris |

[D] So the material order is tungsten -> oxide -> surge -> nitrogen -> thorium -> carbide -> cyanogen -> phase (surge before thorium and carbide, not after).

Human advice for the transitions [S]:

- Atlas: "Expand your base and focus on manufacturing Loci" (`FW/Atlas`).
- Marsh: build defence first, then Chemical Combustion Chambers for power to expand beryllium mining (`FW/Marsh`).
- Ravine: "Use the Oxidation Chamber's heat output to run your Surge Crucible instead of the Electric Heater, to save power." "It is possible to get two Oxidation Chambers and one Electrolyzer running immediately after landing ... get an Afflict built 3 minutes before the first wave." (`FW/Ravine`, edited 2026-03-16)
- Heater choice: oxidation chambers give heat with "minimal power use" but "heat output stops if stalled" (oxide must be consumed); electric heaters are "space efficient" but "extremely power inefficient"; slag heaters "compete with Surge Crucible slag input" (`FW/Oxidation_Chamber`, edited 2026-04-01).
- Power safety: Vent Condenser water for ozone risks "a 'death spiral' brownout"; "Keeping two Turbine Condensers and an Electrolyzer away from the rest of your power network can help avoid this" (`FW/Chemical_Combustion_Chamber`).
- Nitrogen is expensive to keep running: Lustre and Unit Cargo Loader only consume it when active, so constant heating "will waste power" unless stored (`FW/Atmospheric_Concentrator`).
- Tier 4: Quell is preferred over Disrupt on cost because it needs carbide only for the factory, not per unit (`FW/Disrupt`).

### 1.8 Layout conventions described in words [S]

- Bore lasers pass over ducts and conduits but stop at solid blocks; "run either the liquid or conveyor line under the space where the lasers would hit to save space" (`FW/Plasma_Bore`).
- Electrolyzer outputs its two gases on opposite sides; rotate it to choose which side gets which (`FW/Electrolyzer`; source `liquidOutputDirections`).
- Heat producers emit from one marked side; oxidation chambers and slag heaters line up with the 3x3 Heat Redirector, electric heaters (2x2) do not (`FW/Oxidation_Chamber`).
- Split unit-factory inputs off the lines going to the core (`FW/Tank_Fabricator`). Fabricators and refabricators buffer twice one unit's cost (same page).
- Tanks cannot cross walls: "Blast Doors are necessary to let them enter and exit your base" (`FW/Stell`). Mechs walk over blocks; Eludes are "blocked by anything solid" but cross liquid (`FW/Elude`).
- Funnel ground units: "making one-block notches in your own walls that funnel tanks and Eludes" into a Diffuse (`FW/Diffuse`).
- Wall thickness: two layers of large walls stop Precept and Vanquish piercing shots; three for Conquer (`FW/Precept`, `FW/Vanquish`, `FW/Conquer`).
- Ammo buffers: Reinforced Containers for Diffuse, vault storage for Disperse (`FW/Diffuse`, `FW/Disperse`).
- Gas lines: ozone and cyanogen containers explode when destroyed; use Reinforced Bridge Conduits so fire does not travel along lines (`FW/Sublimate`).
- Base in walled pockets: "i made things in pockets with mass drivers" and "I had every section walled off with Breaches"; keep "a couple small convoys of 2-3 mechs" inside for Elude raids (user RiptheBlood, https://steamcommunity.com/app/1127400/discussions/0/601894472784113557, Jan 2025).
- Build Towers placed ahead of time shorten turret build times when air units appear (`FW/Obviate`).

---

## 2. Erekir PvP

### 2.1 How much exists

Erekir PvP is played, but very little is written about it. What I found:

- [S] One wiki section of three bullets, "Tips (Erekir)" (`FW/PvP`, edited 2026-05-28). The same page says its advice "applies to 1v1 games without plugins".
- [S] One video, "Mindustry Erekir PvP Guide 1" by Larofeticus, 14 min, published 2022-10-26 (https://www.youtube.com/watch?v=YDKLuA170_U). Its description now begins: "Update: This was made right when Erekir was first released. It currently only has value as an historical artifact; the game meta has advanced well beyond what we knew then." The description lists "Mindustry V7 (v139) Eviction playtest servers" for Erekir and Serpulo, and describes Eviction as "a Mindustry game mode based on hex war and balanced for competitive and individual focused PvP". I could not obtain the spoken content.
- [S] A few comments in a Jan 2023 Steam thread (https://steamcommunity.com/app/1127400/discussions/0/5562542086334775971/).
- [S] The unit and turret pages of the Fandom wiki contain "With / Against" sections that discuss human-vs-human use (for example `FW/Quell` mentions "common counter-plays against a Tier 4 rush"). These are the richest written source.
- [S] At Erekir's alpha release the developer notes said the new content had "not been properly tested in custom maps or PvP yet" (Build 136 notes as mirrored at https://planet.freegamedev.net/2022/07/16/build-136-erekir-alpha-release/; I saw this only through a search summary).
- [S] "On May 21, 2026, Anuke opened submissions for custom game maps for Erekir ... Maps can support survival, attack, or PvP gamemodes. PvP maps must be mirrored." (`FW/Future_Content`, edited 2026-06-01)
- [D] In v160.7 the bundled custom-map folder lists 19 maps with Serpulo-era names (including glacier, passage and veins, which `FW/PvP` names as Serpulo's 3 built-in PvP maps) and the `erekir` folder lists only the 17 campaign sector maps (https://github.com/Anuken/Mindustry/tree/v160.7/core/assets/maps/default, `.../maps/erekir`; GitHub may have truncated the listings). [I] So there appears to be no bundled Erekir PvP map yet.
- Not found: tournaments, written build orders, replays with commentary, Reddit threads (Reddit could not be searched), any current Erekir PvP server. The official server list (https://github.com/Anuken/MindustryServerList, `servers_v8.json`, 91 groups) gives names and addresses only, not game modes; I did not query servers.

### 2.2 What wins, compositions and counters

Stated strategy [S]:

- "In all situations, the Locus dominates the early-game and rushing them allows an easy victory provided that the enemy does not tech up to Thorium and begin getting Titans. Otherwise, the tank line is generally inferior to other units of the same tier." (`FW/PvP`)
- "Using units to defend is key to surviving early on, as early-game turrets are expensive and inefficient." (`FW/PvP`)
- "Anthicus and Quell are good wallbreakers due to the unique unit-like quality of their missiles, being able to negate the damage output of most turrets. Afflict is able to absorb them better than others." (`FW/PvP`)
- "Erekir pvp usually feels more fun, especially past the very early game"; early-game turrets feel "like a bit of a noob trap"; "most of your production should go into units"; maps on most servers are "usually very very bad (just walls or absurd amount of neutral defenses lul)" (user Underscorecow, Steam, 2023-01-19).
- 2022 view, declared obsolete by its author: "Where v6 Eviction had dagger spam, Erekir will have Elude rush. Learn to defend it or lose every game." (video description above)
- Tier 1 triangle: "Eludes have high mobility to dodge the slow-moving attacks from the Merui. Meruis have high area damage and range that helps against large groups of Stell. Stells have 6 armor, meaning Eludes deal half of their effective damage against it." (`FW/Elude`, edited 2026-04-16)

Counter table (all [S] from the Fandom page named in the row; edit dates 2025-04 to 2026-09):

| Unit | Good at | Countered by | Page |
|---|---|---|---|
| Stell | beating other tier 1 with walls; knocking out turrets or their supply lines; "build more fabricators to overwhelm the enemy with sheer numbers" | walls delay them; Breaches behind walls; "Once the Titan or Afflict comes into play, attacking with just tier 1s alone is infeasible" | `FW/Stell` |
| Elude | fast defence and raids; produced alongside Avert without any beryllium line | walling chokes ("once one gets in your base is in severe trouble"); Stell armor | `FW/Elude` |
| Merui | artillery passes over blocks, so it severs turret ammo better than Stell or Elude | fragile; fabricator needs tungsten; Breaches, Diffuse when they walk in; cannot target air, so ozone-container bombing works | `FW/Merui` |
| Locus | "highly reliable, durable, and spammable"; pure Locus army is fine early; hitscan pierce ignores Cleroi point defense; shoots down missiles when massed | ozone Sublimate kills one in 4 s, cyanogen in 2 s; Titan on clumps; Obviate; mixed Locus + Cleroi | `FW/Locus` |
| Cleroi | deathballs with mutual point defense; works "excellently against the Avert and Obviate" | Locus (rail is not intercepted); Diffuse and ozone Sublimate; Afflict ("not safe to attack against") | `FW/Cleroi` |
| Avert | scouting, harassment of lightly defended parts, sniping a Disperse by moving in close first | every turret outranges it; Disperse; Locus and Cleroi | `FW/Avert` |
| Precept | front line; 11 armor resists all lower tiers | high raw damage (Sublimate, Titan, Lustre); Obviate sparks; missile spam; not Diffuse or Afflict | `FW/Precept` |
| Anthicus | siege by missile saturation; "directly counters the entire ship line, as well as the Precept, Vanquish and Conquer" | cyanogen Sublimate, Afflict, Locus, Tecta; Disperse stops the missiles but not the mech | `FW/Anthicus` |
| Obviate | hit-and-run on walls, unguarded production, areas near the core; hard-counters Quell, Disrupt, Collaris (none target air) | Disperse; two Clerois neutralize one Obviate's attacks | `FW/Obviate`, `FW/Cleroi` |
| Vanquish | 20 armor shuts down all tier 1-2 fire; crush damage against walls ("Ram" stance) | Lustre, Smite; Obviate sparks ignore armor; Anthicus surround; Diffuse knockback (3 watered Diffuses hold one off a double wall) | `FW/Vanquish` |
| Tecta | shield arc absorbs area and piercing shots; shoots down missiles | Afflict, Lustre; Averts and Obviates from the side | `FW/Tecta` |
| Quell | "very cheap units that outperform all other tier 4s on offense"; 44-block range; missiles absorb turret fire; can airlift ground units | Disperse; Obviates or even Averts; a lone Quell is "dead in the water" | `FW/Quell` |
| Conquer, Collaris, Disrupt | Conquer breaches walls and can be dropped by a Disrupt; Collaris outranges almost everything when controlled by hand | Smite, Malign; Obviates (Collaris, Quell, Disrupt cannot hit air); Shockwave Towers stop Collaris shells | `FW/Conquer`, `FW/Collaris`, `FW/Disrupt` |

Turret-versus-unit summary is in section 3.

### 2.3 What gets attacked first

- [S] Supply rather than the turret: "just throw them at the enemy and knock out any turrets, or supply lines for turrets to cut them off" (`FW/Stell`); "If you can take out the transportation blocks supplying it, the fast firing rate will eat all of the ammo inside the block" (`FW/Diffuse`); "aim to knock out the conduit lines going into it, and hope for a chain reaction" (`FW/Sublimate`).
- [S] Power and heat for item-less turrets: Afflicts "are turned off as soon as all nodes or heating are cut - target them" (`FW/Afflict`).
- [S] Specific logistics: "prioritize any vulnerable tungsten-carrying blocks, to disable Disperses"; "Arkycite supply lines are also important targets, as every power generator besides the Turbine Condenser needs it in some form" (`FW/Attack`, edited 2025-08-25).
- [S] Ammo draining: shoot from the edge of Disperse range so it wastes ammo (`FW/Disperse`); drop walls near turret lines so they empty themselves (`FW/Attack`; user Vigilante in the 2023 Steam thread).
- [S] Economy and core raids by air: Avert "harassment against lightly-defended sections", reaching "economy blocks" (`FW/Avert`); Obviate targets are "walls, unguarded production buildings, and areas near the enemy Core" (`FW/Obviate`); bases with "a glaring vulnerability to core snipes by Averts" (`FW/Stronghold`).
- [S] Observed from the campaign AI: "Enemy Elude units love to take out power or go straight to your core" (RiptheBlood, Steam, Jan 2025); "Averts destroy Beryllium supplies from out of turret range" (PLMMJ, Steam, Jul 2023).
- [S] Core-unit tricks: drop a Reinforced Liquid Container of ozone onto clumped units or defences; carry a payload to become targetable and distract a Disperse (`FW/Attack`, `FW/Merui`, `FW/Quell`).
- [S] The game's own RTS AI considers these building classes as attack targets: `generator, factory, core, battery, drill` (`flags` array in `GH/core/src/mindustry/ai/RtsAI.java`); plasma bores and cliff crushers carry the `drill` flag, crafters `factory`, generators `generator`.

### 2.4 How players defend

- [S] Units first, turrets second (`FW/PvP`, `FW/Breach`, Underscorecow).
- [S] Walls at chokes plus a few watered Breaches; water gives the Breach and Diffuse "250% fire rate" for 15 water/s (`FW/Breach`, `FW/Diffuse` infoboxes; `coolantMultiplier` in source).
- [S] Ozone Sublimate where ground units line up; Disperse for air (the "only dedicated anti-air turret"); Afflict as the generalist once surge alloy exists; Titan once thorium exists (`FW/Sublimate`, `FW/Disperse`, `FW/Afflict`, `FW/Titan`).
- [S] Against missiles: cyanogen Sublimate, Afflict, Disperse, massed Locus or Tecta; "spam Ducts in front of the missiles to cheaply absorb" them (`FW/Anthicus`).
- [S] Against Scathe: Regen Projectors, Shielded Walls, static point defence (`FW/Scathe`).
- [S] Air units arrive "in unexpected locations", which "speaks to the necessity of having a bunch of units on defense instead of only relying on turrets" (`FW/Avert`).

### 2.5 How fast units come out

- Not found: any measured PvP timing.
- [S] Proxy, campaign speedruns ("Sector Clear" category; the runs are dated 2023, which [I] means v7 balance): Aegis in 4:41.11 and Lake in 7:29.65 by Yorbil, The Onset in 11:23.10 by Yorbil (https://www.speedrun.com/mindustry/runs/y98w1vrz, https://www.speedrun.com/mindustry/runs/yongvv5z, https://www.speedrun.com/mindustry/runs/zx3072qz). Aegis starts with 300 beryllium + 300 graphite and one enemy Bastion; The Onset starts with nothing and is objective-gated (`FW/Aegis`, `FW/The_Onset`).
- [I] So a strong player has enough tier 1 units to kill a lightly defended Bastion base in under 5 minutes from a 300/300 start, and first units within roughly 2-4 minutes. The cost arithmetic in 1.6 is consistent with that.

---

## 3. Erekir turret roles

Needs and ranges: source v160.7 (`GH/core/src/mindustry/content/Blocks.java`, line given). Range in tiles = source `range` / 8. Roles: Fandom page for each turret.

| Turret (line) | Size | Build cost [S] | Needs [S] | Optional water [S] | Targets [S] | Range [D] | Role as the wiki states it [S] |
|---|---|---|---|---|---|---|---|
| Breach (L4378) | 3x3 | 150 Be, 150 Si, 125 graphite | item ammo, 2 per shot: beryllium; tungsten (+5 tiles, 4 pierce); carbide (+7 tiles, 3 frags, slow fire) | 15/s | air + ground | 23.75 | First turret, piercing shots; holds "until tier 3 units begin showing up" on beryllium; expensive for its stage; tungsten ammo pierces tanks to hit mechs behind (`FW/Breach`) |
| Diffuse (L4490) | 3x3 | 150 Be, 200 Si, 200 graphite, 50 W | item ammo, 3 per shot: graphite (most knockback); silicon (homing, weak against armor); oxide (most damage) | 15/s | air + ground | 15.6 | Short-range shotgun for chokes; hard-counters Merui and Cleroi that walk over it; outranged by Stell and Merui, and by Anthicus, Quell, Disrupt, Obviate; high ammo use (`FW/Diffuse`) |
| Sublimate (L4580) | 3x3 | 150 W, 200 Si, 40 oxide, 400 Be | liquid ammo while firing: ozone 15/s, or cyanogen 18/s (+8.7 tiles, 3 pierce). No items, no power | none | air + ground | 16.25 (25 on cyanogen) | Continuous armor-piercing flame: 540 damage/s on ozone, 1170 on cyanogen; shreds tier 1 groups, kills tanks regardless of armor, counters Cleroi blobs; all tier 1 units outrange it on ozone (`FW/Sublimate`) |
| Titan (L4666) | 4x4 | 250 W, 300 Si, 400 Th | item ammo, 4 per shot: thorium (all-round); oxide (largest radius); carbide (+10 tiles, frags); plus hydrogen 5/s | 30/s | ground only | 48.75 | Long-range artillery against groups of small units, like Serpulo's Ripple; shells are not absorbed by Anthicus missiles; the answer to a Locus rush (`FW/Titan`, `FW/PvP`) |
| Disperse (L4873) | 4x4 | 50 Th, 50 oxide, 200 Si, 350 Be | item ammo: tungsten; thorium; silicon (homing); surge alloy (+3 tiles, lightning) | 20/s | air only | 38.75 | "the only dedicated anti-air turret on Erekir"; also shoots down missile units; burns ammo fast, needs storage (`FW/Disperse`) |
| Afflict (L5057) | 4x4 | 100 surge, 200 Si, 250 graphite, 40 oxide | power 300/s + 20 heat; no items | none | air + ground | 46 | Generalist; fragments reach past its nominal range and detonate missiles early; handles units below tier 4; dies when power or heat is cut (`FW/Afflict`; its tips predate the b148 and b155 changes, as the page itself notes) |
| Lustre (L5180) | 4x4 | 250 Si, 200 graphite, 50 oxide, 90 carbide | power 200/s + nitrogen 6/s while firing | none | air + ground | 31.25 | Very high single-target damage, picks the strongest unit (`UnitSorts.strongest`); "extremely vulnerable to swarms", poor against missiles and Collaris, slow to turn (`FW/Lustre`, edited 2025-08-04) |
| Scathe (L5245) | 4x4 | 450 Si, 400 graphite, 500 W, 100 oxide, 200 carbide | item ammo, 15 per shot, one shot per 10 s: carbide; phase fabric (shielded missile, largest blast); surge alloy | 15/s | ground only | 168.75 | Cruise missiles that behave as units (can be shot down); reveals only 40% of its range in fog; heavy cost for one launcher; hits ducts behind walls (`FW/Scathe`, tips predate b148) |
| Smite (L5689) | 5x5 | 200 oxide, 400 surge, 800 Si, 500 carbide, 300 phase | item ammo: surge alloy, 2 per shot | 15/s | air + ground | about 37.5 | Bursts of ten piercing lightning bolts, about 2000 damage per round; best against single large targets, also clears lines of small units (`FW/Smite`) |
| Malign (L5959) | 5x5 | 200 carbide, 1000 Be, 500 Si, 500 graphite, 200 phase | power 2400/s + 144 heat; no items | none | air + ground | 51.25 (wiki: 47.5) | Final turret; outstanding damage but "less practical than the Smite" because of its heat; can be swamped by Quell swarms (`FW/Malign`, tips predate b147.1) |

Support blocks worth knowing [S] (`Blocks.java#L1990-L2046`, `#L6612`): Radar (60 Si, 50 graphite, 10 Be; 36 power/s); Regen Projector (hydrogen 1/s, 60 power/s); Build Tower (nitrogen 3/s, 180 power/s, range 25 tiles); Shockwave Tower (cyanogen 1.5/s, 100 power/s); Unit Repair Tower (ozone 3/s, 60 power/s). Walls: beryllium 520 health/tile, armor 2; tungsten 720, armor 14; carbide 1080, armor 16; reinforced surge 1000, armor 20 (`Blocks.java#L1836-L1905`, `wallHealthMultiplier = 4`).

---

## 4. Prior art: AI that builds bases in Mindustry

### 4.1 Inside the game (v160.7)

| Component | What it does [S] | Limits [S unless marked] |
|---|---|---|
| `BaseBuilderAI` (`GH/core/src/mindustry/ai/BaseBuilderAI.java`), enabled by team rule `buildAi` | Every 2-12 ticks (by `buildAiTier`), up to 6 tries: pick a random tile within 150 tiles of a random own core; if the tile has an ore with matching base parts, pick a random one of those, else with 1% chance any part; rotate it randomly; accept only if every block passes `Build.validPlace`, drills sit only on the required ore, and no solid block lies on the precomputed ground path from spawn to the enemy core; then queue the blocks as team build plans for builder units. Spawns free core units every 6 s. | Resources are fake: every update it sets each item in the first core to its maximum (`core.items.set(item, core.getMaximumAccepted(item))`). The author's own comment on the selection step: "pick a completely random base part, and place it a random location ((yes, very intelligent))". No build order, no logistics between parts, no reaction to the opponent. Drill check is `instanceof Drill` only, so beam drills, cliff crushers and vent buildings are not understood [D]. |
| `BaseRegistry` and base parts (`GH/core/src/mindustry/ai/BaseRegistry.java`, `GH/core/assets/baseparts`) | 210 bundled schematics. A part's required resource is declared by an Item Source or Liquid Source block inside the schematic; `tier` is a build-time sum used for difficulty sorting; the anchor is the average drill or pump position. | Wall ores are skipped when the ore table is built (`!ore.wallOre`). Named parts are all Serpulo content (dagger, flare, atrax, salvo, ripple, RTG, thorium reactor, foundation, nucleus) [D from file names]. [I] Nothing in it is usable for Erekir. |
| `BaseGenerator` (`GH/core/src/mindustry/maps/generators/BaseGenerator.java`) | World generation for Serpulo enemy sectors: core schematic by difficulty, parts stamped on ore tiles with probability scaled by difficulty, then automatic walls on the side facing the spawn, then an A* path from each core to the spawn is cleared. | Fills the core with every item; can paint ore under drills to make parts fit. Generation-time only. |
| `RtsAI` (`GH/core/src/mindustry/ai/RtsAI.java`), team rule `rtsAi`, on by default in the Erekir campaign (`campaignRuleDefaults.rtsAI = true` in `GH/core/src/mindustry/content/Planets.java`) | Every 2 s: flood-fills idle commandable units into squads (60-unit radius); if a building was damaged, the nearest squad (or all, for a core) goes to the aggressor; otherwise a squad of at least `rtsMinSquad` (4) picks among up to 15 random enemy generators, factories, cores, batteries and drills the one with the best `estimateStats` weight, where weight = (own health / enemy DPS along the straight path) / (enemy health / own DPS), and attacks only if weight >= `rtsMinWeight` (1.2), or the squad reaches `rtsMaxSquad` (50), or the unit cap is hit. | Unit control only; builds nothing. Source comments: "TODO atrocious implementation", "TODO extremely slow especially with many squads". Uses a straight segment, not the real path. |
| `PrebuildAI` (`GH/core/src/mindustry/ai/types/PrebuildAI.java`), team rule `prebuildAi` | Closest thing to an honest-economy builder: core units work through the team's plan queue (a pre-authored base stored as plans), choosing by distance minus a category priority (production 11, distribution 10, liquid 9, crafting 8), only building blocks adjacent to something already built (or production blocks), and when the core cannot afford a block the unit goes and mines the missing item by hand (floor or wall ore) and delivers it. | Rule comment: "EXPERIMENTAL, DO NOT USE: Pre-built base AI. Gives the illusion of intelligent design of pre-building an attack base." Class comment: "don't use this yet, it's not functional!". The layout is fixed in advance; it decides order, not design. |
| `BuilderAI` (`GH/core/src/mindustry/ai/types/BuilderAI.java`) | Rebuilds destroyed blocks from team plans (every 10 ticks under `buildAi`), assists other builders, retreats from enemies. | Rebuild only. |
| Erekir campaign enemy | Pre-authored bases with unit factories, RTS AI, block rebuilding, timed production escalation and World Processors (`FW/Attack`); `enemyBuildSpeedMultiplier = 0.4` on Erekir (`Planets.java`). | Does not expand or build an economy. |

History [S]: the same random-part algorithm was `BaseAI` in v126.2 and v135 (identical "very intelligent" comment); the core-filling line is present by v146 (`.../v146/core/src/mindustry/ai/BaseBuilderAI.java`). Base parts were last revised in Feb-May 2025 (commits "More base schematics", "added core baseparts (#10760)").

Community view of the limits [S]: builder AI "can only make flare, crawlers and Atrax", "can only make Serpulo schematics", and amounts to "spam schematics randomly"; the request lists Erekir support, dependency gating and conveyor routing as missing (https://github.com/Anuken/Mindustry-Suggestions/issues/6511, Aug 2026, closed by the stale bot, no maintainer reply). "it's unfair that the AI can place surge towers shortly after a match starts"; request that enemies "set up drills and move resources to the core" (https://github.com/Anuken/Mindustry-Suggestions/issues/6314, May 2026, same outcome).

### 4.2 Official mod list

https://raw.githubusercontent.com/Anuken/MindustryMods/master/mods.json, fetched 2026-10-11: 925 entries. I searched name, description, repo and internal name (English, plus common Chinese and Russian terms for enemy, AI, build, bot).

- `rts ai | build ai | base builder | base parts`: 0 hits.
- Erekir together with ai/enemy/rts/bot/opponent: 1 hit, irrelevant (a shop mod).
- `pve | bot player | ai player | ai opponent | enemy ai | enemy builds/expands ...`: 1 hit, irrelevant (Viridium, faction-locked PvP tech trees).
- **Result: no listed mod has an enemy that expands with a real economy, on Erekir or Serpulo.** This confirms your earlier search.

Nearest neighbours [S, from each description or README]:

| Mod | What it is | Why it is not what you want |
|---|---|---|
| `fallendraggon/miningaimode` (https://github.com/fallendraggon/miningaimode) | Player-side miner manager: distributes miner units by core stock quotas, "crisis" rebalancing, avoids ore under enemy turret fire | Serpulo units; assists the player; no building |
| `00sunray00/scheme-size-port` and forks | QoL toolkit with "AI support" for the player's own unit | Player automation |
| `beonicwulf/dynamics` (https://github.com/beonicwulf/dynamics) | New planet whose lore says an enemy faction is "developing rapidly" | README and TODO show a content mod; no AI system described |
| `sh1penfire/meld` (https://github.com/sh1penfire/meld) | "An experimental RTS mod"; its maps use World Processors, which the author says "have proven to be a pain to maintain across multiple maps" | Content and combat mod; no base-building AI described |
| `nadeeshafdo/aiassistmod` | Gemini-based adviser | Advice text only |
| `abdullah-ajeebi/pause-enemy`, `darksurvival42-ux/harder-eradication` | Enemy pause / stat multipliers | Not AI |

### 4.3 Other projects (GitHub searches that completed)

| Project | Approach [S] | State and limits [S] |
|---|---|---|
| "Mindustry Ranked" server AI; schematic set at https://github.com/LeoSko/MindustryRankedAISchematics (2021) | Prefab schematics, each named with a difficulty number 00-99; the bot's difficulty grows over time and it draws schematics from a normal distribution around it (sigma 4); a schematic may need one ore or liquid, declared by an Item/Liquid Source; "bot will try to place walls itself" | "Bot's containers and vaults have spawning resources", so the economy is not honest. Plugin code not public (only schematics repo found). Serpulo, v6 era. |
| `ThePythonGuy3/BetterAI` (https://github.com/ThePythonGuy3/BetterAI) | Stated aim: "make enemy AI act like the player" | README: "This mod is in development and it does nothing as of now." Last push 2025-03. |
| `GamerKilroy/Mindustry-Logic` (https://github.com/GamerKilroy/Mindustry-Logic) | Enemy unit and squad AI built from World Processors with a shared-memory "OpenBus" | Units and squads only; design document stage (2026-08 to 2026-10); notes that plain memory cells being number-only "quickly proved it the wrong way to go" |
| `Ilya246/assistBots` (https://github.com/Ilya246/assistBots) | v7 server plugin adding bot units that shoot, mine or build (poly behavior) | Helpers, not a base planner |
| `misha275/mindustry-bot` (https://github.com/misha275/mindustry-bot) | Headless client for v160.5 driven over a local HTTP bridge by an LLM agent; commands for build, schematic place, mine, unit orders | The planner is an external LLM; no built-in strategy |
| `Lambda14/mindustry_ai`, `ZenthXSin/eve-assistant`, `DeterMination-Wind/Mindustry-MCP` | REST/HTTP/MCP bridges exposing game state and actions to external agents | Infrastructure only |
| `RaptorNava/ReinforcementLearning_MindustryMod` (https://github.com/RaptorNava/ReinforcementLearning_MindustryMod) | PPO + LSTM agent that learns to mine copper and lay conveyors; trains in a Python simulator because the real game gives "~20" episodes per hour | Copper-to-core only |
| `yamakajump/mindustry-ai` (https://github.com/yamakajump/mindustry-ai) | RL environment for v8 | README: "Early development. No agent plays the game yet."; reports 592x realtime per headless instance on an empty map; notes the engine is single-threaded |
| `Maksim426/Auto-base-AI-bot` | Name only | No README; not inspected further |
| Logic-processor builders | v126 added unit-build logic instructions and a `logicUnitBuild` rule (release notes via https://newreleases.io/project/github/Anuken/Mindustry/release/v126, seen through a search summary) | No specific base-building logic project found. On Erekir campaign maps "Logic is off-limits" (`FW/Attack`) |

### 4.4 Pattern across all of it [I]

- Every working builder uses **prefab pieces placed by a simple rule** (random near core, ore match, difficulty rank). None plans logistics between pieces; each piece must be self-contained, which is why they lean on item sources, filled cores or resource-spawning containers.
- **Honest resources are the unsolved part.** The only code that pays real costs is `PrebuildAI`, and it avoids the design problem by replaying a human-authored layout.
- Erekir adds constraints the existing code does not model: wall ore reached by facing beams, vents as fixed 3x3 sites, directional heat, two-sided gas outputs, and power by line-of-sight beam nodes.
- Search and learning approaches exist only as early experiments.

---

## 5. General technique: where to place buildings and what to build next

Only items that transfer to a tile grid with fixed resource sites. I opened the pages for Churchill and Buro, OpenRA, BWEB, BWEM, Halldorsson and the Game AI Pro chapter; the Perkins, Barriga and Petra entries rest on search-result summaries of the linked pages.

| Topic | Reference | What to take from it |
|---|---|---|
| What to build next (search) | Churchill and Buro, "Build Order Optimization in StarCraft", AIIDE 2011, https://ojs.aaai.org/index.php/AIIDE/article/view/12435 | [S] Finds action sequences that produce a goal set of units and structures in the shortest time under prerequisite and resource constraints, using abstractions and heuristics to run in real time. [I] For a factory game the "goal" is a rate target (for example 4.8 silicon/s) and the same backward expansion over prerequisites gives the list of modules still missing. |
| What to build next (rules) | OpenRA `BaseBuilderBotModule`, https://github.com/OpenRA/OpenRA/blob/bleed/OpenRA.Mods.Common/Traits/BotModules/BaseBuilderBotModule.cs | [S] Buildings are grouped by role (refinery, power, production, defense); the bot keeps target fractions and limits per type, a minimum excess power that grows with base size, a minimum refinery count before production, a cash threshold before adding production, and pauses after repeated failed placements. [I] Maps directly onto vents, bores, furnaces and fabricators. |
| Spending priorities | 0 A.D. Petra bot, https://trac.wildfiregames.com/wiki/PetraBot (page blocked for me; content from a search summary) | [S] A headquarters module decides, a queue manager allocates resources across queues with adjustable priorities; "the next item in a queue will block everything following it", hence many separate queues. [I] Prevents an expensive item from starving cheap urgent ones, and gives a place to raise defence priority under attack. |
| Prefab placement | BWEB, https://github.com/Cmccrave/BWEB | [S] "Blocks" are groups of building spots of varying sizes fitted into each map area "without trapping units"; "Walls" are found by trying building combinations and testing path length with BFS, with a reserved path and optional door; "Stations" put defense positions at each base. [I] The reserved-path idea is the same as `BaseBuilderAI`'s forbidden path set; keep it. |
| Regions and chokes | BWEM, https://bwem.sourceforge.net/faq.html | [S] Give each walkable tile an altitude = distance to the nearest unwalkable tile; visit tiles from highest to lowest, each one starting a new area, joining a neighbour or merging two; choke points fall out where areas meet. About 100 ms on competition maps. [I] Cheap enough to run once per map on Mindustry's tile grid; chokes are where walls, Diffuses and Sublimates go. |
| Regions and chokes (alternatives) | Perkins, AIIDE 2010, https://ojs.aaai.org/index.php/AIIDE/article/view/12405; Halldorsson and Bjornsson, AIIDE 2015, https://ojs.aaai.org/index.php/AIIDE/article/view/12796 | [S] Perkins: polygonal regions with choke points between them, found 97% of the chokes humans marked (basis of BWTA). Halldorsson: flooding-based decomposition into regions comparable to human labeling. |
| Threat and safety maps | Dave Mark, "Modular Tactical Influence Maps", Game AI Pro 2 ch. 30, http://www.gameaipro.com/GameAIPro2/GameAIPro2_Chapter30_Modular_Tactical_Influence_Maps.pdf | [S] A grid where each agent adds influence that falls off with distance through a response curve (linear or polynomial); allied positive and enemy negative maps sum to show the front and the neutral zone; the useful queries are often negative ones ("Where can they not hit?"). [I] Stamp turret ranges and observed enemy unit paths; place economy in low-threat cells and turrets on the gradient. |
| Defence placement by simulation | Barriga, Stanescu, Buro, "Building Placement Optimization in Real-Time Strategy Games", https://ojs.aaai.org/index.php/AIIDE/article/view/12735 | [S] A genetic algorithm proposes placements and scores them by simulating base assaults; results depend heavily on predicting the attacker's army. [I] Too heavy to run live in a mod; usable offline to tune prefab defence pieces. |
| Fight or wait | `RtsAI.estimateStats` in the game itself (4.1) | [S] Ratio of time-to-kill each way along the approach. [I] Reuse as is for attack decisions; replace the straight segment with the flow-field path. |

Practical notes for a tile-based factory game [I]:

1. Start from sites, not from free space: vents, wall-ore faces within beam range, sand walls, floor ore, liquid pools and core zones are fixed, and each admits only a few legal footprints. Enumerate them once per map and score them (distance to core, threat, yield).
2. Make prefabs with declared ports (item in/out tiles, liquid sides, heat side, power point), then connect ports with a grid path search for ducts and conduits. The existing Mindustry prefabs avoid this step and that is why they need fake resources.
3. Reserve corridors before placing anything: own unit exits, the enemy's ground flow-field path, and duct trunk lines.
4. Drive "what next" from measured rates rather than stock: compare required and actual per-second flows for each item, gas, power and heat (section 1 tables) and build the module that fixes the largest shortfall; keep an OpenRA-style excess-power margin because Erekir power is vent-limited.
5. Keep several spending queues with priorities (economy, units, defence, tech) so that a 400-thorium turret does not block bores and ducts.

---

## 6. Not found, and open disagreements

- Not found: a written Erekir PvP build order; any measured "first unit at minute X" for PvP; Erekir PvP tournaments or current servers; the content of the 2022 Larofeticus video beyond its description; Reddit material (site not searchable from here); the text of the Steam guide "The Turrets of Erekir: A Defense Guide" (https://steamcommunity.com/sharedfiles/filedetails/?id=2968050682, HTTP 429); source code of the Mindustry Ranked AI plugin; any logic-processor base builder project.
- Not checked: whether any of the 210 bundled base-part schematics contain Erekir blocks (they are binary; only file names were read); server game modes in the official server list.
- Disagreements between sources: Malign range and heat (wiki 47.5 tiles, 72/144 heat; source 51.25 tiles, 144 heat at 100%); Carbide Crucible heat (wikis 10; source 40); boosted plasma bores per 3 hydrogen/s (wiki pages say 12 and 15; source gives 12); furnace power (wiki ratio table predates 360 -> 300 power/s).
- Several wiki tip sections are flagged by their own authors as outdated: Breach (no carbide ammo), Afflict (b148, b155), Scathe (b148), Malign (b147.1), Merui and Cleroi (b149), Disperse (recency note for 147).
- Steam forum quotes reached me through a page-summarizing tool that reported them as quotations; wording should be rechecked at the links before being relied on verbatim.
