# Erekir late-game specification (Mindustry v160.7, source-verified)

Source root: `/home/user/src-ref/mindustry/core/src/mindustry/`. Files: `content/Blocks.java` (block fields), `content/UnitTypes.java`, `content/ErekirTechTree.java`, `content/SectorPresets.java`, `content/Items.java`, `content/Liquids.java`, and the classes under `world/blocks/`, `entities/comp/BuildingComp.java`, `type/UnitType.java`, `type/Weapon.java`.

## 0. Conventions (read first)

- **Ticks.** 1 tick = 1/60 s. In `Blocks.java`, `consumePower(x)`, `powerProduction`, liquid amounts and `pumpAmount` are **per tick**. This document gives them **per second (×60)**, so `consumePower(2f)` appears as 120 power/s.
- **Efficiency.** `efficiency` = the minimum over the non-optional consumers (power gives `power.status` 0..1, so a block with too little power runs proportionally slower), times `efficiencyScale()` (heat, for heat crafters). Progress per tick = `edelta()/craftTime` = efficiency × timeScale / craftTime. Liquid consumption per tick = amount × edelta, so it scales with efficiency. Items are consumed once per finished craft. (`BuildingComp.updateConsumption`, `ConsumeLiquid.update`, `GenericCrafter`.)
- **Power draw is not scaled.** A powered block asks for its full `usage` whenever `shouldConsume()` is true and every non-power consumer has efficiency > 0 (`ConsumePower.requestedPower`, `PowerGraph.getPowerNeeded`). A heat crafter running at 50 % heat still draws 100 % power.
- **Output blocked → everything stops.** `GenericCrafter.shouldConsume()` is false when `items(output) + amount > itemCapacity`. Efficiency is then 0. This also applies to the oxidation chamber, so a full oxide buffer also stops its **heat** output.
- **Liquid outputs.** `dumpExtraLiquid = true` is the default, so a multi-output crafter keeps running while any one of its outputs has room. A full output just wastes the overflow.
- **Building cost/time.** Build time in ticks = Σ(amount × item.cost) × `buildCostMultiplier` (`Block.init`). Item costs: Be 1.2, W 1.5, graphite 1.0, Si 0.8, Th 1.1, oxide 1.2, carbide 1.4, surge 1.2, phase 1.3, sand 1.0 (`Items.java`).
- **Erekir 0.75 damage rebalance.** `ErekirTechTree.rebalance()` multiplies `bullet.damage` by 0.75. This hits the top-level bullet of every `ErekirUnitType` weapon (tanks included), the ammo bullets of every Erekir `ItemTurret`, the `ContinuousLiquidTurret` (sublimate), and the `shootType` of `ContinuousTurret` (lustre). It does **not** touch splash damage, frag bullets, interval or spawned bullets, missile units (`MissileUnitType extends UnitType`, not `ErekirUnitType`), or **PowerTurrets (afflict, malign)**. Two bullets are written as `X/0.75f` so they end up at exactly X: breach carbide → 325, avert → 22.
- **Research cost.** If a block has no explicit `researchCost`, each item costs `round10(60·m + amount^1.11·20·m·mItem)`, then `UI.roundAmount` (`Block.researchRequirements`). The Erekir tree then multiplies by **0.9 for every item, but oxide 0.5, surge 0.7, carbide 0.3, phase 0.2** (`ErekirTechTree.load`, `TechNode` constructor). The tree multiplier also applies to explicit `researchCost`. The research numbers below were computed with this formula and can be off by one rounding step. Unit research = (factory/refab/assembler item cost) × `researchCostMultiplier` (Erekir default 10, many T1/T2s set it to 0 = free) × the tree multiplier.

---

## 1. Tech tree (ErekirTechTree.java)

Objective meanings (`game/Objectives.java`):
- `OnSector(X)`: you have a base on X (`sector.hasBase()`). You only need to land, not capture.
- `SectorComplete(X)`: X is captured and has a base.
- `Research(Y)`: Y is unlocked.
- `Produce(item)`: the item has been obtained.

On top of the listed objectives, every node implicitly needs `Research` of all its build-requirement items and of the items its non-optional `ConsumeItems` use (`Block.getDependencies`). Units implicitly need their refabricator researched (`UnitType.getDependencies`). Root: `coreBastion`, with `requiresUnlock = true`.

### 1.1 Sectors (SectorPresets.java + tree)

| Sector | id | difficulty | special | Unlock objectives |
|---|---|---|---|---|
| onset | 10 | 1 | alwaysUnlocked | – |
| aegis | 88 | 3 | | SectorComplete onset, Research ductRouter, ductBridge |
| lake | 41 | 4 | | SectorComplete aegis |
| intersect | 36 | 5 | captureWave 9, attackAfterWaves | SC aegis, SC lake, Research ventCondenser, shipFabricator |
| atlas | 14 | 5 | | SC intersect, Research mechFabricator |
| split | 19 | 2 | | SC atlas, Research reinforcedPayloadConveyor, reinforcedContainer |
| basin | 29 | 6 | | SC atlas |
| marsh | 25 | 4 | | SC basin |
| peaks | 30 | 3 | | SC marsh, SC split |
| ravine | 39 | 4 | captureWave 24 | SC marsh, Research **slag** (liquid) |
| caldera-erekir | 43 | 4 | | SC peaks, SC ravine, Research heatRedirector |
| stronghold | 18 | 7 | | SC caldera, Research coreCitadel |
| crevice | 3 | 6 | captureWave 46 | SC stronghold |
| siege | 58 | 8 | | SC crevice |
| crossroads | 37 | 7 | | SC siege |
| karst | 5 | 9 | captureWave 10 | SC crossroads, Research coreAcropolis |
| origin | 12 | 10 | isLastSector | SC karst, Research coreAcropolis, vanquish, disrupt, collaris, malign, basicAssemblerModule, neoplasiaReactor |

### 1.2 Block and unit nodes

Indentation follows the tree. "Research cost" is computed (see §0).

**Logistics branch** (root → duct, objective OnPlanet erekir)

| Node | Parent | Objectives | Research cost |
|---|---|---|---|
| duct | coreBastion | OnPlanet(erekir) | Be 4 |
| ductRouter | duct | – | Be 27 |
| ductBridge | ductRouter | – | Be 90 |
| armoredDuct | ductBridge | – | Be 270, W 90 |
| surgeConveyor | armoredDuct | – | surge 21, W 72 |
| surgeRouter | surgeConveyor | – | surge 140, W 72 |
| unitCargoLoader | ductBridge | – | Si 2250, surge 14, oxide 15 |
| unitCargoUnloadPoint | unitCargoLoader | – | Si 2700, oxide 10 |
| overflowDuct | ductRouter | OnSector aegis | graphite 360, Be 360 |
| underflowDuct | overflowDuct | – | graphite 360, Be 360 |
| reinforcedContainer | overflowDuct | – | W 810, graphite 1170 |
| ductUnloader | reinforcedContainer | – | graphite 540, Si 540, W 270 |
| reinforcedVault | reinforcedContainer | – | W 3870, Th 2070, Be 3060 |
| reinforcedMessage | ductRouter | OnSector aegis | graphite 270, Be 180 |
| canvas → largeCanvas | reinforcedMessage | – | – |
| reinforcedPayloadConveyor | duct | OnSector atlas | W 1170 |
| payloadMassDriver | reinforcedPayloadConveyor | Research siliconArcFurnace, OnSector split | W 1170, Si 1440, graphite 540 |
| payloadLoader | payloadMassDriver | – | graphite 2430, Si 5130, W 2700 |
| payloadUnloader | payloadLoader | – | graphite 4410, Si 7200, W 5760 |
| largePayloadMassDriver | payloadUnloader | – | phase 60, W 3690, Si 4770, graphite 1710, oxide 450 |
| constructor | payloadMassDriver | OnSector split | Si 1440, Be 2250, W 1170 |
| smallDeconstructor | constructor | OnSector peaks | Be 2070, Si 2070, oxide 400, graphite 1440 |
| largeConstructor | smallDeconstructor | OnSector siege | Si 4770, oxide 1700, W 6480, Th 2430 |
| deconstructor | smallDeconstructor | OnSector siege | Be 8280, oxide 1700, Si 8280, carbide 480 |
| reinforcedPayloadRouter | reinforcedPayloadConveyor | – | W 1710 |

**Drills**

