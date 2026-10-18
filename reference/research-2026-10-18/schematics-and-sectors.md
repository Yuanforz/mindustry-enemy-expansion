# Erekir schematics and sector walkthroughs: research for the base-AI rewrite

Collected 2026-10-18 for `mindustry-enemy-expansion` (game v160.7). Covers what was found, where it came from, which layout patterns repeat, and what the AI's blueprint library should take from them. I treated all downloaded schematics as data. I decoded them and drew them with `tools/run.sh schematic`, and ran nothing from them.

Legend for the text renders (from `tools/src/tools/Ascii.java`): two characters per tile, a block code followed by its facing (`> ^ < v`) on the block's centre tile and `.` on its other tiles.

| Code | Block |
|---|---|
| `F` | silicon arc furnace |
| `M` | any fabricator |
| `m` | any refabricator |
| `w` | prime refabricator |
| `P` | plasma bore |
| `Q` | large plasma bore |
| `I` | impact drill |
| `C` / `c` | cliff crusher / large cliff crusher |
| `e` | electrolyzer |
| `o` | oxidation chamber |
| `G` | chemical combustion chamber |
| `W` | vent condenser |
| `T` | turbine condenser |
| `d` | duct |
| `R` | duct router |
| `B` | duct bridge |
| `L` | duct unloader |
| `U` / `O` | underflow / overflow duct |
| `K` | reinforced container |
| `~` | conduit |
| `b` | bridge conduit |
| `r` | liquid router |
| `j` | liquid junction |
| `l` | liquid container |
| `n` | beam node |
| `1`–`6` | Breach, Diffuse, Sublimate, Titan, Disperse, Afflict |
| `#` | wall |
| `?` | anything else, mostly item/liquid sources that mark the schematic's inputs |

`M` covers all three fabricators; the legend line under each render says which kind it is.

---

## Part A: schematics

### A.1 Where Erekir schematics are shared, and what I could get

| Source | What is there | Result |
|---|---|---|
| **Design-It Discord dump**, https://github.com/MindustryDesignIt/design-it (bot-updated; last commit 2026-10-17) | The curated schematic channel of the biggest design server. Erekir folders: `erekir-units` 109, `oxide` 37, `erekir-surge` 28, `erekir-phase` 25, `erekir-defensive-outpost` 23, `carbide` 16, `bore` 15, `nitrogen` 14, `cyanogen` 13, `silicon-arc` 12, `pyrolisis-gen` 10, `ccc` 10, `unit-delivery` 9, `electrolyzer` 3 | **Best source.** I sparse-cloned and rendered 324 files, none unreadable. Authors include reverie, Tudr, Sleapy, xnuui and summer_dayrise. |
| **MindustryTool**, https://www.mindustry-tool.com (API `https://api.mindustry-tool.com/api/v4/schematics?tags=planet_erekir&sort=download-count_desc`, data at `/api/v4/schematics/{itemId}/data`) | 12,701 schematics in total, but only **88 tagged Erekir** | Downloaded all 88; 84 rendered and 4 were not schematic files. Most are the same Design-It designs, re-uploaded 2025-09-18. Most downloaded: "Large Oxide" 242, "8 unit cargo loader" 200, "T1 Stell 10x" 24. |
| **mindustryschematics.com** (`/?query=erekir`, files at `/schematics/{id}.msch`) | 3,029 schematics, mostly Serpulo; 47 match "erekir" | Downloaded and rendered 46. Dates from the ObjectIds run from 2023-01 to 2026-07. |
| **Chinese QQ-sheet archive**, https://github.com/MinRi2/schematics-archives (last commit 2025-09-05) | `兵厂(E)` (Erekir unit factories) 87, `电弧硅(E)` (arc silicon) 26, `布(E)` (phase fabric) 33, and others | Rendered 131. Many "Erekir" arc and defence entries are **mixtech**: they use Serpulo unloaders, vaults and water extractors, and some pull from the core. Use them with care. |
| holmes-g/erekir-schematics (GitHub) | Only 4 files (CCC 6/8/12, oxidation 8 + bore 3) | Cloned. Too small to matter. |
| **Steam Workshop**, app 1127400 (`searchtext=erekir`, `requiredtags[]=schematic`, sorted by lifetime subscribers) | About 90 Erekir schematics with subscriber counts | **Files could not be downloaded.** `GetPublishedFileDetails` returns an empty `file_url` (UGC v2), and steamcmd cannot run here (no 32-bit libs). I listed the top items and **looked at the preview images** of 16 of them. |
| Steam guides | "The Turrets of Erekir: A Defense Guide" by Egui, 2023-04-30 (pre-v8) | Read. Turret roles only. |
| GitHub search API, Reddit JSON, Miraheze wiki | — | All blocked: GitHub search is not allowed in this session, and Reddit and Miraheze returned 403 or a bot check. |

