# Mindustry v160.7 — item logistics rules for Erekir layouts

Source read: `/home/zoudianyuan/src/Mindustry-v160.7/core/src/mindustry` (paths below are relative to it).
Everything was read from code; the game was not run. "tick" = 1/60 s at normal speed (`delta()==1`).
Directions: `rotation` 0=+x (right), 1=+y (up), 2=-x, 3=-y. A block's "back" is `rotation+2`.

Two notes on the helpers the code uses:

* `Edges.getFacingEdge(source.tile, tile)` (world/Edges.java:57-67) gives back **the tile of the source block nearest to the receiver**. `Tile.relativeTo(x,y)` (world/Tile.java:87-93) is then **the direction from that source tile to the receiver**. So "`facingEdge(source).relativeTo(this) == rotation`" means "the source sits directly **behind** me".
* `proximity` only holds adjacent buildings of the **same team** (`entities/comp/BuildingComp.java:1894`). Every push either goes through `proximity` or `front()`, and `moveForward` checks `other.team == team` (BuildingComp.java:1134-1141).

---

## 0. The basic push mechanisms (BuildingComp.java)

| method | lines | semantics |
|---|---|---|
| `acceptItem` default | 869-871 | `block.consumesItem(item) && items.get(item) < getMaximumAccepted(item)`: only items in the block's consume filter, **per-item** cap = `itemCapacity` (default 10, world/Block.java:77). **No direction check.** |
| `moveForward(item)` | 1134-1141 | gives the item only to `front()`; `front.acceptItem(this,item)` must be true. |
| `dump(item)` | 1077-1119 | goes through `proximity` round-robin from `cdump`. It **moves at most ONE item per call**, to the first neighbour whose `acceptItem` is true. `incrementDump` runs for each neighbour tried. With `item==null` it tries every item the block holds, by item id. |
| `offload(item)` | 1006-1022 | the newly produced item goes to the first accepting neighbour (round-robin). If none accepts, it goes into the block's **own** inventory (`handleItem(self)`), which ignores capacity. |
| `dumpAccumulate` | 1058-1066 | `dump()` once per accumulated tick, so up to 60/s. |
| `front()/back()` | 556-565 | `nearby(d4(rotation) * (size/2+1))` from the building's origin tile. |

Quirk: for **even** sizes the origin tile is the lower-left of the 2×2 centre. So `front()` toward −x/−y lands **one tile past** the adjacent tile. Size 2 covers x..x+1, but front at rot 2 is x−2. None of the rotated item blocks on Erekir that use `front()` (ducts, routers, unloader, factories of size 3/5) are even-sized, so this only matters for custom content.

**dump rate cap:** almost every producer dumps on `timer(timerDump, dumpTime)` with `dumpTime = 5` (Block.java:445). So one producer pushes at most **12 items/s through `dump()`**, however many neighbours it has. The `offload()` done at the moment of production comes on top of that.

`edelta() = efficiency*delta()` (BuildingComp.java:1934). Blocks with no consumers (ducts, routers, bridges, unloader) have efficiency 1 while enabled (BuildingComp.java:1952-1954).

---

## 1. Duct / armored duct (world/blocks/distribution/Duct.java)

Content (content/Blocks.java:2179-2192): `duct` speed=4, `armored-duct` speed=4, armored=true. Both: `itemCapacity=1`, `solid=false`, `rotate=true`, `isDuct=true`, `unloadable=false` (Duct.java:39-52).

