# Erekir payload logistics: rules for a unit-chain layout generator (Mindustry v160.7)

Source root: `/home/zoudianyuan/src/Mindustry-v160.7/core/src/mindustry/`. All paths below are relative to it.
Units: 1 tile = 8 world units (px). 60 ticks = 1 s. `consumePower(x)` is x per tick, so the UI shows x*60 power/s.
Rotation 0=+x, 1=+y, 2=-x, 3=-y. Everything here was read from the code. Nothing was run in the game.

---

## 0. Shared mechanics (these explain all the rest)

### 0.1 "Front" is one tile
`entities/comp/BuildingComp.java:556-559`
```java
public Building front(){ int trns = block.size/2 + 1;
    return nearby(Geometry.d4(rotation).x * trns, Geometry.d4(rotation).y * trns); }
```
`movePayload` (`BuildingComp.java:824-835`) checks the same single tile: `tile.nearby(d4(rotation)*trns)`. The tile must belong to a building **of the same team** that `acceptPayload(self, p)`.
- For a 3x3 block, the tile checked is 2 tiles from the centre along the rotation. For a 5x5 block it is 3 tiles.
- Block-to-block hand-off (fabricator, refabricator, constructor or mass-driver receiver straight into another block) **does not check alignment**. The target only has to cover that one tile. For example, a 3x3 can feed a 5x5 with its centre offset sideways by up to ±2 tiles, or a 3x3 with an offset of up to ±1 tile.
- The input/output arrows that `PayloadBlock.blends()` (`PayloadBlock.java:51-73`) checks are **only drawn**. They have no effect on logic.

### 0.2 `PayloadBlockBuild.moveOutPayload()` is the output routine of every UnitBlock/BlockProducer
`world/blocks/payloads/PayloadBlock.java:212-241`
```java
Vec2 dest = Tmp.v1.trns(rotdeg(), size * tilesize/2f);          // the front edge
payVector.approach(dest, payloadSpeed * delta());                // payloadSpeed = 0.7 px/tick (line 18)
Building front = front();
boolean canDump = front == null || !front.tile.solid();
boolean canMove = front != null && (front.block.outputsPayload || front.block.acceptsPayload);
...
if(payVector.within(dest, 0.001f)){
    if(canMove){ if(movePayload(payload)) payload = null; }     // retried every tick
    else if(canDump){ dumpPayload(); }
}
```
Decision table for whatever sits on the front tile:

| Front tile | Result |
|---|---|
| a payload-capable block (`outputsPayload` or `acceptsPayload`: payload conveyor/router, refabricator, fabricator, mass driver, constructor, loader, assembler, module) | it tries `movePayload` **every tick and never dumps**. If that block refuses (wrong unit type, full, its front side, too big), the source **waits forever** |
| empty, or a non-solid building (duct, conveyor, ...) | `dumpPayload()` releases the unit into the world (see 0.4). A block payload (wall) can never be dumped: `Payload.dump()` defaults to `false` (`Payload.java:50-52`) and `BuildPayload` does not override it, so it stays inside forever |
| a solid building that takes no payloads (wall, drill, turret, ...) | permanently stuck |

Travel time from centre to edge at 0.7 px/tick: about 17 ticks for a 3x3 (12 px) and about 29 ticks for a 5x5 (20 px).

### 0.3 Input side: `moveInPayload()` (`PayloadBlock.java:199-210`)
On arrival, `handlePayload` (`:124-137`) places the payload at the source position, clamped to the block's bounds. `moveInPayload` then moves it toward the centre at 0.7 px/tick, and the block counts as arrived when `payVector.isZero(0.01)`. That takes about 17 ticks for a 3x3 and about 29 ticks for a 5x5. Every `PayloadBlockBuild` holds **one** payload (`payload == null` in `acceptPayload`, `:119-121`).

### 0.4 Dumping a unit (`UnitPayload.dump()`, `world/blocks/payloads/UnitPayload.java:104-141`)
The dump fails, and is retried every tick, in three cases:
- `!Units.canCreate(team, type)`: the unit cap. A red warning flashes.
- the unit's solidity predicate is solid on the tile 1 px ahead of it (ground units: solid buildings and walls; leg units: `legsSolid`; flyers: never).
- **a non-flying unit, when any grounded unit of the same leg/non-leg class is within `physicSize*1.05`**. `pushOutput` (`PayloadBlock.java:75-89`) pushes units that stand in front of a dumping block aside, so this usually clears up by itself.