| Node | Parent | Objectives | Research cost |
|---|---|---|---|
| plasmaBore | coreBastion | – | Be 9 (explicit 10 × 0.9) |
| impactDrill | plasmaBore | OnSector aegis | Si 1080, Be 1350, graphite 900 |
| largePlasmaBore | impactDrill | OnSector caldera | Si 1350, oxide 100, Be 2700, W 1080 |
| eruptionDrill | largePlasmaBore | OnSector stronghold | Si 9900, oxide 300, W 8280, Th 4770 |
| largeCliffCrusher | largePlasmaBore | OnSector stronghold | Si 2430, surge 350, Be 3060, W 1440 |

**Power / crafting branch** (root → turbineCondenser)

| Node | Parent | Objectives | Research cost |
|---|---|---|---|
| turbineCondenser | coreBastion | – | Be 13 |
| beamNode | turbineCondenser | – | Be 4 |
| ventCondenser | beamNode | OnSector aegis | graphite 540, Be 1710 |
| chemicalCombustionChamber | ventCondenser | OnSector basin | graphite 1800, W 900, oxide 5, Si 1350 |
| pyrolysisGenerator | chemicalCombustionChamber | OnSector crevice | graphite 1260, carbide 240, oxide 400, Si 1260 |
| fluxReactor | pyrolysisGenerator | OnSector crossroads, Research cyanogenSynthesizer | graphite 7920, carbide 570, oxide 1350, Si 17100, surge 2870 |
| neoplasiaReactor | fluxReactor | OnSector karst | W 27900, carbide 3300, oxide 2650, Si 18000, phase 1060, surge 5040 |
| beamTower | beamNode | OnSector peaks | Be 810, oxide 150, Si 270 |
| beamLink | beamTower | OnSector crossroads | Be 8280, Si 8280, oxide 2650, carbide 750, surge 1750, phase 500 |
| regenProjector | beamNode | OnSector peaks | Si 2430, W 1710, oxide 650, Be 2430 |
| buildTower | regenProjector | OnSector stronghold | Si 4770, oxide 650, Th 1710 |
| shockwaveTower | buildTower | OnSector siege | surge 1120, Si 4770, oxide 450, W 3060 |
| reinforcedConduit | turbineCondenser | OnSector aegis | Be 270 |
| reinforcedPump | reinforcedConduit | OnSector basin | Be 1170, W 810, Si 540 |
| reinforcedLiquidJunction | reinforcedConduit | – | graphite 180, Be 270 |
| reinforcedBridgeConduit | reinforcedLiquidJunction | – | graphite 270, Be 540 |
| reinforcedLiquidRouter | reinforcedLiquidJunction | – | graphite 720, Be 450 |
| reinforcedLiquidContainer | reinforcedLiquidRouter | – | W 1170, Be 1800 |
| reinforcedLiquidTank | reinforcedLiquidContainer | SectorComplete intersect | W 1170, Be 1440 |
| cliffCrusher | turbineCondenser | – | Be 90, graphite 36 |
| siliconArcFurnace | cliffCrusher | – | Be 135, graphite 45 |
| electrolyzer | siliconArcFurnace | OnSector atlas | Si 1710, graphite 1350, Be 4860, W 2880 |
| oxidationChamber | electrolyzer | Research tankRefabricator, OnSector marsh | W 4050, graphite 2610, Si 3330, Be 4050 |
| surgeCrucible | oxidationChamber | OnSector ravine | Si 3060, graphite 2430, W 2430, oxide 1350 |
| heatRedirector | oxidationChamber | OnSector ravine | W 2880, graphite 2880 |
| electricHeater | heatRedirector | OnSector ravine, Research afflict | W 3330, oxide 1850, Be 3330 |
| slagHeater | electricHeater | OnSector caldera | W 1080, oxide 450, Be 2160 |
| atmosphericConcentrator | electricHeater | OnSector caldera | Si 1800, oxide 450, Be 2160 |
| cyanogenSynthesizer | atmosphericConcentrator | OnSector siege | carbide 810, Si 3690, Be 4410, oxide 650 |
| carbideCrucible | electricHeater | OnSector crevice | W 3420, Th 4770, oxide 950 |
| phaseSynthesizer | carbideCrucible | OnSector karst | carbide 900, Si 3060, Th 3060, W 6480 |
| phaseHeater | phaseSynthesizer | Research phaseSynthesizer | oxide 450, carbide 270, Be 810 |
| heatRouter | electricHeater | – | W 4140, graphite 2880 |
| smallHeatRedirector | heatRouter | – | surge 210, graphite 2610 |
| slagIncinerator | electrolyzer | OnSector basin | W 450 |

(`slagCentrifuge` and `heatReactor` exist but are `BuildVisibility.debugOnly` and are commented out of the tree.)

**Turrets / walls** (root → breach)

| Node | Parent | Objectives | Research cost |
|---|---|---|---|
| breach | coreBastion | Research siliconArcFurnace, tankFabricator | Be 270, Si 270, graphite 180 |
| berylliumWall → berylliumWallLarge | breach | – | Be 180 / Be 630 |
| tungstenWall → tungstenWallLarge → blastDoor | berylliumWall | – | W 180 / W 630 / W 630, Si 630 |
| reinforcedSurgeWall → Large → shieldedWall | tungstenWall | – | (explicit) surge 14, W 90 / surge 28, W 180 / phase 120, surge 280, Be 360 |
| carbideWall → carbideWallLarge | tungstenWall | – | Th 180, carbide 60 / Th 630, carbide 210 |
| diffuse | breach | OnSector lake | Be 4770, Si 6480, graphite 6480, W 1440 |
| sublimate | diffuse | OnSector marsh | W 360, Si 360, oxide 40, Be 720 |
| afflict | sublimate | OnSector ravine | surge 70, Si 270, graphite 360, oxide 25 |
| titan | afflict | OnSector stronghold | W 8280, Si 9900, Th 14400 |
| lustre | titan | OnSector crevice | Si 8280, graphite 6480, oxide 800, carbide 900 |
| smite | lustre | OnSector karst | oxide 3600, surge 11200, Si 29700, carbide 6000, phase 2200 |
| disperse | diffuse | OnSector stronghold | Th 1440, oxide 800, Si 6480, Be 11700 |
| scathe | disperse | OnSector siege | Si 16200, graphite 14400, W 18000, oxide 1700, carbide 2160 |
| malign | scathe | **SectorComplete karst** | carbide 2160, Be 38700, Si 18000, graphite 18000, phase 1440 |
| radar | breach | Research beamNode, turbineCondenser, tankFabricator, OnSector aegis | Si 63, graphite 63 |
| coreCitadel | coreBastion | SectorComplete peaks | Si 15300, Be 30600, W 22500, oxide 3650 |
| coreAcropolis | coreCitadel | SectorComplete siege | Be 27900, Si 9000, W 23400, carbide 4200, oxide 7000 |

**Units** (root → tankFabricator)

| Node | Parent | Objectives | Research cost |
|---|---|---|---|
| tankFabricator | coreBastion | Research siliconArcFurnace, plasmaBore, turbineCondenser | Be 180, graphite 72, Si 72 |
| stell | tankFabricator | – | free |
| unitRepairTower | tankFabricator | OnSector ravine, Research mechRefabricator | graphite 2700, Si 2700, W 2430 |
| shipFabricator | tankFabricator | OnSector lake | Si 4140, Be 3240 |
| elude | shipFabricator | – | free |
| mechFabricator | shipFabricator | OnSector intersect | Si 4230, Be 5400, W 180 |
| merui | mechFabricator | – | free |
| tankRefabricator | mechFabricator | OnSector atlas | Be 4860, W 1800, Si 2250 |
| locus | tankRefabricator | – | free |
| mechRefabricator | tankRefabricator | OnSector basin | Be 6210, W 4230, Si 3600 |
| cleroi | mechRefabricator | – | Si 450, W 360 |
| shipRefabricator | mechRefabricator | OnSector peaks | (explicit) Be 450, W 180, Si 270, oxide 40 |
| avert | shipRefabricator | – | Si 540, W 360 |
| primeRefabricator | shipRefabricator | OnSector stronghold | Th 1710, oxide 3600, W 6480, Si 14400 |
| precept / anthicus / obviate | primeRefabricator | – | free / Th 720, Si 900 / Th 720, Si 900 |
| tankAssembler | shipRefabricator | OnSector siege, Research constructor, atmosphericConcentrator | Th 7200, oxide 1050, carbide 330, Si 9900 |
| vanquish | tankAssembler | – | Be 1440, Si 1800, W 2160 |
| conquer | vanquish | OnSector karst | Si 4860, W 1620, Be 2160, Th 4320, carbide 1440 |
| shipAssembler | tankAssembler | OnSector crossroads | carbide 1020, oxide 3600, W 19800, Si 34200, Th 14400 |
| quell | shipAssembler | – | free |
| disrupt | quell | OnSector karst | Si 7020, W 2160, graphite 2700, Th 4320, carbide 1440 |
| mechAssembler | tankAssembler | OnSector crossroads | carbide 2160, Th 21600, oxide 3600, W 19800, Si 38700 |
| tecta | mechAssembler | – | free |
| collaris | tecta | OnSector karst | Si 6480, W 2160, Be 2700, Th 4320, carbide 1440 |
| basicAssemblerModule | tankAssembler | **SectorComplete karst** | carbide 2550, Th 13500, oxide 3450, phase 2400 |