Downloaded files are in `/tmp/claude-0/-home-user-mindustry-enemy-expansion/88239563-6150-5922-b1f5-c27862a400dc/scratchpad/schematics/`:

- `mt/`, `ms/`, `di/<folder>/`, `cn/`: the files from each source.
- `selected/`: the 13 shown below.
- `MANIFEST.tsv`: file, name and source URL for each of the 589 files.
- `render_*.txt`: all text renders.

Steam previews are in `/tmp/claude-0/-home-user-mindustry-enemy-expansion/88239563-6150-5922-b1f5-c27862a400dc/scratchpad/swprev/`.

**Most-subscribed Erekir schematics on Steam** (lifetime subscribers, last update):

| Subscribers | Schematic | Updated | Notes |
|---|---|---|---|
| 2084 | Silicone Arc Erekir (id 2836703466) | 2022-07-17 | |
| 1582 | Erekir – Chemical Power | 2022-07-29 | |
| 1487 | Erekir – Oxide/Surge Alloy | 2023-05-23 | |
| 1288 | Erekir – Electrolyzer (6) | 2022-07-30 | |
| 980 | Cleroi Factory Erekir | 2022-11-19 | Reads 540 power, 0.88 W/s, 1.25 Be/s, 2.86 Si/s, 3 H₂/s; 2 Cleroi per 45 s; tileable. |
| 815 | Tank Fabricator Erekir | 2022-07-17 | |
| 804 | 【懒人必备】T2双制造厂 (T2 double unit factory) | 2022-11-16 | |
| 743 | Triple Silicon | 2022-11-16 | |
| 653 | Titan Turrets (Erekir) | 2023-07-08 | |
| 361 | Mid Game Defense (Erekir) | 2023-07-08 | |

Two caveats about the Steam list:
- It is dominated by v7-era (2022–23) basics.
- Its top two entries are pixel art ("Makima ART", 3675; "ART anime", 2546). Popularity is not quality.

**Version warning.** Most Design-It names that give a percentage (for example "Afflict 180%", "Nitro Slag 1064%", "400% phase") were tuned to pre-v156 heat values. Check heat counts against v160.7 before copying them. The logistics patterns (adjacency, ducts and bridges) do not depend on version.

### A.2 Naming code used by Design-It (useful to read the files)

- **"on G" / "on Graph":** plasma bores sit on a graphitic wall inside the schematic.
- **"on Tung" / "on Thor":** a large plasma bore on a tungsten or thorium wall.
- **"on GTung":** impact drills on ground tungsten.
- **"on Beryl":** plasma bores on beryllium.
- **"on Vent":** includes a turbine or vent condenser.
- **"ExtH2" / "ExtW" / "ExtN2":** that liquid is supplied from outside.
- **"SGBTW" etc.:** the item inputs (sand, graphite, beryllium, tungsten, water).
- **"Md" / "PMD":** output goes through a payload mass driver.
- **"Raw":** starts from raw ores only.
- **"Mixbelt":** a single duct carries several items.

Every schematic marks its inputs with item and liquid **sources**, so the inputs and their positions can be read directly from the file.

### A.3 Measured pattern counts (all 545 renders, my script `scripts/adj.py`)

- **Furnace next to a unit factory:** of 130 schematics that contain both an arc furnace and a fabricator or refabricator, **92 (71%) have a furnace edge-touching a fabricator or refabricator**. Silicon goes straight from the furnace into the factory with no duct.
- **Unloaders on the furnace:** of 173 schematics with an arc furnace, **96 have a duct unloader touching a furnace**. The unloader pulls silicon or sand out of one block and pushes it into the next.
- **Drills next to the furnace:** only 14 have a bore or crusher directly touching a furnace. Graphite usually arrives by duct or bridge, except in the "on G" cells.
- **Fabricator into refabricator:** in all 147 schematics with both, the fabricator faces straight into the refabricator. A payload conveyor between them is rare and used only for buffers or mass drivers.

### A.4 Patterns, with concrete layouts