### Acceptance (Duct.java:219-228)
```java
return current == null && items.total() == 0 && (armored ?
    ((source.block.rotate && source.front() == this && source.block.hasItems && source.block.isDuct) ||
      Edges.getFacingEdge(source.tile, tile).relativeTo(tile) == rotation) :
    !(source.block.rotate && next == source) && Edges.getFacingEdge(source.tile, tile) != null &&
      Math.abs(Edges.getFacingEdge(source.tile, tile).relativeTo(tile.x, tile.y) - rotation) != 2);
```
* A duct holds **exactly one item**. It accepts only while empty.
* **Plain duct:** accepts from the **back and both sides**, from **any** source block: ducts, routers, machines, drills, bridges, unloader. It **refuses only from its front.**
* **Armored duct:**
  * From the **back:** accepts from anything.
  * From the **sides:** accepts only from a block that is `rotate && isDuct && hasItems && source.front()==this`. `isDuct` is set only on Duct and armored duct (Duct.java:50), DuctBridge (DuctBridge.java:17) and duct-unloader (DirectionalUnloader.java:41). So a duct / bridge end / unloader **pointing into** its side is accepted. **Machines, drills, the core, duct-router, overflow/underflow-duct, surge conveyor and surge router are refused from the side** (DuctRouter/OverflowDuct do not set `isDuct`).
  * Odd case: it would even accept from its front if a duct there faces into it.
* Ducts are omni-acceptors from 3 sides but **never push sideways**: they output only with `moveForward` to `front()`.

### Movement and throughput (Duct.java:200-216, 244-250)
```java
progress += edelta() / speed * 2f;                      // 0.5 per tick at speed 4
if(current != null && next != null){
    if(progress >= (1f - 1f/speed) && moveForward(current)){ ... current = null; ... }
}else progress = 0;
// handleItem: progress = -1f
```
* An item arrives with progress −1. It may leave once progress ≥ 0.75, i.e. after 1.75/0.5 = **3.5 ticks**, which means the 4th tick (or the 3rd, depending on update order).
* Per-duct cycle = **4 ticks → 15 items/s**. This matches the stat `60/speed` = 15/s (Duct.java:62).
* Transit speed is about 3.5 ticks per tile ≈ 17 tiles/s.
* `next = front()` is cached in `onProximityUpdate` (Duct.java:262).
* **If `next == null` (nothing in front), progress is held at 0 and the item sits there forever.**
* **If the front block refuses** (`moveForward` false), the item stays, the duct stays full and refuses input, and the whole line behind stops. This is strict FIFO with head-of-line blocking: one refused item at the head stops every item behind it, even items the front would accept.

### Merging
The single slot goes to whichever upstream block calls `handleItem` first after the duct empties. That is decided by the building update order (`Groups.build.update()`, core/Logic.java:491); there is no fairness logic. If a saturated back input and a saturated side input feed one duct, one of them can be **starved completely**. The merged output is capped at 15/s anyway.

### Notes
* There is **no duct junction on Erekir**. `DuctJunction` exists as a class, but no content instance is defined (Duct.java:70 has the line commented out, and there is no `new DuctJunction` in Blocks.java). Line crossings need a duct-bridge.
* `getReplacement`/`handlePlacementLine` (Duct.java:111-129) only auto-insert bridges/junctions for player drag-placement. They do not matter for direct placement.

---

## 2. Duct router (DuctRouter.java) — also the Erekir "sorter"

Content: speed=4, `itemCapacity=1`, `solid=false`, rotate=true (Blocks.java:2194-2201, DuctRouter.java:26-41). It is not `isDuct`.

* **Input: back only** (DuctRouter.java:160-163): `current == null && items.total()==0 && facingEdge(source).relativeTo(tile) == rotation`. A duct entering from the router's side is **refused**, and that duct then stalls.
* **Output** (DuctRouter.java:138-157): round-robin over `proximity` from `cdump`. It skips the neighbour in direction `rotation+2` (the back) and needs `other.team == team && other.acceptItem(this,current)`. So it gives to **front, left, right**; it never pushes back into the source.
* **Sort filter** (`sortItem` set by config): `!(sortItem != null && (current == sortItem) != (rel == rotation))`. The **sort item goes only to the front. Every other item goes only to the sides.** With no filter, items go round-robin to front/left/right.
* **Timing:** same as a duct (DuctRouter.java:101-121). Progress −1 → ≥0.75 at 0.5/tick, so **15 items/s total**, split over the outputs.
* **Jams:** if no allowed target accepts the current item, it waits forever and input stops. Examples: a sorted router whose front refuses the sort item, or both sides refusing a non-sort item. Mixed lines into a sorter are safe only if every item type always has somewhere to go.
* **Leaks:** a router pushes into **any** accepting neighbour on its front/sides. That includes a passing plain duct whose side touches the router, a machine, the core, or a turret. Leave sides that must not receive items empty, or use armored ducts there.