**Item / liquid nodes** (`nodeProduce` = Produce objective):
- beryllium → sand → silicon → oxide
- beryllium → water → ozone → hydrogen → nitrogen, and hydrogen → cyanogen → neoplasm
- beryllium → graphite → tungsten → slag, arkycite, thorium → carbide, and tungsten → surge alloy → phase fabric

### 1.3 What each sector gates (landing = OnSector)

| Sector | Unlocks (directly gated) |
|---|---|
| aegis | overflowDuct, reinforcedMessage, impactDrill, ventCondenser, reinforcedConduit, radar (+ sector intersect path) |
| lake | diffuse, shipFabricator |
| intersect | mechFabricator; SectorComplete(intersect) → reinforcedLiquidTank |
| atlas | reinforcedPayloadConveyor, electrolyzer, tankRefabricator |
| split | payloadMassDriver, constructor |
| basin | chemicalCombustionChamber, reinforcedPump, slagIncinerator, mechRefabricator |
| marsh | oxidationChamber, sublimate |
| peaks | smallDeconstructor, beamTower, regenProjector, shipRefabricator; SectorComplete(peaks) → coreCitadel |
| ravine | surgeCrucible, heatRedirector, electricHeater (+ afflict), afflict, unitRepairTower |
| caldera | largePlasmaBore, slagHeater, atmosphericConcentrator |
| stronghold | eruptionDrill, largeCliffCrusher, buildTower, titan, disperse, primeRefabricator |
| crevice | pyrolysisGenerator, carbideCrucible, lustre |
| siege | largeConstructor, deconstructor, shockwaveTower, cyanogenSynthesizer, scathe, tankAssembler; SectorComplete(siege) → coreAcropolis |
| crossroads | fluxReactor (+ cyanogenSynthesizer), beamLink, shipAssembler, mechAssembler |
| karst | neoplasiaReactor, phaseSynthesizer, smite, conquer, disrupt, collaris; SectorComplete(karst) → malign, basicAssemblerModule |

---

## 2. Crafting

All of these are `GenericCrafter` subclasses unless noted. "/s" values assume efficiency 1. Default `itemCapacity` is 10. A crafter accepts up to `itemCapacity` of **each** input item (`GenericCrafter.getMaximumAccepted`), from any side (default `acceptItem`).

| Block | Class | Size | Build cost | Power /s | Craft time | Inputs per craft (→ per s) | Output (→ per s) | Heat | itemCap / liquidCap |
|---|---|---|---|---|---|---|---|---|---|
| silicon-arc-furnace | GenericCrafter | 3 | Be 70, graphite 80 | 300 | 50 t | graphite 1 + sand 4 (1.2 + 4.8/s) | Si 4 (4.8/s) | – | 30 / – |
| electrolyzer | GenericCrafter, rotates | 3 | Si 50, graphite 40, Be 130, W 80 | 60 | 10 t (continuous) | water 10/s | ozone 4/s + hydrogen 6/s | – | 0 / 50 |
| oxidation-chamber | **HeatProducer** | 3 | W 120, graphite 80, Si 100, Be 120 | 30 | 120 t | Be 1 (0.5/s) + ozone 2/s | oxide 1 (0.5/s) | **outputs 5** | 10 / 30 |
| atmospheric-concentrator | HeatCrafter | 3 | oxide 60, Be 180, Si 150 | 120 | continuous | – (only heat and power) | nitrogen 16/s | needs 24, maxEff 1 | 0 / 60 |
| carbide-crucible | HeatCrafter | 3 | W 110, Th 150, oxide 60 | 120 | 33.75 t | W 2 + graphite 3 (3.56 + 5.33/s) | carbide 1 (1.778/s) | needs 40, maxEff 1 | 20 / – |
| surge-crucible | HeatCrafter | 3 | Si 100, graphite 80, W 80, oxide 80 | 90 | 45 t | Si 3 (4/s) + slag 160/s | surge 1 (1.333/s) | needs 40, maxEff 1 | 20 / 400 |
| cyanogen-synthesizer | HeatCrafter | 3 | carbide 80, Si 120, Be 140, oxide 40 | 120 | 20 t | graphite 1 (3/s) + arkycite 160/s | cyanogen 12/s | needs 20, maxEff 1 | 10 / 80 |
| phase-synthesizer | HeatCrafter | 3 | carbide 90, Si 100, Th 100, W 200 | 480 | 30 t | Th 2 + sand 6 (4 + 12/s) + ozone 8/s | phase 1 (**2/s**) | needs 32, maxEff 1 | 40 / 40 |
| vent-condenser | AttributeCrafter | 3 | graphite 20, Be 60 | 30 | 120 t (continuous) | – | water 30/s | – | 0 / 60 |
| slag-incinerator | ItemIncinerator | 1 | W 15 | 0 | – | needs slag present (consumes 0) | destroys any item | – | – / 10 |
| cliff-crusher / large-cliff-crusher | WallCrafter | 2 / 3 | see §5 | | | | sand | | |

Notes, from the class code:
- **Electrolyzer outputs only to its sides.** `liquidOutputDirections = {1, 3}`. Ozone goes to side `(rotation+1)%4` (left of the facing direction), hydrogen to `(rotation+3)%4` (right) (`GenericCrafter.dumpOutputs` → `dumpLiquid(liquid, 2f, dir)`, where the dir check is `(outputDir + rotation) % 4 == relativeTo(other)`). It has no item capacity. Because `dumpExtraLiquid = true`, it keeps running while one output still has room; unused hydrogen is simply capped.
- **Oxidation chamber** is a `HeatProducer`, so it is a crafter **and** a heater. Heat goes only out of the side it faces (`rotate = true`). Its heat target is `5 × efficiency`, and efficiency is 0 when the oxide buffer (10) is full or ozone/Be/power is missing. **You must drain its oxide or the heat disappears.** Oxide is dumped to any adjacent acceptor.
- **Vent condenser and turbine condenser** can only be placed where the Σ steam attribute under the 3×3 is ≥ 9 − 0.0001 (`minEfficiency`). Every vent floor tile has `steam = 1` (rhyolite, carbon, arkyic, yellow-stone, red-stone, crystalline, stone, basalt vents). So the whole 3×3 must sit on vent tiles. The vent condenser's efficiency is `baseEfficiency 0 + min(maxBoost 1, attr × 1/9)`, which is exactly 1.0 on a legal placement.
- **Slag incinerator** has `consumeLiquid(slag, 0)`. `ConsumeLiquid.efficiency` = stored / 0, so any stored slag (> 0) gives efficiency 1 and the slag is never consumed. With no slag it rejects items. `acceptItem` = `efficiency > 0`, any item from any side, deleted instantly.
- **Heat crafters** (`HeatCrafter.efficiencyScale`): `min(clamp(heat/req) + max(heat−req,0)/req × overheatScale, maxEfficiency)`. Every Erekir heat crafter sets `maxEfficiency = 1`, so **extra heat gives no speed-up**. (The class default is 4; overheatScale is 1 but unused here.) Below the requirement, speed is linear in heat. Per-craft item cost is unchanged, liquid use per tick scales down with efficiency, and power stays at 100 %. `shouldConsume()` needs `heat > 0`. Heat is read every tick from adjacent heaters that face the crafter (§3); a crafter can take heat from all 4 sides.
- **Phase heater, electric heater, slag heater**: see §3.
- **heat-reactor** (fissile matter) and **slag-centrifuge** (gallium) are debug-only and not obtainable.

---

## 3. Heat

### 3.1 Heat producers (`HeatProducer extends GenericCrafter`; rotate = true, canOverdrive = false)

`heat = approachDelta(heat, heatOutput × efficiency, warmupRate 0.15 × delta)`, so heat ramps at 0.15 per tick (9 heat/s) toward `heatOutput × efficiency`, and falls the same way.