**P1. Silicon furnaces in pairs or rows around a shared feeder spine.**
- "Arc 02" (Design-It, 7x6; render below): two 3x3 furnaces with a 1-tile column between them. That column is router, beam node, router; the top router takes sand and graphite from bridges.
- The same idea scales up:
  - "sili arc 6" (12x10): 2x3 furnaces around a central cross of routers.
  - "Arc 16" (20x13): 3 rows of furnaces with unloader/bridge rows between them and a surge-conveyor output under the bottom row.
  - "Arc 36" (43x14): 3 rows of 12.
- **Rule:** inputs enter between furnaces through a router, unloader or bridge, and **output goes out the opposite face**, often through a duct unloader set to silicon so that output never mixes with input.
- The most-subscribed Steam design, "Silicone Arc Erekir" (2022), is a row of 4 furnaces:
  - two parallel input ducts run underneath,
  - a duct-bridge column between each pair of furnaces lifts inputs up,
  - beam nodes sit between the bridge columns,
  - one output duct runs along the top.
- **Footprint:** 14 wide x 10 high for 4 furnaces.

**P2. Crushers or bores directly under furnaces ("Arc on S160", "Arc on Graph 02").**
- A row of 3 large cliff crushers (3x3) sits on a sand wall.
- A 2-tile band of routers, bridges and unloaders runs above them.
- Two rows of 3 furnaces sit above that band.
- "Arc on Graph 02" puts 3 plasma bores (2x2) directly under 2 furnaces, which take graphite by adjacency.
- **Ratio:** one 3x3 furnace needs about 4.4 small crushers (meta-report 1.5). The designs use crusher rows as long as the furnace row, with boost water via liquid routers.

**P3. The "production cell": furnace in the middle, fabricators touching it.** This is the most important pattern for PLAN §18.23.

"T1 on Graph 03" (Design-It, 10x7, 16 blocks):
- 2 plasma bores on a graphitic wall feed 1 furnace: one bore touches the furnace and the other goes through a single duct.
- 3 tank fabricators touch the furnace on its left and top.
- Beryllium enters at one router plus one bridge.
- Sand is the only external input; it comes from below.

"universal tier 1/2" (10x10):
- 1 furnace touches a tank fabricator, the tank refabricator above it, and a duct to a ship fabricator.
- An electrolyzer touches the furnace's right side. Its hydrogen crosses over the furnace by bridge conduit into the refabricator.

"t1 ship 3" (7x9) and "T1 Ship on G 03" (10x7, Elude cells): Elude needs graphite and silicon.
- Bores dump graphite into both the furnace and the fabricators.
- The furnace dumps silicon into the fabricators.
- There are **no ducts at all** between the parts.

"T2 on Tung SGB 02" (8x13; render below):
- A large plasma bore on a tungsten wall touches the refabricator column.
- An electrolyzer touches the refabricators for hydrogen.
- The furnace sits on top and touches the fabricators.
- Fabricators face down into refabricators.
- Inputs are only sand, graphite and beryllium, at the top.

"T2 Mech 10" (33x15) scales the same cell up: a 3x2 furnace block, a 3x2 electrolyzer block and 10 fabricator/refabricator pairs. Fabricators are spaced by 1-tile unloader columns, so each unloader serves two machines.

**P4. Unit chain geometry.**
- Fabricator (3x3) faces into a refabricator (3x3). Refabricator rows face into a prime refabricator (5x5), which faces into an assembler (5x5) with modules and constructors (wall payloads) around it.
- Typical T2 block: **2 columns x 2 rows** (fab over refab, mirrored), with a 1-tile spine between the columns. The spine holds routers or unloaders for items and a liquid router or bridge conduit for hydrogen.
- Examples: "T2 Ship 02 GSTH2" 9x9; "t2 2 md on tung" 8x12; "T2 Tank SGBTW 02" 8x12.
- The refabricator's front faces outward, away from the spine.

**P5. Turret batteries: two rows of turrets back to back, with the feed bus between them.**

"stackable 3x3 item turret" (15x8):
- 4 Breaches face up and 4 face down.
- Between them run a 2-tile band: a duct line with routers, plus liquid routers for water every 3 tiles.
- Every turret touches one item router and one liquid router.
- It is stackable sideways.

"Stackable Erekir 4x4" (17x10) does the same with 8 Disperses. "Stackable Smite" (25x12) has 8 Smites and 2 reinforced containers as an ammo buffer in the band.