UnitFactory/Reconstructor override `dumpPayload` to send `Call.unitBlockSpawn` (`units/UnitBlock.java:35-39`).

### 0.5 Unit cap
`Units.canCreate` (`entities/Units.java:115-117`) compares `team.data().countType(type)` with the cap. Counts change only when a unit is added to or removed from the world (`UnitComp.java:602-616`). `handleUnitPayload` calls `unit.remove()` (`BuildingComp.java:702-709`).
=> **Units held as payloads in buildings (inside a factory, a refabricator, on a payload conveyor or in an assembler) do not count toward the cap.** The cap is checked only when a unit is dumped into the world, and by the assembler before it builds.

### 0.6 Payload size
`Payload.fits(s)` is `size()/8 <= s` (`Payload.java:55-57`). `UnitPayload.size() = unit.hitSize` (`UnitPayload.java:99-101`). `BuildPayload.size() = block.size*8`.

Unit hitSizes (`content/UnitTypes.java`):

| unit | hitSize | tiles |
|---|---|---|
| stell | 12 | 1.5 |
| locus | 18 | 2.25 |
| precept | 24 | 3.0 |
| vanquish | 28 | 3.5 |
| conquer | 46 | 5.75 |
| merui | 9 | 1.1 |
| cleroi | 14 | 1.75 |
| anthicus | 21 | 2.6 |
| tecta | 30 | 3.75 |
| collaris | 44 | 5.5 |
| elude | 11 | 1.4 |
| avert | 12 | 1.5 |
| **obviate** | **25** | **3.125** |
| quell | 36 | 4.5 |
| disrupt | 46 | 5.75 |

Large walls are 2x2, so their size is 16 px (2 tiles).

---

## 1. UnitFactory (tank/ship/mech-fabricator, 3x3)

Defined in `content/Blocks.java:6448-6480`:

| block | unit | time | cost | power |
|---|---|---|---|---|
| tank-fabricator | stell | 35 s | 40 Be + 50 Si | 1.5/tick = 90/s |
| ship-fabricator | elude | 40 s | 50 graphite + 70 Si | 90/s |
| mech-fabricator | merui | 40 s | 50 Be + 70 Si | 90/s |

All three have `configurable = false`, so `currentPlan` is forced to 0 (`UnitFactory.java:401-403`). They need no configuration.

Item capacity is 2× each requirement × `rules.unitCost(team)` (`:99-110`, `:465-468`). Items are accepted from any side, as long as the item is in the current plan (`:470-474`). Items are consumed all at once when the unit is created (`consume()`, `:443`). Power is drawn continuously.

The factory never accepts payloads: `acceptPayload` returns `false` (`:351-354`). It is a pure source.

Update loop (`world/blocks/units/UnitFactory.java:399-451`):
```java
if(efficiency > 0 && currentPlan != -1){ progress += edelta() * state.rules.unitBuildSpeed(team); ... }
moveOutPayload();                                          // 0.2
if(currentPlan != -1 && payload == null){
    if(progress >= plan.time){ ... Unit unit = plan.unit.create(team);   // NOT added to world
        payload = new UnitPayload(unit); payVector.setZero(); consume(); }
}else{ progress = 0f; }                                    // holding a unit => progress reset
```
`shouldConsume()` returns `enabled && payload == null && team.activateUnitFactories()` (`:453-457`).

**Answer to Q1.** The finished unit becomes a `UnitPayload` at the centre. `moveOutPayload` slides it to the front edge in about 17 ticks. Then:
- If the front tile holds a payload-capable block, the unit is handed over as a payload with `movePayload → front.acceptPayload/handlePayload`, retried every tick.
- If the front tile is empty or non-solid, the unit is dumped as a free unit through `UnitBuild.dumpPayload → UnitPayload.dump()`, which is subject to the cap, solidity and overlap checks.
- If the front tile is solid with no payload support, the factory is stuck.

While it holds the unit the factory makes nothing (`shouldConsume` is false and progress is reset to 0). Real cycle time = `plan.time` + about 17 ticks + however long the hand-off waits.

The command and command position set on the factory are applied to the unit when it is created (`:431-438`).

---

## 2. Reconstructor (tank/ship/mech-refabricator 3x3, prime-refabricator 5x5)

Defined in `content/Blocks.java:6482-6556`:

| block | size | upgrades | time | items per unit | liquid | power |
|---|---|---|---|---|---|---|
| tank-refabricator | 3 | stell→locus | 30 s | 40 Si + 30 W | hydrogen 3/s | 3/tick = 180/s |
| ship-refabricator | 3 | elude→avert | 50 s | 60 Si + 40 W | hydrogen 3/s | 150/s |
| mech-refabricator | 3 | merui→cleroi | 45 s | 50 Si + 40 W | hydrogen 3/s | 150/s |
| prime-refabricator | 5 | locus→precept, cleroi→anthicus, avert→obviate | 60 s | 80 thorium + 100 Si | nitrogen 10/s | 270/s |

Capacities:
- Items: 2× the per-unit cost × `unitCost` (`Reconstructor.java:144-156`), so 80 Si/60 W for the tank refab and 160 thorium/200 Si for the prime.
- Liquids: `liquidCapacity` defaults to 10 × the per-second consumption (`world/Block.java:1428-1440`), so 30 hydrogen and 100 nitrogen.

Items and liquids enter from any side. Liquid and power are consumed only while `constructing` (a unit is inside that can be upgraded, `:376-379`, `:393-395`). Items are taken all at once on completion (`consume()`, `:358`).

**Input side** (`world/blocks/units/Reconstructor.java:241-266`):
```java
if(!(this.payload == null && (this.enabled || source == this)
     && relativeTo(source) != rotation          // anything EXCEPT the front
     && payload instanceof UnitPayload pay)) return false;
return upgrade != null && (team.isAI() || upgrade.unlockedNowHost()) && !upgrade.isBanned();
```
- It accepts from the **back, left or right**, never from the front. `relativeTo(Building)` (`BuildingComp.java:536-545`) compares centre positions along the dominant axis.
- It takes only a `UnitPayload` whose type is in `upgrades`. Walls and wrong unit types are refused, and a source facing it then waits forever (0.2). AI teams skip the research check.
- It holds one unit and has no size limit, so a 3x3 refabricator would accept any unit that is in its upgrade list.

**Free-walking units ("enterPayload" command).** `acceptUnitPayload` returns `hasUpgrade(type)` (`:194-197`). `CommandAI` (`ai/types/CommandAI.java:231-239`) calls `build.canControlSelect(unit)` and then `build.acceptPayload(build, payload)` with `source == build`. In that case `relativeTo` is -1, so a walking unit can enter **from any side, including the front**. `canControlSelect` (`PayloadBlock.java:103-108`) has two cases:
- ground, non-leg units must be within `size*8*0.7 + hitSize/2` of the centre;
- leg, hover and flying units must stand on one of the block's tiles.

Units created by the core are refused.

**Processing** (`:325-367`). It moves the unit in (about 17 ticks for 3x3, 29 for 5x5). Only when the unit has arrived does `progress += edelta()*unitBuildSpeed` run. When `progress >= constructTime` it replaces `payload.unit` with the upgraded type (applying its own command and command position) and calls `consume()`.

**Output.** The result has no further upgrade in this block (`!hasUpgrade`), so `moveOutPayload()` sends it **to the front**, using the same rules as 0.2.
- If the front is blocked, the upgraded unit sits inside and no new unit is accepted (one slot). Nothing else happens: there is no timeout and nothing is destroyed.
- The reconstructor itself has **no unit-cap check**. The cap applies only when the unit is dumped into the world.

Chain throughput: one unit per (`constructTime` + move in + move out + hand-off) per block.

---

## 3. UnitAssembler (tank/ship/mech-assembler, 5x5) and the assembler module

Defined in `content/Blocks.java:6558-6610`. Plans are listed in tier order:

| assembler | tier 0 (no module) | tier 1 (≥1 basic module) | cyanogen | power |
|---|---|---|---|---|
| tank | vanquish, 50 s: 4 stell + 10 tungsten-wall-large | conquer, 180 s: 6 locus + 20 carbide-wall-large | 9/s | 150/s |
| ship | quell, 60 s: 4 elude + 12 beryllium-wall-large | disrupt, 180 s: 6 avert + 20 carbide-wall-large | 12/s | 150/s |
| mech | tecta, 70 s: 5 merui + 12 tungsten-wall-large | collaris, 180 s: 6 cleroi + 20 carbide-wall-large | 12/s | 180/s |