| Block | Size | Cost | Heat out | Consumes | Notes |
|---|---|---|---|---|---|
| electric-heater | 2 | W 30, oxide 30, Be 30 | 3 | 100 power/s | itemCapacity 0; 33.3 power/s per heat unit |
| slag-heater | 3 | W 50, oxide 20, Be 20 | 8 | slag 40/s | liquidCap 120; 5 slag/s per heat |
| phase-heater | 2 | oxide 30, carbide 30, Be 30 | 15 | 1 phase every 480 t (0.125/s) | phase must be in stock (efficiency = has item), consumed at end of each 8 s cycle |
| oxidation-chamber | 3 | (§2) | 5 | Be + ozone + 30 power/s | also makes 0.5 oxide/s |
| neoplasia-reactor | 5 | (§4) | 60 | `HeaterGenerator`, same heat rule | also 8400 power/s |
| heat-source | – | sandbox only | | | |

### 3.2 Heat conductors (`HeatConductor`)

| Block | Size | Cost | splitHeat |
|---|---|---|---|
| heat-redirector | 3 | W 10, graphite 10 | false |
| small-heat-redirector | 2 | surge 3, graphite 8 | false |
| heat-router | 3 | W 15, graphite 10 | **true** |

`visualMaxHeat = 15` is **only** the UI bar scale and the value returned as `heatRequirement()`. It is not a cap. `regionRotated1` is a sprite-index setting with no mechanical effect. A disabled conductor has heat 0.

### 3.3 Exact transfer rule (`BuildingComp.calculateHeat`)

Every heat consumer (HeatCrafter, HeatConductor, heat-using Turret, VariableReactor) recomputes its heat **every tick from scratch**:

```
for each same-team building B in proximity that is a HeatBlock:
  split = B is HeatConductor with splitHeat
  accept if  !B.block.rotate
          || (!split && (relativeTo(B)+2)%4 == B.rotation)   // B faces me
          || ( split &&  relativeTo(B)     != B.rotation)    // router: any side except its back
  skip if B is a conductor whose cameFrom contains me (cycle)
  diff          = min(|B.x-x|, |B.y-y|) / 8                   // offset along the contact edge, tiles
  contactPoints = min( int(mySize/2 + Bsize/2 - diff), min(Bsize, mySize) )
  add = B.heat / Bsize * contactPoints
  if split: add /= 3
  heat += add
```

What this means:
- **Heat is not consumed or divided between consumers.** Each consumer reads the producer's `heat` independently. The total is still bounded, because a producer only gives to buildings on the side it faces and contact along that edge is at most its size. A **router**, by contrast, gives `heat/3 × contact/size` to each of its 3 non-back sides **whether or not anything is there**. Unused router sides waste their third.
- **Size mismatch loses heat.** A 3×3 heater (slag heater, oxidation chamber, 3×3 redirector) feeding a 2×2 consumer has contact ≤ 2, so only 2/3 of its heat passes. A 2×2 heater (electric/phase) on a 3×3 side passes 100 % if its centre is within 0.5 tile of the side's centre, and 50 % if it hangs over the corner. At most 3 tiles of contact fit on one 3×3 side, e.g. one full 2×2 plus one half 2×2.
- **Conductors sum their inputs.** A redirector takes heat from **any** adjacent heater that faces it, on any side (even its own front), and outputs the total to its front. Same-size chains of redirectors are **lossless** (contact 3/3). There is no cap: several heaters around one redirector stack.
- Directly on its 4 sides, a 3×3 crafter can take at most about 4 × 4.5 = 18 heat from 2×2 electric heaters (each side fits 1 full + 1 half heater). To reach 40, use slag heaters (8 each, full contact), phase heaters (15 each), or redirectors that pre-sum heat.
- **Which sides:** heat producers and redirectors output only toward `rotation` (their front). Consumers take from all 4 sides. A router outputs to front, left and right, and never backward.

### 3.4 Heat → efficiency summary

| Consumer | Requirement | Max efficiency | Effect of extra heat |
|---|---|---|---|
| atmospheric concentrator | 24 | 1 | none |
| carbide crucible | 40 | 1 | none |
| surge crucible | 40 | 1 | none |
| cyanogen synthesizer | 20 | 1 | none |
| phase synthesizer | 32 | 1 | none |
| afflict (turret) | 20 | 1 (`maxHeatEfficiency`) | none |
| malign (turret) | 144 | 1 | none |
| flux reactor | `maxHeat` 150 | power ∝ clamp(heat/150) | none above 150 |

Turret efficiency is multiplied by `min(heat/req, maxHeatEfficiency)` (`Turret.updateEfficiencyMultiplier`), so reload slows linearly with missing heat.

---

## 4. Power

Erekir has **no battery block in its tree**. Storage comes from beam nodes (buffered consumers). Only generators, nodes and `conductivePower` blocks connect by adjacency: two plain consumers next to each other do **not** share power (`BuildingComp.getPowerConnections`). Surge conveyor, surge router and shielded wall are `conductivePower`.

| Block | Class | Size | Cost | Output | Inputs | Notes / risk |
|---|---|---|---|---|---|---|
| turbine-condenser | ThermalGenerator | 3 | Be 60 | 180/s (3/9 per tick × attr 9) | none (must sit fully on vents) | also outputs water 5/s to any side; always on |
| chemical-combustion-chamber | ConsumeGenerator | 3 | graphite 40, W 20, oxide 40, Si 30 | 550/s | ozone 2/s + arkycite 40/s | liquidCap 100; default explosion only if destroyed (no damage set) |
| pyrolysis-generator | ConsumeGenerator | 3 | graphite 100, carbide 60, oxide 60, Si 100 | 1400/s | slag 20/s + arkycite 40/s | outputs water 20/s (any side); liquidCap 150 |
| flux-reactor | VariableReactor | 5 | graphite 240, carbide 60, oxide 80, Si 480, surge 120 | up to 18000/s | heat (max 150) + cyanogen 9/s × heat/150 | **explodes**, see below; rebuildable = false |
| neoplasia-reactor | HeaterGenerator | 5 | W 750, carbide 300, oxide 150, Si 500, phase 150, surge 200 | 8400/s | arkycite 80/s + water 10/s + 1 phase per 180 t (0.333/s) | **outputs neoplasm 20/s, explodes when full**; also outputs heat 60 |
| beam-node | BeamNode | 1 | Be 8 | – | buffer 1000 | range 10 tiles, 4 cardinal beams |
| beam-tower | BeamNode | 3 | Be 30, oxide 10, Si 10 | – | buffer 40000 | range 23 tiles |
| beam-link | LongPowerNode | 3 | Be 250, Si 250, oxide 150, carbide 75, surge 75, phase 75 | – | – | laserRange **500 tiles**, maxNodes 1, no autolink, links only to another beam-link |

Mechanics:
- **Beam node/tower** (`BeamNode`): each of the 4 cardinal directions links to the **first** tile within `range` (counted from its edge) that holds a powered, `connectedPower` building of the same team. An insulated building in the way stops the beam. No diagonals, no manual linking.
- **Flux reactor** (`VariableReactor`):
  - Each tick: `heat = calculateHeat()` and `target = clamp(heat/150)`.
  - `efficiencyMet = clamp(cyanogenEfficiency / target)`. Cyanogen efficiency is stored / 0.15 per tick, capped at 1.
  - If met: `instability → 0` at 0.5 per tick.
  - If not met: `instability → 1` at `(1/180) × (1 − met)` per tick. With heat present and no cyanogen at all, that is **3 s to failure**.
  - At instability ≥ 1 it `kill()`s itself. The explosion (2500 damage, radius 17 tiles, 70 slag puddles) happens if `heat > 0` and `rules.reactorExplosions`.
  - Power = 18000/s × target, and cyanogen use = 9/s × target. Heat 150 needs, for example, 10 phase heaters (through redirectors), 19 slag heaters, or 50 electric heaters (5000 power/s, net +13000).
- **Neoplasia reactor** (`ConsumeGenerator` + heat):
  - If its 80-unit neoplasm buffer reaches 79.99 it `kill()`s itself. Neoplasm is made at 20/s, so it explodes **4 s after the output blocks**.
  - Explosion: 2000 damage, radius 9 tiles, 80 neoplasm puddles. Requires warmup ≥ 0.5 and `rules.reactorExplosions`.
  - Neoplasm is `incinerable = false`. Disposal means piping it out, e.g. a leaking reinforced conduit end (`leaks = true`).
  - The reactor holds 10 phase.
- Generators explode on destruction only if `explosionDamage > 0` (only flux and neoplasia set it), plus `baseExplosiveness` from stored items and liquids.

---

## 5. Drills

Hardness: sand 0, graphite 0, beryllium 3, thorium 4, tungsten 5 (`Items.java`). `canMine` = drop.hardness ≤ tier and not blocked.