## 3. Overflow duct / underflow duct (OverflowDuct.java)

Content: both speed=4, cap 1, `solid=false`; `underflow-duct` has `invert=true` (Blocks.java:2203-2218).

* **Input: back only** (OverflowDuct.java:135-138), same rule as the router.
* **Overflow** (`invert=false`) (OverflowDuct.java:98-131):
  1. Front, if it accepts.
  2. Otherwise the sides, starting with right (rotation−1) or left depending on `cdump`. `cdump` toggles 0↔2 after *every* successful hand-off, including hand-offs to the front (line 82).
  3. Never the back.
* **Underflow** (`invert=true`): sides first. If only one side accepts, that side gets it; if both accept, it alternates by `cdump`. If no side accepts, the front gets it; otherwise it waits (`if(invert) return null` after the front check).
* Throughput is 15 items/s, the same cycle as a duct.
* Jams in the same way as a duct if no allowed output accepts the head item.
* Typical use is excess-diversion before a machine. For example: a duct into an overflow duct, with the machine in front and a side branch that goes to the core.

## 4. Duct bridge (DuctBridge.java + DirectionBridge.java)

Content: `duct-bridge` speed=4, `itemCapacity=4` (DuctBridge.java:14), **range = 4** (DirectionBridge.java:31; Blocks.java:2220-2227 does not override it). It is **`solid = true`** (DirectionBridge.java:36), rotate=true, `allowDiagonal=false`, `isDuct=true`, and unloadable (it has the default `unloadable=true`).

* **Link** (`findLink`, DirectionBridge.java:252-260): it scans the tiles at distance 1..4 in its **own rotation direction** and links to the **first** `duct-bridge` of the same team it meets, **whatever that bridge's rotation is**. So at most 3 tiles can lie between two bridges.
  * The span ignores everything in between: buildings, walls, cliffs, other ducts. Nothing in between is checked.
  * Hazard: any duct-bridge tile in the scan path, for example from a perpendicular bridge line, **captures the link**. Do not put bridges of other lines inside a span.
  * If B faces back toward A, then A→B and B→A, and items ping-pong forever.
* **Chaining:** yes. A bridge whose link exists sends to its link. A bridge with no link is an **output end**. The end bridge outputs in **its own rotation**, so chains can turn corners: A(rot 0)→B(rot 1)→C above B.
* **Acceptance** (DuctBridge.java:61-67):
  ```java
  if(findLink() == null) return false;             // output end accepts NOTHING from neighbours
  int rel = relativeToEdge(source.tile);           // direction bridge → source
  return items.total() < itemCapacity && rel != rotation && occupied[(rel + 2) % 4] == null;
  ```
  * An input/intermediate bridge accepts **any item, mixed**, from the back and both sides. It refuses from its front.
  * It also refuses from the side an incoming bridge arrives from. `occupied[r]` is the incoming bridge with rotation r (set at line 33).
  * Example: A(x=0, rot 0)→B(x=3). A duct at x=2 pointing into B is **refused**. Feed B from its sides only.
  * The bridge-to-bridge transfer calls `link.handleItem` directly (line 39), not `acceptItem`.
* **Transfer** (DuctBridge.java:30-44): `progress += edelta()`, and `while(progress > speed)` it sends one item, so **1 item / 4 ticks = 15 items/s**. Progress only builds up while it has items and the link has fewer than 4. Items go out with `items.take()`, which rotates over item ids, so the buffer is **not FIFO**.
  * Rare item loss: if `progress > 2*speed` (lag spike) and the link fills mid-loop, the item has already been `take()`n and is dropped.
* **Output end** (DuctBridge.java:46-51): `moveForward(items.first())` every tick, so up to 60/s, limited by the front. `first()` is the **lowest item id present** (world/modules/ItemModule.java:189-196).
  * **Mixed-item jam:** if the front refuses the lowest-id item, nothing leaves, even if other buffered items would be accepted. A full end bridge (4) stalls the whole chain.
