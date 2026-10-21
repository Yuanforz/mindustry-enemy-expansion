# Erekir power / liquid / heat logistics — rules from Mindustry v160.7 source

All paths are relative to `/home/zoudianyuan/src/Mindustry-v160.7/core/src/mindustry/`.
`BC` = `entities/comp/BuildingComp.java`, `B` = `content/Blocks.java`.
Units: everything in the code is **per tick** (60 ticks = 1 s). The UI shows ×60. `consumePower(5f)` = 5/tick = 300 power/s.

---

## POWER

### 1. How buildings join a power graph

There are exactly two kinds of edges in a power graph (`BC:1188-1205 getPowerConnections`):

**(a) Contact (proximity) edges.** Every same-team adjacent building with a power module is connected **unless both are "pure consumers"**:

```java
// BC:1195
&& !(block.consumesPower && other.block.consumesPower && !block.outputsPower && !other.block.outputsPower
     && !block.conductivePower && !other.block.conductivePower)
&& conductsTo(other) && other.conductsTo(self())   // conductsTo = !block.insulated (BC:1184)
```

So:
- consumer ↔ consumer touching: **NOT connected** (two electrolyzers side by side do not share power).
- producer ↔ anything, battery ↔ anything, beam node ↔ anything: connected by touching.
- `conductivePower = true` blocks act like cables. On Erekir only: `surge-conveyor` (B:2248), `surge-router` (B:2264), `shielded-wall` (B:1912). None of the factory machines conduct.
- Note `Block.consumesPower` defaults to **true** (`world/Block.java:53`), and `PowerDistributor`/generators set it false (`PowerDistributor.java:7-8`). Generators have `outputsPower=true`, so they power everything they touch.
- Proximity is edge-adjacent only (no diagonals; `updateProximity` uses `Edges.getEdges(size)`, BC:1886-1911) and same team only.

**(b) Link edges** (`power.links`, BC:1200-1203): made by beam nodes (and Serpulo-style power nodes/beam-link lasers). Links count no matter what types the two ends are, so a machine that two beam nodes hit **does join the two nodes into one graph** (the graph is pure connectivity; no capacity per edge).

### 1a. beam-node / beam-tower (`world/blocks/power/BeamNode.java`)

Stats (B:2667-2688):
| block | size | range | storage | notes |
|---|---|---|---|---|
| beam-node | 1 | 10 | `consumePowerBuffered(1000)` | `consumesPower = outputsPower = true` |
| beam-tower | 3 | 23 | `consumePowerBuffered(40000)` | same |

Because Blocks.java sets `consumesPower = outputsPower = true` and the consumer is buffered, **beam nodes are batteries** in the graph (`PowerGraph.java:293-294`), and as "outputsPower" blocks they **connect by contact to every adjacent powered building**.