Liquid capacity is 10 × the per-second consumption: 90 for tank, 120 for ship and mech. Payload counts are multiplied by `unitCost(team)`. There are no item requirements: `itemCapacity` is 10 and `acceptItem` returns false because `itemReq` is null (`:739-743`).

### 3.1 Geometry (`world/blocks/units/UnitAssembler.java:76-84`, `:336-340`)
```java
rect.setCentered(x, y, areaSize * tilesize);
float len = tilesize * (areaSize + size)/2f;      // (13+5)/2 = 9 tiles
rect.x += d4x(rotation) * len; rect.y += d4y(rotation) * len;
getUnitSpawn() = (x,y) + d4(rotation) * len       // the centre of the area
```
In block-local coordinates (block centre = (0,0), "forward" = rotation):
- The block occupies forward −2..2 and lateral −2..2.
- **The area is 13×13 tiles at forward 3..15 and lateral −6..+6.** Its back edge touches the block's front face exactly. It is 4 tiles wider than the block on each side.
- **The unit spawn point is forward 9, lateral 0**, the centre of the area.
- No other assembler area of the same team, built or under construction, may overlap this one. The test rect is grown by 0.1 px, so **two areas cannot even share an edge**: they need at least a 1-tile gap (`canPlaceOn`, `:100-107`). Nothing else limits placement. The map border is not checked, and other buildings are allowed inside the area.

### 3.2 Does the area have to be empty?
Only the spawn point is checked, in `checkSolid` (`:679-685`), which feeds `wasOccupied` (`:518`):
```java
(!output.flying && collisions.overlapsTile(rect(spawn, output.hitSize), EntityCollisions::solid)) ||
Units.anyEntities(box(spawn, output.hitSize*1.4), u -> !u.spawnedByCore && same layer as output)
```
- **Ground and leg outputs** (vanquish, conquer, tecta, collaris): no solid tile, static wall or solid building may lie in the `hitSize`-wide square centred on the spawn. That square is 3.5 tiles for vanquish, 5.75 for conquer, 3.75 for tecta and 5.5 for collaris.
- **Flying outputs** (quell, disrupt): no tile check.
- **Units in the way:** no unit of the same layer may be within a `1.4·hitSize` box of the spawn. That box is about 39 px (4.9 tiles) for vanquish and about 64 px (8 tiles) for conquer.
- While the spawn is occupied the assembler **does not progress and does not even take payloads in** (`moveInPayload() && !wasOccupied`, `:504`; the module code at `UnitAssemblerModule.java:138`).
- **The assembler does not push its finished unit away.** That unit appears exactly at the spawn and blocks the next one until it moves off. Set a command position, or order the unit away.
- Non-solid buildings (payload conveyors, ducts) and solid buildings elsewhere in the area are fine. The drones fly.

### 3.3 Drones
`dronesCreated = 4` and `droneConstructTime = 240 ticks` (`:47-49`). Drones are built one after another at the block centre, at a rate scaled by power status (`:478-496`). It takes about 16 s for four drones at full power.

Each drone flies to one corner of the area: `targetPos = spawn + trns(i*90+45, areaSize/2*√2*8)` (`:510-516`). Build speed is scaled by `eff = drones in position / 4` (`:520`, `:531`). If drones are killed, the build slows proportionally and new drones are built.

The drone type `assembly-drone` is flying, has `useUnitCap = false` and `allowedInPayloads = false` (`UnitTypes.java:4609-4626`).

### 3.4 Payload input
`acceptPayload` (`:725-732`):
```java
return (this.payload == null || source instanceof UnitAssemblerModuleBuild) &&
    plan.requirements.contains(b -> b.item == payload.content() &&
        blocks.get(payload.content()) < round(b.amount * unitCost) - (module && slot holds same ? 1 : 0));
```
- **There is no side restriction.** Payloads may come from any of the four sides, even from inside the area. A payload conveyor or router, a fabricator or refabricator pointed at it, or a constructor can all feed it directly. The conditions:
  - Block-to-block: the source's front tile must be on the assembler (0.1).
  - Payload conveyor: its centre must be within ±1 tile sideways of the assembler centre (see 5.2).