"diffu stack alt" (7x15):
- 4 Diffuses, 2 rows of 2.
- **Reinforced containers plus duct unloaders behind the turrets** act as the ammo buffer.
- Bridges cross the water junction band.

"Sub Titan Defense": 3 Sublimates with **liquid containers directly behind them** (ozone buffer), Titans in the next row, and electrolyzer plus regen projector at the back.

Steam "Titan Turrets" (2023-07-08):
- 4 Titans in a 2x2 diamond around a central duct spine (thorium) that ends in a T.
- Hydrogen conduits make a U around the outside.
- Beam nodes sit in the corners.

**These outposts are all wall-less.** Walls are added in place. Official enemy bases put walls as a line in front, with blast doors where the walls cross a passage (PLAN §2).

**P6. Liquids: vent plus electrolyzers by direct contact.**
- "energy [Erekir]" (9x15) is the same as the Caldera enemy power plant, which the wiki calls "should be copied":
  - 1 vent condenser with an electrolyzer touching its left and right sides,
  - a third electrolyzer above,
  - 6 chemical combustion chambers around them, fed ozone by short conduits and liquid routers.
- "[Erekir]Энергия на х4" (7x7): 4 CCCs around a 1-tile cross of liquid routers and bridges. This is the minimum CCC block.
- "Large Oxide" (17x14, 99 blocks):
  - a row of 4 electrolyzers at the bottom,
  - two rows of 4 oxidation chambers with a 3-high heat-redirector band between them,
  - beryllium via unloaders between chambers.

**P7. Bore and drill fields.**
- "Erekir Bore 2.0" (4x5): 2 plasma bores staggered against a wall. Output duct behind; hydrogen boost conduit in front with liquid routers.
- "Impact Drill 10x" (11x21): 2 columns of 5 impact drills (4x4), with a **1-tile water spine of liquid junctions and routers between them**. Duct and bridge collectors on the outer edges, beam nodes every 4 tiles.
- "Eruption Drill 8x" is the same with a surge-conveyor spine.

**P8. Mixbelt (trunk) designs.**
- One duct carries sand, graphite, beryllium and tungsten.
- **Underflow ducts and duct routers** tap each consumer.
- Machines take only what they accept.
- Examples: "T2 Mixbelt 02 SGBTW" 7x16; "T2 Any on GTung 04 Mixbelt" 24x10.
- **Main risk:** a router on a mixed belt can stall when the item at its head has no taker. The designs avoid this with underflow ducts at the end and enough consumers for every item.

### A.5 Text renders of the most instructive designs

**1. "T1 on Graph 03" (Design-It): self-contained T1 tank cell.**

Only sand comes from outside. Beryllium enters through the router on the left; graphite comes from 2 bores on the wall to the right; the furnace dumps silicon into 3 fabricators.

```
  6   n M.M.M.n M.M.M.
  5   B>M.M^M.B>M.M^M.
  4 ? R>M.M.M.? M.M.M.        ? at x0 = beryllium in, ? at x5 = message
  3   M.M.M.F.F.F.n P.P.
  2   M.M<M.F.F F.d<P>P.      bores face a graphitic wall to the right
  1   M.M.M.F.F.F.P.P.
  0           ?   P>P.        ? = sand in, under the furnace
    M=tank-fabricator×3 F=silicon-arc-furnace×1 P=plasma-bore×2 n=beam-node×3 d=duct×1 B=duct-bridge×2 R=duct-router×1
```

**2. "universal tier 1/2" (Design-It / MindustryTool): T1 + T2 around one furnace, 10x10.**

```
  9                 ?           water
  8   m.m.m.b<n b<e.e.e.        H2 bridged over the furnace into the refab
  7 ? m.m^m.F.F.F.e.eve.        ? = tungsten into the refab
  6   m.m.m.F.F F.e.e.e.
  5   M.M.M.F.F.F.dvn           furnace -> duct -> ship fabricator
  4   M.M^M.B^R^R>M.M.M.        tank fab (left) faces up into tank refab
  3   M.M.M.d<R<B<M.M>M.
  2     n R^d>R>d>M.M.M.
  1       d^B^  B^
  0       ? ?   ?               beryllium, sand, graphite
```

**3. "T2 on Tung SGB 02" (Design-It, 8x13): T2 tank cell with its own tungsten and hydrogen.**