| Block | Class | Size | Cost | Tier | Base time | Power /s | Required liquid | Boost | Mines |
|---|---|---|---|---|---|---|---|---|---|
| plasma-bore | BeamDrill | 2 | Be 40 | 3 | 160 t per item per laser | 9 | – | hydrogen 0.25/s → ×2.5 | **wall** ores/walls: Be, graphite, sand walls. NOT tungsten or thorium |
| large-plasma-bore | BeamDrill | 3 | Si 100, oxide 25, Be 100, W 70 | 5 | 100 t | 48 | hydrogen 0.5/s | nitrogen 3/s → ×2.5 | wall Be, graphite, W, Th |
| impact-drill | BurstDrill | 4 | Si 70, Be 90, graphite 60 | 6 | 720 t per burst (Be: 360) | 160 | water 10/s | ozone 3/s → ×1.75 | **floor** ores: Be, W, sand floor. **Thorium blocked** |
| eruption-drill | BurstDrill | 5 | Si 300, oxide 20, W 250, Th 150 | 7 | 281.25 t (Be: 140.6) | 360 | hydrogen 4/s | cyanogen 0.75/s → ×2 | floor Be, W, Th (crystal thorium), sand |
| cliff-crusher | WallCrafter | 2 | graphite 25, Be 20 | – | 110 t / Σattr | 11 | – | – | sand from walls with `sand` attribute |
| large-cliff-crusher | WallCrafter | 3 | Si 80, surge 15, Be 100, W 50 | – | 48 t / Σattr | 60 | hydrogen 1/s | graphite item ×1.6 (1 per 80 t = 0.75/s) | sand |