- Only content the **current plan** still needs is accepted, up to the required count. Anything else is refused, and the source waits forever.
- **The main body has one slot.** A payload is moved to the centre (20 px, about 29 ticks) and then "yeeted" into the internal `PayloadSeq blocks` (`:504-507`, `:692-699`). The move does not need power.
- Free-walking units can enter with `enterPayload` (`acceptUnitPayload`, `:713-718`).
- Building starts **only when the whole requirement list is inside**: `ConsumePayloadDynamic.efficiency` is 0 until `blocks` contains everything (`world/consumers/ConsumePayloadDynamic.java:20-28`; `shouldConsume`, `:393-396`). It also needs `Units.canCreate(team, plan.unit)` (`:395`, `:528`).
  - `progress += edelta*unitBuildSpeed*eff/plan.time`.
  - On completion `spawned()` (`:547-575`) calls `consume()` and `blocks.clear()`.
  - **Refilling is not pipelined.** The next batch can only start loading after the unit spawns. Cycle ≈ refill time + `plan.time`.
- Refill rate through the main slot:
  - From a payload conveyor: one payload per conveyor step (45 ticks, or 35 for reinforced). All conveyors run on the same global clock (5.1) and share the one slot, so several feeding conveyors do not speed this up.
  - From a block pointing straight at it: about one payload per 29 ticks.
  - Example: vanquish needs 14 payloads, about 10.5 s through a conveyor. Conquer needs 26, about 19.5 s plus the time to produce them.

### 3.5 Result
`spawned()` (`:547-566`) does `unit.set(spawn)` and `rotation = rotdeg()`. If the building under the spawn (`unit.buildOn()`) is of the same team and accepts the payload, the unit is handed to it as a payload (source = that building). Otherwise it is added to the world as a free unit.

So a payload conveyor under the spawn point captures the result:
- That is allowed for ground output, because payload conveyors are not solid.
- But conquer (46), disrupt (46), quell (36), tecta (30), collaris (44) and vanquish (28) are all larger than the 3-tile limit, so a conveyor would refuse them anyway.

In practice T4/T5 units always leave as free units at the area centre. Only the command **position** is copied (`:553-555`). The command itself is the unit's default.

### 3.6 Assembler modules ("basic-assembler-module")
Defined at `content/Blocks.java:6603-6610`: 5x5, `tier = 1` (default, `UnitAssemblerModule.java:22`), power 3.5/tick = 210/s, cost 300 carbide, 500 thorium, 250 oxide, 400 phase fabric. It is the only module in vanilla.

**Where it attaches.** It does not go at the back of the assembler; it sits **on the edge of the area, facing into it**. `moduleFits` (`UnitAssembler.java:342-355`):
```java
dx,dy = moduleCentre + d4(modRot) * (size/2 + 0.5) * 8;    // centre of the tile row just in front of the module (3 tiles ahead)
require Tile.relativeTo(moduleCentre, spawn) == modRot;     // module faces the spawn (dominant axis)
require Chebyshev(dx,dy ; spawn) == 8*13/2 - 4  (= 6 tiles) // that tile lies on the area's outer ring
```
So the module lies entirely **outside** the 13×13 area, with its front face against the area border, facing the area centre.
- **Far side** (assembler at origin facing forward): module centre at forward 18, lateral −6..+6.
- **Left and right sides:** module centre at lateral ±9, forward 3..15, rotated to face the area.
- **Assembler side** (facing forward like the assembler): forward 0 with lateral ±5 or ±6, i.e. **beside** the assembler, sharing its edge. Lateral ±7 or more fails.
- Placement requires an existing assembler it fits (`canPlaceOn`, `UnitAssemblerModule.java:49-51`). The link is rechecked whenever the world's tiles change (`:132-136`).

**Tier.** `checkTier` (`UnitAssembler.java:367-381`) sorts the modules and counts consecutive tiers starting at 0. With one or more basic modules `currentTier = 1`. `plan()` is `plans.get(min(currentTier, plans.size-1))` (`:387-390`).
- **The plan is chosen by the module tier and nothing else. With a module attached the assembler can only build the tier-5 unit (conquer/disrupt/collaris). Without one it can only build the tier-4 unit (vanquish/quell/tecta).** To get both you need two assemblers, or you add/remove the module.
- Any tier change resets `progress` to 0 (`:445-452`). Payloads already loaded stay until the next spawn.
- Extra modules raise nothing further. They only add input ports.
- The tier is counted even if the module has no power. Power is needed only for the module to pass payloads on.

**Module as an input port** (`UnitAssemblerModule.java:109-112`, `:131-142`). It accepts payloads from any side if `link.acceptPayload(this, p)` holds; this check skips the "assembler slot is empty" condition. It moves the payload to its centre and then yeets it straight into the assembler's `blocks`. That needs `efficiency > 0` (power) and `!link.wasOccupied`.