* Bridges are solid. They **block beam-drill lasers** and **block a unit factory's exit** if placed in front of its centre (see 9, 10).

## 5. Duct unloader (DirectionalUnloader.java)

Content: speed=4, `solid=false`, `allowCoreUnload` **false** (field default false at line 26, not set in Blocks.java:2229-2236), `itemCapacity=0`.

* Every `speed`=4 ticks (`unloadTimer += edelta() >= speed`, lines 93-119) it moves **one** item from `back()` to `front()` → **15 items/s**.
* Requirements (line 97):
  * `back.items != null && back.canUnload()`, both same team.
  * **`!(back instanceof CoreBuild || (back instanceof StorageBuild sb && sb.linkedCore != null))`**: on Erekir **you cannot unload from the core**, or from storage merged into the core.
  * `reinforced-container` / `reinforced-vault` have `coreMerge=false` (Blocks.java ~3262-3275), so they never link to a core and can be unloaded even when they touch it.
* **Filter null:** it scans items from `offset` by id and takes **any** item the back holds that the front accepts. **This includes a crafter's inputs:** an unfiltered unloader on a silicon furnace will also pull graphite and sand. It also pulls from unit factories, bridges, drills and any block with `unloadable=true`.
  * Blocks with `unloadable=false`: ducts, routers, overflow ducts, unloaders.
  * Surge conveyor: not unloadable while in load state (StackConveyor.java:263-265).
* **Filter set:** only that item.
* **Nothing can push into it.** It has no `acceptItem` override, `consumesItem` is false, and capacity is 0.
* It is `isDuct` and rotated, so it may feed an armored duct's side.

## 6. Surge conveyor (StackConveyor.java) and surge router (StackRouter.java)

Surge conveyor (Blocks.java:2238-2254): speed=5/60, `itemCapacity=10`, `recharge=2` (default), `outputRouter=false`, `baseEfficiency=1`, power 1/60 per tick, `conductivePower=true`, `solid=false`.

**Segment roles** are recomputed in `onProximityUpdate` (lines 221-248). Only surge conveyors count as "blending" neighbours (line 86):
* **Load dock:**
  * Its front is a surge conveyor.
  * No surge conveyor feeds its sides.
  * No surge conveyor feeds its back, unless that one is an unload end.
  * Line 241-248: no surge conveyor has `front()==this` (otherwise it becomes `move`).
* **Unload end:** the front is **not** a surge conveyor (line 228; overrides "load"). A **single isolated tile is therefore an unload end and accepts nothing.** A working line needs ≥2 tiles.
* **Move:** everything else.

**Acceptance** (lines 379-386):
* Only the **load dock** accepts, from back or sides (`front()==source` is refused), from any source.
* **One item type at a time** (`items.any() && !items.has(item)` → refuse). A mixed duct feeding a dock jams on the second type.
* Up to 10 items.
* Not while `cooldown > recharge-1`.

**Transfer** (lines 268-316):
* A dock sends its stack **only when full** (`items.total() >= getMaximumAccepted(lastItem)` = 10). **A dock holding 1-9 items waits forever if the feed stops.**
* A move segment sends the whole stack to the next segment if that one is empty (`link == -1`) and its own cooldown is 0.
* Cooldown falls by `speed*eff*delta` with `eff = efficiency + baseEfficiency`: 1 unpowered, 2 fully powered (line 270-273). After sending, cooldown = 2; the receiving segment gets cooldown = 1.
* Rate: dock period = 24 ticks unpowered and 12 ticks powered → **25 items/s unpowered, 50 items/s powered** (stat `itemCapacity*speed*60` = 50).
* A single 15/s duct cannot saturate a dock. Feed back + both sides.
* **Unload end:** `moveForward(lastItem)` in a `while` loop (line 290), so it can empty the whole stack in one tick into the **front only**. It moves only as many as the front accepts; a duct takes 1 per cycle.