```
 12         ? ?                 sand, graphite
 11       F.F.F.n
 10 ? n BvF.F F.B<              ? = beryllium
  9 M.M.M.F.F.F.R<?
  8 M.MvM.dvM.M.M.              2 tank fabs (touch furnace) face down
  7 M.M.M.dvM.MvM.
  6 m.m.m.dvM.M.M.
  5 m.m<m.Rvm.m.m.              2 tank refabs face out sideways
  4 m.m.m.B>m.m>m.
  3 d^n r r m.m.m.
  2 Q.Q.Q.r e.e.e.              large plasma bore on a tungsten wall
  1 Q.QvQ.B^e.eve.?             electrolyzer (water ?) feeds H2 up
  0 Q.Q.Q.n e.e.e.
```

**4. "Arc 02" (Design-It): two furnaces sharing a router column.**

```
  5   ?   ?                     sand, graphite in
  4   d^B<dvB<                  silicon out at top-left
  3 F.F.F.RvF.F.F.
  2 F.F F.n F.F F.
  1 F.F.F.R^F.F.F.
  0       ?
```

**5. "Arc on S160 ExtH2 06" (Design-It, 10x14): crusher row feeding 6 furnaces.**

```
 12 B>d>d^F.F.F.F.F.F.
 11 F.F.F.F.F F.F.F F.
 10 F.F F.F.F.F.F.F.F.
  9 F.F.F.n L^B^d<L^n           unloader/bridge band between furnace rows
  8 B^? L^F.F.F.F.F.F.
  7 F.F.F.F.F F.F.F F.
  6 F.F F.F.F.F.F.F.F.
  5 F.F.F.n B^R<R^R^n ?
  4 d^B^n ~vb<B<B^d^b<B<        H2 boost line plus sand ducts
  3 d^Lvr r B^LvLvd^r ?
  2 c.c.c.c.c.c.c.c.c.          3 large cliff crushers on a sand wall
  1 c.cvc.c.cvc.c.cvc.
  0 c.c.c.c.c.c.c.c.c.
```

**6. "stackable 3x3 item turret" (Design-It, 15x8): back-to-back Breach rows on one bus.**

```
  7     1.1.1.1.1.1.1.1.1.1.1.1.
  6     1.1>1.1.1>1.1.1>1.1.1>1.
  5     1.1.1.1.1.1.1.1.1.1.1.1.
  4   ? R>B>r r B^L>R>B>r r B^L>R>    item routers + liquid routers (water)
  3 ? b>RvR>b^r r b>RvR>b^r r b>Rv
  2       1.1.1.1.1.1.1.1.1.1.1.1.
  1       1.1>1.1.1>1.1.1>1.1.1>1.
  0       1.1.1.1.1.1.1.1.1.1.1.1.
```

**7. "diffu stack alt" (Design-It, 7x15): Diffuse pair with a container ammo buffer.**

```
 13 d>d>R>d>d>R>d>              supply line
 12   K.K.    K.K.              reinforced containers = buffer
 11   K K.    K K.
 10   LvLv    LvLv              unloaders push ammo down
  9   BvBvl.l.BvBv              bridges cross the water band
  8 ? j j l l.j j
  7   BvBvbvbvBvBv
  6   2.2.2.2.2.2.
  5   2.2>2.2.2>2.
  4   2.2.2.2.2.2.
  3   Bvr b<b>r Bv
  2   2.2.2.2.2.2.
  1   2.2>2.2.2>2.
  0   2.2.2.2.2.2.
```

**8. "energy [Erekir]" (mindustryschematics.com, 2024-06-20): the Caldera power plant.**

The sector map has the same layout.

```
 14 G.G.G.~<r ~>G.G.G.
 13 G.G G.bvr bvG.G G.
 12 G.G.G.~<n ~>G.G.G.
 11 G.G.G.~^n ~^G.G.G.
 10 G.G G.r r r G.G G.
  9 G.G.G.bv~^bvG.G.G.
  8 b^n r ~<~^~>r n b^
  7 G.G.G.e.e.e.G.G.G.          electrolyzer feeds ozone to the CCCs around it
  6 G.G G.e.e>e.G.G G.
  5 G.G.G.e.e.e.G.G.G.
  4 b^~^n bvn ~^n ~^b^
  3 e.e.e.W.W.W.e.e.e.          vent condenser with an electrolyzer on each side
  2 e.e>e.W.W W.e.e>e.
  1 e.e.e.W.W.W.e.e.e.
  0 b>    b>    b>  b>
```