So each module adds one parallel input slot. That matters for the 26-payload tier-5 recipes.

---

## 4. Constructor / large-constructor (BlockProducer)

`content/Blocks.java:6702-6724`, `world/blocks/payloads/{BlockProducer,Constructor}.java`.

| | constructor | large-constructor |
|---|---|---|
| size | 3 | 5 |
| buildSpeed | 0.6 | 5 |
| block sizes it can make | 1..2 (`Constructor.java:22`) | 3..4 |
| filter | tungsten/beryllium/carbide/reinforced-surge wall large, reinforced liquid container, reinforced container, beam node | none (any visible, buildable, non-core block of size 3-4) |
| power | 2.5/tick = 150/s | 5/tick = 300/s |

- **Configuration.** `config(Block.class, ...)` (`Constructor.java:32-37`) sets `recipe` if `canProduce(b)` is true (`:54-56`). A new recipe resets progress. The default is null, which builds nothing. An AI can call `build.configure(Blocks.tungstenWallLarge)`.
- **Items.** It consumes the recipe's `requirements` scaled by `ceil(amount * rules.buildCostMultiplier)` (`BlockProducer.java:40-56`). It accepts from any side up to 2× each requirement (`:91-103`).
- **Production** (`BlockProducer.java:115-137`): `progress += buildSpeed*edelta()` until `recipe.buildTime`. Then it creates a `BuildPayload` and pushes it out **to the front** with `moveOutPayload` (0.2). It accepts no payloads (`:105-108`) and holds one product at a time.
- **The output can never be dumped**, because `BuildPayload` cannot be dumped. The front tile must therefore be a payload acceptor that takes walls: a payload conveyor or router, an assembler or module whose plan needs that wall, or a mass driver. Otherwise the constructor stops.
- **Build times.** `buildTime = Σ amount·item.cost × block.buildCostMultiplier` (`world/Block.java:1453-1464`). Item costs: Be 1.2, W 1.5, thorium 1.1, carbide 1.4 (`content/Items.java`). Each large wall needs 24 of each of its items (`Blocks.java:1843-1903`).

  | wall | buildTime | constructor time | per-unit need |
  |---|---|---|---|
  | tungsten-wall-large | 24 W ×1.5 ×5 = 180 ticks | 300 ticks = **5.0 s** | 10 per vanquish (50 s, equal to its build time); 12 per tecta (60 s) |
  | beryllium-wall-large | 24 Be ×1.2 ×5 = 144 ticks | 240 ticks = **4.0 s** | 12 per quell (48 s) |
  | carbide-wall-large | (24 thorium ×1.1 + 24 carbide ×1.4) ×1 = 60 ticks | 100 ticks = **1.67 s** | 20 per T5 (33 s) |

  These rates are not scaled by `unitBuildSpeed`.
- The large constructor cannot make 2x2 walls (`minBlockSize = 3`), so it plays no part in unit chains.

---

## 5. Payload conveyor, router and mass driver

### 5.1 Payload conveyor (`world/blocks/payloads/PayloadConveyor.java`)
`payload-conveyor`: graphite 10 + copper 10, `moveTime` 45. `reinforced-payload-conveyor`: tungsten 10, `moveTime` 35, health 800 (`Blocks.java:6626-6643`). Both have:
- size 3, `payloadLimit` 3 tiles (`:21-31`), so a unit fits if `hitSize ≤ 24 px`;
- `solid = false`, so units walk over them and are pushed along (`unitOn`, `:273-281`);
- no power.

**Timing is global.** `progress = Time.time % moveTime` and `step = (int)(Time.time / moveTime)` (`:149`, `:387-389`). Every conveyor on the map steps at the same moment. At each step boundary it hands its item to `next` (`:161-193`). A payload moves one conveyor (3 tiles) per step:
- normal: 3 tiles / 0.75 s = **4 tiles/s**, at most **1.33 payloads/s**;
- reinforced: 3 / 0.583 s = **5.14 tiles/s**, **1.71 payloads/s**.

A full line moves as one, because `next.updateTile()` runs first.

**Acceptance** (`:284-288`):
```java
return this.item == null && payload.fits(payloadLimit) && (source == this || this.enabled && progress <= 5f);
```
- It accepts from **any side** (back, left, right, even its own front) when empty, during the first **5 ticks of each global step**. A block that is pushing retries every tick, so the wait is at most one step.
- Merging is first come, first served. T-junctions work.