Surge router (Blocks.java:2256-2268, StackRouter.java): extends DuctRouter. speed=6, `itemCapacity=10`, `solid=false`, power 3/60 per tick, `baseEfficiency=1`.
* **Input: back only**, same item as `current` only, < 10 items, and not while unloading (lines 90-93).
* It waits until it holds **10** items, then `progress += eff` each tick up to 6: 6 ticks unpowered, 3 powered (lines 32-42).
* It then "unloads": every tick it pushes as many as possible through `DuctRouter.target()` (front/left/right, sort-filter rules as in §2) until empty (lines 45-60).
* **Fewer than 10 items with no further feed are held forever.** Mixed items jam it, as with the dock.

## 7. Production blocks: how they output and what they accept

### GenericCrafter / HeatCrafter / HeatProducer (production/GenericCrafter.java)
Covers silicon-arc-furnace, carbide-crucible, surge-crucible, phase-synthesizer, and oxidation-chamber (HeatProducer extends GenericCrafter, heat/HeatProducer.java:13).
* **Accepts** with the default `acceptItem`: only consumed items (`itemFilter` set by `ConsumeItems.apply`, world/consumers/ConsumeItems.java:23-29), from **any side, any source**, up to `itemCapacity` **per item type** (`getMaximumAccepted` = itemCapacity, GenericCrafter.java:368). Boost items count too: e.g. large-cliff-crusher accepts graphite, its `.boost()` item.
* Capacity is **not** auto-scaled to recipe size for crafters. Only unit factories, reconstructors, assemblers and constructors use the "2× requirement" capacity rule.
* Examples:
  * silicon-arc-furnace: itemCapacity 30. It takes **up to 30 graphite and 30 sand**, though a craft uses 1 + 4.
  * oxidation-chamber: no itemCapacity set → 10 beryllium.
  * carbide-crucible: 20.
* **Stops crafting** when `items.get(out) + outAmount > itemCapacity` (GenericCrafter.java:205-212). Silicon furnace: stops at ≥27 silicon held.
* **Output:**
  * `craft()` → `offload(output)` per produced item (lines 308-333). Each item goes to the first accepting neighbour round-robin; if none, it stays inside.
  * Then `dumpOutputs()` every 5 ticks: up to `max(1, round(amount))` `dump(output.item)` calls (lines 335-344). Silicon furnace: up to 4 per 5 ticks.
  * It **only ever dumps its output items**; inputs are never dumped. But it dumps to **every adjacent same-team acceptor**: the core, other machines that consume the item, turrets that take it as ammo, duct-routers/overflow ducts whose back faces it, linked bridges, surge-conveyor docks, and **plain ducts whose back OR side touches it**.
  * It does **not** dump into a duct pointing into it (front refusal), nor into armored ducts touching it with their side.
* Numbers:
  * silicon-arc-furnace (Blocks.java siliconArcFurnace): craftTime 50, 4 Si/craft → **4.8 Si/s**, using 1.2 graphite/s and 4.8 sand/s, power 5/tick.
  * oxidation-chamber: 1 oxide / 120 ticks.

### BeamDrill — plasma-bore, large-plasma-bore (production/BeamDrill.java)
* Rotated. Lasers start on the `size` tiles directly in front of the face and run up to `range` tiles (plasma 5, large 6). The **first solid tile** on each laser (`Tile.solid()`, world/Tile.java:462) ends that laser. It mines only if `wallDrop()` (Tile.java:584-589) has `hardness ≤ tier`. Any solid building (bridge, machine, wall block) in the path ends the laser **with no drop** (lines 375-411).
* Placement (`canPlaceOn`, lines 211-226) needs at least one laser to hit a mineable wall.
* Ducts, duct routers, overflow ducts, duct unloaders and surge conveyors/routers are non-solid, so lasers pass under them.
* Production (lines 248-262): every `drillTime/drillMultiplier` ticks (time += edelta·boost; boost = lerp(1, 2.5, optional hydrogen/nitrogen)), **each laser that hits ore** adds 1 item to the drill's own inventory, while `items.total() < itemCapacity` (default 10; large 20). Different ores on different lasers give mixed output.
  * plasma-bore (drillTime 160, size 2): 0.375/s per laser, **0.75/s max**, 1.875/s with hydrogen.
  * large-plasma-bore (100, size 3): **1.8/s**, 4.5/s with nitrogen. Hydrogen is mandatory.