The other selected files ("Impact Drill 10x", "Erekir Bore 2.0", "Sub Titan Defense", "T2 Mech/Tank Md 2", "Large Oxide") are rendered in `schematics/selected.txt`.

---

## Part B: the 17 Erekir sectors

Source: Fandom Unofficial Mindustry Wiki, wikitext read through `api.php?action=query&prop=revisions` (the `?action=raw` URL hits a Cloudflare check). The last edit date of each page is shown. Miraheze and Reddit were blocked, and I found no usable video transcripts.

| # | Sector (wiki edit date) | Mode / threat | Enemy | Resources and terrain | Key point for an AI player |
|---|---|---|---|---|---|
| 1 | The Onset (2026-03-04) | attack, low | 2 Bastions, 2 tank fabricators (Stell); production starts after the tutorial objectives | Be, graphite, vents; small linear map; areas unlock by objective | Only items delivered **by duct** count for objectives. Left route to core 1 needs about 5 Stells, core 2 about 10. |
| 2 | Aegis (2025-10-03) | attack, medium | 1 Bastion, tanks | Be, graphite, tungsten (introduces the impact drill) | Loadout 300 Be / 300 graphite. Small map. |
| 3 | Lake (2025-08-23) | attack, medium | 1 Bastion on an island in slag; Elude production | Be, graphite, slag; islands, no land bridge | **Only Elude (hover) can attack.** No tungsten, so Diffuse cannot be built. |
| 4 | Intersect (2025-09-22) | 9 survival waves, then attack, high | 3 spawns (tanks top, mechs bottom, Averts from all three); enemy core behind a thin wall | Be, graphite, W, slag, arkycite; few resources; shield projector over the 2nd core zone until wave 1 | First wave at 8:20, then every 3 min. Breach + Diffuse front line; impact drill early. **Merui needed** to climb the wall to the core. |
| 5 | Atlas (2025-07-08) | attack, high | 3 Bastions + 1 Citadel; the SE base starts Merui/Elude at 4 min | Be, graphite, W, slag; S core walled (needs Merui), E core in slag (Elude/Merui); W core has a Diffuse | Mass **Locus** (tank refab + electrolyzer). Take the 2 southern cores first, then build Merui there. |
| 6 | Split (2025-07-08) | attack, low | 1 core behind 1 Breach; **no enemy production** | Be, graphite, W, slag; two halves | Tank and ship fabricators **banned**. Merui across via payload mass driver. |
| 7 | Basin (2026-05-24) | attack, high | 4 Bastions + 2 Citadels. Start: 2 Stell factories. After the first 2 cores fall, **nuke on the start core at +6 min** and 4 Locus, 3 Cleroi, 1 Avert, 3 Stell, 3 Merui factories start | Be, graphite, W, slag, arkycite; starts in a power deficit, no tungsten nearby | 3 water-cooled Breaches + large walls at the choke; Eludes vs the NE base; Locus for the NW base. **Have materials for a new core before killing the 2nd core.** Line of Breaches vs Averts. |
| 8 | Marsh (2025-07-10) | attack, medium | 1 Bastion. Front pass: 6 Be Breaches (4 boosted) + 3-tile wall. Side pass: 4 Breaches + 2 ozone Sublimates on a shared liquid container + blast doors. Locus/Merui 8 min after the last objective | Be, graphite, W, arkycite; few vents | CCC power (tutorial). T2 swarm (Locus/Cleroi) wins. **Do not rush the objectives:** the 8-minute timer starts after the last one. |
| 9 | Peaks (2025-07-16) | attack, medium | 2 Bastions with Avert factories; Breach/Diffuse and 2 Disperses | Be, graphite, W, slag, arkycite; 4 walled sections; water-starved start | **Only air units are useful.** Payload mass drivers between sections. Bases open about 3 min after the objectives. |
| 10 | Ravine (2026-03-16) | survival, 24 waves, medium | No cores. Ground spawn E, air spawn N. Locus w1, Stell w2, Merui w5, Avert w10, Cleroi w14, Elude w16, Obviate w21 | Starting base has bores, furnace, impact drill + vent condenser, 2 Breaches; 1 slag pool | First wave at 11 min, then every 1:50. Surge crucible on oxidation-chamber heat leads to **Afflict** before wave 1. |
| 11 | Caldera (2026-04-02) | attack, medium | 1 Bastion with Breach, Diffuse, Sublimate; Elude production | Islands in slag; unit cap +5 | Unit cargo loaders (start with 50 surge). Avert attack, or kill the Breach ammo containers. Enemy power block = render 8. |
| 12 | Stronghold (2025-08-23) | attack, high | 8 Bastions + Citadel + Acropolis; escalations from Merui (3 min) up to Anthicus | Be, graphite, W, **thorium**, arkycite | Kill the 2 near cores (Merui over the ledge), then the map opens after 6 min. Averts snipe weak cores; target heat and logistics feeding the Afflicts/Disperses. |
| 13 | Crevice (2025-07-19) | survival, 45 waves, high | Stell/Merui/Elude lines up to T3; guardian 4 Tecta at w45 | Be, graphite, W (only 2 wall tiles plus an eruption field past a slag river), Th, arkycite, slag | Sublimates + Titans for ground, Disperse for Obviates, carbide leads to pyrolysis power. |
| 14 | Siege (2026-05-25) | attack, **extreme** | 3 Bastions + Citadel; Scathe missiles; Stell/Elude/Merui after 10 min, then Cleroi, Locus, Anthicus | 2 parallel canyons with no link; arkycite only in the E canyon | Ship refabricator banned. Payload transport between canyons. Vanquish rush is possible; shoot down Scathe missiles (Disperse/Afflict, Locus up front). |
| 15 | Crossroads (2026-06-29) | attack, high | 2 Bastions + Citadel + coreless Cleroi base; shield projectors powered by pyrolysis; escalation at 10, 18:20, 21:20 and 38 min | Start crater in a power deficit (pumps for pyrolysis are pre-placed) | Quell outranges everything except Smite and Afflict. Kill the Obviate core early. |
| 16 | Karst (2025-07-19) | survival, 9 waves, extreme | Huge waves every 8 min (first at 11); guardian Collaris w9 | All resources; start has an Afflict on 2 oxidation chambers | Rush pyrolysis, Afflict + Disperse, then surge → phase → Smite. |
| 17 | Origin (2026-06-05) | attack, eradication | 3 Bastions + 2 Citadels + 4 Acropolis; tiers unlock at 10 / 30 / 60 / 90 / 110 min; 2 Maligns (electric heat; neoplasia heat) | Tungsten on a slag island; arkycite; unit cap +15; huge loadout | Elude/Avert early raids; CCC to pyrolysis; mass Quells. Destroy the central core last, because it triggers retaliation waves. |

