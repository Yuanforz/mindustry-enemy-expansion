# Erekir mechanics specification (from the Mindustry source checkout given as build v160.7)

Source root: `…/research/mindustry-src-tree/repo/core/src/mindustry/`. All citations are `File.java:line` in that tree. Everything here was read from the source; nothing was built or run. The checkout contains no map assets, so per-map rules could not be read (see L).
Short names used below:

| Short name | Path under `mindustry/` |
|---|---|
| `Blocks.java` | `content/Blocks.java` |
| `BuildingComp.java` | `entities/comp/BuildingComp.java` |
| `Block.java`, `Build.java`, `Tile.java`, `Edges.java` | `world/…` |
| `Duct.java`, `DuctRouter.java`, `OverflowDuct.java`, `DuctBridge.java`, `DirectionBridge.java`, `DirectionalUnloader.java`, `StackConveyor.java`, `StackRouter.java` | `world/blocks/distribution/…` |
| `CoreBlock.java`, `StorageBlock.java` | `world/blocks/storage/…` |
| `BeamDrill.java`, `BurstDrill.java`, `Drill.java`, `WallCrafter.java`, `GenericCrafter.java`, `AttributeCrafter.java`, `HeatCrafter.java`, `Pump.java` | `world/blocks/production/…` |
| other block classes | `world/blocks/<package>/…` as named in the text |

## Conventions and confidence

- **Time.** One tick = 1/60 s at normal speed. `Building.delta()` is `Time.delta * timeScale` (`BuildingComp.java:1929-1931`) and `edelta()` is `efficiency * delta()` (`BuildingComp.java:1934-1936`). Every "per tick" number below assumes `Time.delta = 1` and `timeScale = 1` (no overdrive; Erekir has no overdrive block in its tech tree).
- **Per tick vs per second.** Liquid amounts, power usage and power production in `Blocks.java` are **per tick**. `x / 60f` therefore means "x per second". The stats UI multiplies by 60 (`ConsumePower.java:47`: `stats.add(Stat.powerUse, usage * 60f, StatUnit.powerSecond);`, `ConsumeLiquid.java:51`: `stats.add(…, liquid, amount * 60f, true);`). In the tables I give `/s` values (= source value x 60) and say so.
- **Items per craft** are integers consumed once per completed craft (`ConsumeItems.trigger`, `ConsumeItems.java:44-47`).
- **Efficiency.** A building's `efficiency` is the minimum over its non-optional consumers (`BuildingComp.java:1976-1992`), then multiplied by `efficiencyScale()` (`BuildingComp.java:1939-1943`). Power consumers return `power.status` (0..1) (`ConsumePower.java:38-40`), so under-powered blocks run proportionally slower, not off.
- **Proximity.** `proximity` holds only edge-adjacent buildings of the **same team** (`BuildingComp.java:1894`: `if(other == null || other.team != team) continue;`). Diagonal neighbours are never in proximity (`Edges.getEdges(size)` is the ring of edge tiles).
- **Rotation numbering.** 0 = +x (right), 1 = +y (up), 2 = -x (left), 3 = -y (down) (`BuildingComp.java:523-528`).
- **What "derived" means.** Where I write *derived*, the number is my arithmetic on the cited code, not something I ran. Where I write **UNSURE**, treat the rule as unverified and test it as described. I did not build or run the game.

---

# A. Item transport

## A.1 Shared primitives

| Primitive | Rule | Citation |
|---|---|---|
| `moveForward(item)` | Offers the item only to `front()`; needs same team and `acceptItem`. | `BuildingComp.java:1134-1141`: `Building other = front(); if(other != null && other.team == team && other.acceptItem(self(), item)){` |
| `dump(item)` | Round-robin over **all** proximity buildings (all four sides), first one whose `acceptItem` is true gets one item. | `BuildingComp.java:1086-1097` |
| `offload(item)` | Like dump, but if nobody accepts the item is put in the block's own inventory. | `BuildingComp.java:1006-1020` |
| `front()` / `back()` for multiblocks | The single building at `size/2 + 1` tiles from the centre tile along the rotation. For even sizes this is the tile in line with the block's origin tile, not the whole edge. | `BuildingComp.java:556-565` |
| default `acceptItem` | A block accepts an item iff it consumes it and has room: from **any side, any source**. | `BuildingComp.java:869-871`: `return block.consumesItem(item) && items.get(item) < getMaximumAccepted(item);` |
| default `itemCapacity` | 10 | `Block.java:77` |
| default `dumpTime` | 5 ticks between `dump()` calls for crafters/drills | `Block.java:445` |

No transport block drops items on the ground. A blocked item simply stays where it is and the line backs up.

## A.2 Field values (Blocks.java)

| Block | Class | `speed` | `itemCapacity` | Cost | Other | Lines |
|---|---|---|---|---|---|---|
| `duct` | `Duct` | 4 | 1 | beryllium 1 | health 90 | `Blocks.java:2179-2184` |
| `armored-duct` | `Duct` | 4 | 1 | beryllium 2, tungsten 1 | `armored = true`, health 140 | `Blocks.java:2186-2192` |
| `duct-router` | `DuctRouter` | 4 | 1 | beryllium 10 | `solid = false` | `Blocks.java:2194-2201` |
| `overflow-duct` | `OverflowDuct` | 4 | 1 | graphite 8, beryllium 8 | | `Blocks.java:2203-2209` |
| `underflow-duct` | `OverflowDuct` | 4 | 1 | graphite 8, beryllium 8 | `invert = true` | `Blocks.java:2211-2218` |
| `duct-bridge` | `DuctBridge` | 4 | 4 (class default) | beryllium 15 | `range` = 4 (class default, `DirectionBridge.java:31`), `solid = true` | `Blocks.java:2220-2227` |
| `duct-unloader` | `DirectionalUnloader` | 4 | 0 | graphite 20, silicon 20, tungsten 10 | `solid = false`, `allowCoreUnload` not set (default **false**) | `Blocks.java:2229-2236` |
| `surge-conveyor` | `StackConveyor` | 5/60 | 10 | surge-alloy 1, tungsten 1 | `outputRouter = false`, `baseEfficiency = 1`, power 1/60 per tick, `conductivePower = true` | `Blocks.java:2238-2254` |
| `surge-router` | `StackRouter` | 6 | 10 (class) | surge-alloy 5, tungsten 1 | `baseEfficiency = 1`, power 3/60 per tick, `conductivePower = true` | `Blocks.java:2256-2269` |
| `unit-cargo-loader` | `UnitCargoLoader` | - | 200 | silicon 80, surge-alloy 50, oxide 20 | size 3, `unitBuildTime = 480`, power 8/60 per tick, nitrogen 10/60 per tick | `Blocks.java:2271-2284` |
| `unit-cargo-unload-point` | `UnitCargoUnloadPoint` | - | 100 | silicon 60, tungsten 60 | size 2 | `Blocks.java:2286-2294` |
| `reinforced-container` | `StorageBlock` | - | 160 | tungsten 30, graphite 40 | size 2, `coreMerge = false` | `Blocks.java:3257-3263` |
| `reinforced-vault` | `StorageBlock` | - | 900 | tungsten 125, thorium 70, beryllium 100 | size 3, `coreMerge = false` | `Blocks.java:3265-3271` |

## A.3 `duct` / `armored-duct`

**Holds** exactly one item (`Duct.java:45`: `itemCapacity = 1;`), and accepts only when empty (`Duct.java:220`: `return current == null && items.total() == 0 &&`).

**Accepts from (plain duct)** — back and both sides, from any block type; never from the front:

```java
// Duct.java:225-226
//standard acceptance - do not accept from front
!(source.block.rotate && next == source) && Edges.getFacingEdge(source.tile, tile) != null && Math.abs(Edges.getFacingEdge(source.tile, tile).relativeTo(tile.x, tile.y) - rotation) != 2
```

`getFacingEdge(source.tile, tile).relativeTo(tile)` is the direction from the source's nearest tile to the duct. It equals `rotation` for a source directly behind, and differs from `rotation` by 2 for a source in front. So:

- **Side feeding / merging works on plain ducts.** Any item-outputting block (another duct, a router, a crafter's `dump`, a drill's `offload`, an unloader) placed beside or behind a plain duct can insert into it.
- A block in front of the duct cannot push items back into it.

**Accepts from (armored duct)** — from directly behind, from anything; from the sides **only** from `isDuct` blocks that are rotated to face it:

```java
// Duct.java:223-224
((source.block.rotate && source.front() == this && source.block.hasItems && source.block.isDuct) ||
Edges.getFacingEdge(source.tile, tile).relativeTo(tile) == rotation) :
```

`isDuct = true` is set only by `Duct` (`Duct.java:50`), `DuctBridge` (`DuctBridge.java:17`) and `DirectionalUnloader` (`DirectionalUnloader.java:41`). Consequences for armored ducts:

- A crafter/drill beside an armored duct **cannot** insert into it. A crafter/drill directly behind it can.
- `duct-router`, `overflow-duct`, `underflow-duct`, `surge-conveyor` are not `isDuct`: they can feed an armored duct only from directly behind.
- Because the first clause does not test direction, an `isDuct` block in *front* of an armored duct that faces back into it would also be accepted (two ducts facing each other). Do not build that.

**Outputs** only forward, to `front()`, via `moveForward` (`Duct.java:204`).

**Throughput (derived): 15 items/s; item travel 3–4 ticks per tile.**

```java
// Duct.java:201-208
progress += edelta() / speed * 2f;
if(current != null && next != null){
    if(progress >= (1f - 1f/speed) && moveForward(current)){
        items.remove(current, 1);
        current = null;
        progress %= (1f - 1f/speed);
    }
}else{
    progress = 0;
}
// Duct.java:245-246 (handleItem)
current = item;
progress = -1f;
```

With `speed = 4`: progress starts at -1 on receipt, gains `1/4*2 = 0.5` per tick, and the hand-off threshold is `1 - 1/4 = 0.75`. That needs 4 updates (-0.5, 0, 0.5, 1.0). A duct can hold one item and can only be refilled after it has handed its item on, so a saturated line passes one item per 4 ticks = **15 items/s**, the same as the stat line `stats.add(Stat.itemsMoved, 60f / speed, StatUnit.itemsSecond);` (`Duct.java:62`). I traced a saturated chain for both possible building update orders (upstream-first and downstream-first) and got a 4-tick period in both; a lone item moves 1 tile per 3 ticks if the upstream duct updates before the downstream one in the same tick, 4 ticks otherwise. **UNSURE** about which update order the game uses for a given layout (entity order); it affects latency only, not the 15/s cap. Test: feed a 30-tile duct from a full container via duct-unloader into a core and read the core's item rate.

Ducts consume no power; efficiency is 1 whenever enabled (`BuildingComp.java:1952-1954`).

**End of a duct.**

- Nothing in front (`next == null`): `progress = 0` every tick (`Duct.java:209-211`), the item stays, the line backs up. Nothing spills.
- A building in front that refuses the item: `moveForward` returns false, the item stays, the line backs up.
- A building of another team in front: refused (`BuildingComp.java:1136`: `other.team == team`).

**Crossing.** There is no duct junction in this build: the only reference is commented out (`Duct.java:70`: `//if(junctionReplacement == null) junctionReplacement = Blocks.ductJunction;`) and `Blocks.java` defines no `duct-junction` block (class `DuctJunction.java` exists but is unused by vanilla content). Two duct lines must cross with a `duct-bridge`. Remember that a plain duct crossing perpendicular *into* another plain duct merges into it.

## A.4 `duct-router`

- **Accepts** only from directly behind, from any block: `DuctRouter.java:161-162`: `return current == null && items.total() == 0 && (Edges.getFacingEdge(source.tile, tile).relativeTo(tile) == rotation);`
- **Outputs** to front, left and right (never back), round-robin over proximity, to any same-team building that accepts:

```java
// DuctRouter.java:148
if(!(sortItem != null && (current == sortItem) != (rel == rotation)) && !(rel == (rotation + 2) % 4) && other.team == team && other.acceptItem(this, current)){
```

- **Sorting config** (`config(Item.class, …)`, `DuctRouter.java:43`; `saveConfig = true`, so a build plan can carry the item as config). With `sortItem` set: the sort item goes **only to the front**; every other item goes **only to the sides**. With no config: all three outputs are used in rotation.
- There is no fallback: if the allowed outputs refuse the item, the router holds it and blocks its input.
- **Throughput (derived)**: identical timing code to the duct (`DuctRouter.java:102-112`, `handleItem` sets `progress = -1f` at `:181`), so 15 items/s through the router in total.
- It is not `isDuct`, so it cannot side-feed an armored duct.

## A.5 `overflow-duct` / `underflow-duct`

- **Accepts** only from directly behind (`OverflowDuct.java:136-137`, same expression as the router).
- **`overflow-duct`** (`invert = false`): front first; only if the front refuses does it try left/right (alternating start side via `cdump`):

```java
// OverflowDuct.java:115-129
Building front = front();
if(front != null && front.team == team && front.acceptItem(this, current)){
    return front;
}
if(invert) return null;
for(int i = -1; i <= 1; i++){
    int dir = Mathf.mod(rotation + (((i + cdump + 1) % 3) - 1), 4);
    if(dir == rotation) continue;
    Building other = nearby(dir);
```

- **`underflow-duct`** (`invert = true`): sides first (alternating when both accept); front only when **neither** side accepts (`OverflowDuct.java:101-113`, then `:115-120`).
- Never outputs backwards. Same timing code as the duct → 15 items/s (derived).

## A.6 `duct-bridge`

- **Direction rule.** A bridge sends to the first same-team `duct-bridge` found along its own rotation, 1 to `range` tiles away. Nothing in between is examined; only the target tiles are tested:

```java
// DirectionBridge.java:252-259
for(int i = 1; i <= range; i++){
    Tile other = tile.nearby(Geometry.d4x(rotation) * i, Geometry.d4y(rotation) * i);
    if(other != null && other.build instanceof DirectionBridgeBuild build && build.block == DirectionBridge.this && build.team == team){
        return build;
    }
}
```

- **Range** = 4 tiles centre to centre (`DirectionBridge.java:31`: `public int range = 4;`, not overridden for `duct-bridge`), i.e. it spans at most 3 tiles of obstacle. Axis-aligned only.
- The target's own rotation is irrelevant for linking. **The first bridge in range always captures the link** — you cannot skip over a nearer bridge of the same team in the same row/column.
- **Chaining works.** A bridge that has a link forwards its buffer to it; a bridge with no link in range is an output end. The receiving bridge can have any rotation, so chains can turn corners (the receiving bridge then looks for its own link along *its* rotation).
- **Accepting from ducts.** Only a bridge that currently has a link accepts items, from back and sides but not from its front, and not from a side through which another bridge is linking in:

```java
// DuctBridge.java:61-67
//only accept if there's an output point.
if(findLink() == null) return false;
int rel = this.relativeToEdge(source.tile);
return items.total() < itemCapacity && rel != rotation && occupied[(rel + 2) % 4] == null;
```

  `occupied[d]` is set by an incoming bridge whose rotation is `d` (`DuctBridge.java:33`: `link.occupied[rotation % 4] = this;`). So an **end bridge (no link) cannot be fed by ducts at all**, and a middle bridge refuses adjacent sources on the side an incoming bridge arrives from.
- **Output end.** A bridge without a link pushes its items to `front()` only (`DuctBridge.java:46-51`: `if(link == null && items.any()){ … if(moveForward(next)){`), one item per tick at most, no `speed` limit. So the output bridge's rotation chooses the exit side, and there must be **no other same-team duct-bridge within 4 tiles in front of it**, or it becomes a middle bridge instead.
- **Throughput (derived).** Sender side: `progress += edelta(); while(progress > speed){ … link.handleItem(this, next); progress -= speed; }` (`DuctBridge.java:35-42`) → one item per 4 ticks = 15 items/s. Buffer 4 items (`DuctBridge.java:14`).
- A bridge is `solid = true` (`DirectionBridge.java:36`), unlike ducts.
- Two bridges facing each other link to each other and loop items forever; avoid.

## A.7 `duct-unloader`

```java
// DirectionalUnloader.java:94-97
if((unloadTimer += edelta()) >= speed){
    Building front = front(), back = back();
    if(front != null && back != null && back.items != null && front.team == team && back.team == team && back.canUnload() && (allowCoreUnload || !(back instanceof CoreBuild || (back instanceof StorageBuild sb && sb.linkedCore != null)))){
```

- **Takes from `back()`, puts into `front()`.** Both must exist and be same team. It has no inventory (`itemCapacity = 0`, `DirectionalUnloader.java:38`), so it moves an item only when the front accepts it at that instant.
- **Cannot unload a core.** `allowCoreUnload` defaults to `false` (`DirectionalUnloader.java:26`) and `Blocks.java:2229-2236` does not change it. A `CoreBuild` behind it, or a storage block linked to a core, is skipped. On Erekir there is therefore **no block that takes items out of a core**.
- **Can unload** any building with an item module whose block has `unloadable = true` (`BuildingComp.java:727-729`; default true at `Block.java:108`): `reinforced-container`, `reinforced-vault`, crafters (including their *input* items — the unloader does not distinguish), drills, `unit-cargo-loader`, `unit-cargo-unload-point`. It cannot unload ducts, routers, overflow ducts or bridges (`unloadable = false`: `Duct.java:44`, `DuctRouter.java:30`, `OverflowDuct.java:28`; `DuctBridge` does not set it false, so a bridge buffer is technically unloadable — **UNSURE** whether that is useful, I did not analyse it further). `surge-conveyor` allows unloading except in its loading state (`StackConveyor.java:263-265`).
- **Config is optional** (`configurable = true`, `saveConfig = true`). With an item set, only that item is moved (`DirectionalUnloader.java:111-115`). Without one it cycles through all items the back holds and the front accepts (`DirectionalUnloader.java:98-110`).
- **Speed (derived)**: one item per `speed` = 4 ticks → 15 items/s (`stats.add(Stat.speed, 60f / speed, …)`, `DirectionalUnloader.java:61`).
- It is `isDuct`, so it may feed an armored duct from the side.

## A.8 `surge-conveyor` (StackConveyor) and `surge-router` (StackRouter)

A surge conveyor line has three roles decided in `onProximityUpdate` (`StackConveyor.java:223-248`). For `surge-conveyor` (`outputRouter = false`):

| Role | Condition (my reading) | Behaviour |
|---|---|---|
| unload (2) | `front()` is not a surge conveyor: `if(!outputRouter && !(front() instanceof StackConveyorBuild)) state = stateUnload;` (`:228`) | Pushes its stack item by item into `front()` only, as many as accepted in one tick (`:290`: `moveForward(lastItem)`). |
| load (1) | front is a stack conveyor, no stack conveyor faces into it from behind or the sides (`:226`, `:241-247`) | The only role that accepts items. |
| move (0) | everything else | Passes whole stacks forward; accepts nothing from outside. |