* **Output: `dump()` with no filter every 5 ticks** (lines 264-266). 1 item per 5 ticks = 12/s max, to **any adjacent acceptor on any face**, including the front face.

### BurstDrill — impact-drill, eruption-drill (production/BurstDrill.java, extends Drill)
* Mines only the **dominant** floor ore under its footprint (most tiles; Drill.countOre 198-215). Not rotated.
* Every `drillTime/multiplier` ticks (impact 720, beryllium ×2 → 360; eruption 281.25; ozone/hydrogen boost ×1.75/×2 through optionalEfficiency) it **offloads `dominantItems` items at once** (lines 106-109): one per ore tile, each to the next accepting neighbour, the remainder into its own buffer.
* It then `dump(dominantItem)` every 5 ticks (lines 83-84), 12/s max.
* It runs only while `items.total() <= itemCapacity - dominantItems` (impact 40, eruption 60; lines 91, 128-130).

### WallCrafter — cliff-crusher (production/WallCrafter.java)
* Rotated. `eff` = sum of `Attribute.sand` of the solid tiles **directly adjacent to its front face** (distance 1 only, lines 141-176). Placement needs `eff > 0` (line 137-139).
* One sand per `drillTime/eff` ticks (cliff-crusher 110, large 48): `offload(output)` (line 213-215), then `dump(output)` every 5 ticks (220-222).
* It stops when it holds `itemCapacity` (10) sand.

### UnitFactory — tank/ship/mech-fabricator (units/UnitFactory.java)
* Erekir fabricators are `configurable=false`, so `currentPlan` is forced to 0 (line 400-402). Single plans: stell 40 Be + 50 Si; elude 50 graphite + 70 Si; merui 50 Be + 70 Si.
* `acceptItem` (lines 471-474): only items in the **current plan**, up to `getMaximumAccepted = round(capacities[item] * rules.unitCost(team))`. `capacities[item]` = **2× the largest requirement of that item over all plans** (lines 99-106). Stell: 80 Be, 100 Si.
* Any side, including the unit-exit front. It keeps accepting while a finished unit waits to exit.
* It consumes the items only when the unit is created (line 443).
* Unit exit (payloads/PayloadBlock.java:222-239): `front()` is the tile next to the centre on the facing side. If that tile holds a **solid** building that is not a payload block (`front.tile.solid()`), the unit can never leave. `shouldConsume` is false while `payload != null` (UnitFactory.java:454-457), so the **factory stalls forever**. Non-solid ducts in front are fine.

### Reconstructor — refabricators (units/Reconstructor.java)
* Default `acceptItem` (consume filter): from any side, up to `round(2×amount × unitCost)` per item (lines 144-153, 274-276).
  * tank-refabricator: Si 80, W 60.
  * ship-refabricator: Si 120, W 80.
  * mech-refabricator: Si 100, W 80.
  * prime-refabricator: thorium 160, Si 200.
* It accepts the unit payload from any side except its front (`relativeTo(source) != rotation`, line 245).

### UnitAssembler (units/UnitAssembler.java)
* Erekir assembler plans have no `itemReq`, so `acceptItem` is always false (lines 740-743). Inputs are **payloads** only: units plus large walls (`acceptPayload` 726-732), through modules/payload conveyors.
* Constructor/BlockProducer (payloads/BlockProducer.java:92-101) accepts the recipe's items up to 2× each, from any side.

### Item turrets (defense/turrets/ItemTurret.java:187-189)
* `acceptItem`: the item is one of its ammo types and `totalAmmo + ammoMultiplier ≤ maxAmmo` (default 30, Turret.java:47; titan 12). Any side.
* Several ammo types are accepted, so mixed ammo lines are fine; a non-ammo item at the head of a feeding duct jams it.
* Turrets never output. Adjacent drills and crafters that produce an ammo item feed them directly, e.g. a beryllium drill next to breach, or silicon/graphite next to diffuse.