**Common threads across sectors** (they feed the bullets below):
1. Sector type decides which units can be used at all: hover on Lake and Caldera, mech on Intersect, air on Peaks, payload transport on Split and Siege.
2. Several maps open in stages, with objective or timer gates and a nuke on Basin. The AI must plan a second core before the trigger.
3. Enemy bases are beaten by cutting their supply (ammo containers, heat, power) more often than by frontal assault.
4. Power is the opening bottleneck nearly everywhere: Basin, Crossroads and Karst start in a deficit. That is why the community copies CCC and pyrolysis blocks.

---

## Implications for the AI's blueprint library and layout

1. **Add "production cell" blueprints as the main unit of base growth, instead of separate lines.**
   - **What a cell is:** an arc furnace with fabricators and refabricators touching it. 71% of community designs that have both put them edge to edge. The furnace dumps silicon into every touching acceptor (`GenericCrafter` offload, mechanics-spec §crafter), so the cell needs no silicon duct.
   - **T1 template:** "T1 on Graph 03", 10x7: furnace + 3 tank fabricators, plus 2 bores if a graphitic wall touches the cell.
   - **T2 template:** "T2 on Tung SGB 02", 8x13: furnace on top, 2 fab→refab columns, electrolyzer and large bore underneath.
2. **Give each cell exactly one or two declared external inputs and reserve their entry tiles.** Usually sand from the crusher field, sometimes beryllium. Everything else should be internal by adjacency. This directly fixes "later factories cannot be fed" (PLAN §18.23): the hard-to-route lines disappear, and only a short sand or beryllium feed has to reach the cell.
3. **Choose cell variants by terrain, as the "on G / on Tung / on Beryl" naming does.** Anchor a cell on the ore wall it needs: bores inside the blueprint touching the wall. Use the external-input variant only when no suitable wall is near. The `@anchor` mechanism can be extended to an "ore wall on side X" anchor.
4. **Model silicon production as furnace pairs or rows around a feeder spine, not single furnaces.**
   - Two furnaces share a 1-wide column of router, node, router ("Arc 02"); rows of furnaces alternate with 1–2 tile unloader/bridge bands.
   - The existing `[arc-furnace]` blueprint (sand left, graphite below, silicon right) should become a 2-furnace module with the spine between them and the output on the far faces.
   - Keep a crusher-row variant with large cliff crushers directly beneath ("Arc on S160").