- **Loading dock acceptance**: any source on back or sides (not its front), one item type per stack, up to 10:

```java
// StackConveyor.java:381-385
if(cooldown > recharge - 1f) return false; //still cooling down
return !((state != stateLoad) //not a loading dock
||  (items.any() && !items.has(item)) //incompatible items
||  (items.total() >= getMaximumAccepted(item)) //filled to capacity
||  (front()  == source));
```

- **A loading dock only dispatches full stacks**: `if(state != stateLoad || (items.total() >= getMaximumAccepted(lastItem))){` (`StackConveyor.java:302`). A partial stack (fewer than 10 of one item) sits in the dock indefinitely. Mixed inputs block the dock: a different item is refused while the dock holds something else.
- A single isolated surge conveyor is in unload state and accepts nothing. Minimum useful line: 2 tiles.
- **Throughput (derived).** `eff = efficiency + baseEfficiency` (`:270`) = 2 when fully powered, 1 unpowered (`baseEfficiency = 1`). After dispatch the dock has `cooldown = recharge` (= 2, `:41`, `:311`), reduced by `speed * eff` per tick (`:273`). Next dispatch needs `cooldown <= 0`: `2 / (5/60 * 2)` = 12 ticks powered → 10 items / 12 ticks = **50 items/s** powered, **25 items/s** unpowered. Stat line agrees for the powered case: `Mathf.round(itemCapacity * speed * 60)` = 50 (`:67`). Stack travel: receiver gets `cooldown = 1` (`:312`) → 6 ticks per tile powered, 12 unpowered.
- **UNSURE**: the load/unload state logic uses the autotiler's `blends` (`:71-87`, `:225-228`) and I have not traced every neighbour arrangement (e.g. two lines merging, a conveyor feeding another from the side). Test side-merging of surge conveyors before relying on it.

`surge-router`:

- **Accepts** only from directly behind, only one item type at a time, up to 10, and not while unloading (`StackRouter.java:90-93`).
- **Waits until it holds 10**, then after a delay unloads everything through the `DuctRouter.target()` rule (front/left/right, optional sort item): `if(!unloading && current != null && items.total() >= itemCapacity){ if(progress < cap){ progress += eff; }` (`StackRouter.java:32-36`), `cap = speed = 6`. Like the conveyor it never forwards a partial batch.
- Intended to sit after the unload end of a surge conveyor and feed loading docks of other surge conveyors.

## A.9 `unit-cargo-loader` / `unit-cargo-unload-point`

- The loader builds one `manifold` drone (`UnitCargoLoader.java:24`: `public UnitType unitType = UnitTypes.manifold;`) in `unitBuildTime` = 480 ticks, consuming power 8/s and nitrogen 10/s **only while it has no drone** (`UnitCargoLoader.java:141-143`: `return unit == null;`).
- The loader **accepts any item from any side** up to 200 total: `return items.total() < itemCapacity;` (`UnitCargoLoader.java:136-138`).
- Placement requires unit-cap room for the drone: `return super.canPlaceOn(tile, team, rotation) && Units.canCreate(team, unitType);` (`UnitCargoLoader.java:65-67`).
- The drone (`CargoAI`, `ai/types/CargoAI.java`) carries up to 100 items of one type (`UnitTypes.java:4597`: `itemCapacity = 100;`) to an unload point **configured with that exact item**: `targets.selectFrom(…, u -> u.item == item);` (`CargoAI.java:105`, `:150`). An unload point with no item config never receives anything. If no point exists for the carried item the drone deletes its cargo (`CargoAI.java:57-59`: `unit.clearItem();`).
- The unload point does not accept items from ducts (it has no consumers, so the default `acceptItem` is false); it only takes unit stacks (`UnitCargoUnloadPoint.java:77-79`). It dumps to **any adjacent** acceptor once per tick (`UnitCargoUnloadPoint.java:68`: `if(dumpAccumulate()){`).
- An unload point that stays full for 6 s is marked stale and skipped when alternatives exist (`UnitCargoUnloadPoint.java:20`, `:71-73`).

## A.10 Cores as item sinks

- **A core accepts items from any side and any source**; there is no direction test: `return state.rules.coreIncinerates || items.get(item) < getMaximumAccepted(item);` (`CoreBlock.java:747-749`). `coreIncinerates` defaults to true (`game/Rules.java:225`) and is forced true in the campaign (`core/Logic.java:72`, `:106`), so a core never refuses: overflow is burned.
- **Erekir cores burn non-buildable items on entry.** All three set `incinerateNonBuildable = true` (`Blocks.java:3197`, `:3216`, `:3234`), and `CoreBlock.java:890`: `boolean incinerate = incinerateNonBuildable && !item.buildable;` then `:901`: `if(items.get(item) >= storageCapacity || incinerate){` → the item is not stored. Of the Erekir item list (`content/Items.java:143-146`: graphite, thorium, silicon, phase-fabric, surge-alloy, sand, beryllium, tungsten, oxide, carbide, fissile-matter, dormant-cyst) only **sand** has `buildable = false` (`Items.java:35-37`). **Sand sent to an Erekir core is destroyed, not stored.**
- **Nothing takes items out of a core** (A.7). Core stock is for building only.

## A.11 `reinforced-container` / `reinforced-vault`

- Accept any item from any side, each item type separately up to `itemCapacity` (`StorageBlock.java:54-56`, `separateItemCapacity = true` at `:26`): 160 per item type for the container, 900 for the vault.
- They never output by themselves (`update = false`, `StorageBlock.java:23`; `outputsItems()` returns false, `:36-38`). Use a `duct-unloader` with the container behind it.
- **They do not merge with cores.** Core linking requires `coreMerge`:

```java
// CoreBlock.java:841-843
public boolean owns(Building core, Building tile){
    return tile instanceof StorageBuild b && ((StorageBlock)b.block).coreMerge && (b.linkedCore == core || b.linkedCore == null);
}
```

  Both Erekir storage blocks set `coreMerge = false` (`Blocks.java:3262`, `:3270`). Placing one against a core does **not** extend core capacity and does not make its contents core stock. (Serpulo `container`/`vault` keep the default `coreMerge = true`, `StorageBlock.java:17`, and would link via `CoreBlock.java:767-771`, but they cost titanium/thorium and are not in the Erekir tree.)

---

# B. Drills and extraction

## B.1 Ore sources on Erekir

`Tile.wallDrop()` (used by beam drills) and `Tile.drop()` (used by floor drills):

```java
// Tile.java:580-589
public @Nullable Item drop(){
    return overlay == Blocks.air || overlay.itemDrop == null ? floor.itemDrop : overlay.itemDrop;
}
public @Nullable Item wallDrop(){
    return block.solid ?
        block.itemDrop != null ? block.itemDrop :
        overlay.wallOre && !block.synthetic() ? overlay.itemDrop :
        null : null;
}
```

| Environment block | Kind | Drop | Item hardness | Citation |
|---|---|---|---|---|
| `graphitic-wall` | static wall block with its own `itemDrop` | graphite | 0 (default, `type/Item.java:30`; graphite sets none, `Items.java:31-33`) | `Blocks.java:1025-1028` |
| `ore-wall-beryllium` | overlay, `wallOre = true` (must sit under a non-synthetic solid block) | beryllium | 3 (`Items.java:107`) | `Blocks.java:1021-1023` |
| `ore-wall-graphite` | overlay, `wallOre = true` | graphite | 0 | `Blocks.java:1030-1032` |
| `ore-wall-tungsten` | overlay, `wallOre = true` | tungsten | 5 (`Items.java:113`) | `Blocks.java:1034-1036` |
| `ore-wall-thorium` | overlay, `wallOre = true` | thorium | 4 (`Items.java:56`) | `Blocks.java:1017-1019` |
| `ore-beryllium` | floor overlay | beryllium | 3 | `Blocks.java:1011` |
| `ore-tungsten` | floor overlay | tungsten | 5 | `Blocks.java:1013` |
| `ore-crystal-thorium`, `ore-thorium` | floor overlay | thorium | 4 | `Blocks.java:1015`, `:1005` |
| `sand-floor`, `darksand` | floor with `itemDrop` | sand | 0 | `Blocks.java:384-392` |

No other wall block in `Blocks.java` has an `itemDrop`. "Beryllium wall" / "tungsten wall" as ore are always a wall-ore **overlay** under some ordinary static wall; detect them with `tile.wallDrop()`, not by block name.

## B.2 `plasma-bore` / `large-plasma-bore` (BeamDrill)

| | `plasma-bore` | `large-plasma-bore` | Lines |
|---|---|---|---|
| size | 2 | 3 | `Blocks.java:3057-3087` |
| cost | beryllium 40 | silicon 100, oxide 25, beryllium 100, tungsten 70 | |
| `tier` | 3 | 5 | |
| `range` | 5 | 6 | |
| `drillTime` | 160 | 100 | |
| `itemCapacity` | 10 (default) | 20 | |
| power | 0.15/tick = 9/s | 0.8/tick = 48/s | |
| required liquid | none | hydrogen 0.5/60 per tick = 0.5/s | |
| optional boost | hydrogen 0.25/60 per tick = 0.25/s | nitrogen 3/60 per tick = 3/s | |
| `optionalBoostIntensity` | 2.5 (class default, `BeamDrill.java:44`) | 2.5 | |
| minable (hardness <= tier) | graphite, beryllium | graphite, beryllium, thorium, tungsten | |

**Facing tiles.** The drill has `size` parallel lanes leaving its front edge. Lane `p` starts at the tile just outside the front edge (`Block.nearbySide`, `Block.java:949-957`) and scans up to `range` tiles; it stops at the **first solid tile**, which counts only if it has a minable wall drop:

```java
// BeamDrill.java:385-399
for(int i = 0; i < range; i++){
    int rx = l.x + dx*i, ry = l.y + dy*i;
    Tile other = world.tile(rx, ry);
    if(other != null){
        if(other.solid()){
            Item drop = other.wallDrop();
            if(drop != null && drop.hardness <= tier && (blockedItems == null || !blockedItems.contains(drop))){
                facingAmount ++;
                …
            }
            break;
```

- `i` runs 0..range-1, so the ore wall may be 1..`range` tiles from the drill edge (plasma-bore: gap of 0..4 empty tiles; large: 0..5).
- `Tile.solid()` is `block.solid || floor.solid || (build != null && build.checkSolid())` (`Tile.java:462-464`). **Any solid building in a lane blocks that lane** (walls, crafters, bridges, other drills). Non-solid buildings (ducts, duct-routers, overflow ducts, duct-unloaders) do not, so ducts may run through the beam path.
- A lane whose first solid tile is a wall without a valid drop contributes nothing.

**Placement.** `canPlaceOn` is true only if at least one lane finds a minable wall for the given rotation (`BeamDrill.java:198-214`). An automated placer must pass the final rotation or the placement is rejected.

**Rate.**

```java
// BeamDrill.java:246-262
float multiplier = Mathf.lerp(1f, optionalBoostIntensity, optionalEfficiency);
float drillTime = getDrillTime(lastItem);
…
time += edelta() * multiplier;
if(time >= drillTime){
    for(Tile tile : facing){
        Item drop = tile == null ? null : tile.wallDrop();
        if(items.total() < itemCapacity && drop != null){
            items.add(drop, 1);
```