### Storage (storage/StorageBlock.java:53-74)
* Not core-linked: accepts **any** item from any side up to `itemCapacity` **per item** (reinforced-container 160, vault 900). It never outputs by itself; use a duct-unloader.
* Core-linked storage forwards to the core.

### Unit cargo (briefly)
* unit-cargo-loader (units/UnitCargoLoader.java:136-138): accepts **any** item from any side until it holds 200 in total.
* unit-cargo-unload-point (UnitCargoUnloadPoint.java):
  * Refuses items from blocks: it has no consume filter; only `acceptStack` from units, lines 76-79.
  * Output: `dumpAccumulate()` (line 68), up to **1 item per tick to any neighbour, any item**.
  * Its item filter only tells units what to bring. It goes `stale` after 6 s full.

## 8. The core (storage/CoreBlock.java)

* `acceptItem` (lines 747-750): `state.rules.coreIncinerates || items.get(item) < getMaximumAccepted(item)`.
  * `coreIncinerates` defaults to **true** (game/Rules.java:225), and Logic.java:72/106 forces it to true for wave/attack games. So the core **always accepts every item from every side**, and the line into the core never stalls.
  * Items beyond `storageCapacity` (itemCapacity + linked storage) are destroyed in `handleItem` (lines 889-915). Bastion 2000, citadel 3000, acropolis 4000 per item.
* Erekir cores have **`incinerateNonBuildable = true`** (Blocks.java:3197, 3216, 3234). Every item with `buildable=false` is **burned on arrival**: **sand**, coal, spore-pod, blast-compound, pyratite (content/Items.java:37, 46, 91, 97, 103). **Sand sent to the core is lost.** Cliff-crusher sand must go straight to its consumers.
* The core never dumps or outputs items to blocks.
* **Unloading from the core is not possible on Erekir:**
  * duct-unloader: `allowCoreUnload=false` (§5).
  * `CoreBuild.canUnload()` = `unloadable && rules.allowCoreUnloaders` (lines 284-286). That rule only matters for the Serpulo `unloader`.
  * So core stock reaches buildings only through the builder unit (construction). Production lines must be fed **directly** from drills and crafters.
* Placement (lines 186-203):
  * `canPlaceOn` returns true on floors with `allowCorePlacement`.
  * Otherwise it must replace a smaller core, and with `requiresCoreZone` (all Erekir cores) every covered tile must be a core zone.
  * `canBreak`: the last core can't be removed.

## 9. Mixed-item hazards (summary)

1. **Duct FIFO head-of-line:** one item the front refuses stops the whole line (Duct.java:203-208), including items that would be accepted.
2. **Mixed input line into a machine deadlocks if one input is over-supplied.** Example, a sand+graphite line into a silicon furnace with more graphite than 1:4:
   * Graphite fills to its cap of 30 and the head graphite is refused.
   * Sand behind it cannot arrive.
   * When the furnace's sand drops below 4 it stops crafting, so graphite never drops below 30.
   * Result: permanent deadlock.

   Avoid shared input lines unless the ratio is exact, or put an overflow-duct before the machine with a side branch that sinks the excess (core, other consumers).
3. **Output leaking into input lines:**
   * A machine or drill pushes outputs into any adjacent plain duct whose back or side touches it. That includes the machine's **own input duct** where it runs along the machine's edge before turning in.
   * The output item then reaches a block that refuses it (the machine itself), and the input line jams.
   * Only the last duct, which has the machine in front, is safe.
   * Fix: use **armored ducts** next to machines (they refuse side input from non-ducts), or keep plain ducts off machine edges.
   * Routers and overflow ducts leak in the same way into neighbouring lines.
4. **Bridges:** the output end emits the lowest item id first, so a mixed buffer with that item refused jams the chain (§4).
5. **Surge conveyor dock / surge router:** one type per stack; a second type on the feeding line jams it. A partial stack (<10) never leaves (§6).
6. **Unfiltered duct-unloader:** takes a machine's inputs as well as its outputs (§5).
7. **Core:** never refuses, but burns non-buildable items and anything over capacity.
8. **Item loss** happens only at core incineration and in the rare DuctBridge lag case (§4).