**Where it sends payloads** (`onProximityUpdate`, `:101-122`). `next` is the building on the tile 2 ahead, and only counts if one of these holds:
- **the same size (3) and exactly grid-aligned**: `accept.tile == this.tile + d4(rot)*3`, with no sideways offset. This applies to conveyor→conveyor, conveyor→3x3 refabricator, conveyor→mass driver and conveyor→loader;
- **a larger block** whose centre is within `(S−3)*8/2` px sideways. For a 5x5 (prime refabricator, assembler, module) that is **±1 tile**;
- a **smaller** block (size 1-2) is never `next`.

If `next == null` and the front tile is not solid (`checkBlocked`, `:124-128`), the item is **dumped**: units go into the world (0.4) and walls stay stuck. So **a misaligned 3x3 refabricator in front of a conveyor will not be fed**. The ground unit is not dumped either, because the refabricator's tile is solid, so the line stalls. A flying unit (avert) gets dumped as a free unit.

**Outputs that do not fit:** an obviate (25 px > 24) coming out of a prime refabricator that faces a payload conveyor is **refused forever**, so the prime refabricator blocks. The same applies to any T4/T5 unit.

### 5.2 Payload router (`world/blocks/payloads/PayloadRouter.java`)
`payload-router` (graphite 15 + copper 10, `moveTime` 45) and `reinforced-payload-router` (tungsten 15, `moveTime` 35) are subclasses of the conveyor. They have the same size, timing, acceptance and `next` alignment rules, and their `rotation` is changed at run time.

- **Sorting** is set with `config(Block)` or `config(UnitType)` (`:36-38`).
- `recDir = source.relativeTo(this)`, the direction of travel (`:127-135`).
- **A matching payload always goes straight on** (`rotation = recDir`).
- A non-matching one rotates clockwise looking for an accepting `next`. When a sort item is set it **skips the straight direction**. It **does not skip the direction it came from**, so if the feeding conveyor is empty it can send the payload back into it (`:76-103`). The source conveyor accepts from any side.
- With no sort item set it cycles through all 4 directions, back included.
- `invert` (false in vanilla) would flip the match.
- The configured sort is compared by exact type, so a router can split stells from walls, or one unit type from another.

### 5.3 Payload mass driver (`world/blocks/payloads/PayloadMassDriver.java`)

| | payload-mass-driver | large-payload-mass-driver |
|---|---|---|
| size | 3 | 5 |
| range | 700 px = 87.5 tiles | 2100 px = 262.5 tiles |
| max payload | 2.5 tiles (20 px) | 4 tiles (32 px) |
| reload + charge | 45 + 70 = 115 ticks, about 0.52 shots/s | 130 + 100 = 230 ticks, 0.26/s |
| power | 0.5/tick = 30/s | 3/tick = 180/s |
| cost | W 40, Si 50, graphite 20 | phase 10, W 120, Si 150, graphite 60, oxide 30 |

Defined at `content/Blocks.java:6654-6675`.

- **Accepts** from any side if empty and `payload.size() ≤ maxPayloadSize*8` (`:466-469`).
- The small driver therefore takes stell, locus, merui, cleroi, elude, avert and 2x2 walls. It does **not** take precept (24), anthicus (21) or obviate (25).
- The large driver also takes all T3 units, vanquish (28) and tecta (30). It does not take quell, conquer, disrupt or collaris.
- **The link** is set with `config(Point2/Integer)`. It must point to a driver of the **same block type**, the same team and within range (`linkValid`, `:471-473`).
- A driver with a valid link is a **sender**: it loads the payload and fires once both turrets are aligned and charged.
- A **receiver** (idle or accepting state) outputs with `moveOutPayload` toward **its own rotation (front)** (`:222-234`).
- Both ends need power. A shooter with no power is dropped from the queue (`:201-210`), and a receiver with no power does not rotate to accept (`:236-239`).
- Several senders can target one receiver; they are queued. A receiver that also has a link fires the payload on, acting as a relay.

---

## 6. Payload loader / unloader, deconstructor, cargo blocks (not useful for unit chains)