Every `drillTime` ticks each valid lane yields one item (of that lane's own ore; lanes may differ):

`items/s = facingAmount x 60 / drillTime x efficiency x (1 + 1.5 x optionalEfficiency)`

No `drillMultipliers` are set for either bore, so there is no per-item speed difference and hardness does not slow a beam drill.

| | per ore lane | all lanes | all lanes boosted (x2.5) |
|---|---|---|---|
| `plasma-bore` | 0.375/s | 0.75/s (2 lanes) | 1.875/s |
| `large-plasma-bore` | 0.6/s | 1.8/s (3 lanes) | 4.5/s |

**Output.** `dump()` every 5 ticks to **any adjacent** acceptor on any side (`BeamDrill.java:264-266`). No duct facing rule; a plain duct touching any side (and not pointing into the drill) receives items. An armored duct only if the drill is directly behind it. The drill stops (and stops consuming) when its inventory is full or no lane is valid (`BeamDrill.java:270-272`).

## B.3 `impact-drill` / `eruption-drill` (BurstDrill, a floor drill)

| | `impact-drill` | `eruption-drill` | Lines |
|---|---|---|---|
| size (tiles covered) | 4 (16) | 5 (25) | `Blocks.java:3089-3140` |
| cost | silicon 70, beryllium 90, graphite 60 | silicon 300, oxide 20, tungsten 250, thorium 150 | |
| `tier` | 6 | 7 | |
| blocked item | thorium (`blockedItem = Items.thorium`) | none | |
| `drillTime` | 720 | 281.25 | |
| `drillMultipliers` | beryllium x2 | beryllium x2 | |
| `itemCapacity` | 40 | 60 | |
| power | 160/60 per tick = 160/s | 6/tick = 360/s | |
| required liquid | water 10/60 per tick = 10/s | hydrogen 4/60 per tick = 4/s | |
| optional boost | ozone 3/60 per tick = 3/s, `liquidBoostIntensity = 1.75` | cyanogen 0.75/60 per tick = 0.75/s, `liquidBoostIntensity = 2` | |

- **Which tiles.** It mines the floor/overlay drop under its own footprint: `canMine` requires `tile.drop() != null`, `hardness <= tier`, not blocked, and the tile's block is not a static wall (`Drill.java:231-235`). Only the **dominant** item is produced: the most numerous non-`lowPriority` item under the footprint (`Drill.java:215-228`); sand is `lowPriority` (`Items.java:36`).
- **Placement** needs at least one minable tile under the footprint (`Drill.java:126-137`).
- **Burst.** Progress advances by `delta() * speed` per tick; at `drillTime / multiplier` ticks the drill emits `dominantItems` items at once (one per ore tile under it):

```java
// BurstDrill.java:53-55, 94, 99, 106-109
return drillTime / drillMultipliers.get(item, 1f);
float speed = Mathf.lerp(1f, liquidBoostIntensity, optionalEfficiency) * efficiency;
progress += delta() * speed;
if(dominantItems > 0 && progress >= drillTime && items.total() < itemCapacity){
    for(int i = 0; i < dominantItems; i++){
        offload(dominantItem);
```

`items/s = oreTiles x 60 x efficiency x boost / (drillTime / multiplier)`; hardness is not in the formula (`hardnessDrillMultiplier = 0f`, `BurstDrill.java:39`).

| Full footprint of one ore | burst | period | rate | boosted |
|---|---|---|---|---|
| impact, tungsten | 16 | 12 s | 1.33/s | 2.33/s (x1.75) |
| impact, beryllium | 16 | 6 s | 2.67/s | 4.67/s |
| eruption, tungsten or thorium | 25 | 4.6875 s | 5.33/s | 10.67/s (x2) |
| eruption, beryllium | 25 | 2.34 s | 10.67/s | 21.33/s |

- It only charges while `items.total() <= itemCapacity - dominantItems` (`BurstDrill.java:91`, `:129`), so output must be cleared between bursts.
- **Output**: `offload` to any adjacent acceptor at burst time, remainder into its own inventory, then `dump` every 5 ticks (`BurstDrill.java:83-85`). Any side.
- **UNSURE**: `Drill.canMine` uses `tile.drop()`, which does not test `wallOre`. If a map had a wall-ore overlay on a tile with no wall, a floor drill would mine it. I did not check whether map generation can produce that.

## B.4 `cliff-crusher` / `large-cliff-crusher` (WallCrafter)

| | `cliff-crusher` | `large-cliff-crusher` | Lines |
|---|---|---|---|
| size | 2 | 3 | `Blocks.java:3021-3055` |
| cost | graphite 25, beryllium 20 | silicon 80, surge-alloy 15, beryllium 100, tungsten 50 | |
| `drillTime` | 110 | 48 | |
| attribute / output | `Attribute.sand` / sand | same | |
| power | 11/60 per tick = 11/s | 1/tick = 60/s | |
| required liquid | none | hydrogen 1/60 per tick = 1/s | |
| optional item boost | none | graphite, 1 per `boostItemUseTime` = 80 ticks, x1.6 (`itemBoostIntensity`, `WallCrafter.java:47`) | |
| `itemCapacity` | 10 | 20 | |

- **Facing rule.** Only the `size` tiles touching the front edge are examined (depth 1). Each must be solid; its **block's** `sand` attribute is added:

```java
// WallCrafter.java:171-174
Tile other = world.tile(rx, ry);
if(other != null && other.solid()){
    float at = other.block().attributes.get(attribute);
    eff += at;
```

- **Placement** requires that sum > 0 (`WallCrafter.java:137-139`).
- **Rate**: `if(cons && (time += edelta() * eff) >= drillTime){ offload(output);` (`WallCrafter.java:213-214`) → `sand/s = 60 / drillTime x (sum of attributes) x efficiency x boost`.
- **Sand attribute per wall** (`Blocks.java:597-676`): `stone-wall` 1, `dune-wall` 2, `regolith-wall` 1, `yellow-stone-wall` 1.5, `rhyolite-wall` 1, `carbon-wall` 0.7, `ferric-stone-wall` 0.5, `beryllic-stone-wall` 1.2, `red-stone-wall` 1.5, `sand-wall` 2. Not listed there, hence 0: `arkyic-wall`, `crystalline-stone-wall`, `red-ice-wall`, `red-diamond-wall`, `graphitic-wall`, and every building.
- Examples (derived): cliff-crusher on two attribute-1 walls: 60/110 x 2 = 1.09 sand/s. Large on three attribute-1 walls: 60/48 x 3 = 3.75/s, 6/s with graphite.
- **Output**: `offload` then `dump(output)` every 5 ticks to any adjacent acceptor (`WallCrafter.java:214`, `:220-222`). Stops when it holds `itemCapacity` sand (`:226-228`).
- The large crusher accepts graphite from any side (default `acceptItem`).
- Remember A.10: sand cannot be stored in an Erekir core.

## B.5 `vent-condenser` (the only AttributeCrafter on Erekir)

`Blocks.java:3000-3019`: size 3, cost graphite 20, beryllium 60; `attribute = Attribute.steam`, `minEfficiency = 9f - 0.0001f`, `baseEfficiency = 0f`, `boostScale = 1f / 9f`; power 0.5/tick = 30/s; output water 30/60 per tick = 30/s; `liquidCapacity = 60`; `itemCapacity = 0`.

- **Tile rule: all 9 tiles under it must be vent floor.** `return baseEfficiency + tile.getLinkedTilesAs(this, tempTiles).sumf(other -> other.floor().attributes.get(attribute)) >= minEfficiency;` (`AttributeCrafter.java:71-74`). Every `*-vent` floor has `attributes.set(Attribute.steam, 1f)` (`Blocks.java:504-542`), and a vent is a 3x3 patch of that floor (`environment/SteamVent.java:20-30`). 8 of 9 tiles gives 8 < 8.9999 → not placeable.
- **Output**: efficiency multiplier = `baseEfficiency + Math.min(maxBoost, boostScale * attrsum) + attribute.env()` (`AttributeCrafter.java:99-101`) = 1.0 on a full vent (if the environment attribute is 0, see K), so 30 water/s at full power. Water leaves in every direction to any adjacent liquid acceptor (default `liquidOutputDirections = {-1}`, `GenericCrafter.java:35`).
- No items in or out.
- The power-producing vent block is `turbine-condenser`, a `ThermalGenerator` — see C.

---

# C. Power

## C.1 Units

- **All power numbers in `Blocks.java` are per tick.** `consumePower(float powerPerTick)` (`Block.java:1161-1163`); generator output is `powerProduction * productionEfficiency` per tick (`power/PowerGenerator.java:208-210`), accumulated as `producer.getPowerProduction() * producer.delta()` (`power/PowerGraph.java:100`). UI "power units/second" = value x 60.
- **Shortage is shared proportionally.** Every non-buffered consumer on a graph gets `power.status = coverage` where `coverage = Math.min(1, produced / needed)` after batteries (`PowerGraph.java:189`, `:204`, `:246`). A consumer's efficiency is that status (`ConsumePower.java:38-40`). There is no priority.

## C.2 Which adjacent buildings share a graph without nodes

```java
// BuildingComp.java:1192-1198
for(Building other : proximity){
    if(other != null && other.power != null
    && other.team == team
    && !(block.consumesPower && other.block.consumesPower && !block.outputsPower && !other.block.outputsPower && !block.conductivePower && !other.block.conductivePower)
    && conductsTo(other) && other.conductsTo(self()) && !power.links.contains(other.pos())){
        out.add(other);
```

Two edge-adjacent same-team buildings that both have a power module join one graph **unless both are pure consumers** (consume, do not output, not conductive). So:

| Pair (edge-adjacent) | Connected? |
|---|---|
| generator – consumer | yes |
| generator – generator | yes (generators are `consumesPower = false`, `outputsPower = true`, `power/PowerDistributor.java:7-8`) |
| beam-node / beam-tower – anything with power | yes (they set `consumesPower = outputsPower = true`, `Blocks.java:2669`, `:2682`) |
| beam-link – anything with power | yes (`PowerNode` sets both false, `power/PowerNode.java:46-47`, so the exclusion never applies) |
| consumer – consumer | **no** |
| consumer – `surge-conveyor` / `surge-router` / `shielded-wall` – consumer | yes; these three set `conductivePower = true` (`Blocks.java:2248`, `:2264`, `:1912`) |

- `connectedPower` is `true` by default (`Block.java:57`) and no block in `Blocks.java` sets it false, so it never excludes anything in vanilla.
- `insulated = true` exists only on the Serpulo plastanium walls (`Blocks.java:1734`, `:1743`) and `PowerDiode` (`power/PowerDiode.java:22`). No Erekir block is insulated.
- **Batteries and diodes**: `battery`, `battery-large` and `diode` exist only as Serpulo blocks (`Blocks.java:2506-2521`, costs in copper/lead/titanium/plastanium/metaglass) and are not in the Erekir tech tree (J). On Erekir the storage role is played by the beam nodes themselves (C.3).

## C.3 `beam-node` / `beam-tower` (BeamNode)

| | `beam-node` | `beam-tower` | Lines |
|---|---|---|---|
| size | 1 | 3 | `Blocks.java:2667-2688` |
| cost | beryllium 8 | beryllium 30, oxide 10, silicon 10 | |
| `range` | 10 | 23 | |
| buffer (acts as battery) | 1000 units | 40000 units | `consumePowerBuffered(…)` |

Link search, run for each of the 4 axis directions:

```java
// BeamNode.java:247-262
int offset = size/2;
//find first block with power in range
for(int j = 1 + offset; j <= range + offset; j++){
    var other = world.build(tile.x + j * dir.x, tile.y + j * dir.y);
    //hit insulated wall
    if(other != null && other.isInsulated()){
        break;
    }
    //power nodes do NOT play nice with beam nodes, do not touch them as that forcefully modifies their links
    if(other != null && other.block.hasPower && other.block.connectedPower && other.team == team && !(other.block instanceof PowerNode)){
        links[i] = other;
        dests[i] = world.tile(tile.x + j * dir.x, tile.y + j * dir.y);
        break;
    }
}
```

Rules that follow:

1. **Axis-aligned only, one ray per direction**, cast from the node's centre tile. For `beam-tower` (size 3) the ray is the centre row/column only, not the full 3-tile edge.
2. **Range** is counted from the node's edge: `beam-node` reaches tiles 1..10 away; `beam-tower` reaches centre offsets 2..24, i.e. 1..23 tiles beyond its edge.
3. **The first same-team building with `hasPower` on the ray is linked and the ray stops there.** It may be a consumer, generator, another beam node, a turret, a surge conveyor — anything with a power module. A multi-tile building counts if any of its tiles lies on the ray.
4. **A beam cannot pass over a powered building** to reach something behind it.
5. **Non-powered buildings and terrain are transparent.** `world.build(...)` is null for static walls and empty tiles, and buildings without `hasPower` (ducts, walls, containers, conduits, bores' targets…) fail the test and the loop continues. Only `insulated` buildings block, and Erekir has none.
6. Enemy-team powered buildings are skipped (no link, no block).
7. `PowerNode` subclasses — including `beam-link` — are never beam targets and do not stop the ray.
8. Links are recomputed automatically whenever any tile in the world changes: `if(lastChange != world.tileChanges){ … updateDirections(); }` (`BeamNode.java:192-195`). **No configuration**; placement alone determines connectivity.
9. Linking is mutual once either side finds the other (`BeamNode.java:286-289`: both `power.links` get the other's position and the graphs merge), so a tower can reach a node that is beyond the node's own range.

Because a ray stops at the first powered building, a row of consumers in line with a node is **not** all powered by that node: only the nearest one is linked, and consumers do not conduct to each other (C.2). Either give each consumer its own ray, or put a generator/node/conductive block between them.

## C.4 `beam-link` (LongPowerNode, a classic laser node)

`Blocks.java:2690-2702`: size 3, cost beryllium 250, silicon 250, oxide 150, carbide 75, surge-alloy 75, phase-fabric 75; `maxNodes = 1`, `laserRange = 500f` (tiles), `autolink = false`, `sameBlockConnection = true`.

- Links only to **another `beam-link`**: `(sameBlockConnection && tile.block != link.block)) return false;` (`PowerNode.java:363`).
- Exactly one laser link per beam-link (`maxNodes = 1`), so they work in pairs.
- **Must be configured**; nothing is linked on placement because `getPotentialLinks` returns immediately when `!autolink` (`PowerNode.java:221-222`). Config is either an `Integer` packed tile position of the partner (`PowerNode.java:60-95`, toggles the link) or a `Point2[]` of offsets relative to the node (`PowerNode.java:97-109`).
- Range test is circle (radius 500 tiles around the source) against the target's rectangle (`PowerNode.java:365`); effectively map-wide.
- It joins the local grid only by edge adjacency (C.2); beam nodes ignore it as a ray target. Put a beam node or generator directly against it.
- **UNSURE**: whether a laser link between beam-links is subject to the `PowerNode.insulated` raycast at config time. `linkValid` (`PowerNode.java:362-372`) does not call it, and Erekir has no insulated blocks, so it should not matter.

## C.5 Generators

All values straight from `Blocks.java`; "/s" = source value x 60.

| Block | Class | Size | Cost | Inputs | Power out | Other outputs | Lines |
|---|---|---|---|---|---|---|---|
| `turbine-condenser` | `ThermalGenerator` | 3 | beryllium 60 | none (vent tiles) | `powerProduction = 3f / 9f` per unit of steam; on a full vent 3/tick = **180/s** | water `5f / 60f / 9f` per steam unit → 5/60 per tick = **5/s**; `liquidCapacity = 20` | `2704-2727` |
| `chemical-combustion-chamber` | `ConsumeGenerator` | 3 | graphite 40, tungsten 20, oxide 40, silicon 30 | ozone 2/60 per tick (2/s), arkycite 40/60 per tick (40/s) | `550f / 60f` per tick = **550/s** | none | `2729-2749` |
| `pyrolysis-generator` | `ConsumeGenerator` | 3 | graphite 100, carbide 60, oxide 60, silicon 100 | slag 20/60 per tick (20/s), arkycite 40/60 per tick (40/s) | `1400f / 60f` per tick = **1400/s** | water 20/60 per tick (20/s); `liquidCapacity = 150` | `2751-2779` |
| `flux-reactor` | `VariableReactor` | 5 | graphite 240, carbide 60, oxide 80, silicon 480, surge-alloy 120 | heat (0..`maxHeat` = 150), cyanogen 9/60 per tick at full heat | `18000f / 60f` per tick = **18000/s** at 150 heat | none | `2781-2815` |
| `neoplasia-reactor` | `HeaterGenerator` | 5 | tungsten 750, carbide 300, oxide 150, silicon 500, phase-fabric 150, surge-alloy 200 | arkycite 80/60 per tick (80/s), water 10/60 per tick (10/s), phase-fabric 1 per `itemDuration` = 180 ticks | `powerProduction = 140f` per tick = **8400/s** | neoplasm 20/60 per tick (20/s), heat 60 out of its front; `liquidCapacity = 80` | `2817-2879` |

### `turbine-condenser`

- **Vent rule: all 9 tiles must be vent floor.** `return tile.getLinkedTilesAs(this, tempTiles).sumf(other -> other.floor().attributes.get(attribute)) > minEfficiency;` (`power/ThermalGenerator.java:78-81`) with `minEfficiency = 9f - 0.0001f` and each vent tile worth 1.0 steam.
- `productionEfficiency = sum + attribute.env();` (`ThermalGenerator.java:88`) = 9 on a vent → power 9 x 3/9 = 3/tick.
- **It also outputs water** (5/s), dumped to any adjacent liquid acceptor: `liquids.add(outputLiquid.liquid, added); dumpLiquid(outputLiquid.liquid);` (`ThermalGenerator.java:94-98`). The amount is capped at capacity, so a full water buffer never reduces power.
- Not rotatable; needs no inputs. Compare `vent-condenser` (B.5): same footprint and vent rule, 30 water/s, costs 30 power/s instead of producing 180/s.

### `chemical-combustion-chamber`, `pyrolysis-generator`

- `productionEfficiency = efficiency * efficiencyMultiplier;` (`power/ConsumeGenerator.java:116`); efficiency is the minimum fill ratio across the required liquids (`ConsumeLiquids.java:51-60`), so partial supply gives partial power.
- Pyrolysis water output is capped at capacity (`ConsumeGenerator.java:137-138`); `explodeOnFull` is false for it (default, `ConsumeGenerator.java:29`), so a full water buffer is harmless.
- Liquids are accepted from any side (default `acceptLiquid`, `BuildingComp.java:873-875`), subject to the conduit-facing rule in D.

### `flux-reactor` — explosion risk

```java
// power/VariableReactor.java:131-142
float target = Mathf.clamp(heat / maxHeat);
float efficiencyMet = Mathf.clamp(Mathf.zero(target) ? 1f : efficiency / target);
boolean met = efficiencyMet >= 0.99999f;
instability = Mathf.approachDelta(instability, met ? 0f : 1f, met ? 0.5f : unstableSpeed * (1f - efficiencyMet));
efficiency *= target;
```

- Output scales with heat received: power = 300/tick x `heat/150`. Cyanogen use scales the same way.
- With **no heat** it is inert and safe. With heat but **insufficient cyanogen**, `instability` rises at up to `unstableSpeed = 1f / 60f / 3f` per tick (`VariableReactor.java:25`): with zero cyanogen it reaches 1 in 180 ticks (3 s) and the block is killed (`VariableReactor.java:85-87`).
- On death it explodes if `state.rules.reactorExplosions` (default true, `game/Rules.java:61`) and `heat > 0f` (`VariableReactor.java:96-99`): `explosionRadius = 17` tiles, `explosionDamage = 2500` (`Blocks.java:2790-2791`), plus slag puddles.
- `rebuildable = false` (`VariableReactor.java:39`).
- Heat is collected by the general rule in E (it is a `HeatConsumer`).

### `neoplasia-reactor` — explosion risk

- `explodeOnFull = true` (`Blocks.java:2823`): `if(explodeOnFull && liquids.get(outputLiquid.liquid) >= liquidCapacity - 0.01f){ kill();` (`ConsumeGenerator.java:141-142`). At 20 neoplasm/s into an 80-unit buffer, **an undrained reactor dies about 4 s after starting**. Explosion: radius 9, damage 2000, 80 neoplasm puddles (`Blocks.java:2834-2848`), if warmup >= 0.5.
- Neoplasm is dumped to any adjacent liquid acceptor (`ConsumeGenerator.java:139`). No vanilla Erekir block consumes neoplasm; it must be piped away to somewhere that keeps accepting. **UNSURE** what the intended sink is (a leaking open conduit end creates neoplasm puddles, which is a hazard of its own — neoplasm is a `CellLiquid`, `content/Liquids.java:49`). Test before automating.
- It is rotatable (`power/HeaterGenerator.java:21`) and emits 60 heat from its front (E).

---

# D. Liquids

## D.1 Field values

| Block | Class | Size | `liquidCapacity` | Cost | Other | Lines |
|---|---|---|---|---|---|---|
| `reinforced-conduit` | `ArmoredConduit` | 1 | 50 | beryllium 2 | **`leaks = true`**, `liquidPressure = 1.03f`, not solid | `Blocks.java:2413-2423` |
| `reinforced-liquid-junction` | `LiquidJunction` | 1 | - | graphite 4, beryllium 8 | `solid = false` | `2425-2433` |
| `reinforced-bridge-conduit` | `DirectionLiquidBridge` | 1 | 120 | graphite 8, beryllium 20 | `range = 4`, `hasPower = false`, `floating = true` | `2435-2447` |
| `reinforced-liquid-router` | `LiquidRouter` | 1 | 150 | graphite 8, beryllium 4 | `solid = false` | `2449-2459` |
| `reinforced-liquid-container` | `LiquidRouter` | 2 | 1000 | tungsten 10, beryllium 16 | solid | `2461-2469` |
| `reinforced-liquid-tank` | `LiquidRouter` | 3 | 2700 | tungsten 40, beryllium 50 | solid | `2471-2478` |
| `reinforced-pump` | `Pump` | 2 | 160 | beryllium 40, tungsten 30, silicon 20 | `pumpAmount = 80f / 60f / 4f` per tile per tick; **requires hydrogen 1.5/60 per tick**; no power | `2404-2411` |

Every liquid block holds **one liquid type at a time**: a conduit/router/bridge accepts a liquid only if it matches the current one or the block holds < 0.2 units (`liquid/Conduit.java:243`, `liquid/LiquidRouter.java:42`, `distribution/DirectionLiquidBridge.java:90`).

## D.2 `reinforced-conduit`

**Acceptance.** Base conduit rule — never from the front:

```java
// Conduit.java:243-244
return (liquids.current() == liquid || liquids.currentAmount() < 0.2f)
    && (tile == null || source == this || (source.relativeTo(tile.x, tile.y) + 2) % 4 != rotation);
```

plus the armored restriction:

```java
// ArmoredConduit.java:25-26
return super.acceptLiquid(source, liquid) && (tile == null || source.block instanceof Conduit || source.block instanceof DirectionLiquidBridge || source.block instanceof LiquidJunction ||
    source.tile.absoluteRelativeTo(tile.x, tile.y) == rotation || !source.proximity.contains(this));
```

So:

- From another **conduit**, a **bridge conduit** or through a **junction**: accepted from the back and from both sides (side-merging conduits works).
- From **any other block** (crafter, pump, generator, router, container, tank): accepted **only when that block is directly behind the conduit**, i.e. the conduit points straight away from it. A conduit running *past* a crafter's side takes nothing from it.
- The last clause (`!source.proximity.contains(this)`) is the junction pass-through case.

**Output.** Forward only, once per tick (`Conduit.java:251-252`: `moveLiquidForward(leaks, liquids.current());`).

**Leaking — yes, an open end leaks.** `ArmoredConduit` defaults to `leaks = false` (`ArmoredConduit.java:12`) but `reinforced-conduit` sets `leaks = true` (`Blocks.java:2416`).

```java
// BuildingComp.java:933-939
if(next.build != null){
    return moveLiquid(next.build, liquid);
}else if(leaks && !next.block().solid && !next.block().hasLiquids){
    float leakAmount = liquids.get(liquid) / 1.5f;
    Puddles.deposit(next, tile, liquid, leakAmount, true, true);
    liquids.remove(liquid, leakAmount);
}
```

- Front tile has **no building and is not solid** (open floor): two thirds of the content is dumped as a puddle every tick. Gases just vanish (`entities/Puddles.java:42-47`).
- Front tile is a static wall: no leak, liquid stays.
- Front tile has **any building** (even one without liquids): no leak; `moveLiquid` simply transfers nothing if the building has no liquids or refuses.

So never leave a reinforced conduit pointing at empty ground, including temporarily during construction order.

**Throughput (derived, UNSURE).** There is no fixed per-tick cap. Flow per tick is driven by fill-fraction difference:

```java
// BuildingComp.java:949-952
float ofract = next.liquids.get(liquid) / next.block.liquidCapacity;
float fract = liquids.get(liquid) / block.liquidCapacity * block.liquidPressure;
float flow = Math.min(Mathf.clamp((fract - ofract)) * (block.liquidCapacity), liquids.get(liquid));
flow = Math.min(flow, next.block.liquidCapacity - next.liquids.get(liquid));
```

With capacity 50 and pressure 1.03, two equally full conduits still pass `0.03 x 50 = 1.5` units/tick. My steady-state reading: a line of any length sustains about **1.5 units/tick = 90 units/s**; higher flows need a falling fill gradient (each segment `Q/50` lower than the last, partly offset by the 1.03 factor), which limits length — roughly 23 tiles at 180/s and 12 tiles at 300/s by my arithmetic. Treat these as estimates and measure: pump a known rate into lines of different lengths and read the delivered rate. For anything above ~90/s over distance, use parallel lines.

**Mixing hazard.** If a conduit tries to push into a block holding a different liquid, `moveLiquid` runs a reaction check (`BuildingComp.java:959-979`): a flammable liquid (flammability > 0.3: hydrogen 1, ozone 1, cyanogen 2, arkycite 0.4 — `content/Liquids.java:65-95`) meeting a hot one (temperature > 0.7: slag 1.0, `Liquids.java:21-22`) damages both blocks continuously; hot meeting cold evaporates liquid. Keep slag lines from feeding into gas/arkycite lines.

## D.3 `reinforced-liquid-junction`

Passes liquid straight through to the block on the opposite side of where it came from (`liquid/LiquidJunction.java:44-53`: `int dir = (source.relativeTo(tile.x, tile.y) + 4) % 4; Building next = nearby(dir);`). Holds nothing (`update = false`). If the far side refuses, nothing moves. Two perpendicular conduit lines cross through it independently.

## D.4 `reinforced-bridge-conduit`

Same linking code as the duct bridge (A.6; `DirectionBridge.findLink`, `DirectionBridge.java:252-259`): first same-team bridge conduit along its rotation within `range = 4` tiles, nothing in between is checked, chaining works, corners are possible.

```java
// DirectionLiquidBridge.java:82-91
var link = findLink();
//only accept if there's an output point, or it comes from a link
if(link == null && !(source instanceof DirectionBridgeBuild b && b.findLink() == this)) return false;
int rel = this.relativeToEdge(source.tile);
return
    hasLiquids && team == source.team &&
    (liquids.current() == liquid || liquids.get(liquids.current()) < 0.2f) && rel != rotation &&
    (occupied[(rel + 2) % 4] == null || occupied[(rel + 2) % 4] == source);
```

- A bridge **with** a link accepts from back and sides (not front) from **any** block type — there is no armored restriction, so a crafter can output straight into a bridge start placed against it.
- An **end** bridge accepts only from the bridge linking into it, and outputs to its front only, **without leaking** (`DirectionLiquidBridge.java:67-70`: `moveLiquidForward(false, liquids.current());`).
- Transfer between linked bridges is `moveLiquid(link, …)` every tick (`DirectionLiquidBridge.java:63`), gradient-driven with capacity 120.

## D.5 `reinforced-liquid-router` / `-container` / `-tank`

- Accept from **any side and any source**, one liquid type (`LiquidRouter.java:41-43`).
- Every tick they `dumpLiquid(liquids.current())` to all adjacent liquid acceptors (`LiquidRouter.java:25-27`), moving `(fract - ofract) * liquidCapacity / 2` toward any neighbour that is proportionally emptier (`BuildingComp.java:906-911`). They equalise fill fraction with neighbours; they are not one-way.
- They can push into a reinforced conduit only if the conduit points directly away from them (D.2), and into consumers directly.
- No leak code: a router/tank with nothing attached just holds its liquid.

## D.6 `reinforced-pump`

- Placement: every tile under it that has a `liquidDrop` must give the same liquid, and at least one must have one (`Pump.java:84-96`). Partial coverage is allowed; output is proportional to covered tiles.
- Output: `amount * pumpAmount * edelta()` per tick where `amount` = sum of `floor.liquidMultiplier` over covered liquid tiles (`Pump.java:146-150`, `:162`). Full 2x2 coverage with multiplier 1: 4 x 80/60/4 = 80/60 per tick = **80 units/s**.
- **It needs hydrogen** (1.5/s) as a non-optional consumer and no power (`Blocks.java:2406`); without hydrogen efficiency is 0 and it pumps nothing. Hydrogen is accepted from any side (a reinforced conduit must point into the pump).
- It dumps only the pumped liquid to all adjacent acceptors (`Pump.java:178-180`).
- `floating = true` (`Pump.java:29`), so it may sit on deep liquid floors.
- Pumpable Erekir floors: `arkycite-floor` → arkycite (`Blocks.java:486-498`), `molten-slag` → slag (`Blocks.java:318-335`), both with default `liquidMultiplier`; water floors where a map has them.

## D.7 Liquid outputs of crafters

Erekir blocks that produce liquid: `electrolyzer` (ozone + hydrogen), `atmospheric-concentrator` (nitrogen), `cyanogen-synthesizer` (cyanogen), `vent-condenser` (water), `turbine-condenser` (water), `pyrolysis-generator` (water), `neoplasia-reactor` (neoplasm), `reinforced-pump`.

**`liquidOutputDirections`** (`GenericCrafter.java:35`, default `{-1}` = all sides). Entry *i* applies to `outputLiquids[i]` and is a side **relative to the block's rotation**:

```java
// GenericCrafter.java:347-349 and BuildingComp.java:902
int dir = liquidOutputDirections.length > i ? liquidOutputDirections[i] : -1;
dumpLiquid(outputLiquids[i].liquid, 2f, dir);
…
if(outputDir != -1 && (outputDir + rotation) % 4 != relativeTo(other)) continue;
```

Only `electrolyzer` uses it: `outputLiquids = LiquidStack.with(Liquids.ozone, 4f / 60, Liquids.hydrogen, 6f / 60); liquidOutputDirections = new int[]{1, 3};` (`Blocks.java:1396-1397`).

| Electrolyzer `rotation` | Ozone leaves on side `(rotation+1)%4` | Hydrogen leaves on side `(rotation+3)%4` |
|---|---|---|
| 0 (facing +x) | +y (top) | -y (bottom) |
| 1 (facing +y) | -x (left) | +x (right) |
| 2 (facing -x) | -y (bottom) | +y (top) |
| 3 (facing -y) | +x (right) | -x (left) |

Any liquid acceptor touching any tile of that 3-tile edge qualifies; a reinforced conduit there must point directly away from the electrolyzer. Nothing leaves through the front or back. Water is accepted on any side.

**Stalling on full outputs** (`GenericCrafter.java:214-231`):

```java
if(outputLiquids != null && !ignoreLiquidFullness){
    boolean allFull = true;
    for(var output : outputLiquids){
        if(liquids.get(output.liquid) >= liquidCapacity - 0.001f){
            if(!dumpExtraLiquid){
                return false;
            }
        }else{
            allFull = false;
        }
    }
    if(allFull){
        return false;
    }
}
```

- `ignoreLiquidFullness = true`: never stalls on liquid; overflow is discarded.
- `dumpExtraLiquid = true` (the default, `GenericCrafter.java:38`): the crafter stalls only when **all** liquid outputs are full. A single full output is simply capped (`Math.min(output.amount * inc, liquidCapacity - liquids.get(output.liquid))`, `GenericCrafter.java:247`) — that liquid is wasted while the other keeps being produced.
- `dumpExtraLiquid = false`: stalls as soon as **any** output is full.

No Erekir block in `Blocks.java` sets either field, so all use the default. Practical meaning: **an electrolyzer whose ozone side is blocked keeps making hydrogen (and vice versa), consuming water and power at the normal rate**; single-output crafters (concentrator, cyanogen synthesizer, vent condenser) stall when their one buffer is full. No buildable Erekir crafter has both an item output and a liquid output.

Output transfer rate from the crafter buffer is `(fract - ofract) * liquidCapacity / 2` per neighbour per tick (`BuildingComp.java:910`), far above production, so the buffer tracks the fill level of whatever it feeds.

---

# E. Heat

## E.1 The rule (one function serves every heat consumer)

```java
// BuildingComp.java:422-446
for(var build : proximity){
    if(build != null && build.team == team && build instanceof HeatBlock heater){
        boolean split = build.block instanceof HeatConductor cond && cond.splitHeat;
        // non-routers must face us, routers must face away - next to a redirector, they're forced to face away due to cycles anyway
        if(!build.block.rotate || (!split && (relativeTo(build) + 2) % 4 == build.rotation) || (split && relativeTo(build) != build.rotation)){
            //if there's a cycle, ignore its heat
            if(!(build instanceof HeatConductorBuild hc && hc.cameFrom.contains(id()))){
                //x/y coordinate difference across point of contact
                float diff = (Math.min(Math.abs(build.x - x), Math.abs(build.y - y)) / tilesize);
                //number of points that this block had contact with
                int contactPoints = Math.min((int)(block.size/2f + build.block.size/2f - diff), Math.min(build.block.size, block.size));
                //heat is distributed across building size
                float add = heater.heat() / build.block.size * contactPoints;
                if(split){
                    //heat routers split heat across 3 surfaces
                    add /= 3f;
                }
                sideHeat[Mathf.mod(relativeTo(build), 4)] += add;
                heat += add;
```

Consequences:

1. **From any side.** A consumer collects from every edge-adjacent heat block on all four sides.
2. **The heater must face the consumer.** For producers and redirectors the test is `(relativeTo(build) + 2) % 4 == build.rotation`: the heater's rotation points from heater to consumer. A heater merely touching with its side or back gives nothing.
3. **Multiple heaters add** (`heat += add` per heater).
4. **Heat is not divided by demand.** A heater does not "spend" heat; each consumer it faces reads the heater's full value scaled by contact. Heat is a level, not a flow.
5. **Contact scaling — the alignment rule.** A heater's output is spread evenly over the tiles of its edge (`heater.heat() / build.block.size`), and a consumer receives one share per tile of overlap, capped at the smaller block's size:
   `contactPoints = min( floor(sizeA/2 + sizeB/2 - lateralOffsetInTiles), min(sizeA, sizeB) )`.

| Heater → consumer | Alignment | Contact tiles | Heat received |
|---|---|---|---|
| 3x3 → 3x3 | centred | 3 | 100% |
| 3x3 → 3x3 | shifted 1 tile | 2 | 67% |
| 3x3 → 3x3 | shifted 2 tiles | 1 | 33% |
| 2x2 → 3x3 | both heater tiles on the edge (offset 0.5) | 2 | 100% |
| 2x2 → 3x3 | hanging over by one tile (offset 1.5) | 1 | 50% |
| 2x2 → 5x5 (flux reactor) | fully on the edge | 2 | 100% |
| 3x3 → 5x5 | fully on the edge | 3 | 100% |
| 3x3 → 2x2 | best case | 2 | **67% at most** |

   So a 2x2 heater does **not** need to cover the consumer's whole edge; it needs its **own** whole front edge on the consumer. A heater larger than the consumer always loses heat. One heater edge may straddle two consumers, each getting its share of tiles.
6. **Heat routers** (`splitHeat = true`) emit on the three sides other than their back (`relativeTo(build) != build.rotation`), each side getting one third of the router's heat (then contact-scaled). A side with nothing on it wastes its third.
7. **Cycles** between conductors are detected and ignored (`cameFrom`).

## E.2 Producers

All are `HeatProducer` (a `GenericCrafter` subclass) except the reactor; all are rotatable (`heat/HeatProducer.java:22`: `rotate = true;`) and emit from their front. Heat approaches `heatOutput * efficiency` at `warmupRate` 0.15 per tick (`HeatProducer.java:51`), so partial inputs give proportionally less heat and a stalled producer cools to 0.

| Block | Size | Cost | `heatOutput` | Consumes | Lines |
|---|---|---|---|---|---|
| `electric-heater` | 2 | tungsten 30, oxide 30, beryllium 30 | 3 | power 100/60 per tick = 100/s | `Blocks.java:1452-1465` |
| `slag-heater` | 3 | tungsten 50, oxide 20, beryllium 20 | 8 | slag 40/60 per tick = 40/s | `1467-1483` |
| `phase-heater` | 2 | oxide 30, carbide 30, beryllium 30 | 15 | 1 phase-fabric per `craftTime` = 480 ticks (8 s) | `1485-1494` |
| `oxidation-chamber` | 3 | tungsten 120, graphite 80, silicon 100, beryllium 120 | 5 | ozone 2/60 per tick (2/s), 1 beryllium per 120 ticks, power 0.5/tick (30/s); **makes 1 oxide per 120 ticks** | `1429-1450` |
| `neoplasia-reactor` | 5 | (C.5) | 60 | (C.5) | `2817-2879` |
| `heat-reactor` | 3 | - | 10 (class default) | `BuildVisibility.debugOnly` — **not obtainable** | `1689-1701` |

The oxidation chamber is a crafter and a heater at once: because it is a `GenericCrafter`, it stops (`shouldConsume` false, `GenericCrafter.java:206-212`) when its oxide output is full (`itemCapacity` 10), and then its heat falls to 0. Oxide must be removed for it to keep heating.

## E.3 Conductors

| Block | Size | Cost | Behaviour | Lines |
|---|---|---|---|---|
| `heat-redirector` | 3 | tungsten 10, graphite 10 | sums all heat facing into it from any side, re-emits the sum from its front | `Blocks.java:1496-1505` |
| `small-heat-redirector` | 2 | surge-alloy 3, graphite 8 | same, 2x2 | `1507-1517` |
| `heat-router` | 3 | tungsten 15, graphite 10 | sums inputs, emits one third on each of front/left/right | `1519-1529` |

- `heat = enabled ? calculateHeat(sideHeat, cameFrom) : 0f;` (`heat/HeatConductor.java:90`) and `heat()` returns it unchanged (`:105-107`). **A redirector itself loses nothing**; a chain of aligned 3x3 redirectors carries 100% over any distance. Loss comes only from contact mismatch (E.1.5) and from routers' unused sides.
- A redirector may be fed on several sides at once (inputs add) — the way to combine heaters.
- Do not point two redirectors at each other; the cycle is ignored, not summed.

## E.4 Consumers

**`HeatCrafter`** (`production/HeatCrafter.java:88-91`):

```java
float over = Math.max(heat - heatRequirement, 0f);
return Math.min(Mathf.clamp(heat / heatRequirement) + over / heatRequirement * overheatScale, maxEfficiency);
```

- **Partial heat gives partial speed, linearly.** Zero heat = no work at all (`shouldConsume` requires `heat > 0`, `HeatCrafter.java:62-64`).
- Above the requirement, extra heat adds `overheatScale` (default 1) per multiple of the requirement up to `maxEfficiency`. **All five Erekir heat crafters set `maxEfficiency = 1f`**, so excess heat is useless for them.
- The multiplier scales `efficiency`, so liquid and power-proportional inputs scale with it too.

| Block | `heatRequirement` | `maxEfficiency` | Lines |
|---|---|---|---|
| `atmospheric-concentrator` | 24 | 1 | `Blocks.java:1422-1423` |
| `carbide-crucible` | 40 | 1 | `1549-1550` |
| `surge-crucible` | 40 | 1 | `1593-1594` |
| `cyanogen-synthesizer` | 20 | 1 | `1626-1627` |
| `phase-synthesizer` | 32 | 1 | `1661-1662` |

**`flux-reactor`**: heat 0..150 sets output and cyanogen demand (C.5).

**Turrets** with `heatRequirement > 0` collect heat the same way (`defense/turrets/Turret.java:544-545`) and scale efficiency: `efficiency *= Math.min(Math.max(heatReq / heatRequirement, cheating() ? 1f : 0f), maxHeatEfficiency);` (`Turret.java:678-679`), with `maxHeatEfficiency` default 3 (`Turret.java:55`) — unlike crafters, turrets can benefit from up to 3x heat unless overridden. Per-turret values are in H.

---

# F. Crafters

## F.1 Rules common to every crafter

- **Item input: any side, any source.** No crafter overrides `acceptItem`; the default applies (`BuildingComp.java:869-871`), with capacity `itemCapacity` **per input item type** (`GenericCrafter.java:368-370`: `return itemCapacity;`). A duct must point into the crafter to deliver (ducts only output forward, A.3).
- **Item output: any side.** On each craft the product is `offload`ed (`GenericCrafter.java:323-325`) to the next adjacent acceptor in rotation, or kept in the crafter if nobody accepts; every 5 ticks `dump(output.item)` retries (`GenericCrafter.java:336-342`). **No duct needs to "face away"**: a plain duct touching any side receives the product unless the crafter is in front of that duct; an armored duct only if the crafter is directly behind it. Anything adjacent that accepts the item also receives it — another crafter consuming it, a turret using it as ammo, a container, a core — so adjacency chains work and unwanted adjacency steals output.
- **Output-full stall.** `if(items.get(output.item) + scaleOutput(output.amount) > itemCapacity){ return false; }` (`GenericCrafter.java:206-212`) — a crafter stops when one more batch would exceed `itemCapacity`. Liquid-output stall is in D.7.
- **Progress.** `progress += getProgressIncrease(craftTime);` = `edelta() / craftTime` per tick (`GenericCrafter.java:240`, `BuildingComp.java:1208-1210`); one craft per `craftTime` ticks at efficiency 1. Liquid inputs are drained continuously at `amount * edelta()` per tick (`ConsumeLiquid.java:37-39`); item inputs are removed once per craft.
- **Rotation.** `GenericCrafter` and `HeatCrafter` are not rotatable. `electrolyzer` sets `rotate = true` (`Blocks.java:1362`); every `HeatProducer` is rotatable (`heat/HeatProducer.java:22`).
- **Power** below is per tick with (/s) in brackets.

## F.2 Table (values from `Blocks.java`)

| Block | Class | Size | Build cost | Item input per craft | Liquid input per tick | Output | `craftTime` | Power | Heat needed | Rotatable | `itemCapacity` / `liquidCapacity` | Lines |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `silicon-arc-furnace` | `GenericCrafter` | 3 | beryllium 70, graphite 80 | graphite 1, sand 4 | - | silicon 4 | 50 | 5 (300/s) | - | no | 30 / - | `1335-1354` |
| `electrolyzer` | `GenericCrafter` | 3 | silicon 50, graphite 40, beryllium 130, tungsten 80 | - | water 10/60 | ozone 4/60 per tick (side rot+1), hydrogen 6/60 per tick (side rot+3) | 10 (irrelevant) | 1 (60/s) | - | **yes** | 0 / 50 | `1356-1398` |
| `atmospheric-concentrator` | `HeatCrafter` | 3 | oxide 60, beryllium 180, silicon 150 | - | - | nitrogen 16/60 per tick | default 80 (irrelevant) | 2 (120/s) | 24 | no | 0 / 60 | `1400-1427` |
| `oxidation-chamber` | `HeatProducer` | 3 | tungsten 120, graphite 80, silicon 100, beryllium 120 | beryllium 1 | ozone 2/60 | oxide 1; **heat 5 from its front** | 120 | 0.5 (30/s) | - | **yes** | 10 / 30 | `1429-1450` |
| `carbide-crucible` | `HeatCrafter` | 3 | tungsten 110, thorium 150, oxide 60 | tungsten 2, graphite 3 | - | carbide 1 | `60f * 2.25f/4f` = 33.75 | 2 (120/s) | 40 | no | 20 / - | `1537-1554` |
| `surge-crucible` | `HeatCrafter` | 3 | silicon 100, graphite 80, tungsten 80, oxide 80 | silicon 3 | slag 160/60 | surge-alloy 1 | `60f * 3f/4f` = 45 | 1.5 (90/s) | 40 | no | 20 / 400 | `1587-1621` |
| `cyanogen-synthesizer` | `HeatCrafter` | 3 | carbide 80, silicon 120, beryllium 140, oxide 40 | graphite 1 | arkycite 160/60 | cyanogen 12/60 per tick | `80f / 4f` = 20 | 2 (120/s) | 20 | no | 10 / 80 | `1623-1653` |
| `phase-synthesizer` | `HeatCrafter` | 3 | carbide 90, silicon 100, thorium 100, tungsten 200 | thorium 2, sand 6 | ozone 8/60 | phase-fabric 1 | `60f * 2f / 4f` = 30 | 8 (480/s) | 32 | no | 40 / 40 | `1655-1687` |
| `slag-incinerator` | `ItemIncinerator` | 1 | tungsten 15 | any item (destroyed) | slag, amount 0 (must be present) | none | - | - | - | no | - | `1531-1535` |
| `slag-centrifuge` | `GenericCrafter` | 3 | - | - | - | - | - | - | - | - | - | `1556-1585` — `BuildVisibility.debugOnly`, **not buildable** |
| `heat-reactor` | `HeatProducer` | 3 | - | - | - | - | - | - | - | - | - | `1689-1701` — `BuildVisibility.debugOnly`, **not buildable** |

`debugOnly` is `new BuildVisibility(() -> false)` (`world/meta/BuildVisibility.java:12`), and both blocks are commented out of the tech tree (`ErekirTechTree.java:241-243`).

## F.3 Derived steady-state rates at 100% efficiency

| Block | Consumes per second | Produces per second |
|---|---|---|
| `silicon-arc-furnace` | graphite 1.2, sand 4.8, power 300 | silicon 4.8 |
| `electrolyzer` | water 10, power 60 | ozone 4, hydrogen 6 |
| `atmospheric-concentrator` | power 120, heat 24 | nitrogen 16 |
| `oxidation-chamber` | beryllium 0.5, ozone 2, power 30 | oxide 0.5, heat 5 |
| `carbide-crucible` | tungsten 3.56, graphite 5.33, power 120, heat 40 | carbide 1.78 |
| `surge-crucible` | silicon 4, slag 160, power 90, heat 40 | surge-alloy 1.33 |
| `cyanogen-synthesizer` | graphite 3, arkycite 160, power 120, heat 20 | cyanogen 12 |
| `phase-synthesizer` | thorium 4, sand 12, ozone 8, power 480, heat 32 | phase-fabric 2 |

The cyanogen synthesizer's graphite use (one per 20-tick craft = 3/s) follows directly from `craftTime = 80f / 4f;` and `consumeItem(Items.graphite);` (`Blocks.java:1648`, `:1651`); it surprised me, so check it in game before sizing a graphite line.

## F.4 `slag-incinerator`

`acceptItem` is `return efficiency > 0;` (`production/ItemIncinerator.java:63-66`) and `handleItem` discards the item (`:56-61`). Efficiency comes from `consumeLiquid(Liquids.slag, 0f)` (`Blocks.java:1534`): the block needs some slag in it but uses none. It accepts **any** item from any side — an output duct that touches one will lose items into it. **UNSURE** about the exact efficiency value when the required amount is 0 (the formula divides by `amount`, `ConsumeLiquid.java:46`); test that it works with a small amount of slag and stops when empty.

---

# G. Units

## G.1 Fabricators (`UnitFactory`)

| Block | Size | Build cost | Plan | Unit cost | Time | Power | Lines |
|---|---|---|---|---|---|---|---|
| `tank-fabricator` | 3 | silicon 200, beryllium 150 | `stell` | beryllium 40, silicon 50 | `60f * 35f` = 2100 ticks | 1.5/tick (90/s) | `Blocks.java:6448-6457` |
| `ship-fabricator` | 3 | silicon 250, beryllium 200 | `elude` | graphite 50, silicon 70 | `60f * 40f` = 2400 | 1.5/tick | `6459-6469` |
| `mech-fabricator` | 3 | silicon 200, beryllium 250, tungsten 10 | `merui` | beryllium 50, silicon 70 | `60f * 40f` = 2400 | 1.5/tick | `6471-6480` |

- **`configurable = false`** on all three (`Blocks.java:6451`, `:6463`, `:6474`), and `if(!configurable){ currentPlan = 0; }` runs every tick (`units/UnitFactory.java:401-403`). **The plan is fixed; there is nothing to choose, for players or AI teams.**
- **Items**: accepted from any side, only the plan's items, up to 2x the plan amount each (`UnitFactory.java:471-474`, `:99-107`, `:466-468`). Consumed when the unit completes (`:443`). Costs and capacities are multiplied by `state.rules.unitCost(team)` (`:109`).
- **Time**: `progress += edelta() * Vars.state.rules.unitBuildSpeed(team);` until `plan.time` (`UnitFactory.java:411`, `:428`).
- **Runs only when** `enabled && payload == null && team.activateUnitFactories()` (`UnitFactory.java:454-457`); the last term is `state.tick >= state.rules.unitActivationDelay(this)` (`game/Team.java:127-129`). Erekir's planet value `enemyFactoryActivationDelay` is 0 (default, `type/Planet.java:127`), so no delay unless the map's team rules set one.
- **Where the unit comes out: the front** (the block is rotatable). `moveOutPayload` (`payloads/PayloadBlock.java:212-241`):

```java
Building front = front();
boolean canDump = front == null || !front.tile.solid();
boolean canMove = front != null && (front.block.outputsPayload || front.block.acceptsPayload);
…
if(canMove){
    if(movePayload(payload)){ payload = null; }
}else if(canDump){
    dumpPayload();
}
```

  | In front of the fabricator's centre tile | Result |
  |---|---|
  | a payload block (refabricator, payload conveyor, assembler, module…) that accepts the unit | handed over as payload |
  | a payload block that **refuses** it (wrong unit type, busy) | **waits forever**; it never falls back to dumping |
  | nothing, or a non-solid building (duct, conduit) | unit is released onto the map |
  | a solid building that is not a payload block (wall, crafter) | stuck |
  | static wall (no building) | `canDump` is true but `UnitPayload.dump()` refuses for ground units when the tile ahead is solid for them (`payloads/UnitPayload.java:115-123`) → stuck |

  Release also fails while the team is at unit cap (`UnitPayload.java:108-112`) or another grounded unit overlaps the exit (`:126-128`). A stuck payload stops the factory (`payload == null` is required to run).
- `front()` is one tile (the one in line with the block centre, A.1), so the receiving payload block must cover that tile.

## G.2 Refabricators (`Reconstructor`)

| Block | Size | Build cost | Upgrades | Items per upgrade | Liquid | Power | `constructTime` | Lines |
|---|---|---|---|---|---|---|---|---|
| `tank-refabricator` | 3 | beryllium 200, tungsten 80, silicon 100 | stell → locus | silicon 40, tungsten 30 | hydrogen 3/60 per tick (3/s) | 3/tick (180/s) | `60f * 30f` = 1800 | `Blocks.java:6482-6498` |
| `ship-refabricator` | 3 | beryllium 250, tungsten 120, silicon 150, oxide 15 | elude → avert | silicon 60, tungsten 40 | hydrogen 3/s | 2.5/tick (150/s) | `60f * 50f` = 3000 | `6500-6517` |
| `mech-refabricator` | 3 | beryllium 250, tungsten 175, silicon 150 | merui → cleroi | silicon 50, tungsten 40 | hydrogen 3/s | 2.5/tick | `60f * 45f` = 2700 | `6519-6535` |
| `prime-refabricator` | 5 | thorium 250, oxide 200, tungsten 200, silicon 400 | locus → precept, cleroi → anthicus, avert → obviate | thorium 80, silicon 100 | nitrogen 10/60 per tick (10/s) | 4.5/tick (270/s) | `60f * 60f` = 3600 | `6537-6556` |

- **How a unit gets in.** As a payload pushed by an adjacent payload source, from the back or either side — never through the reconstructor's own front:

```java
// units/Reconstructor.java:243-246, 265
if(!(this.payload == null
&& (this.enabled || source == this)
&& relativeTo(source) != rotation
&& payload instanceof UnitPayload pay)){
    return false;
}
…
return upgrade != null && (team.isAI() || upgrade.unlockedNowHost()) && !upgrade.isBanned();
```

  So **a fabricator must face into the refabricator** (its front-centre tile must be a refabricator tile), or a `reinforced-payload-conveyor` must lead into it. No payload conveyor is required when the two blocks touch.
- **AI teams skip the research check** (`team.isAI() || upgrade.unlockedNowHost()`); `Team.isAI()` is true for every non-default team in campaign, wave or attack mode (`game/Team.java:112-114`).
- A unit with no upgrade here is refused, which jams the block feeding it (G.1 table).
- Items are accepted from any side (default rule), up to 2x the requirement (`Reconstructor.java:144-153`), and consumed at completion (`:358`). Liquid and power are drawn only while a unit is being upgraded (`:377-379`).
- The upgraded unit leaves through the **front** by the same `moveOutPayload` rule (`Reconstructor.java:333-334`), so refabricator → prime-refabricator → assembler can be chained by facing each into the next.
- Units can also enter payload blocks by the `enterPayload` unit command (`ai/UnitCommand.java:90`, `ai/types/CommandAI.java:231`). **UNSURE** how reliably AI-controlled units do this; I did not trace it.

## G.3 Assemblers (`UnitAssembler`) and `basic-assembler-module`

| Block | Size | Build cost | Tier-0 plan (no module) | Tier-1 plan (with module) | Liquid | Power | Lines |
|---|---|---|---|---|---|---|---|
| `tank-assembler` | 5 | thorium 500, oxide 150, carbide 80, silicon 650 | `vanquish`, 3000 ticks: stell x4, tungsten-wall-large x10 | `conquer`, 10800 ticks: locus x6, carbide-wall-large x20 | cyanogen 9/60 per tick (9/s) | 2.5/tick (150/s) | `Blocks.java:6558-6571` |
| `ship-assembler` | 5 | carbide 100, oxide 200, tungsten 550, silicon 900, thorium 400 | `quell`, 3600 ticks: elude x4, beryllium-wall-large x12 | `disrupt`, 10800 ticks: avert x6, carbide-wall-large x20 | cyanogen 12/s | 2.5/tick | `6573-6585` |
| `mech-assembler` | 5 | carbide 200, thorium 600, oxide 200, tungsten 550, silicon 1000 | `tecta`, 4200 ticks: merui x5, tungsten-wall-large x12 | `collaris`, 10800 ticks: cleroi x6, carbide-wall-large x20 | cyanogen 12/s | 3/tick (180/s) | `6587-6600` |
| `basic-assembler-module` | 5 | carbide 300, thorium 500, oxide 250, phase-fabric 400 | - | - | - | 3.5/tick (210/s) | `6603-6610` |

All three set `areaSize = 13`.

**Build area.** A 13x13-tile square immediately in front of the assembler, centred on its axis:

```java
// units/UnitAssembler.java:76-83
rect.setCentered(x, y, areaSize * tilesize);
float len = tilesize * (areaSize + size)/2f;
rect.x += Geometry.d4x(rotation) * len;
rect.y += Geometry.d4y(rotation) * len;
```

Centre of the area is `(13 + 5)/2 = 9` tiles in front of the assembler centre; the area starts at the assembler's front edge.

**Placement.** `canPlaceOn` rejects the assembler if its area overlaps the area of any other same-team assembler or assembler under construction (`UnitAssembler.java:101-107`). Nothing in that method inspects what is inside the area. The assembler has `quickRotate = false` (`:71`).

**Area must be clear at the centre.** Each tick `wasOccupied = checkSolid(spawn, false);` (`:518`): for a non-flying output, any solid tile overlapping a box of the output unit's `hitSize` at the area centre blocks it, as does any non-core unit of the same movement layer within 1.4x `hitSize` (`:679-685`). While occupied the assembler neither consumes nor progresses (`:395`, `:528`). Keep the area empty of buildings to be safe. **UNSURE** whether other code prevents building inside the area; `Build.validPlace` has no assembler check (K).

**Drones.** The assembler builds 4 `assembly-drone`s by itself (`dronesCreated = 4`, `droneConstructTime = 60f * 4f`, scaled by power; `:48-49`, `:478`), no item cost. Progress is multiplied by the fraction of drones in position at the area corners: `float eff = (units.count(u -> ((AssemblerAI)u.controller()).inPosition()) / (float)dronesCreated);` (`:520`), `progress += edelta() * state.rules.unitBuildSpeed(team) * eff / plan.time` (`:531`).

**Payload requirements and delivery.**

```java
// UnitAssembler.java:726-732
var plan = plan();
return (this.payload == null || (source instanceof UnitAssemblerModuleBuild)) &&
        plan.requirements.contains(b -> b.item == payload.content() &&
        blocks.get(payload.content()) < Mathf.round(b.amount * state.rules.unitCost(team)) - …
```

- The assembler accepts a payload (unit **or block**) that the current plan still needs, from **any side** — there is no direction test, unlike the reconstructor. One payload at a time is taken into the block, then counted into its internal store (`yeetPayload`, `:692-694`).
- Sources: a fabricator/refabricator facing into it; a `reinforced-payload-conveyor` (3x3, `Blocks.java:6636-6643`) leading into it; a `constructor` facing into it; or any attached module (below). A payload conveyor's target must be the same size and exactly aligned, or larger and within `(targetSize - 3)/2` tiles of its axis — 1 tile for a 5x5 target (`payloads/PayloadConveyor.java:105-120`).
- **The wall blocks are payloads, not items.** They are made by a `constructor` (`Blocks.java:6702-6710`: size 3, cost silicon 50, beryllium 75, tungsten 40, power 2.5/tick), configured with a `Block` (`payloads/Constructor.java:32-37`); its `filter` already lists `tungstenWallLarge, berylliumWallLarge, carbideWallLarge, reinforcedSurgeWallLarge, reinforcedLiquidContainer, reinforcedContainer, beamNode`. It takes that block's build cost as items from any side (up to 2x, `payloads/BlockProducer.java:91-103`), builds at `buildSpeed = 0.6` against the block's `buildTime` (`BlockProducer.java:122-124`), and pushes the payload out of its front.
- `payload-mass-driver` (`Blocks.java:6654-6664`) can move payloads between distant points; I did **not** analyse `PayloadMassDriver.java` (linking, acceptance), so treat it as undetermined.
- When everything is present, power and cyanogen are consumed and the unit appears at the area centre (`:547-566`).
- No vanilla plan uses `itemReq`, so assemblers accept no loose items (`:740-743`).

**Modules and tier.** A module is valid only where an assembler's `moduleFits` holds:

```java
// UnitAssembler.java:342-355
dx = ox + Geometry.d4x(rotation) * (other.size/2f + 0.5f) * tilesize,
dy = oy + Geometry.d4y(rotation) * (other.size/2f + 0.5f) * tilesize;
Vec2 spawn = getUnitSpawn();
if(Tile.relativeTo(ox, oy, spawn.x, spawn.y) != rotation){ return false; }
float dst = Math.max(Math.abs(dx - spawn.x), Math.abs(dy - spawn.y));
return Mathf.equal(dst, tilesize * areaSize / 2f - tilesize/2f);
```

- The module must **face the area centre**, and the point half a tile in front of its front edge must lie on the outermost ring of the 13x13 area (Chebyshev distance 6 tiles from the centre). In practice: the module sits **just outside the area, front edge flush against the area boundary, facing inward**; it may slide along that side as long as its front-centre stays within the 13-tile span. `UnitAssemblerModule.canPlaceOn` requires such an assembler to exist (`units/UnitAssemblerModule.java:49-51`), so **the assembler must be placed first**.
- **Tier**: `currentTier` is the highest module tier reachable without a gap (`UnitAssembler.java:367-381`) and `plan()` is `plans.get(Math.min(currentTier, plans.size - 1))` (`:387-390`). `basic-assembler-module` has `tier = 1` (class default, `UnitAssemblerModule.java:22`). So: **no module → first plan; one or more basic modules → second plan only.** There is no config to select a plan; removing the module is the only way back. Extra modules do not raise the tier further.
- A module is also a payload input port: it accepts what the assembler needs and forwards it when powered (`UnitAssemblerModule.java:110-112`, `:138-141`).
- **UNSURE**: `checkTier` does not look at module power, so an unpowered module appears to still raise the tier; verify.

## G.4 `unit-repair-tower`

`Blocks.java:6612-6621`: size 2, cost graphite 90, silicon 90, tungsten 80; `range = 100f` world units (12.5 tiles); `healAmount = 1.5f` per tick (90 HP/s) to **every** damaged allied unit in range (`units/RepairTower.java:76-80`); power 1/tick (60/s) and ozone 3/60 per tick (3/s), consumed only while a damaged unit is in range (`RepairTower.java:89-91`).

## G.5 How an AI team's units are commanded (RtsAI)

**Switch.** `RtsAI.update()` runs for a team when its team rule `rtsAi` is true:

```java
// core/Logic.java:568-570
if(rules.rtsAi){
    if(data.rtsAi == null) data.rtsAi = new RtsAI(data);
    data.rtsAi.update();
}
```

`rtsAi` is `Rules.TeamRule.rtsAi` (`game/Rules.java:373`), with `rtsMinSquad = 4`, `rtsMaxSquad = 50`, `rtsMinWeight = 1.2f` (`Rules.java:375-379`).

**Controller choice.** `controller = u -> !playerControllable || (u.team.isAI() && !u.team.rules().rtsAi) ? aiController.get() : new CommandAI();` (`type/UnitType.java:283`). On an AI team **without** `rtsAi`, units get their type's plain AI (ground/flying attack AI). **With** `rtsAi` they get `CommandAI` and wait for orders from `RtsAI`.

**Where the flag comes from on Erekir — could not determine.** `CampaignRules.apply` only writes `rtsAi` inside `if(planet.showRtsAIRule){` (`game/CampaignRules.java:24-28`), and only Serpulo sets `showRtsAIRule = true` (`content/Planets.java:148`). Erekir sets `campaignRuleDefaults.rtsAI = true` (`Planets.java:86`) but that value is never applied for Erekir by this code. The Erekir sector presets set no rules (`content/SectorPresets.java:166-239`). So on Erekir the flag must come from each map's saved rules, which are binary map assets not present in this checkout. **Test**: on the target sector read `Vars.state.rules.teams.get(Team.malis).rtsAi` at runtime — or set it yourself for the team your mod drives.

**What RtsAI does** (`ai/RtsAI.java`), every 120 ticks (`:82`):

1. Collects idle units — `CommandAI` with no command and not attacking — and flood-fills them into squads by proximity (`:112-131`, radius `squadRadius = 60f` + 1.5 x hit size).
2. **Defence first.** Buildings of the team damaged since the last pass are recorded (`:47-54`). A squad is sent to the nearest damaged building (a core always wins) if it is a core, or the squad has at least `rtsMinSquad` units, or was already mobilised, or the building is within 1000 world units (`:181-202`). It then targets the nearest enemy unit within 350 units of that building, else the nearest enemy turret that can reach it (`:210-244`).
3. **Otherwise attack.** Needs at least `rtsMinSquad` units (`:281`). Candidate targets are enemy buildings flagged `generator, factory, core, battery, drill` (`:36`, `:292-294`), minus ones squad members found unreachable; 15 random candidates are scored by `estimateStats` = (time for defenders along the path to kill the squad) / (time for the squad to kill those defenders) (`:329-363`). The best is attacked only if the score is at least `rtsMinWeight`, or the squad has `rtsMaxSquad` units, or the team is at unit cap (`:320-322`).
4. Orders are issued as `unit.command().commandTarget(...)` / `commandPosition(...)` (`:262-266`).
5. If team rule `aiCoreSpawn` is set it also spawns the core's unit type at cores every 7 s (`:90-100`).

Consequences for bases your mod builds: generators, crafters (`BlockFlag.factory`, `GenericCrafter.java:61`), drills and cores are the things an RTS-AI enemy targets; heat producers clear that flag (`heat/HeatProducer.java:26`). Units your bases produce will sit idle until at least 4 are close together, unless something is attacking their team's buildings.

---

# H. Turrets and defence

## H.1 Read this first: listed bullet damage is not the in-game damage

At content load `ErekirTechTree.load()` calls `rebalance()` (`content/ErekirTechTree.java:50-51`, invoked from `core/ContentLoader.java:73`):

```java
// ErekirTechTree.java:19-23, 33-45
if(balanced.add(bullet.id)){
    bullet.damage *= 0.75f;
}
…
if(block instanceof Turret turret && Structs.contains(block.requirements, i -> !Items.serpuloItems.contains(i.item))){
    if(turret instanceof ItemTurret item){ … rebalanceBullet(bullet) for each ammo type
    }else if(turret instanceof ContinuousLiquidTurret cont){ … each ammo type
    }else if(turret instanceof ContinuousTurret cont){ rebalanceBullet(cont.shootType); }
```

- The top-level bullet `damage` of every Erekir **`ItemTurret`**, **`ContinuousLiquidTurret`** and **`ContinuousTurret`** is multiplied by **0.75** before play. `splashDamage`, frag bullets, lightning and missile explosions are untouched.
- **`PowerTurret`s (`afflict`, `malign`) are not covered** and keep their listed damage.
- The same x0.75 is applied to the weapons of every `ErekirUnitType` (`ErekirTechTree.java:27-31`).

The table gives `listed → effective`.

## H.2 Shared turret rules

- **Item ammo: any side, any source.** `return ammoTypes.get(item) != null && totalAmmo + ammoTypes.get(item).ammoMultiplier <= maxAmmo;` (`defense/turrets/ItemTurret.java:187-189`). Each item adds `ammoMultiplier` ammo units (`:168`); each shot uses `ammoPerShot` units (`entry.amount -= ammoPerShot;`, `defense/turrets/Turret.java:688`). Defaults: `maxAmmo = 30`, `ammoPerShot = 1` (`Turret.java:47`, `:49`); bullet `ammoMultiplier` default 2 (`entities/bullet/BulletType.java:99`). A turret adjacent to a crafter that makes its ammo is fed directly by the crafter's `dump`.
- Ammo cannot be taken back out (`ItemTurret.java:150-152`).
- **Range** is in world units, 8 per tile (`Vars.java:135`); effective range is `range + ammo.rangeChange` (`Turret.java:350`).
- **Targeting defaults**: `targetAir = true`, `targetGround = true` (`Turret.java:95`, `:97`).
- **Coolant is optional** on every Erekir turret that has one: `coolant.update = false; coolant.booster = true; coolant.optional = true;` (`defense/turrets/BaseTurret.java:72-74`). It adds reload progress `amount * edelta() * capacity * coolantMultiplier` per tick (`defense/turrets/ReloadTurret.java:33`) on top of the base 1 per tick; with water (`heatCapacity = 0.4`, `content/Liquids.java:14`) the fire-rate factor is `1 + amount x 0.4 x coolantMultiplier` (derived): breach/diffuse/scathe/smite 2.5x, titan 1.75x, disperse 1.83x.
- **Heat** for turrets: `efficiency *= Math.min(Math.max(heatReq / heatRequirement, cheating() ? 1f : 0f), maxHeatEfficiency);` (`Turret.java:678-679`) and with zero heat the turret cannot consume at all (`Turret.java:627-628`). Collection follows E.1. Both heat turrets set `maxHeatEfficiency = 1f`.
- **Power**: an under-powered power turret fires proportionally slower (efficiency = power status).
- Placement range check against enemies (`placeOverlapRange`) is only active when `rules.placeRangeCheck` is true; Erekir sets it false (`content/Planets.java:76`).

## H.3 Turret table

| Turret | Class | Size | Build cost | Range (units / tiles) | Targets | `reload` (ticks) | `ammoPerShot` / `maxAmmo` | Power | Heat | Required liquid | Optional coolant | Lines |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `breach` | `ItemTurret` | 3 | beryllium 150, silicon 150, graphite 125 | 190 / 23.75 | air + ground | 40 | 2 / 30 | - | - | - | water 15/60 per tick, `coolantMultiplier = 15` | `Blocks.java:4378-4488` |
| `diffuse` | `ItemTurret` | 3 | beryllium 150, silicon 200, graphite 200, tungsten 50 | 125 / 15.6 | air + ground | 30 (15 pellets per shot, `ShootSpread(15, 4f)`) | 3 / 30 | - | - | - | water 15/60, x15 | `4490-4578` |
| `sublimate` | `ContinuousLiquidTurret` | 3 | tungsten 150, silicon 200, oxide 40, beryllium 400 | 130 / 16.25 | air + ground | continuous | liquid, `liquidCapacity = 50` | - | - | its ammo liquid | - | `4580-4664` |
| `titan` | `ItemTurret` | 4 | tungsten 250, silicon 300, thorium 400 | 390 / 48.75 | **ground only** (`targetAir = false`) | `60f * 2.3f` = 138 | 4 / 12 | - | - | **hydrogen 5/60 per tick (5/s)** | water 30/60, `coolantMultiplier = 3.75` | `4666-4871` |
| `disperse` | `ItemTurret` | 4 | thorium 50, oxide 50, silicon 200, beryllium 350 | 310 / 38.75 | **air only** (`targetGround = false`) | 9 (4 shots, `ShootAlternate`) | 1 / 30 | - | - | - | water 20/60, `coolantMultiplier = 6.25` | `4873-5055` |
| `afflict` | `PowerTurret` | 4 | surge-alloy 100, silicon 200, graphite 250, oxide 40 | 368 / 46 | air + ground | 50 | - | 5/tick (300/s) | **20**, `maxHeatEfficiency = 1` | - | none | `5057-5178` |
| `lustre` | `ContinuousTurret` | 4 | silicon 250, graphite 200, oxide 50, carbide 90 | 250 / 31.25 | air + ground | continuous | - | 200/60 per tick (200/s) | - | **nitrogen 6/60 per tick (6/s)** | none | `5180-5243` |
| `scathe` | `ItemTurret` | 4 | silicon 450, graphite 400, tungsten 500, oxide 100, carbide 200 | 1350 / 168.75 | **ground only** | 600 | 15 / 45 | - | - | - | water 15/60, x15 | `5245-5687` |
| `smite` | `ItemTurret` | 5 | oxide 200, surge-alloy 400, silicon 800, carbide 500, phase-fabric 300 | 300 / 37.5 | air + ground | 100 (5 barrels) | 2 / 30 | - | - | - | water 15/60, x15 | `5689-5957` |
| `malign` | `PowerTurret` | 5 | carbide 200, beryllium 1000, silicon 500, graphite 500, phase-fabric 200 | 410 / 51.25 | air + ground | 3.5 | - | 40/tick (2400/s) | **144**, `maxHeatEfficiency = 1` | - | none | `5959-6298` |

## H.4 Ammo

| Turret | Ammo | Bullet damage listed → effective | `ammoMultiplier` (ammo units per item) | Other | Line |
|---|---|---|---|---|---|
| `breach` | beryllium | 85 → 63.75 | 1 (2 items per shot) | `pierceCap = 2` | `4384` |
| | tungsten | 95 → 71.25 | 2 (1 item per shot) | `rangeChange = 40f`, `pierceCap = 4` | `4401` |
| | carbide | `325f/0.75f` = 433.3 → 325 | 2 | `reloadMultiplier = 0.2f`, `rangeChange = 7f*8f` = 56, 3 frags of 227 | `4420` |
| `diffuse` | graphite | 41 → 30.75 per pellet | 1 (3 items per shot) | knockback 4 | `4494` |
| | oxide | 90 → 67.5 | 2 | | `4509` |
| | silicon | 35 → 26.25 | 1 | homing | `4524` |
| `sublimate` | ozone | 60 → 45 per damage tick | 1.2 → uses `18/60 / 1.2` = 15/60 per tick (15/s) | `pierceCap = 2` | `4633` |
| | cyanogen | 130 → 97.5 | default 2 → 9/60 per tick (9/s) | `rangeChange = 70f`, `pierceCap = 3` | `4644` |
| `titan` | thorium | 350 → 262.5, plus splash 350 (radius 65) | 1 (4 items per shot, holds 3 shots) | | `4670` |
| | carbide | 700 → 525, plus splash 750 (radius 36) | 1 | `rangeChange = 10f*8f` = 80, `reloadMultiplier = 0.8f`, 12 frags (splash 50) | `4704` |
| | oxide | 300 → 225, plus splash 180 (radius 110) | 1 | `rangeChange = 8f`, `reloadMultiplier = 0.7f`, corroded 8 s | `4766` |
| `disperse` | tungsten | 65 → 48.75 | 3 | air only (`collidesGround = false`) | `4877` |
| | thorium | 90 → 67.5 | 1 | `reloadMultiplier = 0.85f`, `rangeChange = -120f`, `pierceCap = 2` | `4901` |
| | silicon | 37 → 27.75 | 4 | homing | `4929` |
| | surge-alloy | 65 → 48.75, plus 3 lightning x 18 | 3 | `reloadMultiplier = 0.75f`, `rangeChange = 8f * 3f` = 24 | `4955` |
| `afflict` | power | 180 (not rebalanced), 20 frags of 35 | - | `pierceCap = 2` | `5061-5126` |
| `lustre` | power + nitrogen | 210 → 157.5 per damage tick | - | `PointLaserBulletType`, sorts by strongest | `5183-5184` |
| `scathe` | carbide | missile, explosion 1000 (radius 65) | 1 (15 items per shot) | ground only | `5251`, `5288` |
| | phase-fabric | missile, explosion 320 (radius 120) + frags | 5 (3 items per shot) | `reloadMultiplier = 0.8f` | `5338`, `5387` |
| | surge-alloy | missile, explosion 1800 (radius 40) + split missiles of 180 | 1 (15 items per shot) | `reloadMultiplier = 0.9f` | `5442`, `5482` |
| `smite` | surge-alloy | 250 → 187.5 x 5 barrels, plus lightning | 1 (2 items per shot) | `pierceCap = 4` | `5693` |
| `malign` | power + heat | 70 (not rebalanced) + lightning 18 + frag laser 65 | - | `FlakBulletType` | `5974-6033` |

Continuous bullets apply their damage every `damageInterval` = 5 ticks (`entities/bullet/ContinuousBulletType.java:11`, `entities/bullet/PointLaserBulletType.java:26`).

`sublimate` accepts only its two ammo liquids, one at a time (`defense/turrets/ContinuousLiquidTurret.java:138-142`), from any side — but a reinforced conduit must point into it (D.2). It starts firing only once it holds 4 ticks' worth (`ContinuousLiquidTurret.java:87-91`).

I did not read every bullet's secondary fields (frag/interval bullet trees for `afflict`, `smite`, `malign`, `scathe` are long). The numbers above are the top-level values; use the cited line ranges if you need the full damage model.

## H.5 Walls

`wallHealthMultiplier = 4` (`Blocks.java:1706`).

| Block | Size | Health | Armor | Cost | Notes | Lines |
|---|---|---|---|---|---|---|
| `beryllium-wall` | 1 | 520 | 2 | beryllium 6 | | `1836-1841` |
| `beryllium-wall-large` | 2 | 2080 | 2 | beryllium 24 | | `1843-1849` |
| `tungsten-wall` | 1 | 720 | 14 | tungsten 6 | | `1851-1856` |
| `tungsten-wall-large` | 2 | 2880 | 14 | tungsten 24 | | `1858-1864` |
| `blast-door` | 2 | 2800 | 14 | tungsten 24, silicon 24 | `AutoDoor`: opens for own team's ground units | `1866-1871` |
| `reinforced-surge-wall` | 1 | 1000 | 20 | surge-alloy 6, tungsten 2 | `lightningChance = 0.05f`, `lightningDamage = 30f` | `1873-1880` |
| `reinforced-surge-wall-large` | 2 | 4000 | 20 | surge-alloy 24, tungsten 8 | same | `1882-1890` |
| `carbide-wall` | 1 | 1080 | 16 | thorium 6, carbide 6 | | `1892-1896` |
| `carbide-wall-large` | 2 | 4320 | 16 | thorium 24, carbide 24 | | `1898-1903` |
| `shielded-wall` | 2 | 4160 | 15 | phase-fabric 20, surge-alloy 12, beryllium 12 | `ShieldWall`: power 3/60 per tick (3/s); shield 900 HP, regen 2/tick, 10 s break cooldown (`defense/ShieldWall.java:15-17`); `conductivePower = true` | `1905-1919` |

There is no large shielded wall.

## H.6 Support blocks

| Block | Size | Cost | Needs | Effect and range | Lines |
|---|---|---|---|---|---|
| `regen-projector` | 3 | silicon 80, tungsten 60, oxide 40, beryllium 80 | power 1/tick (60/s), hydrogen 1/60 per tick (1/s); optional phase-fabric boost | Heals own-team blocks inside a **square** of side `range = 28` tiles centred on it (`indexer.eachBlock(team, Tmp.r1.setCentered(x, y, range * tilesize), …)`, `defense/RegenProjector.java:118`) by `healPercent = 4f / 60f` % of max health per tick (4%/s); x2 with phase-fabric (1 per 480 ticks, `RegenProjector.java:29-30`) | `Blocks.java:2011-2037` |
| `build-tower` | 3 | silicon 150, oxide 40, thorium 60 | power 3/tick (180/s), nitrogen 3/60 per tick (3/s) | `BuildTurret`: within `range = 200f` world units (25 tiles) rebuilds blocks from the team's rebuild queue and helps units' build plans (`defense/BuildTurret.java:157-190`); `buildSpeed = 1.5f` | `1999-2009` |
| `shockwave-tower` | 3 | surge-alloy 50, silicon 150, oxide 30, tungsten 100 | power 100/60 per tick (100/s), cyanogen 1.5/60 per tick (1.5/s) | Every `reload = 80f` ticks damages enemy hittable bullets within `range = 170f` world units (21.25 tiles) by up to `bulletDamage = 160` (`defense/ShockwaveTower.java:27`, `:75-87`) | `2040-2047` |
| `radar` | 1 | silicon 60, graphite 50, beryllium 10 | power 0.6/tick (36/s) | Reveals fog out to `fogRadius = 34` tiles, growing over `discoveryTime` = 600 ticks (`defense/Radar.java:18`); `BuildVisibility.fogOnly` | `1990-1997` |
| `unit-repair-tower` | 2 | see G.4 | | | |

**Shield blocks.** There is no `barrier-projector` in `Blocks.java`. The two `BaseShield` blocks, `shield-projector` (size 3) and `large-shield-projector` (size 4, `radius = 400f`), are declared `requirements(Category.effect, BuildVisibility.editorOnly, with());` (`Blocks.java:2050-2065`): editor-only, no cost, not in the tech tree. The only shield a player can build on Erekir is `shielded-wall`.

---

# I. Cores

## I.1 Values

| Core | Size | Build cost | `itemCapacity` (per item type) | Health | Armor | Unit | `unitCapModifier` | Lines |
|---|---|---|---|---|---|---|---|---|
| `core-bastion` | 4 | graphite 1000, silicon 1000, beryllium 800 | 2000 | 4500 | 5 | `evoke` | 15 | `Blocks.java:3185-3205` |
| `core-citadel` | 5 | silicon 4000, beryllium 4000, tungsten 3000, oxide 1000 | 3000 | 16000 | 10 | `incite` | 15 | `3207-3223` |
| `core-acropolis` | 6 | beryllium 6000, silicon 5000, tungsten 5000, carbide 3000, oxide 3000 | 4000 | 30000 | 15 | `emanate` | 15 | `3225-3241` |

All three: `incinerateNonBuildable = true`, `requiresCoreZone = true`, `buildCostMultiplier = 0.7f`. Bastion also has `isFirstTier = true`, `alwaysUnlocked = true`.

- **Storage is shared and additive across a team's cores.** Every core points at one item module (`CoreBlock.java:760-764`), and `storageCapacity = itemCapacity + …` plus `other.block.itemCapacity` for every other core (`CoreBlock.java:767`, `:773-776`). Two bastions hold 4000 of each item.
- Capacity is per item type. Overflow is burned (A.10). Sand is burned on entry (A.10). Adjacent Erekir containers do not add capacity (A.11).
- The core is not `unloadable` by Erekir blocks (A.7).

## I.2 Upgrading by placing a bigger core over a smaller one

```java
// CoreBlock.java:180-183
public boolean canReplace(Block other){
    //coreblocks can upgrade smaller cores
    return super.canReplace(other) || (other instanceof CoreBlock && size >= other.size && other != this);
}
// CoreBlock.java:186-203
public boolean canPlaceOn(Tile tile, Team team, int rotation){
    if(tile == null) return false;
    //in the editor or with gamerule, you can place them anywhere for convenience
    if(state.isEditor() || state.rules.coreBuildAndConfig) return true;
    CoreBuild core = team.core();
    //special floor upon which cores can be placed
    tile.getLinkedTilesAs(this, tempTiles);
    if(!tempTiles.contains(o -> !o.floor().allowCorePlacement || o.block() instanceof CoreBlock)){
        return true;
    }
    //must have all requirements
    if(core == null || (!state.rules.infiniteResources && !core.items.has(requirements, state.rules.buildCostMultiplier))) return false;
    return tile.block() instanceof CoreBlock && size > tile.block().size && (!requiresCoreZone || tempTiles.allMatch(o -> o.floor().allowCorePlacement));
}
```

Three cases, outside the editor and without `coreBuildAndConfig`:

1. **New core.** Every tile under the footprint is `core-zone` floor (`allowCorePlacement = true`, `Blocks.java:587-590`) and none holds a core → allowed. It is then built like any block (construction time, cost drawn from the existing core).
2. **Upgrade.** The placement's origin tile is part of an existing core of **strictly smaller** size, the team's core holds the **full cost** (x `buildCostMultiplier`), and — because all Erekir cores have `requiresCoreZone = true` — **every tile under the larger footprint is core-zone floor**. Skipping a tier (bastion → acropolis) passes these tests.
3. Anything else → refused.

Additional geometric condition from `Build.validPlace`: the new block's bounds must contain the replaced block's bounds (`Build.java:262`). With Mindustry's origin convention (footprint spans `-(size-1)/2 … size/2` around the origin), a citadel's origin must be the bastion's origin or +1 in each axis; an acropolis's origin must be the citadel's origin or -1 in each axis (derived; check with `Build.validPlace` before committing a layout).

**The upgrade is instant and paid in full from the core**: `placeBegan` sets the block immediately when the previous block was a core (`CoreBlock.java:206-225`), and `beforePlaceBegan` deducts `requirements x buildCostMultiplier` from the stored items (`CoreBlock.java:229-238`).

A core can never be deconstructed if it is the team's last (`CoreBlock.java:174-177`).

## I.3 Where cores may go / no-build zones

- **Core zones only** on Erekir (case 1 above). The check is on the floor under all tiles of the footprint, so a 4x4 bastion needs a 4x4 patch of `core-zone`; an eventual acropolis there needs 6x6.
- **Enemy core radius.** Unless `polygonCoreProtection` is set, nothing can be placed where another team's protected core is within `enemyCoreBuildRadius + extraCoreBuildRadius + 8` world units: `if(tile.within(x, y, state.rules.buildRadius(tile.team) + tilesize)){ return true; }` (`game/Teams.java:60-71`, `game/Rules.java:294-296`). Default `enemyCoreBuildRadius = 400f` (`Rules.java:125`) = 50 tiles. This applies to **every** team's placements, including an AI team building near the player's core.
- **`polygonCoreProtection`** (default false, `Rules.java:127`): if true, a tile is buildable only by the team owning the **nearest** protected core (`Build.java:191-209`).
- Erekir's `ruleSetter` (`content/Planets.java:74-83`) sets neither value. **Could not determine** whether individual Erekir maps override `polygonCoreProtection`, `enemyCoreBuildRadius` or per-team `protectCores` in their saved rules (map assets are not in this checkout). Read `Vars.state.rules` at runtime.
- **`coreDestroyClear = true`** on Erekir (`Planets.java:81`): when an AI team's core is destroyed, that team's buildings within `enemyCoreBuildRadius` of it are destroyed too (`core/Logic.java:174-178`). A base your mod builds around an enemy core dies with that core.

---

# J. Tech tree (`content/ErekirTechTree.java`)

## J.1 How a node becomes researchable

1. **Parent researched.** A node is only visible once its parent node is unlocked (`ui/dialogs/ResearchDialog.java:391-397`: `l.visible = !locked && l.parent.visible;`).
2. **All objectives complete**: `return node.content.unlockedHost() || !node.objectives.contains(i -> !i.complete());` (`ResearchDialog.java:403-406`). Objectives are the explicit ones in the tree **plus implicit ones** added by the node constructor:

```java
// content/TechTree.java:129-134
//add dependencies as objectives.
content.getDependencies(d -> {
    if(used.add(d)){
        objectives.add(new Research(d));
    }
});
```

   - For a block, dependencies are every **build-cost item** and every **non-optional item it consumes** (`Block.java:1298-1312`); liquids are deliberately excluded (commented out, `Block.java:1313-1320`). "Research(item)" for an item means the item is unlocked, i.e. has been produced.
   - For a unit, dependencies are the **reconstructor that outputs it** and its research-cost items (`type/UnitType.java:766-782`).
   - A sector node whose parent is a sector automatically gets `SectorComplete(parent)` (`TechTree.java:45-48`).
3. **Research cost paid** from the pooled core items of every sector on the planet that has a base (`ResearchDialog.java:213-240`). Cost per build-cost item, unless the block sets `researchCost` explicitly:
   `Mathf.round(60 * researchCostMultiplier + Mathf.pow(requirements[i].amount, 1.11f) * 20 * researchCostMultiplier * researchCostMultipliers.get(item, 1f), 10)` (`Block.java:1284-1295`), then every amount is scaled by the Erekir tree's per-item multipliers — 0.9 for all items except oxide 0.5, surge-alloy 0.7, carbide 0.3, phase-fabric 0.2 (`ErekirTechTree.java:56-63`, applied at `TechTree.java:118-123`). Units cost their build items x `researchCostMultiplier` (10 for `ErekirUnitType`, `type/unit/ErekirUnitType.java:14`) (`UnitType.java:1405-1427`).

Objective meanings (`game/Objectives.java`):

| Objective | Complete when | Line |
|---|---|---|
| `Research(x)` | `x.unlockedHost()` | `:21-23` |
| `Produce(x)` | `x.unlockedHost()` (items/liquids unlock when first produced: `BuildingComp.java:1044-1049`, `:896`) | `:49-51` |
| `OnSector(s)` | `s.sector.hasBase()` — the player has a base there | `:100-102` |
| `SectorComplete(s)` | `s.sector.save != null && s.sector.isCaptured() && s.sector.hasBase()` | `:75-77` |
| `OnPlanet(p)` | any sector of `p` has a base | `:125-127` |

Root: `Planets.erekir.techTree = nodeRoot("erekir", coreBastion, true, …)` (`ErekirTechTree.java:65`). `core-bastion` is `alwaysUnlocked`.

## J.2 Blocks, in file order

"Explicit objectives" are exactly those written in the file. Every block additionally needs its parent researched and all its build-cost items (and consumed items) produced.

| # | Node | Parent | Explicit objectives | Line |
|---|---|---|---|---|
| 1 | `duct` | `core-bastion` | `OnPlanet(erekir)` | 68 |
| 2 | `duct-router` | `duct` | - | 69 |
| 3 | `duct-bridge` | `duct-router` | - | 70 |
| 4 | `armored-duct` | `duct-bridge` | - | 71 |
| 5 | `surge-conveyor` | `armored-duct` | - | 72 |
| 6 | `surge-router` | `surge-conveyor` | - | 73 |
| 7 | `unit-cargo-loader` | `duct-bridge` | - | 77 |
| 8 | `unit-cargo-unload-point` | `unit-cargo-loader` | - | 78 |
| 9 | `overflow-duct` | `duct-router` | `OnSector(aegis)` | 84 |
| 10 | `underflow-duct` | `overflow-duct` | - | 85 |
| 11 | `reinforced-container` | `overflow-duct` | - | 86 |
| 12 | `duct-unloader` | `reinforced-container` | - | 87 |
| 13 | `reinforced-vault` | `reinforced-container` | - | 91 |
| 14 | `reinforced-message` | `duct-router` | `OnSector(aegis)` | 97 |
| 15 | `canvas` | `reinforced-message` | - | 98 |
| 16 | `large-canvas` | `canvas` | - | 99 |
| 17 | `reinforced-payload-conveyor` | `duct` | `OnSector(atlas)` | 104 |
| 18 | `payload-mass-driver` | `reinforced-payload-conveyor` | `Research(silicon-arc-furnace)`, `OnSector(split)` | 106 |
| 19 | `payload-loader` | `payload-mass-driver` | - | 108 |
| 20 | `payload-unloader` | `payload-loader` | - | 109 |
| 21 | `large-payload-mass-driver` | `payload-unloader` | - | 110 |
| 22 | `constructor` | `payload-mass-driver` | `OnSector(split)` | 116 |
| 23 | `small-deconstructor` | `constructor` | `OnSector(peaks)` | 117 |
| 24 | `large-constructor` | `small-deconstructor` | `OnSector(siege)` | 118 |
| 25 | `deconstructor` | `small-deconstructor` | `OnSector(siege)` | 122 |
| 26 | `reinforced-payload-router` | `reinforced-payload-conveyor` | - | 129 |
| 27 | `plasma-bore` | `core-bastion` | - | 136 |
| 28 | `impact-drill` | `plasma-bore` | `OnSector(aegis)` | 137 |
| 29 | `large-plasma-bore` | `impact-drill` | `OnSector(caldera)` | 138 |
| 30 | `eruption-drill` | `large-plasma-bore` | `OnSector(stronghold)` | 139 |
| 31 | `large-cliff-crusher` | `large-plasma-bore` | `OnSector(stronghold)` | 143 |
| 32 | `turbine-condenser` | `core-bastion` | - | 150 |
| 33 | `beam-node` | `turbine-condenser` | - | 151 |
| 34 | `vent-condenser` | `beam-node` | `OnSector(aegis)` | 152 |
| 35 | `chemical-combustion-chamber` | `vent-condenser` | `OnSector(basin)` | 153 |
| 36 | `pyrolysis-generator` | `chemical-combustion-chamber` | `OnSector(crevice)` | 154 |
| 37 | `flux-reactor` | `pyrolysis-generator` | `OnSector(crossroads)`, `Research(cyanogen-synthesizer)` | 155 |
| 38 | `neoplasia-reactor` | `flux-reactor` | `OnSector(karst)` | 156 |
| 39 | `beam-tower` | `beam-node` | `OnSector(peaks)` | 164 |
| 40 | `beam-link` | `beam-tower` | `OnSector(crossroads)` | 165 |
| 41 | `regen-projector` | `beam-node` | `OnSector(peaks)` | 171 |
| 42 | `build-tower` | `regen-projector` | `OnSector(stronghold)` | 173 |
| 43 | `shockwave-tower` | `build-tower` | `OnSector(siege)` | 174 |
| 44 | `reinforced-conduit` | `turbine-condenser` | `OnSector(aegis)` | 181 |
| 45 | `reinforced-pump` | `reinforced-conduit` | `OnSector(basin)` | 183 |
| 46 | `reinforced-liquid-junction` | `reinforced-conduit` | - | 187 |
| 47 | `reinforced-bridge-conduit` | `reinforced-liquid-junction` | - | 188 |
| 48 | `reinforced-liquid-router` | `reinforced-liquid-junction` | - | 192 |
| 49 | `reinforced-liquid-container` | `reinforced-liquid-router` | - | 193 |
| 50 | `reinforced-liquid-tank` | `reinforced-liquid-container` | `SectorComplete(intersect)` | 194 |
| 51 | `cliff-crusher` | `turbine-condenser` | - | 202 |
| 52 | `silicon-arc-furnace` | `cliff-crusher` | - | 203 |
| 53 | `electrolyzer` | `silicon-arc-furnace` | `OnSector(atlas)` | 204 |
| 54 | `oxidation-chamber` | `electrolyzer` | `Research(tank-refabricator)`, `OnSector(marsh)` | 205 |
| 55 | `surge-crucible` | `oxidation-chamber` | `OnSector(ravine)` | 207 |
| 56 | `heat-redirector` | `oxidation-chamber` | `OnSector(ravine)` | 210 |
| 57 | `electric-heater` | `heat-redirector` | `OnSector(ravine)`, `Research(afflict)` | 211 |
| 58 | `slag-heater` | `electric-heater` | `OnSector(caldera)` | 212 |
| 59 | `atmospheric-concentrator` | `electric-heater` | `OnSector(caldera)` | 216 |
| 60 | `cyanogen-synthesizer` | `atmospheric-concentrator` | `OnSector(siege)` | 217 |
| 61 | `carbide-crucible` | `electric-heater` | `OnSector(crevice)` | 222 |
| 62 | `phase-synthesizer` | `carbide-crucible` | `OnSector(karst)` | 223 |
| 63 | `phase-heater` | `phase-synthesizer` | `Research(phase-synthesizer)` | 224 |
| 64 | `heat-router` | `electric-heater` | - | 230 |
| 65 | `small-heat-redirector` | `heat-router` | - | 231 |
| 66 | `slag-incinerator` | `electrolyzer` | `OnSector(basin)` | 239 |
| 67 | `breach` | `core-bastion` | `Research(silicon-arc-furnace)`, `Research(tank-fabricator)` | 251 |
| 68 | `beryllium-wall` | `breach` | - | 252 |
| 69 | `beryllium-wall-large` | `beryllium-wall` | - | 253 |
| 70 | `tungsten-wall` | `beryllium-wall` | - | 257 |
| 71 | `tungsten-wall-large` | `tungsten-wall` | - | 258 |
| 72 | `blast-door` | `tungsten-wall-large` | - | 259 |
| 73 | `reinforced-surge-wall` | `tungsten-wall` | - | 264 |
| 74 | `reinforced-surge-wall-large` | `reinforced-surge-wall` | - | 265 |
| 75 | `shielded-wall` | `reinforced-surge-wall-large` | - | 266 |
| 76 | `carbide-wall` | `tungsten-wall` | - | 272 |
| 77 | `carbide-wall-large` | `carbide-wall` | - | 273 |
| 78 | `diffuse` | `breach` | `OnSector(lake)` | 280 |
| 79 | `sublimate` | `diffuse` | `OnSector(marsh)` | 281 |
| 80 | `afflict` | `sublimate` | `OnSector(ravine)` | 282 |
| 81 | `titan` | `afflict` | `OnSector(stronghold)` | 283 |
| 82 | `lustre` | `titan` | `OnSector(crevice)` | 284 |
| 83 | `smite` | `lustre` | `OnSector(karst)` | 285 |
| 84 | `disperse` | `diffuse` | `OnSector(stronghold)` | 293 |
| 85 | `scathe` | `disperse` | `OnSector(siege)` | 294 |
| 86 | `malign` | `scathe` | `SectorComplete(karst)` | 295 |
| 87 | `radar` | `breach` | `Research(beam-node)`, `Research(turbine-condenser)`, `Research(tank-fabricator)`, `OnSector(aegis)` | 303 |
| 88 | `core-citadel` | `core-bastion` | `SectorComplete(peaks)` | 308 |
| 89 | `core-acropolis` | `core-citadel` | `SectorComplete(siege)` | 309 |
| 90 | `tank-fabricator` | `core-bastion` | `Research(silicon-arc-furnace)`, `Research(plasma-bore)`, `Research(turbine-condenser)` | 314 |
| 91 | `unit-repair-tower` | `tank-fabricator` | `OnSector(ravine)`, `Research(mech-refabricator)` | 317 |
| 92 | `ship-fabricator` | `tank-fabricator` | `OnSector(lake)` | 321 |
| 93 | `mech-fabricator` | `ship-fabricator` | `OnSector(intersect)` | 324 |
| 94 | `tank-refabricator` | `mech-fabricator` | `OnSector(atlas)` | 327 |
| 95 | `mech-refabricator` | `tank-refabricator` | `OnSector(basin)` | 330 |
| 96 | `ship-refabricator` | `mech-refabricator` | `OnSector(peaks)` | 333 |
| 97 | `prime-refabricator` | `ship-refabricator` | `OnSector(stronghold)` | 336 |
| 98 | `tank-assembler` | `ship-refabricator` | `OnSector(siege)`, `Research(constructor)`, `Research(atmospheric-concentrator)` | 342 |
| 99 | `ship-assembler` | `tank-assembler` | `OnSector(crossroads)` | 350 |
| 100 | `mech-assembler` | `tank-assembler` | `OnSector(crossroads)` | 358 |
| 101 | `basic-assembler-module` | `tank-assembler` | `SectorComplete(karst)` | 366 |

Not in the tree at all (commented out or absent): `slag-centrifuge`, `heat-reactor` (`ErekirTechTree.java:241-243`), `shield-projector`, `large-shield-projector`, any battery, diode, mender or overdrive block.

Odd dependency worth noting: `electric-heater` requires `Research(afflict)` (line 211), and `afflict` needs heat — the first heat source a player can have is the `oxidation-chamber` (5 heat; `afflict` needs 20).

## J.3 Units

| Unit | Parent node | Explicit objectives | Implicit (producer) | Line |
|---|---|---|---|---|
| `stell` | `tank-fabricator` | - | - | 315 |
| `elude` | `ship-fabricator` | - | - | 322 |
| `merui` | `mech-fabricator` | - | - | 325 |
| `locus` | `tank-refabricator` | - | `tank-refabricator` | 328 |
| `cleroi` | `mech-refabricator` | - | `mech-refabricator` | 331 |
| `avert` | `ship-refabricator` | - | `ship-refabricator` | 334 |
| `precept`, `anthicus`, `obviate` | `prime-refabricator` | - | `prime-refabricator` | 337-339 |
| `vanquish` | `tank-assembler` | - | - | 344 |
| `conquer` | `vanquish` | `OnSector(karst)` | - | 345 |
| `quell` | `ship-assembler` | - | - | 351 |
| `disrupt` | `quell` | `OnSector(karst)` | - | 352 |
| `tecta` | `mech-assembler` | - | - | 359 |
| `collaris` | `tecta` | `OnSector(karst)` | - | 360 |

A reconstructor refuses to upgrade into a unit the host has not researched, **except for AI teams** (G.2).

## J.4 Sectors

| Sector | Parent | Objectives | Line |
|---|---|---|---|
| `onset` | `core-bastion` | - (`alwaysUnlocked`, `content/SectorPresets.java:167`) | 377 |
| `aegis` | `onset` | `SectorComplete(onset)`, `Research(duct-router)`, `Research(duct-bridge)` | 378 |
| `lake` | `aegis` | `SectorComplete(aegis)` | 379 |
| `intersect` | `aegis` | `SectorComplete(aegis)`, `SectorComplete(lake)`, `Research(vent-condenser)`, `Research(ship-fabricator)` | 383 |
| `atlas` | `intersect` | `SectorComplete(intersect)`, `Research(mech-fabricator)` | 384 |
| `split` | `atlas` | `SectorComplete(atlas)`, `Research(reinforced-payload-conveyor)`, `Research(reinforced-container)` | 385 |
| `basin` | `atlas` | `SectorComplete(atlas)` | 389 |
| `marsh` | `basin` | `SectorComplete(basin)` | 390 |
| `ravine` | `marsh` | `SectorComplete(marsh)`, `Research(Liquids.slag)` | 391 |
| `caldera` | `ravine` | `SectorComplete(peaks)`, `SectorComplete(ravine)`, `Research(heat-redirector)` | 392 |
| `stronghold` | `caldera` | `SectorComplete(caldera)`, `Research(core-citadel)` | 393 |
| `crevice` | `stronghold` | `SectorComplete(stronghold)` | 394 |
| `siege` | `crevice` | `SectorComplete(crevice)` | 395 |
| `crossroads` | `siege` | `SectorComplete(siege)` | 396 |
| `karst` | `crossroads` | `SectorComplete(crossroads)`, `Research(core-acropolis)` | 397 |
| `origin` | `karst` | `SectorComplete(karst)`, `Research(core-acropolis)`, `Research(vanquish)`, `Research(disrupt)`, `Research(collaris)`, `Research(malign)`, `Research(basic-assembler-module)`, `Research(neoplasia-reactor)` | 398 |
| `peaks` | `marsh` | `SectorComplete(marsh)`, `SectorComplete(split)` | 409 |

## J.5 Items and liquids (`nodeProduce`: objective `Produce(self)`)

| Content | Parent | Line |
|---|---|---|
| beryllium | `core-bastion` | 419 |
| sand | beryllium | 420 |
| silicon | sand | 421 |
| oxide | silicon | 422 |
| water | beryllium | 428 |
| ozone | water | 429 |
| hydrogen | ozone | 430 |
| nitrogen | hydrogen | 431 |
| cyanogen | hydrogen | 435 |
| neoplasm | cyanogen | 436 |
| graphite | beryllium | 444 |
| tungsten | graphite | 445 |
| slag | tungsten | 446 |
| arkycite | tungsten | 450 |
| thorium | tungsten | 454 |
| carbide | thorium | 455 |
| surge-alloy | tungsten | 461 |
| phase-fabric | surge-alloy | 462 |

---

# K. Building rules

## K.1 `Build.validPlace` in order (`world/Build.java:183-273`)

`validPlace` = `validPlaceIgnoreUnits(…) && checkNoUnitOverlap(type, x, y)` (`Build.java:173-175`). A placement is rejected if any of the following holds. `x, y` is the block's origin tile; a block of size `s` covers offsets `-(s-1)/2 … s/2` on each axis (`Build.java:240-245`), so even-sized blocks extend one tile further to +x/+y.

| # | Rejection condition | Citation |
|---|---|---|
| 1 | Block not available here: `!type.environmentBuildable()`, or `!type.isPlaceable()` unless `state.rules.waves && team == state.rules.waveTeam && type.isVisible()` | `Build.java:185` |
| 2 | Enemy core protection (I.3): nearest protected core belongs to another team (`polygonCoreProtection`), else any other team's protected core within build radius | `Build.java:189-213` |
| 3 | Origin tile off the map | `Build.java:217` |
| 4 | `type.canPlaceOn(tile, team, rotation)` false (block-specific, K.3) | `Build.java:219-221` |
| 5 | Darkness at origin: `!type.ignoreBuildDarkness && world.getDarkness(x, y) >= 3` | `Build.java:229-231` |
| 6 | No shallow contact: `!type.requiresWater && !contactsShallows(tile.x, tile.y, type) && !type.placeableLiquid` | `Build.java:233-235` |
| 7 | Block count limit reached (`rules.blockLimits`), **non-AI teams only** | `Build.java:238`, `Block.java:1044-1050` |
| 8 | For each covered tile, any of 8a–8i | `Build.java:249-264` |
| 8a | tile off map | |
| 8b | `type.size == 2 && world.getDarkness(wx, wy) >= 3` | |
| 8c | `state.rules.staticFog && state.rules.fog && !fogControl.isDiscovered(team, wx, wy)` | |
| 8d | `check.floor().isDeep() && !type.floating && !type.requiresWater && !type.placeableLiquid` | |
| 8e | derelict building there and `!state.rules.derelictRepair` | |
| 8f | same block already there with the same rotation (`type.rotate`) | |
| 8g | `!check.interactable(team)` — tile's building belongs to another non-derelict team (`game/Teams.java:125-127`) | |
| 8h | `!check.floor().placeableOn && !type.ignoreBuildDarkness` | |
| 8i | existing block not replaceable (K.2), or `type.requiresWater` without water | |
| 9 | `state.rules.placeRangeCheck` and an enemy building within the block's overlap range | `Build.java:268-270` |
| 10 | A solid (or `solidifes`) block whose footprint overlaps a unit, tested with `Units.anyEntities(…)` — only grounded, non-legged units count (`entities/Units.java:29-35`); non-solid blocks such as ducts skip this test | `Build.java:178-180` |

`validPlace` does **not** check research/unlock state or item cost.

Definitions used above:

- `environmentBuildable()` = `isOnPlanet(state.getPlanet())` (`Block.java:1117-1119`) = `planet == null || planet == Planets.sun || shownPlanets.isEmpty() || shownPlanets.contains(planet)` (`ctype/UnlockableContent.java:132-134`). A planet's tech tree adds that planet to every node's content (`content/TechTree.java:155-157`, called from `type/Planet.java:456-458`); blocks in no tree are auto-assigned to each landable planet where all their cost items are available (`Block.java:1336-1344`). Result: **Serpulo-tree blocks (conveyor, battery, mender, unloader, …) are not placeable on Erekir sectors.**
- `isPlaceable()` = `isVisible() && (!isBanned() || state.rules.editor) && supportsEnv(state.rules.env)` (`Block.java:1035-1037`); `supportsEnv` is `(envEnabled & env) != 0 && (envDisabled & env) == 0 && (envRequired == 0 || (envRequired & env) == envRequired)` (`Block.java:1053-1055`).
- `isDeep()` = `drownTime > 0` (`environment/Floor.java:339-341`). Deep on Erekir: `arkycite-floor` (`drownTime = 200f`, `Blocks.java:494`), `molten-slag` (`drownTime = 230f`, `Blocks.java:319`), plus deep water/tar/cryofluid where present.
- `contactsShallows` (`Build.java:294-314`): at least one tile inside or bordering the footprint is not deep.
- `getDarkness` (`core/World.java:484-534`): with `rules.borderDarkness`, the outermost ring of map tiles is 4 (unbuildable), the next ring 2 (buildable); generated (non-preset) sectors also darken outside a polygon; static walls deep inside rock carry their own darkness.
- Blocked floors: `placeableOn = false` is set only on `space` (`Blocks.java:336-339`).

## K.2 Replacing an existing block

```java
// Block.java:795-800
public boolean canReplace(Block other){
    if(other.alwaysReplace) return true;
    if(other.privileged) return false;
    return other.replaceable && (other != this || (rotate && quickRotate)) && ((this.group != BlockGroup.none && other.group == this.group) || other == this) &&
        (size == other.size || (size >= other.size && ((subclass != null && subclass == other.subclass) || group.anyReplace)));
}
```

and the new block's bounds must contain the old block's whole bounds (`Build.java:262`).

| Thing on the tile | Result |
|---|---|
| air | `alwaysReplace` (`environment/AirBlock.java:12`) → free |
| boulders and other `Prop`s | `alwaysReplace` (`environment/Prop.java:17`) → removed automatically when construction begins (`Build.java:121-126`) |
| static walls, cliffs | `alwaysReplace = false`, group none (`environment/StaticWall.java:28`) → **blocked** |
| `TallBlock` (crystal clusters, crystal blocks/orbs), trees | not `alwaysReplace`, group none → **blocked** |
| a building of the same `BlockGroup`, same size | replaceable |
| a smaller building of the same group | replaceable only if the group has `anyReplace` or the Java class matches, and it lies wholly inside the new footprint |
| a building of another group, or group `none` | **blocked** — deconstruct first |
| the same block, different rotation | allowed if `rotate && quickRotate`: it is **rotated in place instantly, for free** (`Build.java:82-92`); `quickRotate` defaults to true (`Block.java:397`) but is false for turrets and assemblers (K.5), which are rejected |
| the same block, same rotation | rejected (8f) — treat as "already done" |

Groups (`world/meta/BlockGroup.java:4`): `none, walls(true), projectors(true), turrets(true), transportation(true), power, liquids(true), drills, units, logic(true), payloads(true), heat(true)` — `true` = `anyReplace`.

| Group | Erekir members (source of the assignment) |
|---|---|
| `transportation` (any) | duct, armored-duct, duct-router, overflow/underflow duct, duct-bridge, duct-unloader, surge-conveyor, surge-router, reinforced-container, reinforced-vault, **cores** (`Duct.java:39`, `DuctRouter.java:26`, `OverflowDuct.java:23`, `DirectionBridge.java:38`, `DirectionalUnloader.java:31`, `StackConveyor.java:50`, `StorageBlock.java:27`) |
| `liquids` (any) | conduit, junction, bridge conduit, router/container/tank, pump (`liquid/LiquidBlock.java:25`, `DirectionLiquidBridge.java:24`, `Pump.java:28`), plus `vent-condenser`, `turbine-condenser`, `electrolyzer` by explicit `group = BlockGroup.liquids` (`Blocks.java:3003`, `:2707`, `:1364`) |
| `power` (same size only) | beam-node, beam-tower, beam-link, all generators except turbine-condenser (`power/PowerBlock.java:13`) |
| `walls` (any) | all walls, blast-door, shielded-wall (`defense/Wall.java:41`) |
| `turrets` (any) | all turrets and build-tower (`BaseTurret.java:44`, `BuildTurret.java:43`) |
| `projectors` (any) | regen-projector (`RegenProjector.java:42`) |
| `drills` (same size only) | impact-drill, eruption-drill (`Drill.java:80`) |
| `units` (same size only) | fabricators, refabricators, assemblers, payload-mass-driver (`UnitBlock.java:13`, `UnitAssembler.java:69`, `PayloadMassDriver.java:66`) |
| `payloads` (any) | payload conveyor/router, constructor, assembler module (`PayloadBlock.java:28`, `PayloadConveyor.java:30`) |
| `heat` (any) | heat-redirector, small-heat-redirector, heat-router (`Blocks.java:1501`, `:1513`, `:1524`) |
| `none` | all crafters and heaters, plasma bores, cliff crushers, unit-cargo blocks, radar, shockwave-tower, unit-repair-tower — **never replaceable by another block type** |

Practical consequences: a duct plan over an existing router silently replaces the router; an electrolyzer plan over a patch of conduits replaces them; a core placed over ducts/containers that fit inside its footprint replaces them.

## K.3 Block-specific `canPlaceOn` (all overrides in the tree)

| Block(s) | Rule | Citation | Section |
|---|---|---|---|
| plasma bores | at least one lane hits a minable wall **for the given rotation** | `BeamDrill.java:198-214` | B.2 |
| impact/eruption drill | at least one covered tile has a minable floor ore | `Drill.java:126-137` | B.3 |
| cliff crushers | sum of `sand` attribute of the solid tiles on the front edge > 0 **for the given rotation** | `WallCrafter.java:137-139` | B.4 |
| vent-condenser | steam attribute sum >= 8.9999 (all 9 tiles vent) | `AttributeCrafter.java:71-74` | B.5 |
| turbine-condenser | steam attribute sum > 8.9999 | `power/ThermalGenerator.java:78-81` | C.5 |
| reinforced-pump | covered liquid tiles all the same liquid, at least one | `Pump.java:84-96` | D.6 |
| cores | core-zone / upgrade rules | `CoreBlock.java:186-203` | I.2 |
| unit-cargo-loader | `Units.canCreate(team, unitType)` (unit cap room) | `units/UnitCargoLoader.java:65-67` | A.9 |
| assemblers | build area must not overlap another assembler's area | `units/UnitAssembler.java:101-107` | G.3 |
| assembler module | must fit an existing assembler's perimeter | `units/UnitAssemblerModule.java:49-51` | G.3 |

Everything else uses `return true;` (`Block.java:613-615`).

## K.4 Liquid floors, `floating`, `placeableLiquid`, `requiresWater`

- No Erekir block sets `placeableLiquid` or `requiresWater` (no occurrence in `Blocks.java` or the Erekir block classes).
- `floating = true` (may stand on deep liquid): `reinforced-conduit` (`liquid/Conduit.java:50`), `reinforced-liquid-junction` (`LiquidJunction.java:13`), `reinforced-liquid-router/-container/-tank` (`LiquidRouter.java:15`), `reinforced-bridge-conduit` (`Blocks.java:2444`), `reinforced-pump` (`Pump.java:29`).
- **Everything else — ducts, bridges' end points, beam nodes, crafters, walls — cannot be placed on arkycite or slag floor.** Item lines must go around a pool or over it with duct bridges (range 4); liquid lines may cross directly.
- `floating` also matters for attributes: `sumAttribute` ignores deep tiles for non-floating blocks (`Block.java:582-588`).

## K.5 `rotate` and `quickRotate`

- `rotate` false by default (`Block.java:122`). Rotatable Erekir blocks: ducts and all duct logistics, surge-conveyor/router, conduits, bridge conduits, plasma bores, cliff crushers, electrolyzer, heat producers and conductors, neoplasia-reactor, fabricators/refabricators/assemblers/modules, constructors, payload conveyors, payload-mass-driver, and **all turrets** (`rotate = true; quickRotate = false;`, `defense/turrets/Turret.java:169-170`; for a turret the placed rotation is only its starting aim). Not rotatable: cores, containers, generic and heat crafters, beam nodes, turbine/vent condenser, pumps, liquid routers/tanks/junctions, impact/eruption drills, walls, reactors other than neoplasia.
- For non-rotatable blocks the `rotation` argument is ignored by placement except where a `canPlaceOn` uses it (bores, crushers, assembler, module).
- `quickRotate = false` on turrets (above) and assemblers (`units/UnitAssembler.java:71`): these cannot be re-rotated in place — placing the same block over itself with another rotation fails the `(other != this || (rotate && quickRotate))` test in `canReplace`. Every other rotatable block is re-rotated in place for free (K.2).

## K.6 Rules Erekir sets

`Planet.applyRules` (`type/Planet.java:295-306`) runs the planet's `ruleSetter`, then sets `rules.attributes = defaultAttributes`, `rules.env = defaultEnv`, `rules.planet`, and (campaign only) `campaignRules.apply`.

| Rule | Erekir value | Effect | Citation |
|---|---|---|---|
| `waveTeam` | `Team.malis` | the enemy team | `content/Planets.java:75` |
| `placeRangeCheck` | false | no "too close to enemy building" rejection | `:76` |
| `hideSpawns` | false | | `:77` |
| `fog`, `staticFog` | true, true | undiscovered tiles are unbuildable for non-AI teams (8c) | `:78-79` |
| `lighting` | false | | `:80` |
| `coreDestroyClear` | true | AI base around a dead AI core is wiped (I.3) | `:81` |
| `onlyDepositCore` | true | players (and logic-controlled units) can drop carried items only into cores (`BuildingComp.java:744-746`, used at `input/InputHandler.java:523` and `logic/LExecutor.java:526`); block-to-block transfer is unaffected | `:82` |
| `env` | `Env.scorching \| Env.terrestrial` | blocks must support it (`supportsEnv`) | `:56` |
| `attributes` | `heat = 0.8` | environmental heat attribute only; steam is unaffected | `:72` |
| `defaultCore` | `core-bastion` | | `:66` |
| campaign `fog`, `hideSpawns` | true, false | `rules.staticFog = rules.fog = fog;` | `:84-85`, `game/CampaignRules.java:19-20` |
| campaign `rtsAI` default | true, **but not applied** for Erekir (G.5) | | `:86` |

Applied by the engine on every campaign world load, for the wave team (`core/Logic.java:104-116`):

```java
state.rules.coreIncinerates = true;
state.rules.infiniteResources = false;
…
if(state.getPlanet().enemyInfiniteItems){
    state.rules.waveTeam.rules().infiniteResources = true;
    state.rules.waveTeam.rules().fillItems = true;
}
state.rules.waveTeam.rules().buildSpeedMultiplier *= state.getPlanet().enemyBuildSpeedMultiplier;
```

- `enemyInfiniteItems` defaults to true (`type/Planet.java:129`) and Erekir does not change it, so **in the Erekir campaign the enemy team builds for free and its core is refilled to capacity every tick** (`core/Logic.java:554-560`). If your mod wants the enemy to pay honestly, it must reset `infiniteResources` and `fillItems` on that team after each `WorldLoadEvent`.
- `enemyBuildSpeedMultiplier = 0.4f` for Erekir (`Planets.java:68`): the wave team's build speed is multiplied by 0.4. **UNSURE** whether this compounds across save/load cycles (it is a `*=` on a value that may be saved with the map); check the value after reloading a save.

## K.7 What differs for an AI team

`Team.isAI()` is `(state.rules.waves || state.rules.attackMode || state.isCampaign()) && this != state.rules.defaultTeam && !state.rules.pvp` (`game/Team.java:112-114`).

| Difference | Citation |
|---|---|
| Fog: every tile counts as discovered and visible | `game/FogControl.java:105`, `:118` |
| Block limits are not applied | `Block.java:1045` |
| Reconstructors upgrade without research | `units/Reconstructor.java:265` |
| Wave team in campaign has no unit cap | `entities/Units.java:119-124` |
| Wave team with `rules.waves`: banned/unsupported-env blocks are still placeable if visible (planet availability still applies) | `Build.java:185` |
| **Builder units** build a plan only if `state.isEditor() \|\| (state.rules.waves && team == state.rules.waveTeam && current.block.isVisible()) \|\| (current.block.unlockedNowHost() && current.block.environmentBuildable() && current.block.isPlaceable())` — outside wave mode an AI team's builder units can only construct blocks the **host player has researched** | `entities/comp/BuilderComp.java:161` |
| Campaign wave team: infinite build resources, filled core, build speed x0.4 (K.6) | `core/Logic.java:112-116` |
| Units use plain AI unless the team's `rtsAi` rule is on (G.5) | `type/UnitType.java:283` |
| Team rule `cheat` makes buildings ignore all consumers (`cheating()` → `team.rules().cheat`) | `entities/comp/TeamComp.java:17-19`, `BuildingComp.java:1952` |

Everything in A–I applies identically to every team: proximity, acceptance and power links are all same-team only.

---

# L. Open points (not determined from the source)

| # | Topic | Why undetermined | Suggested test |
|---|---|---|---|
| 1 | Whether `rtsAi` is on for the enemy team on each Erekir sector | set in map files, not in Java; maps not in this checkout | read `Vars.state.rules.teams.get(Team.malis).rtsAi` on the sector |
| 2 | Per-map `polygonCoreProtection`, `enemyCoreBuildRadius`, `protectCores`, banned blocks, `unitFactoryActivationDelay` | same | dump `Vars.state.rules` on load |
| 3 | Exact reinforced-conduit throughput vs length | gradient-driven, depends on update order; my 90/s figure is an estimate | measure delivered rate for lines of 5, 20, 50 tiles |
| 4 | Duct latency (3 vs 4 ticks/tile) | depends on building update order | time one item over 30 tiles |
| 5 | Surge-conveyor load/move/unload state for side-merging layouts | autotiler logic not fully traced | build a T-merge and inspect |
| 6 | `payload-mass-driver` linking and acceptance | `PayloadMassDriver.java` not read | read the class before using it |
| 7 | Whether buildings inside an assembler's 13x13 area are otherwise prevented | only the centre-occupancy check found | place a wall inside the area and watch `wasOccupied` |
| 8 | Unpowered assembler module still raising tier | `checkTier` ignores power | cut power to the module |
| 9 | A sink for neoplasm | no consumer found in Erekir content | check what vanilla players pipe it into |
| 10 | `slag-incinerator` efficiency with a zero-amount consumer | division by zero in the formula | feed items with and without slag present |
| 11 | Floor drills on a wall-ore overlay with no wall | `Drill.canMine` does not test `wallOre` | only matters if a map has such tiles |
| 12 | Coolant fire-rate factors in H.2 | derived from the reload formula, base reload code not re-read | compare shots/minute with and without water |
| 13 | Secondary bullet effects of `afflict`, `smite`, `malign`, `scathe` | only top-level values extracted | read the cited ranges |
| 14 | Units entering refabricators/assemblers by the `enterPayload` command under AI control | not traced | observe an AI unit ordered to enter |
| 15 | `enemyBuildSpeedMultiplier` compounding on reload | `*=` applied on each world load | compare the team rule before and after save/load |