## 10. Placement rules relevant to the generator (world/Build.java:183-271, Block.java)

* Footprint of a block of size s at (x, y): x−(s−1)/2 … x+s/2 (same for y). So s=2 covers x..x+1, s=3 covers x−1..x+1, s=4 covers x−1..x+2, s=5 covers x−2..x+2 (Build.java:240-247).
* A tile is invalid if any of these holds:
  * It is undiscovered under **static fog**: Erekir ruleSetter sets `fog=true, staticFog=true` (content/Planets.java:74-83). Check at Build.java:252.
  * It is deep liquid and the block isn't floating/placeableLiquid (253).
  * The floor is not `placeableOn` (257).
  * The block must touch at least one non-deep tile (`contactsShallows`, 233).
  * It holds a derelict building (254).
  * It holds the same block with the same rotation (255).
  * Otherwise, anything already there must be replaceable (`canReplace`, Block.java:795-800): same `group` and size rules. For example, ducts, routers, bridges and overflow ducts are all `BlockGroup.transportation` and replace each other at size 1. The new block's bounds must contain the old one's.
* No placement within an enemy core's build radius (210-212). On Erekir `placeRangeCheck=false` (Planets.java:76).
* Block-specific `canPlaceOn`:
  * BeamDrill: must hit a mineable wall within range.
  * Drill/BurstDrill: some ore under the footprint (Drill.java:126-135).
  * WallCrafter: sand attribute > 0 on the front-adjacent tiles.
  * Core: see §8.
* Solidity:
  * Non-solid: duct, armored duct, duct router, overflow/underflow duct, duct unloader, surge conveyor/router.
  * Solid: duct-bridge, machines, drills, factories, turrets, core.
  * Solidity matters for beam-drill lasers, unit exit from factories, and unit pathing.
* Rotation matters for: ducts, routers, overflow ducts, bridges, unloader, surge conveyor/router, beam drills, wall crafters, unit factories (unit exit side), reconstructors (exit side = front; input not from the front).
* Rotation does not matter for: GenericCrafters, burst drills, core, storage, turrets.

## 11. Quick reference table

| block | accepts from | accepts what | buffer | outputs to | rate |
|---|---|---|---|---|---|
| duct | back + sides (any source) | anything | 1 | front only | 15/s |
| armored-duct | back (any); sides only from duct/bridge-end/unloader pointing in | anything | 1 | front only | 15/s |
| duct-router | back only | anything | 1 | front/left/right round-robin; with filter: item→front, others→sides | 15/s |
| overflow-duct | back only | anything | 1 | front, else sides (alternating) | 15/s |
| underflow-duct | back only | anything | 1 | sides (alternating), else front | 15/s |
| duct-bridge (linked) | back + sides, not the side a bridge arrives from | anything, mixed | 4 | link 1..4 tiles ahead | 15/s |
| duct-bridge (end) | nothing from neighbours | — | 4 | its front only, lowest item id first | ≤60/s |
| duct-unloader | nothing (pulls from back; not core) | filter or any | 0 | front only | 15/s |
| surge-conveyor dock | back + sides | one type | 10, sends only when full | next segment | 25/s (50/s powered) |
| surge-conveyor end | nothing | — | 10 | front only | burst |
| surge-router | back only | one type | 10, routes when full | front/left/right | burst |
| GenericCrafter | any side | its inputs, ≤ itemCapacity each | per item | any adjacent acceptor (outputs only) | dump ≤12/s + offload |
| BeamDrill | — | — | 10/20 | any adjacent acceptor | dump 12/s |
| BurstDrill | — | — | 40/60 | burst offload + dump 12/s | — |
| UnitFactory | any side | current plan's items, ≤ 2× req | per item | — | — |
| Reconstructor | any side | recipe items, ≤ 2× req | per item | — | — |
| ItemTurret | any side | ammo, ≤ maxAmmo | ammo | — | — |
| core | any side | anything (burns sand etc. + overflow) | 2000-4000 per item | none (not unloadable) | ∞ |