- **payload-loader** (3x3, 2/tick base power, up to 40/tick extra for batteries, `Blocks.java:6726-6733`; `PayloadLoader.java:89-101`). It accepts **only `BuildPayload`s** that store items (unloadable, capacity ≥ 10, size ≤ 3), liquids (capacity ≥ 10) or buffered power. It fills them and sends them out the front.
- **payload-unloader** does the reverse and outputs the items, liquids or power. Walls and units are refused, so a loader in a unit line would just stall it.
- **small-deconstructor/deconstructor** (`PayloadDeconstructor.java:131-153`) break payloads back into items. They are sinks.
- **unit-cargo-loader** and **unit-cargo-unload-point** carry **items** with "manifold" units (`UnitCargoLoader.java:24`). They have nothing to do with payloads.

---

## 7. Placement checklist for the layout generator

1. **Every hand-off checks one tile**: the tile `size/2+1` ahead of the source centre along its rotation (0.1).
   - **Block → block** (fabricator, refabricator, constructor, mass-driver receiver into anything): any target covering that tile works, with no alignment.
   - **Payload conveyor or router → anything**: the target must be a 3x3 aligned exactly on the 3-grid, or a 5x5 whose centre is within ±1 tile sideways (5.1).
2. **Refabricator inputs**: back, left or right only (`relativeTo(source) != rotation`). Its output is always its front.

   Fabricator → refabricator → prime refabricator, or fabricator → refabricator → assembler, can therefore be built by **touching blocks with no conveyors**. Example: tank fabricator(rot 0) centred at x=0, tank refabricator(rot 0) at x=3, then a 5x5 (prime refabricator, or a tank assembler with a module that wants locus) centred at x=7. Each target covers the source's front tile. Lines can turn, because a refabricator also accepts from its sides. Assemblers never take T3 units, so a prime refabricator is always the end of a chain.
3. **The assembler takes payloads from all four sides**, but only what its current plan still needs. Units and walls share its single main slot, and modules add slots. Typical layout:
   - a fabricator pointing into one side, a constructor (set to the wall) into another; or
   - conveyors merging into one feed line, conveyors accepting from any side.

   The assembler must be **fed only the right content**. Anything else (for example, stells arriving at an assembler that has a module) blocks the feeding line forever.
4. **Keep the assembler's spawn clear.** The area is 13×13 directly in front of the assembler, laterally centred, forward 3..15 tiles.
   - No solid tile or building within ½·hitSize of the area centre for ground/leg outputs (vanquish 3.5, tecta 3.75, conquer/collaris about 6 tiles square).
   - No own units within the 1.4·hitSize box. Set the assembler's command position so new units walk off.
   - Areas of the same team must not overlap or touch (≥1 tile gap). Other buildings in the area are allowed.
   - The drones need the four area corners to be reachable by air; they fly.
5. **Modules**: 5x5, outside the area, front face on the area border, facing the area centre. On the assembler's own side that means flush beside it (lateral ±5/±6). **One module switches the assembler to its T5 recipe only.** The assembler must be finished before the module can be placed.
6. **Size limits**:
   - payload conveyors and routers: units with hitSize ≤ 24, so **obviate does not fit**, nor any T4/T5;
   - small mass driver: ≤ 20 (T1, T2 and walls);
   - large mass driver: ≤ 32.
7. **Dead ends**:
   - A unit can leave as a free unit only if the front tile of the last block is empty or non-solid and the cap allows.
   - Walls never leave a conveyor or constructor except into an acceptor.
   - A payload-capable front that refuses blocks the source forever. There is no "else dump".
8. **Unit cap**: units inside buildings or on conveyors are **not** counted. Factories and refabricators keep working at the cap; only the final dump into the world waits. Assemblers check the cap before building.
9. **Timing**:
   - fabricators 35/40/40 s;
   - refabricators 30/50/45 s, prime 60 s;
   - assemblers 50/60/70 s (T4) and 180 s (T5);
   - constructor 5/4/1.67 s per tungsten/beryllium/carbide large wall;
   - move-in/out about 17 ticks for 3x3 and 29 for 5x5;
   - conveyor 0.75 s (0.583 s reinforced) per 3 tiles.

   Everything scales with `rules.unitBuildSpeed(team)` (the constructor does not) and costs scale with `rules.unitCost(team)`. **The assembler starts building only once the whole batch is inside, and the batch is cleared at spawn**, so refilling adds to every cycle.
10. **Rotation drawing is cosmetic only.** Erekir fabricators are not configurable (fixed plan). Refabricators are configurable only to set the command. The constructor must be configured with the wall type. A router can be configured to sort.