5. **Use duct unloaders as the standard way to take a product out of a crafter into the next consumer** (96 of 173 furnace designs). This is legal on Erekir: unloaders cannot pull from the core, but they can pull from crafters and containers. It avoids routers that push silicon back into the wrong block.
6. **Fabricator → refabricator → prime refabricator should always face directly into the next block.** Lay T2 out as 2 mirrored columns with a 1-tile spine between them carrying items (routers or unloaders) and hydrogen (a liquid router or bridge conduit). Refabricator fronts face outward and must stay clear: a blocked exit stalls the factory forever (mechanics-spec, unit release).
7. **Defence: build batteries as two back-to-back turret rows with a 2-tile bus between them.** The bus holds one duct with a router per turret and a liquid router per turret pair for water. This is how Breach, Disperse and Smite stacks are built, and it matches the official enemy bases (supply passing behind, a router beside each turret). Make the battery a repeatable module that grows sideways along a front.
8. **Give ammo-hungry turrets a buffer as part of the blueprint.**
   - Diffuse, Disperse, Smite: a reinforced container plus unloaders behind ("diffu stack alt", "Stackable Smite").
   - Sublimate: a liquid container directly behind ("Sub Titan Defense"; the Marsh enemy base does the same).
   - The existing `breach-depot` already does this; extend it to all of them.
9. **Walls stay a separate layer.** None of the community turret blueprints include walls. Walls are fitted to the terrain, with blast doors where the AI's own ground units must exit (Marsh, Crossroads).
10. **Copy the community power blocks as fixed modules, because power is the opening bottleneck on many sectors.**
    - Vent condenser with electrolyzers touching it, plus 6 CCCs around them: "energy [Erekir]", 9x15, identical to the Caldera enemy plant.
    - The 7x7 CCC quad.
    - Oxidation-chamber rows with a heat-redirector band ("Large Oxide").
    - These are mostly liquid-by-contact layouts, which the mod's "adjacent blocks feed each other" rule already supports.
11. **Lay drill fields as pairs of drill columns around a 1-tile liquid spine with collectors on the outside edges** ("Impact Drill 10x", "Eruption Drill 8x"). This is better than single drills each with its own pipe. For walls, use staggered plasma bores with the duct behind and the boost conduit in front ("Erekir Bore 2.0").
12. **Trunk option: a short "mixbelt".**
    - One duct carrying sand, graphite and beryllium (sometimes tungsten), with underflow ducts or routers at each consumer.
    - It is viable only if every item on the belt has a guaranteed sink, or there is an overflow to the core at the end, or routers will stall on the wrong item.
    - Prefer cells (bullets 1–3); use a mixbelt only to link 2–3 cells in a row.
13. **Use the schematics' item and liquid sources as the input spec.** If more community designs are imported, read the source blocks as declared inputs and their positions. That converts a community schematic into the mod's `in item x y dir` format almost mechanically. Treat heat percentages in names as pre-v156 until checked.
14. **Make campaign play per-sector aware.**
    - Unit type by terrain: Lake and Caldera hover; Intersect, Atlas south and Stronghold Merui; Peaks air; Split and Siege payload transport.
    - Have a second-core plan before triggering scripted escalation (Basin nuke, Origin central core).
    - Prefer cutting enemy ammo, power and heat over frontal assault (wiki strategies for Basin, Caldera, Stronghold, Siege).

## Sources that failed or were partial

- **Steam Workshop:** file downloads failed because `file_url` is empty and steamcmd cannot run (no 32-bit libs). Previews only.
- **GitHub:** the repository search API is blocked in this session. I found repos through a web search and cloned them directly.
- **Fandom:** `?action=raw` hits a Cloudflare check; `api.php` worked.
- **Miraheze:** 403 or bot check, both for curl and for WebFetch.
- **Reddit:** JSON search returned 403.
- **Web search:** returned little for walkthroughs or videos, and one query was refused. No YouTube transcripts were obtained.
- **MindustryTool:** 4 of 88 files were not schematics.
- **Chinese archive:** many of its "Erekir" arc and defence entries are mixtech.