Formulas:
- **BeamDrill.** One laser per tile of the front edge, each with reach `range` (5 / 6 tiles). A laser hits the first solid tile and counts if `wallDrop()` (a static wall's `itemDrop`, or a wall-ore overlay) is mineable. Every `drillTime` ticks (time += edelta × boost; drill time does **not** depend on hardness) it adds 1 item per facing mineable tile.
  - Rate = lasers × 60 / drillTime: plasma bore 0.75/s (1.875 boosted); large 1.8/s (4.5 boosted).
  - Boost = `optionalBoostIntensity` 2.5, linear in optional efficiency.
  - It does not mine floor ores. If the lasers face different items, all are still produced, but the displayed item is null.
- **BurstDrill.** `getDrillTime = drillTime / drillMultipliers[item]` (no hardness term). Each burst offloads **`dominantItems` items at once** (the count of tiles of the dominant ore under the drill).
  - Rate = n × 60 / drillTime × boost. Boost is linear `lerp(1, liquidBoostIntensity, optionalEfficiency)` (stat shows 1.75 / 2, not squared).
  - Impact drill, 16 tiles: 1.333/s (Be 2.667/s); ×1.75 boosted → 2.33 (Be 4.67).
  - Eruption drill, 25 tiles: 5.333/s (Be 10.67/s); ×2 boosted → 10.67 (Be 21.3).
  - It only works while `items.total() ≤ itemCapacity − dominantItems` (40 / 60).
- **Dominant ore** (`Drill.countOre`): sort by not-`lowPriority` first, then by tile count, then by item id. **Sand is `lowPriority`**, so any real ore under a burst drill wins even with fewer tiles.
- **WallCrafter.** `eff = Σ sand attribute of the solid tiles touching its front edge` (2 or 3 tiles). Rate = eff × 60 / drillTime (× 1.6 with the graphite boost).
  - Sand attributes: sand-wall 2, dune 2, yellow-stone 1.5, red-stone 1.5, beryllic 1.2, regolith 1, rhyolite 1, stone 1, carbon 0.7, ferric 0.5; arkyic, crystalline, red-ice 0.
  - Cliff crusher on 2 rhyolite tiles: 1.09/s. Large crusher on 3 tiles of attribute 1: 3.75/s, 6/s boosted.

---

## 6. Logistics

### 6.1 Ducts (speed 4 → 1 item / 4 ticks = 15 items/s; capacity 1)

| Block | Cost | Accepts from | Outputs to |
|---|---|---|---|
| duct | Be 1 | back and sides, from any block; never from its own front | front only (`moveForward`) |
| armored-duct | Be 2, W 1 | back from anything; sides **only from a duct whose front is this** | front |
| duct-router | Be 10 | **back only** | front/left/right round-robin. With a sort item: the sort item goes **only front**, others **only to the sides** |
| overflow-duct | graphite 8, Be 8 | back only | front; if blocked, alternately left/right |
| underflow-duct | graphite 8, Be 8 | back only | left/right first (alternating), front only if both sides refuse |
| duct-bridge | Be 15 (buildCostMult 2) | back + sides (not front); only if it has an output link | to the next bridge in line ≤ 4 tiles ahead, 1 item / 4 t; capacity 4; the last bridge outputs front |
| duct-unloader | graphite 20, Si 20, W 10 | takes from the building **behind** | puts into the building **in front**, 1 item / 4 t (15/s). Config item, or round-robin by item id |

Duct timing: on receive, `progress = −1`; each tick +0.5; the item moves when progress ≥ 0.75. That is 4 ticks per tile, so ≈ 15 tiles/s, matching the stat `60/speed`.

**The duct unloader cannot unload a core or core-linked storage** (`allowCoreUnload = false`, and `StorageBuild.linkedCore` is checked). Erekir has no other unloader, so **items in the core cannot be taken back out by logistics**. Reinforced containers/vaults set `coreMerge = false`, so they never link to a core and can always be unloaded.

**The core destroys sand.** All Erekir cores have `incinerateNonBuildable = true`, and sand has `buildable = false`. Sand delivered to the core is deleted and not counted.

### 6.2 Surge conveyor (StackConveyor) and surge router (StackRouter)

Surge conveyor: surge 1 + W 1, `speed = 5/60`, `itemCapacity = 10`, `recharge = 2`, `outputRouter = false`, 1 power/s, `baseEfficiency = 1`, `conductivePower`.

- **Speed factor** `eff = power.status + baseEfficiency`: **2 when powered, 1 when unpowered**. An unpowered surge conveyor still runs, at half speed.
- **States** (`onProximityUpdate`):
  - **Load**: the first segment, i.e. a stack conveyor in front, none feeding from behind, no side feeds, and no other stack conveyor facing into it.
  - **Unload**: front is not a stack conveyor (because `outputRouter = false`).
  - **Move**: everything else.
- **Only the loading segment accepts items** (`acceptItem` requires state == load). Middle segments reject everything, including side inputs.
- The loader accepts from any adjacent block except its front (ducts, drills, crafters, unloaders). It takes **one item type per stack** (other items are rejected while it holds a stack). It does not accept while `cooldown > recharge − 1`.
- **A stack is sent only when the loader holds a full 10** (`items.total() >= getMaximumAccepted`) **and** its cooldown is 0 **and** the next segment is empty (`link == -1`). A partial stack never leaves.
- **Timing** (powered): cooldown drops by 1/6 per tick.
  - After sending, the loader's cooldown is 2. It can accept again after 6 ticks and send again after 12 ticks.
  - The receiving segment gets cooldown 1, so it needs 6 ticks to "centre" before passing the stack on.
  - So: 10 tiles/s movement and at most **one 10-stack per 12 ticks = 50 items/s** (stat), or 25/s unpowered.
  - Real throughput = min(50, rate at which the loader is filled). One duct feeding gives 15/s; three ducts, 45/s.
- **Unload end**: each tick it pushes items to the front building only (`moveForward`), in a loop until refused, so a core or container takes the whole stack instantly. It never dumps sideways.

Surge router: surge 5 + W 1, `speed = 6`, capacity 10, 3 power/s, `baseEfficiency 1`.
- Accepts **only from its back**, one item type, only while not unloading.
- Once it holds **10 items** it waits `speed` (6) / eff ticks (3 powered), then dumps the whole stack in one tick to front/left/right targets (DuctRouter targeting, sort config honoured), as fast as the targets accept.

### 6.3 Unit cargo

- **unit-cargo-loader** (3×3, Si 80, surge 50, oxide 20): holds 200 items, accepted from any side.
  - It builds one tethered **manifold** drone (100 items, speed 3.5, flying, 200 hp) in 480 ticks (8 s). During that time only, it uses 8 power/s and **nitrogen 10/s** (`shouldConsume = unit == null`).
  - The drone counts toward the unit cap, and the loader can't be placed if the cap is reached.
- **CargoAI**:
  - It takes the loader's most plentiful item that has a matching unload point, up to 100 at a time.
  - It flies to a **non-stale unload point configured to that exact item** (round-robin). An unconfigured point (item = null) **never** receives anything.
  - It deposits in bursts every 90 ticks.
  - If no point exists, it **deletes** its cargo (`clearItem`). If a point stays full, after 120 ticks it tries the next one.
- **unit-cargo-unload-point** (2×2, Si 60, W 60): capacity 100. It dumps to adjacent blocks and goes "stale" after being full for 6 s.

### 6.4 Storage

| Block | Size | Cost | Capacity (per item type) |
|---|---|---|---|
| reinforced-container | 2 | W 30, graphite 40 | 160 |
| reinforced-vault | 3 | W 125, Th 70, Be 100 | 900 |
| core-bastion | 4 | graphite 1000, Si 1000, Be 800 | 2000 (health 4500, armor 5, unit evoke) |
| core-citadel | 5 | Si 4000, Be 4000, W 3000, oxide 1000 | 3000 (health 16000, armor 10, unit incite) |
| core-acropolis | 6 | Be 6000, Si 5000, W 5000, carbide 3000, oxide 3000 | 4000 (health 30000, armor 15, unit emanate) |

Cores need a core zone and have `buildCostMultiplier 0.7`.

### 6.5 Reinforced liquids

| Block | Cost | Capacity | Notes |
|---|---|---|---|
| reinforced-conduit | Be 2 | 50 | ArmoredConduit (side input only from conduits); `leaks = true`, pressure 1.03 |
| reinforced-liquid-junction | graphite 4, Be 8 | – | |
| reinforced-bridge-conduit | graphite 8, Be 20 | 120 | DirectionLiquidBridge, range 4 |
| reinforced-liquid-router | graphite 8, Be 4 | 150 | |
| reinforced-liquid-container | W 10, Be 16 | 1000 | 2×2 |
| reinforced-liquid-tank | W 40, Be 50 | 2700 | 3×3 |
| reinforced-pump | Be 40, W 30, Si 20 | 160 | 2×2; 20/s per liquid tile × floor `liquidMultiplier` (80/s on 4 tiles of slag/arkycite/shallow water, ×1.5 on deep water); needs **hydrogen 1.5/s** |

### 6.6 Payload logistics

| Block | Size | Cost | Power /s | Key numbers |
|---|---|---|---|---|
| reinforced-payload-conveyor | 3 | W 10 | – | moveTime 35 t per 3 tiles (≈1.71 payloads/s per lane), payloadLimit 3 (3×3 blocks; units hitSize ≤ 24) |
| reinforced-payload-router | 3 | W 15 | – | same moveTime/limit |
| payload-mass-driver | 3 | W 40, Si 50, graphite 20 | 30 | range 700 = **87.5 tiles**; reload 45 + charge 70 → ≥115 t/shot (0.52/s); max payload 2.5 blocks (2×2 blocks, units hitSize ≤ 20) |
| large-payload-mass-driver | 5 | phase 10, W 120, Si 150, graphite 60, oxide 30 | 180 | range 2100 = **262.5 tiles**; reload 130 + charge 100 → ≥230 t/shot (0.26/s); max payload 4 (4×4 blocks; units hitSize ≤ 32: vanquish 28 and tecta 30 fit, conquer, collaris, quell and disrupt do not) |
| payload-loader | 3 | graphite 80, Si 160, W 90 | 120 (+2400 while charging a battery) | puts 8 items / 2 t (240/s) and 40 liquid/tick into a block payload ≤ 3×3 (container with itemCap ≥ 10 and unloadable, liquid block with cap ≥ 10, or buffered-power block such as a beam node/tower); exports when full |
| payload-unloader | 3 | graphite 140, Si 220, W 180 | 120 | reverse: 8 items / 2 t out of the payload, offloads 4 per tick, power out ≤ 80/tick |

Mass driver protocol (`PayloadMassDriver`):
- The shooter needs a configured link, a loaded payload, `reloadCounter ≤ 0`, and both turrets aligned to within 1°.
- The receiver must be powered, in `accepting` state, empty and reloaded. Shooters queue (`waitingShooters`), so several drivers can feed one receiver.
- Charging takes `chargeTime / efficiency`.
- The receiver outputs the payload forward like a payload conveyor.

### 6.7 Constructors / deconstructors

| Block | Size | Cost | Power /s | Rule |
|---|---|---|---|---|
| constructor | 3 | Si 50, Be 75, W 40 | 150 | `buildSpeed 0.6`, block size 1–2, **filter list only**: tungsten-wall-large, beryllium-wall-large, carbide-wall-large, reinforced-surge-wall-large, reinforced-liquid-container, reinforced-container, beam-node. Time = block.buildTime / 0.6. Consumes the block's requirements; buffers up to 2× of each |
| large-constructor | 5 | Si 150, oxide 100, W 200, Th 80 | 300 | `buildSpeed 5`, any visible non-core block of size 3–4 |
| small-deconstructor | 3 | Be 70, Si 70, oxide 25, graphite 50 | 60 | payload ≤ 3; deconstructSpeed 3 → time = buildTime / 3; **refunds 100 %** of requirements (× buildCostMultiplier rule / unitCost); itemCapacity 100 per item |
| deconstructor | 5 | Be 250, oxide 100, Si 250, carbide 50 | 180 | deconstructSpeed 6, capacity 320 |

Constructor times:
- tungsten-wall-large: buildTime 24 × 1.5 × 5 = 180 t → 300 t = 5 s, 24 W.
- beryllium-wall-large: 144 t → 4 s, 24 Be.
- carbide-wall-large: 60 t → 1.67 s, 24 Th + 24 carbide.

---

## 7. Unit production

### 7.1 Factories and refabricators

`UnitFactory`/`Reconstructor` item capacity = 2 × each requirement. A refabricator accepts a unit payload from any side **except its front**. Times are at 100 % efficiency and `unitBuildSpeed 1`.

| Block | Size | Cost | Power /s | Liquid | Recipe | Time |
|---|---|---|---|---|---|---|
| tank-fabricator | 3 | Si 200, Be 150 | 90 | – | stell ← Be 40, Si 50 | 35 s |
| ship-fabricator | 3 | Si 250, Be 200 | 90 | – | elude ← graphite 50, Si 70 | 40 s |
| mech-fabricator | 3 | Si 200, Be 250, W 10 | 90 | – | merui ← Be 50, Si 70 | 40 s |
| tank-refabricator | 3 | Be 200, W 80, Si 100 | 180 | hydrogen 3/s | stell → locus, + Si 40, W 30 | 30 s |
| ship-refabricator | 3 | Be 250, W 120, Si 150, oxide 15 | 150 | hydrogen 3/s | elude → avert, + Si 60, W 40 | 50 s |
| mech-refabricator | 3 | Be 250, W 175, Si 150 | 150 | hydrogen 3/s | merui → cleroi, + Si 50, W 40 | 45 s |
| prime-refabricator | 5 | Th 250, oxide 200, W 200, Si 400 | 270 | **nitrogen 10/s** | locus→precept, cleroi→anthicus, avert→obviate, + Th 80, Si 100 | 60 s |

### 7.2 Assemblers (`UnitAssembler`) and module

| Block | Size | Cost | Power /s | Liquid (only while building) | Tier 0 plan | Tier 1 plan (needs 1 module) |
|---|---|---|---|---|---|---|
| tank-assembler | 5 | Th 500, oxide 150, carbide 80, Si 650 | 150 | cyanogen 9/s | vanquish ← 4 stell + 10 tungsten-wall-large, 50 s | conquer ← 6 locus + 20 carbide-wall-large, 180 s |
| ship-assembler | 5 | carbide 100, oxide 200, W 550, Si 900, Th 400 | 150 | cyanogen 12/s | quell ← 4 elude + 12 beryllium-wall-large, 60 s | disrupt ← 6 avert + 20 carbide-wall-large, 180 s |
| mech-assembler | 5 | carbide 200, Th 600, oxide 200, W 550, Si 1000 | 180 | cyanogen 12/s | tecta ← 5 merui + 12 tungsten-wall-large, 70 s | collaris ← 6 cleroi + 20 carbide-wall-large, 180 s |
| basic-assembler-module | 5 | carbide 300, Th 500, oxide 250, phase 400 | 210 | – | tier 1 | |

Assembler mechanics:
- **Tier.** `currentTier` = highest module tier reachable with no gaps, counting from 0. The basic module is tier 1.
  - The plan used is `plans[min(currentTier, plans.size−1)]`. With 0 modules the assembler can **only** build the T4; with ≥ 1 basic module it can **only** build the T5.
  - Changing tier resets progress.
  - A module must be placed on the edge of the assembler's build area (`moduleFits`).
- **Build area.** `areaSize = 13`: a 13×13 square in front of the assembler. Overlapping areas can't be placed. If the spawn area is blocked by solid units (`wasOccupied`), production pauses and liquid isn't consumed.
- **Drones.** It spawns 4 assembly drones, 240 t each (power-scaled). Progress rate is multiplied by (drones in position / 4).
- **Inputs are payloads**, not items: units (from fabricators/refabricators via payload conveyors or mass drivers) and wall blocks (from a constructor). They enter from any side and are counted up to the required amount (× `rules.unitCost`).
- **Full raw cost** (`UnitType.getRequirements` summing payload costs):
  - vanquish: Be 160, Si 200, W 240
  - conquer: Be 240, Si 540, W 180, Th 480, carbide 480
  - quell: graphite 200, Si 280, Be 288
  - disrupt: graphite 300, Si 780, W 240, Th 480, carbide 480
  - tecta: Be 250, Si 350, W 288
  - collaris: Be 300, Si 720, W 240, Th 480, carbide 480
- **unit-repair-tower** (2×2, graphite 90, Si 90, W 80): range 100 (12.5 tiles), heals 1.5 hp/tick (90 hp/s) to each damaged unit in range, 60 power/s, ozone 3/s.

### 7.3 Unit stats (UnitTypes.java)

Speed is in world units per tick; tiles/s = speed × 7.5.

"Main weapon" figures already include the 0.75 rebalance on the top-level bullet. Splash, frag and missile-explosion damage are **not** reduced.

Two rules for counting shots:
- Weapons default to `mirror = true` (2 mounts). With `alternate = true` (default) the pair fires 2 shots per `reload`, staggered. With `alternate = false` both fire together.
- **Rough DPS** = shots/s × (direct + splash) against a single target. It is an upper bound, not a simulation.

| Unit | Tier | Type | Health | Armor | Speed (tiles/s) | hitSize | Main weapon (per shot, reload) | Range | Rough DPS | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| stell | T1 | tank | 850 | 6 | 5.6 | 12 | 30, 50 t, 1 gun | 160 (20 t) | 36 | |
| locus | T2 | tank | 2100 | 8 | 5.25 | 18 | rail 36 (pierce), 18 t | 160 | 120 | |
| precept | T3 | tank | 5000 | 11 | 4.8 | 24 | 90 + 50 splash + 4 frags × 35, 80 t | ~196 | ~105 (+frags) | |
| vanquish | T4 | tank | 11000 | 20 | 4.7 | 28 | 112.5 + 50 splash + 5 frags (35 + 20 splash), 80 t; plus 2 mirrored point guns 37.5/shot at 22 t and 27 t | 190 | ~120 main + ~370 point guns | crush 2.6 |
| conquer | T5 | tank | 22000 | 26 | 3.6 | 46 | 270 (pierce 3), 100 t + 12 spawned bullets (60 + 65 splash) | 280 | 162 main + spawn bullets (up to ~900) | crush 5 |
| merui | T1 | legs (walks over blocks), hover | 680 | 4 | 5.4 | 9 | artillery 30 splash, 63 t | 138 | ~29 | **ground only** |
| cleroi | T2 | legs | 1100 | 5 | 4.5 | 14 | 22.5 + 43 splash, 33 t, pair fires together | 140 | ~238 | + point-defense gun |
| anthicus | T3 | legs | 2700 | 7 | 4.9 | 21 | 2 missiles per mount (140 splash, r 25), mirrored, 130 t | ~334 | ~258 | missile units (hp 55) |
| tecta | T4 | legs | 6500 | 5 | 4.5 | 30 | 3 homing missiles × 38.25 + 60 splash per mount, mirrored, 40 t | ~231 | ≤ ~880 (theoretical) | front shield arc 2500 hp, regen 45/s |
| collaris | T5 | legs | 18000 | 9 | 8.25 | 44 | artillery 195 + 120 splash + 15 frags (37 + 35 splash), mirrored, 130 t | 385 | ~291 + frags | **ground only** |
| elude | T1 | hover (ElevationMove) | 600 | 1 | 13.5 | 11 | 2 × 12 homing per mount, mirrored, 40 t | 150 | 72 | crosses liquids, is a ground target |
| avert | T2 | flying | 1100 | 3 | 15 | 12 | 2 × 22 (helix), 35 t, + 2 frags × 10 | 90 | 75 | building damage ×0.6 |
| obviate | T3 | flying | 2300 | 6 | 13.5 | 25 | 2 × 56.25, 140 t + lightning 16 dmg every 4 t per orb (no air) | 180 | ~48 + ~200 lightning | |
| quell | T4 | flying, payload 4×4 | 6000 | 4 | 8.25 | 36 | missile → 110 splash, mirrored pair, 55 t | 361 | ~240 | **ground only**; suppression field |
| disrupt | T5 | flying, payload 6×6 | 12000 | 9 | 7.5 | 46 | 3 missiles (140 splash) per mount, mirrored, 70 t | ~469 (missile 4.6 × default lifetime 102) | ~720 | **ground only**; 3 suppression fields (range 320) |

All legged Erekir mechs have `allowLegStep = true` (`UnitType.init`: any `Legsc`), so they path over buildings and walls (`Pathfinder.costLegs`).

---

## 8. Turrets

Coolant (water only, optional booster): extra reload per tick = 0.4 (water heatCapacity) × coolant amount per tick × `coolantMultiplier` (`ReloadTurret.updateCooling`). Range is in world units (÷ 8 = tiles). Item ammo: each item gives `ammoMultiplier` ammo (default **2**). A salvo uses `ammoPerShot` (once per salvo; `consumeAmmoOnce` defaults to true). Damage includes the 0.75 rebalance where it applies. All are `targetAir = targetGround = true` unless stated.

| Turret | Size | Cost | Range | Reload | Ammo (items per salvo → per-bullet damage) | Power / heat / other | Coolant (water) | Targets |
|---|---|---|---|---|---|---|---|---|
| breach | 3 | Be 150, Si 150, graphite 125 | 190 (23.75 t) | 40 t | **Be** 2/shot → 63.75 pierce 2; **W** 1/shot → 71.25 pierce 4, +40 range; **carbide** 1/shot → 325, ×0.2 reload, +56 range, 3 frags × 227 | buildTime fixed 9 s | 15/s → reload ×2.5 | air + ground |
| diffuse | 3 | Be 150, Si 200, graphite 200, W 50 | 125 | 30 t | 15-pellet spread: **graphite** 3 items → 30.75 each; **oxide** 1.5 → 67.5; **silicon** 3 → 26.25 homing | maxAmmo 30 | 15/s → ×2.5 | air + ground |
| sublimate | 3 | W 150, Si 200, oxide 40, Be 400 | 130 (16 t) | continuous | liquid 18/s: **ozone** (eff. 15/s, ammoMult 1.2) 45 per 5 t = 540 dps, pierce 2; **cyanogen** 18/s → 97.5 per 5 t = 1170 dps, +70 range | – | – | air + ground |
| titan | 4 | W 250, Si 300, Th 400 | 390 (48.75 t) | 138 t | 4 items/shot: **Th** 262.5 + 350 splash r 65; **carbide** 525 + 750 splash r 36, ×0.8 reload, +80 range, 12 frags; **oxide** 225 + 180 splash r 110, ×0.7, lingering 15 dmg field 2.5 s | **hydrogen 5/s required** | 30/s → ×1.75 | **ground only** |
| disperse | 4 | Th 50, oxide 50, Si 200, Be 350 | 310 (38.75 t) | 9 t, 4 bullets/salvo | per salvo: **W** 1/3 item → 48.75 each; **Th** 1/2 → 67.5, pierce 2, −120 range, ×0.85; **Si** 1/4 → 27.75 homing; **surge** 1/3 → 48.75 + interval bullets, ×0.75 | – | 20/s → ×1.83 | **air only** |
| afflict | 4 | surge 100, Si 200, graphite 250, oxide 40 | 368 (46 t) | 50 t | power turret: 180 (not reduced) + 20 frags × 35 + interval bullets | **300 power/s, heat 20** (maxHeatEff 1) | – | air + ground |
| lustre | 4 | Si 250, graphite 200, oxide 50, carbide 90 | 250 (31 t) | continuous | point laser 157.5 per 5 t = 1890 dps single target | **200 power/s + nitrogen 6/s** | – | air + ground (targets strongest) |
| scathe | 4 | Si 450, graphite 400, W 500, oxide 100, carbide 200 | **1350 (168.75 t)** | 600 t | missile per 15 ammo: **carbide** 15 items → explosion 1000 splash r 65 (+7 × 100 splash); **phase** 3 items (mult 5) → 320 splash r 120, missile has 3000-hp force field, ×0.8; **surge** 15 items → 1800 splash r 40 + 5 split missiles × 180, ×0.9 | buildings ×0.1 | 15/s → ×2.5 | **ground only** |
| smite | 5 | oxide 200, surge 400, Si 800, carbide 500, phase 300 | 300 (37.5 t) | 100 t | **surge** 2 items/salvo → 10 bullets (5 barrels × helix pair) × 187.5, pierce 4, + lightning 30 every 3 t | minWarmup 0.99 | 15/s → ×2.5 | air + ground |
| malign | 5 | carbide 200, Be 1000, Si 500, graphite 500, phase 200 | 410 (51 t) | 3.5 t | power turret: homing flak 70 (not reduced) + lightning 18 / laser frags 65 | **2400 power/s, heat 144** (maxHeatEff 1) | – | air + ground |

Malign needs 144 heat. That is 10 phase heaters (150), or 18 slag heaters, or 48 electric heaters (4800 power/s), delivered through redirectors.

---

## 9. Resources on Erekir

`Items.erekirItems` = graphite, thorium, silicon, phase fabric, surge alloy, sand, beryllium, tungsten, oxide, carbide (+ hidden fissile matter, dormant cyst).

| Resource | Source |
|---|---|
| beryllium | floor ore `ore-beryllium` (impact/eruption drill, ×2 speed), wall ore `ore-wall-beryllium` (plasma bores) |
| tungsten | floor `ore-tungsten` (impact/eruption), wall `ore-wall-tungsten` (large plasma bore only; tier 5) |
| thorium | floor `ore-crystal-thorium` (**eruption drill only**; impact drill blocks thorium), wall `ore-wall-thorium` (large plasma bore) |
| graphite | `graphitic-wall` (wall block with itemDrop) and `ore-wall-graphite` (plasma bore). No floor graphite |
| sand | cliff crushers (walls with sand attribute); sand/darksand floors (burst drills; `lowPriority`). **Incinerated by Erekir cores** |
| silicon | silicon arc furnace (graphite + sand) |
| oxide | oxidation chamber (Be + ozone) |
| carbide | carbide crucible (W + graphite + heat 40) |
| surge alloy | surge crucible (Si + slag + heat 40) |
| phase fabric | phase synthesizer (Th + sand + ozone + heat 32) |
| water | vent condenser (30/s on vents), turbine condenser by-product (5/s), pyrolysis by-product (20/s), reinforced pump on water floors |
| slag | reinforced pump on `molten-slag` floor (liquidMultiplier 1 → 80/s per pump). No craftable source (slag centrifuge is debug-only and consumes slag) |
| arkycite | reinforced pump on `arkycite-floor` |
| ozone | electrolyzer (4/s each) |
| hydrogen | electrolyzer (6/s each) |
| nitrogen | atmospheric concentrator (16/s, heat 24) |
| cyanogen | cyanogen synthesizer (12/s) |
| neoplasm | neoplasia reactor only (20/s, a waste product that must be removed) |

Liquid hazards: ozone explosiveness 1 / flammability 1, hydrogen flammability 1, cyanogen flammability 2, arkycite flammability 0.4 (`Liquids.java`). Neoplasm spreads (`CellLiquid`), moves through blocks and is not incinerable.

---

## 10. Production chains (one machine at 100 %, per second)

Electrolyzer unit E = water 10 → ozone 4 + H₂ 6, 60 power.

| Product | Machine output | Direct inputs | Upstream to supply them | Total power (approx.) | Notes |
|---|---|---|---|---|---|
| **oxide** | 0.5/s (+5 heat) | Be 0.5, ozone 2, 30 power | 0.5 E (water 5, gives H₂ 3 spare) | 60 | heat is a free by-product but stops if oxide backs up |
| **nitrogen** | 16/s | heat 24, 120 power | 3 slag heaters (120 slag/s = 1.5 pumps, H₂ 2.25) or 8 electric heaters (+800 power) | 120 (+800 if electric) | |
| **cyanogen** | 12/s | arkycite 160, graphite 3, heat 20, 120 power | 2 pumps on arkycite (H₂ 3); graphite 3/s = 4 plasma bores (boosted 1.875 each = 1.6 bores); heat: 3 slag heaters (24) or 7 electric | 120 + 36 (bores) + heat | |
| **carbide** | 1.778/s | W 3.556, graphite 5.333, heat 40, 120 power | W: 2 large plasma bores unboosted (1.8 each) or 1 boosted (4.5, N₂ 3); graphite: 3 boosted plasma bores; heat 40: 5 slag heaters, or 3 phase heaters (45, 0.375 phase/s), or ~14 electric heaters (1333 power) via redirectors | 120 + drills + heat | per carbide: 2 W + 3 graphite |
| **surge alloy** | 1.333/s | Si 4, slag 160, heat 40, 90 power | Si: 0.833 arc furnace (graphite 1, sand 4, 250 power); slag 160 = 2 reinforced pumps (H₂ 3 = 0.5 E); heat 40 (as above; slag heaters add 40 slag/s each) | ~370 + heat | per surge: 3 Si + 120 slag |
| **phase fabric** | **2/s** | Th 4, sand 12, ozone 8, heat 32, 480 power | Th: 3 large plasma bores (1.8) or 1 boosted (4.5), or 0.75 eruption drill on crystal thorium (25 tiles 5.33/s); sand 12: e.g. 4 large cliff crushers (3 tiles attr 1, unboosted 3.75/s); ozone 8 = 2 E (water 20 → 1 vent condenser); heat 32: 4 slag heaters or 3 phase heaters (self-consumption 0.375/s ≈ 19 % of output) | 480 + 120 (E) + drills | per phase: 2 Th + 6 sand + 4 ozone |
| **thorium** | 1.8/s (large bore, 3 Th wall tiles), 4.5/s with N₂ 3/s | H₂ 0.5/s, 48 power | 1/12 E | ~53 | eruption: 5.33/s (10.67 with cyanogen 0.75/s), H₂ 4/s, 360 power |
| **silicon** | 4.8/s | graphite 1.2, sand 4.8, 300 power | ~1 boosted plasma bore of graphite (1.875), 1–2 cliff crushers | 300+ | |

Power references: turbine condenser 180, CCC 550 (ozone 2 = 0.5 E + arkycite 40), pyrolysis 1400 (slag 20 + arkycite 40; gives water 20), flux up to 18000 (cyanogen 9 = 0.75 synthesizer + heat 150), neoplasia 8400 (arkycite 80, water 10, phase 0.333; plus 60 heat).

---

## Things that differ from common belief (source-verified)

1. **Extra heat does nothing** for any Erekir crafter, afflict or malign (`maxEfficiency`/`maxHeatEfficiency = 1`). The HeatCrafter class default of 400 % is overridden in every Erekir crafter.
2. **Heat is not split between consumers.** Each consumer reads the source independently; the limit is contact length. Only heat **routers** divide by 3, and they waste the share of empty sides. A 3×3 source into a 2×2 consumer loses 1/3.
3. **The oxidation chamber stops heating when its oxide output is full.**
4. **Surge conveyors only accept items at the first segment, only one item type, and only send full stacks of 10.** Unpowered, they still run at half speed (`baseEfficiency = 1`). The surge router also waits for a full 10.
5. **Erekir cores can't be unloaded** (the duct unloader has `allowCoreUnload = false`), and **sand put into an Erekir core is deleted** (`incinerateNonBuildable`, sand `buildable = false`).
6. **Beam drill speed ignores hardness.** The plasma bore can't mine tungsten or thorium at all (tier 3). The impact drill can't mine thorium. Burst-drill liquid boosts are linear (×1.75 / ×2), while beam-drill boosts are ×2.5.
7. **The 0.75 damage nerf skips PowerTurrets** (afflict, malign), splash, frags and missiles, but **does hit tank units and lustre**.
8. **Assemblers build only one unit type per tier.** With a module, a tank assembler can no longer make vanquish. Inputs are **payloads** (units + constructor-built walls), not items. The cyanogen is consumed only while actually building.
9. **The unit cargo loader uses nitrogen only while building its drone.** Unload points with no item configured never receive anything, and a drone with no valid destination deletes its cargo.
10. The **flux reactor** fails 3 s after its cyanogen runs out while it has heat. The **neoplasia reactor** destroys itself 4 s after its neoplasm output backs up.
11. The **phase synthesizer** makes 2 phase/s (craft time 30 t): far faster than the Serpulo phase weaver, but it needs 12 sand/s and 4 thorium/s.