Link rule (`BeamNode.updateDirections`, BeamNode.java:241-293):
```java
for(int i = 0; i < 4; i ++){            // 4 axis directions only (d4), no diagonals
  int offset = size/2;
  for(int j = 1 + offset; j <= range + offset; j++){
    var other = world.build(tile.x + j * dir.x, tile.y + j * dir.y);   // ONLY the centre row/column
    if(other != null && other.isInsulated()) break;                       // insulated: beam stops, no link
    if(other != null && other.block.hasPower && other.block.connectedPower && other.team == team
       && !(other.block instanceof PowerNode)){
        links[i] = other; ...; break;                                     // first powered building wins
    }
  }
```
Rules that follow:
- **One link per direction, max 4 beam links per node**, plus contact edges.
- The scan line is the **single row/column through the node's centre tile**. For the 3×3 tower it scans only the middle row/column, from distance 2 to 24 from the centre (i.e. up to 23 tiles beyond its edge). For beam-node: tiles at distance 1..10.
- A target is hit if **any tile** of it lies on the scan line (`world.build` returns the multi-tile building for any of its tiles). A 3×3 machine is reachable from any of its 3 rows/columns.
- The beam **stops at the first same-team building with `hasPower`** — consumer, producer, other beam node, turret, unit factory, etc. It cannot reach a second machine behind the first. That second machine is only powered if it touches a producer/battery/beam node (consumer–consumer contact doesn't conduct) or is hit by another beam.
- The beam **passes through** anything without `hasPower`: walls, ducts, conduits, heat redirectors, containers, construction sites (`ConstructBlock` has no power), and **enemy-team** powered buildings (team check fails → continue), and `PowerNode` subclasses (beam-link is a `LongPowerNode`, so beams pass through beam-links; comment at :257).
- Blocked only by `insulated` buildings (`Block.insulated`, Block.java:160). Only `plastanium-wall(-large)` (B:1734,1743) and `PowerDiode` (`PowerDiode.java:22`) are insulated — none on Erekir's tech tree. Static walls/cliffs are not buildings and do not block.
- Linking is mutual and automatic: each node rescans whenever `world.tileChanges` changes (`updateTile`, :189-196; `tileChanges++` on every `TileChangeEvent`, `core/World.java:41`, fired by `Tile.fireChanged`, `world/Tile.java:695-699`, unless the world is generating). No config/player action is needed. Node–node links come out naturally because each node's first powered building in that direction is the other node.
- `BeamNode.getNodeLinks` (:115-139) and `drawPlace` are **only used for the placement preview** (`Block.drawPotentialLinks`, Block.java:515-533); placing a building does not itself create beam links. `Building.placed()` (BC:1362-1371) only auto-links to **autolink `PowerNode`s** (`PowerNode.getNodeLinks`, PowerNode.java:278); on Erekir there are none (beam-link has `autolink = false`).
- If a link changes (something powered placed in between), the old graph is split and reflowed (:267-282).

### 1b. beam-link (B:2690-2702, `LongPowerNode`)
`laserRange = 500`, `maxNodes = 1`, `autolink = false`, `sameBlockConnection = true` → links only to another beam-link and only by explicit configuration; conducts by contact (it has consumesPower=outputsPower=false, PowerNode.java:46-47). Beam nodes ignore it as a beam target.

### 1c. Insulation for Serpulo power nodes (for completeness)
`PowerNode.insulated(x,y,x2,y2)` raycasts and rejects a laser crossing any insulated building (PowerNode.java:382-388).

### 2. PowerGraph (`world/blocks/power/PowerGraph.java`)
Classification on `add` (:290-299): outputs∧consumes∧!buffered → producer+consumer; outputs∧consumes∧buffered → **battery** (beam nodes); outputs → producer; consumes with `consPower` → consumer.

Per tick (`update`, :216-257):
1. `needed = Σ requestedPower·delta` over consumers with `shouldConsumePower` (:105-116). `requestedPower = usage · (shouldConsume() ? 1 : 0)` (`ConsumePower.java:56-60`). A machine that is blocked (output full, no input) or missing a non-power input requests **0** power (`shouldConsumePower=false` when any non-power consumer has efficiency ≈0, BC:1979-1981).
2. `produced = Σ getPowerProduction·delta`.
3. Shortfall drawn from batteries proportionally (`useBatteries`, :154-168); surplus charges all batteries by the same fraction of their free space (:170-185).
4. `coverage = min(1, produced/needed)` → every non-buffered consumer gets `power.status = coverage` (:187-214). **No priority**; all consumers in the graph are throttled equally.

Effect of shortage: `ConsumePower.efficiency = power.status` (`ConsumePower.java:38-40`); building efficiency = **min over all non-optional consumers** (BC:1976-1992), so a crafter at 60 % power runs at 60 % speed (progress uses `edelta = efficiency·delta`, BC:1934). Heaters produce `heatOutput·efficiency` heat. Satisfaction display: `getSatisfaction = clamp(produced/needed)` (:86-93).
Graph with no producer and no stored energy gives coverage 0.
If `team.rules().cheat` all consumers get status 1 (:217-225; BC:1952).

### 3. Erekir generators
| block | B line | size | power | inputs | outputs / rules |
|---|---|---|---|---|---|
| turbine-condenser (`ThermalGenerator`) | 2704 | 3 | `powerProduction = 3/9` per tick per vent tile → **3/tick = 180/s** on 9 vent tiles | none | water `5/60/9` per tick per vent tile = **5/s**, dumped to all sides (`ThermalGenerator.java:94-98`); full water tank only caps output, power unaffected |
| chemical-combustion-chamber (`ConsumeGenerator`) | 2729 | 3 | 550/60 /tick = **550/s** | ozone 2/s + arkycite 40/s | liquidCapacity 100 |
| pyrolysis-generator | 2751 | 3 | 1400/60 /tick = **1400/s** | slag 20/s + arkycite 40/s | water 20/s dumped to all sides (`ConsumeGenerator.java:136-139`); not stopped by full output |
| flux-reactor (`VariableReactor`) | 2781 | 5 | 18000/s × clamp(heat/150) | cyanogen 9/s × heat/150; **heat input** max 150 | explodes if cyanogen short while heated (below) |
| neoplasia-reactor (`HeaterGenerator`) | 2817 | 5 | 140/tick = **8400/s** | arkycite 80/s, water 10/s, 1 phase fabric per 3 s | neoplasm 20/s to all sides; **explodes when neoplasm reaches capacity 80** (`explodeOnFull`, ConsumeGenerator.java:141-144); outputs **60 heat** out its front face |

Turbine/vent-condenser placement: `canPlaceOn` requires Σ steam attribute over the 3×3 footprint `> 9 − 0.0001` (ThermalGenerator.java:78-81, `minEfficiency` B:2709; AttributeCrafter.java:71-73, B:3004), i.e. **all 9 tiles must be vent floor** (each vent floor has `Attribute.steam = 1`, B:504-542; any vent type counts). Production = sum (+ sector env attribute) (`ThermalGenerator.java:88`), computed on `onProximityAdded` (:118-122).

Flux reactor (`VariableReactor.java:126-143`): `target = clamp(heat/150)`; `efficiencyMet = clamp(cyanogenEfficiency/target)`; if not met, `instability` rises toward 1 at `(1/180)·(1−met)` per tick and the reactor **kills itself at instability ≥ 1** (:85-87) — ~3 s with no cyanogen while heated. With heat 0 it is safe but produces 0. Heat must come from heaters **facing** it (same `calculateHeat` as crafters).

ConsumeGenerators: power = `powerProduction · efficiency` where efficiency = min liquid/item fraction (ConsumeGenerator.java:112-116, PowerGenerator.java:208-210).

### 4. Consumer power (per tick; ×60 for /s) — selected Erekir blocks
silicon-arc-furnace 5 (B:1353), electrolyzer 1 (1370), atmospheric-concentrator 2 (1418), oxidation-chamber 0.5 (1438), electric-heater 100/60 (1464), carbide-crucible 2 (1553), surge-crucible 1.5 (1620), cyanogen-synthesizer 2 (1652), phase-synthesizer 8 (1686), vent-condenser 0.5 (3017), cliff-crusher 11/60 (3023), large-cliff-crusher 1 (3037), plasma-bore 0.15 (3059), large-plasma-bore 0.8 (3073), impact-drill 160/60 (3107), eruption-drill 6 (3137), fabricators 1.5 (6456-6479), refabricators 2.5–4.5 (6488-6545), assemblers 2.5–3 (6569-6598), radar 0.6 (1996), regen-projector 1 (2017), build-tower 3 (2007), unit-repair-tower 1 (6619).
Rule: all are plain (non-buffered, non-conductive) consumers → each needs its own beam hit or contact with a producer/beam node.

---

## LIQUIDS

### Core transfer mechanics (BC)
- `acceptLiquid` default: `block.hasLiquids && block.consumesLiquid(liquid)` (BC:873-875) — a machine accepts **only liquids it consumes, from any side**, no fullness check (fullness is applied when moving).
- Capacity is **per liquid**: `LiquidModule` keeps one amount per liquid type; transfers compare `liquids.get(liquid)` against `liquidCapacity` (BC:920, 952). A 50-cap electrolyzer can hold 50 water + 50 ozone + 50 hydrogen.
- `moveLiquid(next, liquid)` (BC:943-983), used by conduits and bridges (pushes to one target):
  ```java
  ofract = next.liquids.get(liquid) / next.block.liquidCapacity;
  fract  = liquids.get(liquid) / block.liquidCapacity * block.liquidPressure;
  flow   = min(clamp(fract - ofract) * block.liquidCapacity, liquids.get(liquid));
  flow   = min(flow, next.block.liquidCapacity - next.liquids.get(liquid));
  if(flow > 0 && ofract <= fract && next.acceptLiquid(self(), liquid)) transfer
  ```
  Requires same team and `next.block.hasLiquids`.
- `dumpLiquid(liquid, scaling=2, outputDir=-1)` (BC:891-913), used by machines/routers/pumps: round-robin over **all adjacent buildings**; transfer `(fract − ofract)·cap/2` if `ofract < fract` and target accepts. With `outputDir != -1` only the neighbour whose centre lies in direction `(outputDir + rotation) % 4` (centre-to-centre dominant axis, `relativeTo(Building)`, BC:536-545) is used.
- Fill-fraction based: a big-capacity receiver (tank 2700) fills slowly in relative terms; liquid flows only "downhill" in fill fraction (×pressure).

### Mixing / reactions
- Conduits, routers, containers, tanks and bridges hold **one liquid at a time**: they accept a liquid only if it is their current liquid or they hold `< 0.2` of the current one (Conduit.java:241-245; LiquidRouter.java:40-43; DirectionLiquidBridge.java:90). So a water line refuses hydrogen (the hydrogen stays in its source) — but once a line drains below 0.2 it will accept anything.
- `LiquidModule.add/remove` sets `current` to the liquid just touched (LiquidModule.java:118-136); residue of the previous liquid stays in the module.
- When a transfer is refused and the target doesn't consume the liquid and both are >10 % full (BC:961-981), for `blockReactive` liquids: flammable (>0.3) meeting hot (temp>0.7) → **both blocks take continuous damage + fire**; hot meeting cold (<0.55) → the pushed liquid is destroyed (0.7/tick, steam). Erekir: slag temp 1.0; arkycite flam 0.4, ozone 1, hydrogen 1, cyanogen 2 (content/Liquids.java:21-94). **Slag next to any of arkycite/ozone/hydrogen/cyanogen lines = damage**; slag next to water/nitrogen = slag evaporates. Neoplasm is `blockReactive=false`.
- **Contamination hazard:** a machine that dumps output to all sides (pyrolysis/turbine water, neoplasm, vent-condenser water, pumps, cyanogen-synth, atmospheric-concentrator nitrogen…) will push its output into any adjacent router/container/tank/bridge/plain-conduit-back that is empty or holds <0.2 — e.g. into the router that feeds it arkycite when that router runs low. Routers likewise push into any adjacent machine that consumes their liquid (and back into nothing else).

### 5. Erekir liquid blocks (B:2404-2479)
| block | class | size | capacity | other |
|---|---|---|---|---|
| reinforced-conduit | ArmoredConduit | 1 | 50 | `liquidPressure = 1.03`, **`leaks = true`** (overrides ArmoredConduit default false) |
| reinforced-liquid-junction | LiquidJunction | 1 | – | passes straight through (`getLiquidDestination`, LiquidJunction.java:44-53) |
| reinforced-bridge-conduit | DirectionLiquidBridge | 1 | 120 | `range = 4` |
| reinforced-liquid-router | LiquidRouter | 1 | 150 | dumps to all sides |
| reinforced-liquid-container | LiquidRouter | 2 | 1000 | dumps to all sides |
| reinforced-liquid-tank | LiquidRouter | 3 | 2700 | dumps to all sides |
| reinforced-pump | Pump | 2 | 160 | hydrogen 1.5/s; 80/60/4 per tick per liquid tile |

**Reinforced conduit.**
- Output only to the tile it points at (`moveLiquidForward`, BC:928-941), once per tick (Conduit.java:248-257).
- Never accepts from the building it points at (Conduit.java:243: `(source.relativeTo(tile)+2)%4 != rotation`).
- Armoured rule (ArmoredConduit.java:23-27): accepts **from the side only if the source is a Conduit, a DirectionLiquidBridge or a LiquidJunction**; any other building (machine, pump, **liquid router**, container, tank) can feed it **only from directly behind** (`source.tile.absoluteRelativeTo(conduit) == conduit.rotation`; for multi-tile sources this is the dominant axis from the source centre, `world/Tile.java:114-135`). (The `!source.proximity.contains(this)` clause only covers input arriving through a junction.)
- **Leaks**: if the front tile has no building and its block is not solid and has no liquids, it dumps `amount/1.5` per tick onto the floor (BC:934-938) — loses ~2/3 of its contents per tick. Pointing at a solid static wall, at another team's building or at a building that doesn't accept → no leak, just stall (or reaction, above).
- Throughput (derived from the formula, not a game stat): in a chain each step carries `Q = 50·(1.03·f_i − f_{i+1})` per tick. With the head full, a line of any length carries ≥ 1.5/tick (90/s); higher flows need a falling fill gradient, so the maximum length L for demand Q/tick is ≈ `ln(f*/(f*−1))/ln 1.03`, f* = Q/1.5: about 47 tiles at 120/s, 23 at 180/s, 12 at 300/s, 5–6 at 600/s. Treat as an estimate (update order matters); check high-flow lines (surge-crucible slag 160/s, cyanogen-synth arkycite 160/s, neoplasia arkycite 80/s) for length.

**Reinforced bridge conduit** (DirectionLiquidBridge.java, DirectionBridge.java):
- Links to the **first same-block bridge within 1..4 tiles in its facing direction** (`findLink`, DirectionBridge.java:252-260); the target's rotation is irrelevant. Range 4 = up to 3 tiles of gap.
- With a link it pushes via `moveLiquid` every tick (DirectionLiquidBridge.java:60-65). Without a link (end of chain) it outputs **only forward** to the tile it faces, never leaking (:67-71).
- Accepts (:80-92) only if it **has an outgoing link, or the source is a bridge that links to it** — so the end bridge cannot be fed directly by a conduit/machine; not from its front side; one incoming bridge per direction (`occupied`). Accepts from any building (not armoured) on back/sides.

**Liquid router / container / tank** (LiquidRouter.java:23-43): accept any liquid from any side (subject to the one-liquid rule), `dumpLiquid(current)` to **all** neighbours each tick.

**Reinforced pump** (Pump.java; B:2404-2411):
- Placement: at least one footprint tile must have a `liquidDrop` floor and all liquid tiles must be the same liquid (Pump.java:84-96); non-liquid tiles allowed but pump nothing.
- Rate per tick = `pumpAmount · Σ liquidMultiplier` over footprint (onProximityUpdate :140-152, updateTile :160-164): 20/s per tile at multiplier 1 → **80/s on 4 full tiles** (deep water ×1.5 = 120/s). Floors: deep-water/sandWater types 1.5 (B:210-256), shallow water 1, slag (B:320), arkycite-floor (B:486).
- Needs hydrogen 1.5/s (efficiency = hydrogen fraction); stops consuming when its tank is full (`shouldConsume`, :155-157). It accepts hydrogen from any side (it consumes it) and dumps the pumped liquid to all sides (:178-180).

### 6. Producers' liquid outputs
- `GenericCrafter` (`world/blocks/production/GenericCrafter.java`): adds output continuously, `amount · efficiency` per tick (:244-249), then `dumpLiquid(liquid, 2, dir)` with `liquidOutputDirections` (:345-351). Default `{-1}` = **all sides**. Directions are **relative to rotation** and indexed in the order of `outputLiquids`.
  - **electrolyzer** (B:1356-1397): `rotate = true`, outputs ozone 4/s and hydrogen 6/s, `liquidOutputDirections = {1, 3}` → **ozone only to side `rotation+1` (left), hydrogen only to side `rotation+3` (right)**; nothing out of front or back. Consumes water 10/s from any side.
  - `dumpExtraLiquid = true` (default, :38): it keeps running while **any** output has room; a full output is simply discarded (`min(..., cap − amount)`), progress scaled by the max free fraction (:269-287). Stops only when **all** outputs are full (:214-230) — then also stops drawing power.
  - Item outputs: crafter stops when an output item would exceed `itemCapacity` (:206-212). **This includes oxidation-chamber (a HeatProducer): if its oxide isn't removed, it stops and its heat decays to 0.**
- `ThermalGenerator` / `ConsumeGenerator` (turbine, pyrolysis, neoplasia) dump to all sides with no direction (ThermalGenerator.java:94-98; ConsumeGenerator.java:136-145). Vent-condenser (AttributeCrafter, GenericCrafter) dumps water to all sides, stops when full.
- Consumer side: `ConsumeLiquid(s).efficiency = min(amount held / (rate·delta·efficiencyScale), 1)` (`world/consumers/ConsumeLiquid.java`, `ConsumeLiquids.java`); building efficiency = min of all inputs. A crafter whose liquid input arrives at 70 % of its rate runs at 70 % (smoothed by its buffer).
- Default `liquidCapacity` when not set = `round(10 · max(1, maxInputRate·60))` (Block.java:1428-1440).

---

## HEAT

### 7. Producers, conductors, consumers
Producers (`HeatProducer` extends GenericCrafter, `world/blocks/heat/HeatProducer.java`): `rotate = true`; `heat → heatOutput · efficiency`, approached at 0.15/tick (:51). Erekir (B):
| block | line | size | heat | input |
|---|---|---|---|---|
| electric-heater | 1452 | 2 | 3 | power 100/s |
| slag-heater | 1467 | 3 | 8 | slag 40/s |
| phase-heater | 1485 | 2 | 15 | 1 phase fabric / 8 s |
| oxidation-chamber | 1429 | 3 | 5 | ozone 2/s, beryllium, power 0.5; outputs oxide (must be taken away) |
| neoplasia-reactor (HeaterGenerator) | 2817 | 5 | 60 | see power |

Consumers (`HeatCrafter`, `world/blocks/production/HeatCrafter.java`): atmospheric-concentrator 24 (B:1423), carbide-crucible 40 (1549), surge-crucible 40 (1593), cyanogen-synthesizer 20 (1626), phase-synthesizer 32 (1661); all `maxEfficiency = 1`. Flux reactor (needs up to 150), afflict/malign turrets (B:5156 = 20, B:6274 = 144; `Turret.java:544-545, 679`) use the same calculation.

Conductors (`HeatConductor`, `world/blocks/heat/HeatConductor.java`): heat-redirector 3×3 (B:1496), small-heat-redirector 2×2 (B:1507), heat-router 3×3 `splitHeat = true` (B:1519). The conductor's heat = sum of what it receives (:86-91); **no loss**.

**The rule — `calculateHeat` (BC:416-464)**, evaluated by each receiver over its adjacent same-team HeatBlocks:
```java
boolean split = build.block instanceof HeatConductor cond && cond.splitHeat;
if(!build.block.rotate || (!split && (relativeTo(build) + 2) % 4 == build.rotation)
                       || (split && relativeTo(build) != build.rotation)){
    float diff = Math.min(Math.abs(build.x - x), Math.abs(build.y - y)) / tilesize;
    int contactPoints = Math.min((int)(block.size/2f + build.block.size/2f - diff),
                                 Math.min(build.block.size, block.size));
    float add = heater.heat() / build.block.size * contactPoints;
    if(split) add /= 3f;
    heat += add;
```
- Heaters and redirectors give heat **only out of their front face** (the receiver must be the building their rotation points at).
- Heat router gives to **front, left and right** (every side except its back, where `relativeTo == rotation`), **each side gets heat/3** whether or not anything is there (unused thirds are lost).
- Contact share: `heat / sourceSize × (number of tiles of the source's face that touch the receiver)`. A 2×2 heater fully against a 3×3 crafter gives 100 %; shifted to touch with only one tile, 50 %. A 3×3 redirector offset by 1 tile against a 3×3 crafter gives 2/3. Total out of one face is never more than the source's heat — no duplication.
- A receiver **sums** all heaters facing it on any side (e.g. 40 heat for a crucible = 5 electric heaters, or 5 slag heaters, or 3 phase heaters…).
- Heat does not pass through consumers. Redirector chains pass it on in full (they update recursively in the same tick, :457-459); loops are ignored via `cameFrom` (:431, 449-454).
- Overheat / underheat (`HeatCrafter.efficiencyScale`, :87-91): `min(clamp(heat/req) + max(heat−req,0)/req·overheatScale, maxEfficiency)`. With `maxEfficiency = 1` on all Erekir crafters, **extra heat is wasted, no bonus, no damage**; below requirement speed (and liquid use) scales linearly with heat/req; **heat = 0 → no consumption at all** (`shouldConsume`, :61-64). No overheat damage exists for crafters. Flux reactor clamps at 150.

---

## Most important / surprising rules (checklist for the layout generator)
1. **Consumers touching consumers don't share power**; only producers, beam nodes (batteries) and the three conductive blocks relay by contact. Every machine needs a beam hit or contact with a generator/beam node.
2. Beam node/tower: 4 straight lines through the **centre tile only**, range 10 / 23 beyond edge, **stops at the first own powered building** (one target per direction), passes through non-powered blocks and enemy buildings; links self-update on any tile change. Beam nodes are batteries (1000 / 40000).
3. Under power shortage all consumers in a graph are slowed equally to produced/needed; blocked machines draw 0.
4. Turbine/vent condenser need **all 9 tiles on vents**.
5. **Reinforced conduit accepts side input only from conduits/bridges/junctions**; machines and **routers/tanks must be directly behind it**. It leaks onto empty floor at its front.
6. Bridge conduit: range 4, the last bridge outputs **only forward** and cannot be fed except by a bridge.
7. Electrolyzer: ozone out its left side (rotation+1), hydrogen out its right side (rotation+3) only. Everything else dumps output to **all sides**, including into empty neighbouring routers/lines (contamination).
8. One liquid per conduit/router/tank; a line holding ≥0.2 of another liquid blocks. Slag adjacent-blocked against flammable liquids damages both blocks.
9. Heat: front face only (router: 3 faces at 1/3 each), share ∝ touching tiles, summed over heaters, excess wasted (Erekir maxEfficiency 1), heat 0 → machine idle.
10. **Neoplasia explodes when neoplasm isn't drained; flux reactor explodes if heated but short of cyanogen; oxidation chamber's heat dies if its oxide isn't taken away.**
