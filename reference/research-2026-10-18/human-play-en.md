# How humans play Erekir: macro decisions, layout, attacks, sectors, mistakes

Research date 2026-10-18. Target: Mindustry v8, build 160.7. This report follows on from `reference/meta-report.md` (sections 1-3: recipes, ratios, turret roles, counters, tech order). It does not repeat that material. It covers how players decide what to do.

## 0. How to read this

**Tags.** Each claim carries one of these:
- **[Q]** the source says it, quoted or closely paraphrased. Steam and Fandom quotes are verbatim except where cut (marked …).
- **[I]** my own inference or synthesis.

**Version timeline.** Use it to date each source.
- Erekir alpha was build 136 (Jul 2022). v7 was builds 140-146 (Nov 2022 to 2024).
- The v8 beta started at build 147 (Apr 2025). It added "New ammo types for Erekir turrets", difficulty modifiers, unit stances and a new pathfinder (https://newreleases.io/project/github/Anuken/Mindustry/release/v147).
- v8 stable is build 157.1, released about 2026-04-14. Its headline item was "A complete overhaul of the Serpulo campaign maps". Erekir got only turret ammo and balance changes (https://newreleases.io/project/github/Anuken/Mindustry/release/v157.1).
- The heat rebase came in build 156 (see meta-report §1.1).
- **[I] So the Erekir sector maps are essentially the v7 maps.** Most campaign advice from 2022-2025 still applies to sequence and strategy, but not to exact numbers. One supporting data point: a 2026 comment under the 2022 Basin guide says "It keep working on V8" (YouTube Y9b3ov0yFK8 comments, about 5 months ago).
- **[Q]** One player claims the Erekir sectors are "getting reworked, like literally all of them in V9" (KarinFysh, Steam, 2026-04-10, https://steamcommunity.com/app/1127400/discussions/0/3810657379793594901/). I could not verify this.

**Source shorthand.**
- `FW/X` = `https://mindustry-unofficial.fandom.com/wiki/X`, raw wikitext read through api.php; the last-edit date is given.
- `FWC/X` = article comments on that page (`wikia.php?controller=ArticleCommentsController`), dated per comment.
- `ST/<id>` = `https://steamcommunity.com/app/1127400/discussions/0/<id>/`.
- `YT/<id>` = `https://www.youtube.com/watch?v=<id>`. Descriptions and comments were read through the innertube `next` endpoint; transcripts were blocked.
- `SRC` = speedrun.com API (`/api/v1/runs?game=3dx2z7p1`).

---

## 1. Development flow and macro decisions

### 1.1 The overall loop players describe

- **[Q]** The macro shape of every Erekir sector, in a veteran's words: "all sectors boil down to build your base and turtle up with defenses just strong enough to withstand whatever the enemy will throw at you and wait like 3 hours until you amass an anthicus/locus combo murder ball and beat the entire sector in like, a single 5 minute long steamroll zerg rush." He adds: "Erekir kinda has just one answer for every sector… It boils down to if you know what resource to get first, what unit to rush, and where to rush." (KarinFysh, ST/592900478058115306, 2025-06-21)
- **[Q]** The same pattern, from a Basin answer marked as the solution: "typical Erekir gameplay of waiting for an army to build behind walls, protected by turrets, then trying to bombrush the main base." (RiptheBlood, ST/601894472784113557, 2025-01-27)
- **[Q]** From a speedrunner's Stronghold comment: "don't pay attention to ramping, it's there to make you stressed, on most maps focus on core defense and production because then you can outeco the enemy, the ramping always stops after a while but when you have everything established and well defended you can shift all of your focus into creating an unstoppable army (this is how I beat Siege)" (YT/RoUAMK8-Seo comment, about 2023)
- **[Q]** The opposite school argues for tempo: "Neutralize enemy spawners by attacking their respective base **before** the production escalation of the unit begins" (FW/Attack, 2025-08-25). The Origin ship strategy says to destroy the north base "before the first timer" (FW/Origin, 2026-06-05).
- **[I] Synthesis.** Human play runs in three phases:
  1. A fast economy and a minimal defence that units back up.
  2. A long build-up behind a stable defence until the unit cap is full.
  3. One decisive, concentrated push, then consolidation: claim the cores, garrison them, rebuild.

  Early harassment of the enemy's unit production is used only when a cheap target exists, such as an exposed factory or an undefended core.

### 1.2 Opening: economy first, units before turrets

- **[Q]** "just build a tank factory AS SOON as you spawn in, since both beryllium and silicon are very close to each other on almost every map right next to your core." (KarinFysh, ST/3810657379793594901, 2026-04-10)
- **[Q]** The "battery era" trick: "spam the tiny beam nodes somewhere in the corner, where you will stockpile on power excess power while you tinker with your base… once you're done with your design, you can run like, 3 silicon furnaces for X amount of time to have a HUGE jumpstart. I use this tech to maximize unit production." (same post) [I] Power is vent-capped, so idle power early on is a stockpile, not waste.
- **[Q]** "There's a reason that the Erekir tutorial teaches you how to build units before it teaches you how to build towers… Maintaining a responsive, mobile force of units lets you mop up problematic waves anywhere on the map." (Mind Gardener, ST/592895726921500459, 2025-06-05)
- **[Q]** "Early game turrets on erekir also feel like a bit of a noob trap/something you should only build to defend against harass while most of your production should go into units." (Underscorecow, ST/5562542086334775971, 2023-01-19, PvP context)
- **[Q]** A counterpoint: "My defence on both basin and intersect consists only of breach turrets fed by beryllium. 5-6 on intersect and 10-12 on basin" (MefistoSV, ST/3810657379793594901, 2024-02-11). The reply: "theres basically zero way to produce enough turrets on intersect… the resource production rate is too … low" (JerryTheCultist, same thread, 2026-04-09). [I] Turret-only defence works only on the wave-light early maps. Even there it competes with units for the same beryllium and silicon.
- **[Q]** Order of economic expansion in a verified Karst build (b146): "Start by increasing power, beryllium, silicon, and tungsten production in this order." (FW/Karst/Strategies, 2025-05-31)
- **[Q]** An old wiki note, hidden in the Erekir page source: "get some basic resources up and running then placing turrets where the enemies are most likely to come, then if you have enough resources, venture into units. It is always advised to keep your unit production line separate from basic resource production line (… place another [silicon furnace] for unit production so that you have enough silicon)." (FW/Erekir, 2026-10-02, inside an HTML comment, so dropped from the visible page) [I] Even so, the rule "dedicated furnace per production cell" is sound.

### 1.3 Power is the binding constraint

- **[Q]** Pre-oxide: "Every vent that you have access to should be occupied with a Turbine Condenser. While it is tempting to replace some with the Vent Condenser for extra Water, you will be set back… It is much safer to use the trace quantities of Water obtained from Turbine Condensers to operate blocks like the Impact Drill." (FW/Power, 2025-08-28)
- **[Q]** Pre-carbide, once oxide allows Chemical Combustion Chambers: "you are free to replace all of your Turbine Condensers with Vent Condensers as long as you have the Arkycite… Electric Heaters should be avoided in favour of the Slag Heater or residual heat from the Oxidation Chamber." Also: "One Vent Condenser has enough water to run three Electrolyzers, each of which can run two Chemical Combustion Chambers for a total of six." (FW/Power)
- **[Q]** Later stages: the Pyrolysis Generator plus an Electrolyzer on its water "should supply everything except running Phase Fabric-level blocks"; "The Flux Reactor is inefficient for this stage of the game - do not use it and spend your Cyanogen on units instead." (FW/Power)
- **[Q]** A Peaks power answer: "a single vent condenser can supply 3 electrolyzers, and those can fuel 6 chemical combustion chambers. 3600 power should be more than enough… Just remember to connect your 2 bases with a beam tower." (Venorik Barra, ST/5738190785824510361, 2023-04-14)
- **[Q]** Caldera's enemy base contains a 6x12 block of 6 CCC, 3 Electrolyzers and a Vent Condenser. The wiki says it "should be copied for future use" (FW/Caldera, 2026-04-02). A video comment with 156 likes: "literally everyone independently came to the same conclusion to copy that energy schematic" (YT/ZAsHOh_uadA).
- **[Q]** Brownout pain: "as soon as the power runs out our containers stop unloading… We've been at such a power deficit for the mission that we can't set up the production needed to start power production." (Kenuda, ST/3788128816418203722, 2023-03-12). Another: "you basically have to [explore for vents] or your base will be in perpetual brownout" (JaxterSmith, ST/4360121202644066132, 2024-04-17).
- **[I] Rule.** Grab every vent, hold back the Vent Condenser and Electric Heater until combustion power exists, and treat "first CCC/Pyrolysis online" as the gate for heavy industry such as extra furnaces, heaters and refabricators.

### 1.4 How many factories, and when to stop expanding mining

Nobody I found states a fixed fabricator-per-furnace practice beyond the wiki ratio (1 furnace : about 3 T1 fabricators, meta-report §1.5). What players report building:

| Context | Production reported [Q] | Source |
|---|---|---|
| Basin, before triggering the nuke | "Build a whole bunch of tank factories (I built 4, but you might want more) in the NW where they won't get nuked" | Gnord, ST/4136060426843421871, 2024-01-20 |
| Basin, slow strategy | ~15 Stells, then attack; later "Wait until you have 45 Cleroi, 45 Locuses, and at least 4k of Beryllium, Silicon, Graphite, and Tungsten" | FW/Basin (strategy 2, says "may only work in build 146") |
| Basin, 2026 | "I had 40 (not 45) of each T2 units… My forces simply steamrolled the map" | xBleacheDx…, ST/601894472784113557, 2026-07-13 |
| Siege | "Created 2 locus and 2 stell factories (~2h)… creating 2 Precept factories" | PinkKitty100, FWC/Siege, 2024-10-24 |
| Crossroads, Averts only | "Place and run 2 Silicon Arc Furnaces, Ship Fabricators and Refabricators"; attack at about 12-15 Averts | FW/Crossroads/Strategies, 2025-07-19 |
| Origin, ships | "Build two Ship Fabricators and wait until there are around a dozen Eludes"; "Once you have a dozen Quells, switch to or add Disrupt" | FW/Origin |
| Hex PvP | "rush eco & power early on… then build around 9 elude factories and 4 locus factories" | YT/FJGx-V4umt8 comment, about 2023 |
| Hex PvP, top player | "6x avert fac … build", "a high eco build that expects a opponent to not show up within the first 15 minutes" | YT/BgFxeaDIuhI comment by a featured player, 2023 |
| Stronghold farm | "Once you have sufficient T3 (30 Anthicus + 30 Obviate) to take out all 8 cores, then destroy the 1st bastion core" | YT/vBCt_7qVgWg comment, 29 likes |
| Origin, endgame | ship assembler, "A group of around 20 [Quells] is enough to completely negate a Malign's bullets" | FW/Origin |

- **[Q]** Each Erekir core adds 15 to the unit cap, and upgrading does not add more (FW/Core:_Bastion). Caldera gives +5 and Origin +15 by map rule (FW/Caldera, FW/Origin). Players say "max out units", "till max cap" and "full unit cap of Locus and Cleroi" (FWC/Basin 2026-04-07; YT/ZP6kdroYSJw comment). [I] **The unit cap, not a resource level, is the most common trigger for "now attack".** Players claim extra cores mainly to raise it: "A second core was claimed on the Obviate base to keep Avert production continuing by raising the cap" (FW/Crossroads/Strategies).
- **[Q]** When to stop mining: "Place defenses first. If you didn't have enough resources, you need to upgrade your drills." (FW/Crossroads/Strategies, WantechFan). Ravine: "two Oxidation Chambers and one Electrolyzer… resulting in a number of boosted bores to fix beryllium spending" (FW/Ravine, 2026-03-16). [I] Players do not stop expanding mining. They stop adding new extraction sites once the unit factories run continuously, then upgrade the existing sites (hydrogen boost, Large Plasma Bore, Eruption Drill) rather than spread out.

### 1.5 Teching up vs massing low tiers

- **[Q]** "Unit tiers are significantly less of an improvement, and mass-producing tier1 trash is viable surprisingly late in the game, fighting much higher tier units." (Gnord's review, ST/4042609216213209006, 2024-01-01) Also: "Malign can annihilate tier 5 enemys but fails to kill 80 small tonks" (YT/MUpuKWYANo0 top comment, 143 likes, 2022).
- **[Q]** PvP (v140, Nov 2022): "Currently Eludes and Locus are super overpowered… being the only thing you need to win" and "Eludes and Locus still the only things you need." (Larofeticus descriptions, YT/VFzbQwy9soo, YT/FJGx-V4umt8). His 2022 guide is marked "only has value as an historical artifact" (YT/YDKLuA170_U).
- **[Q]** Speedrun tech choices (Larofeticus, v141, early 2023):
  - Stronghold under 30 min with "no need to use any of the thorium research" (a commenter's summary, YT/iizNPSnqgOU).
  - Crevice: "rush surge, built afflicts, and ignore carbide" (YT/iizNPSnqgOU description).
  - Karst: "spamming cyanogen sublimates at four locations" (YT/3dxR-XQeqPo).
  - Crossroads: Quell beats Avert and Obviate (YT/hmYfHd-ijIk; SRC run 17.07 min).
- **[Q]** Siege, skipping a tier: the "Vanquish Rush… immediately build both the Cyanogen Synthesizer and Tank Assembler before securing any thorium… Since the Vanquish itself requires no thorium… you can reliably produce them from the start" (FW/Siege, 2026-05-25). Players confirm: "Vanquish rush works well can confirm" (FWC/Siege, 2026-06-30).
- **[Q]** Tech pays off when it answers a specific threat. In Stronghold: "Avoid being caught unprepared when the Obviates arrive. Boost any bores mining thorium if needed to quickly research the Disperse" (FW/Stronghold). In Crossroads: "tech up to surge alloy before that time to defend against their [Obviate] attacks with the Afflict" (FW/Crossroads).
- **[I] Decision rule players follow.** Tech up when one of these holds:
  - (a) the next tier unlocks a hard counter to a threat you see now (Disperse or Afflict against air, Afflict against missiles);
  - (b) a tier gives range or armour that outclasses the enemy's current turrets (Locus range > Sublimate; Quell 44-block range);
  - (c) the economy is saturated at the current tier and the cap is full.

  Otherwise keep massing the cheapest unit that wins.

### 1.6 Defence sizing and where turrets go

- **[Q]** Classic placement: at the choke nearest your core, "3 water-cooled Breaches and Large Beryllium Walls" (FW/Basin); "at least a Breach and a Diffuse" (FW/Intersect).
- **[Q]** "on erekir it's not really the amount of your turrets, but the placement really and if they are cooled… also worth to build ammo buffers… you can use duct bridges, since they have an internal storage of 4 items." (KarinFysh, ST/3810657379793594901)
- **[Q]** Later and harder maps:
  - "4 Sublimates at choke points with tungsten walls and regen projectors can hold back basically everything on that map" (Siege; Venorik Barra, ST/3810656958854938509, 2023-09-03).
  - "Each barricade requires a triple-layer of Large Tungsten Walls"; "Four Disperses with water cooling will handle all ships from that spawn point"; "place Build Towers first" (FW/Karst/Strategies).
  - "Bases can be easily defended with a single afflict and a slag heater" (FW/Caldera).
  - "your entire base should receive coverage from at least two [Disperses] at any point" (FW/Crevice).
  - "1 200% afflict can easily beat 15 (uncoordinated) anthicus" (FWC/Afflict, 2024-02-23, pre-v156 heat scale).
- **[Q]** The generic non-cheese formula, from a Stronghold comment: "build T2 air units just to defend against air units, and clog chokepoints with ground units or Afflict, then attack with lots of tank + mech, ideally at the unit cap." (YT/vBCt_7qVgWg comment, about 2024)

### 1.7 Reacting to being attacked mid-expansion

- **[Q]** "Enemy Elude units love to take out power or go straight to your core, so wheverever you find them targetting, leave some units as a patrol and build defenses." Also "leave a couple small convoys of 2-3 mechs scattered throughout the base for the random enemy elude swarms." (RiptheBlood, ST/601894472784113557)
- **[Q]** "Erekir mechs are weak to tank fire, so keep a few tanks next to your mining platforms… keep a few tanks next to your core, or near any other vulnerable position." (Mind Gardener, ST/592895726921500459). Also: "build a small cache of hovercraft to kill anything that slips by your defensive line… also useful for scouting" (Bookslayer10, same thread, 2025-07-24).
- **[Q]** Air-unit raids arrive "in unexpected locations, which speaks to the necessity of having a bunch of units on defense instead of only relying on turrets" (FW/Avert, per meta-report). Players complain: "Enemy forces that instantly know the gaps in your defences and will slip in to … up your economy" (Ekimmak, ST/4360121202644066132, 2024-04-01). Also: "Air units tend to spawn in really inconvenient, unlabeled places, and then approach your base in unpredictable ways" (Gnord review).
- **[Q]** After a big hit: "Once the nuke goes off, try to rebuild defenses and power first, then decide if you need/want the rebuild the factory." (RiptheBlood). "When nuked, rebuild immediately. Get a defensive wall in the narrow choke point… Then immediately rebuild your resource gathering." (Gnord, ST/4136060426843421871)
- **[Q]** Repair is manual and slow early. "I have to manually repair every time an enemy comes"; "there is a cooldown that the buildings have when they received damage not long ago" (ST/4763207947397356673, 2024-09). The fixes players use are the Regen Projector, Build Towers ("place Build Towers first", FW/Karst/Strategies) and "Hold 'b'… to rebuild everything in an area" (ST/4360121202644066132).
- **[I] Priority order on any interruption.**
  1. Kill the raiders with the mobile reserve.
  2. Restore power links.
  3. Restore ammo and resource lines.
  4. Restore production.
  5. Add a permanent defence at the breach point; air needs a Disperse or Afflict near the economy.

### 1.8 Scouting and planning

- **[Q]** "The scariest part of this planet is not knowing where things are because of the fog of war. take that away and everything feels a lot easier" (YT/iizNPSnqgOU, 50 likes). Another: "the first campaign mission where I immediately built a unit and scouted the whole map in the first few minutes, and it was way easier as a result… picking better choke points" (YT/3dxR-XQeqPo comment).
- **[Q]** "I found myself rushing t2 air at the start just to get a flyer that can reveal the map." (YT/iCnSjUlYVW4 comment). Radars: "can be deconstructed to 'relocate' it, meaning you only have to keep one active" (FW/Radar, 2025-08-01). Scathe as "an expensive radar" (Steam guide 2968050682, 2023-04-30).
- **[Q]** Many players' first attempt at a sector is a scouting loss: "I just got into the habit of accepting the fact that I was likely going to have to use my first attempt to explore… then… come back on the 2nd attempt, knowing exactly where and which direction I wanted to build out." (Kyrros, ST/3714936444652157170, 2022-12-04)
- **[Q]** "pause the game when your planning or building something" (YT/Y9b3ov0yFK8 comment, 25 likes). [I] An AI has map knowledge from analysis, but it should still value scouting the player's base and changes to it.

---

## 2. Base layout philosophy

- **[Q]** Compartments, not one sprawl: "My base for this map was actually very small and i made things in pockets with mass drivers, so a small chunk for tungsten mining sent left to the original core for T2 tanks, and more sent southeast for T2 tanks and T1 eludes. I had every section walled off with Breaches." (RiptheBlood, ST/601894472784113557, 2025-01)
- **[Q]** Fixed modules sized to ratios. MindustryJP's Erekir schematic list (YT/PUTX0JY9omQ, 2023-08-06) is ordered by use. Each name says what the module holds and how many copies:
  - Production: "Silicon (3)", "Electrolyzer (3)", "Oxidation Chamber (6)", "Oxidation + Surge (6+1)", "Oxidation + Carbide (6+1)", "Surge Crucible (400%)" and similar.
  - Power: "Chemical Combustion Generator (8)", "Pyrolysis Generator (8)".
  - Units: "Tank T1×3", "Tank T2×3", "Ship T1 + T2 + T3".

  The "400%" crucibles predate b156 and are now 100% max.
- **[Q]** The traits of good schematics, per the wiki: "Compactness… Resource cost - cheaper schematics can be built earlier… Tileability - minimize the wiring needed to link two copies… Ratios… Action demand - … building drills or bores into the schematic." (FW/Schematic, 2026-08-26)
- **[Q]** In PvP, speed beats optimisation: "setting up fast defenses and drills as opposed to intricately placing them is far better"; "spending time to optimise drill coverage is pointless"; "Defenses should be simple and attached closely to supply belts instead of being separate." (FW/PvP, 2026-05-28; the 1v1 tips are written for Serpulo)
- **[Q]** Unit production cells built where inputs already pass: "Build a Ship Fabricator and Ship Refabricator setup. The most convenient location is near the pre-built lines, where you have silicon, graphite, and tungsten ready to siphon from them" (FW/Karst/Strategies). On Peaks, the choice is between "reuse your Silicon lines from T1 production" in the main base and putting the Refabricator near the tungsten and hydrogen (FW/Peaks, 2025-07-16).
- **[Q]** Separate power islands: the meta-report quotes FW/Chemical_Combustion_Chamber on keeping "two Turbine Condensers and an Electrolyzer away from the rest of your power network". Peaks: "connect your 2 bases with a beam tower" (ST/5738190785824510361).
- **[Q]** Ammo next to the turret: "Add more Sublimates with Reinforced Liquid Tank ammo storage" (FW/Karst/Strategies). On Disperse supply: "leading a long, unprotected duct line can risk cutting them off of their ammo" (Steam guide "The Turrets of Erekir", 2023-04-30). The guide also says Titan works best "If there happens to be Thorium near where you want build some turrets"; later players supply turrets with Unit Cargo Loaders: "One requester pad to two disperse turrets is usually enough to shut down any air unit harassment for effectively no infrastructure cost" (YT/8nZIiMJ5vHs comment). [I] Put turrets where their ammo already is, or feed them by cargo drone. Don't run long, exposed lines to a turret.
- **[Q]** Vent outposts: "I usually immediately commandeer the thermal vents and build Turbine/Radar pairs over each vent - they're cheap and make for a moderately decent early warning system… better still if I am able to run power lines all the way back to my base." The "super-paranoid" version adds "an attached water splitter with an additional tank to hold ozone to power a Sublimate… surrounded by cheap or moderate walls." (Kyrros, ST/3714936444653765872, 2022-12-05)
- **[Q]** Pathing tricks with walls:
  - Leave the enemy's own wall standing: "The enemy won't destroy their own walls… their wall becomes YOUR wall… This will block the tanks, but let the mechs through. This separates their attack firepower and lets you optimize two defense rows." Choke tanks "by forcing them to path through long windy walls". At the mech entry "put 3+ layers of walls, and a ton of turrets… The mechs will walk over the walls, but their bullets won't." (Gnord, ST/4136060426843421871, 2024-01-20)
  - Mazes: "make a long path that goes to your base (make sure it isn't blocked), add some turrets… the ai tries to find a way that isn't blocked thus forcing the Boss to follow that path of death" (YT/vBCt_7qVgWg comment on Crevice).
  - "With ground units they'll prefer an actual walkable path rather than blasting through a wall, so leaving a death zone that they can find a path through isn't so tough" (ST/4030222029795816783, 2023-12).
- **[Q]** Do not seal the base completely. "as soon as I walled my base off, they would stop attacking… it's like the enemy has stored all it's units… and sends them in full force"; "I left a hole in the wall and the frequent attacks were manageable." (DBAA3, ST/4030221179840180386, 2023-11-26, Atlas)
- **[Q]** Clutter does not protect: "beam nodes unlike their serpulo counterpart do not stop enemy unit projectiles, meaning that spamming them is just waste of beryllium" (YT/CszRkYCU9Sw comment). Ducts can absorb missiles ("spam Ducts in front of the missiles", FW/Anthicus via meta-report).
- **[Q]** Forward bases: "If there's a core pad available in a conquered area, try to build your own ASAP, as that then sets up a 'no build' zone around that core, preventing rebuilding… (and also makes for a very nice early warning)" (Kyrros, ST/3714936444653765872). A listed mistake: "You place Core: Citadel on that core zone which takes a long time to build (place Core: Bastion instead). You didn't place defenses after placing your core." (YT/RoUAMK8-Seo comment). The Origin ship strategy disagrees: "Creating new cores is a waste of time and resources as they create new targets" (FW/Origin). [I] Claim a forward core when it adds unit cap or denies a rebuild site. Build the cheap Bastion and defend it at once. Skip it when the base is about to be destroyed anyway.
- **[Q]** Mass drivers move units over terrain. In Basin, "It is also possible to move units by Payload Mass Driver to the southern side of the carbonic wall" (FW/Basin). A 23-like YouTube comment describes a sneak base on an enemy-side vent that shipped tanks over with mass drivers until "around 30 of them" (YT/Y9b3ov0yFK8).

---

## 3. Attack behaviour

### 3.1 Army size thresholds

| Target | Army size players used [Q] | Source |
|---|---|---|
| Onset, first core (few walls) | "around 5 Stells" | FW/The_Onset, 2026-03-04 |
| Onset, second core | "at least 10 Stells" | FW/The_Onset |
| Basin, NE base | ~15 Stells | FW/Basin strategy 2 |
| Basin, phase 2 (4-6 cores) | 40-45 each of Locus and Cleroi, i.e. the cap from 3 cores | FW/Basin; ST/601894472784113557 (2026) |
| Origin, north Elude/Stell base | "a squad of about 8-10" Eludes, if enemy unit production has not started | FW/Origin |
| Crossroads, Obviate and Anthicus bases | ~12-15 Averts | FW/Crossroads/Strategies |
| Stronghold, surge-wall core | "around 30 averts to disable the disperse's ammo supply" | FWC/Stronghold, 2024-02-09 |
| Stronghold, all 8 cores | "30 Anthicus + 30 Obviate" | YT/vBCt_7qVgWg comment |
| Origin, Malign-defended core | "around 20" Quells | FW/Origin |

- **[I]** These numbers are fractions or multiples of the 15-per-core unit cap. Players nearly always attack with "everything the cap allows" against fortified bases. They use small squads (5-15) only against weak or exposed targets.
- **[Q]** A failure mode at the threshold: "I get through the wall, and then several locuses come out and demolish my entire army and my base is practically defenseless" (Kitkat, ST/4136060426843421871, 2024-01-17). [I] An attack sent below threshold costs the army and also leaves the home base open.
- **[Q]** No one knows the "critical minimum": "Could probably do it with less T2 units but idk the 'critical minimum' at which your losses may snowball downhill" (ST/601894472784113557, 2026-07-13).

### 3.2 What players hit first

- **[Q]** Supply lines and ammo before turrets:
  - "attack through the base from the bottom up focusing supply chains to deplete turret ammo until you eventually find a core to destroy" (RiptheBlood).
  - Against Stronghold's main base: "sever the power supply of the Afflict, the main ozone line of the Sublimates, then the tungsten duct of the Breaches… build some beryllium walls for them to waste their last bit of ammunition on. Wait for the turrets to run out of ammunition before attacking again." (FW/Stronghold, commented-out older strategy)
  - "a large Avert rush can easily destroy the base's Tungsten sources… then you can lure the Disperse to expend all of its ammo" (same).
  - Caldera: "destroy the Reinforced Containers storing ammo for the enemy Breaches and victory will be easily achieved" (FW/Caldera).
- **[Q]** Power and heat: Crossroads' shields go down when their power is cut. "trash any powersuppliers you find… see if there's any unprotected powerplants or anything supplying powerplants with fuel to destroy" (ST/3714936444653765872). Origin: "the eastern Malign must be disabled by destroying the Neoplasia Reactor heating it" (FW/Origin). Siege: "The eastern pass base ships arkycite to the western pass, and destroying it will cripple the first base's power supply" (FW/Siege).
- **[Q]** Unit production first:
  - "Try and get the top core first because it's the only one that will send out the air units" (FW/Stronghold).
  - "Aim to take out the Obviate-producing core as soon as possible" (FW/Crossroads).
  - "Prioritise targeting unit production on these bases" (FW/Origin).
  - "place an Avert to constantly snipe the eruption drill at the bottom of the main base, stopping all tier 3 enemy unit production" (FWC/Stronghold, 2025-10-17).
  - "You can gut obviate production with a tungsten breach" (FWC/Crossroads, 2025-01-25).
- **[Q]** Core snipes where defence is thin: "Many bases in this sector have a glaring vulnerability to core snipes by Averts" (FW/Stronghold). Also "go straight to the core and destroy it, then place tungsten walls over the core and ducts over the obviate factory so they dont rebuild it" (FWC/Stronghold, 2024-02-09).
- **[Q]** Destroying the core first can collapse the base: in Origin's north base, Eludes "slip in behind it and take out the core, which will destroy the factories and most of the turrets instantly" (FW/Origin).

### 3.3 Siege tactics and micro

- **[Q]** Distract, then dive from the flank. "Send one or two Eludes into the top-left corner to distract the Breach while the remaining Eludes slip in behind" (FW/Origin). In Stronghold, "Send a large group of Locuses from the east to attract fire… then quickly deploy Averts from the south" (FW/Stronghold). Also "the t4 tanks were just to bait the army away from the regen blocks so i could go around with locus and kill them" (YT/SE6zBVjIKH0 comment).
- **[Q]** Attrition by single units: "make them waste ammo by sending units 1 at a time AFTER causing damage with eludes" (RiptheBlood). Against Scathe: "Move a single, bulky unit forwards from the main army to make sure Scathe missiles only hit one target at a time" (FW/Siege).
- **[Q]** Spread against splash: "If the enemy has turrets such as Afflict, Titan, or Scathe, spread your units out" (FW/Attack). Also "micromanaging your Averts to separate them helps win encounters" (FW/Stronghold, older text).
- **[Q]** Avoid the turret, not the base: "Avoid engaging with the Afflicts, as they kill many Averts in just a few shots"; "Approach from the empty space north of the map to avoid the ground path defenses"; "Stay on the southern side of the core with your Averts, to prevent the other Disperse from firing." (FW/Crossroads/Strategies)
- **[Q]** Outrange: T2 tanks "have a longer range than sublimate making it a good counter" (YT/hrqr1RUsH2I comment). Quell's "range lets it fire safely against all turrets other than the Smite and Afflict" (FW/Crossroads). Titans at the final Stronghold core: "use the claimed areas beside it, to build two Titans… The Titans will destroy the walls and turrets" (FW/Stronghold).
- **[Q]** Heal, then attack again. "Heal the Averts and then attack the core where Anthicus are produced… Heal the Averts and then attack the west base." (FW/Crossroads/Strategies). The Origin ship plan says "Build Unit Repair Towers to keep your airforce healthy". [I] Players attack in cycles: strike, pull back to a repair point, strike again. They do not fight to the last unit.
- **[Q]** Hold ground after a win. "never let the enemy rebuild. I remember always having a couple mechs just in range of the destroyed turrets so the enemy wouldn't rebuild it"; "make sure to have turrets to stop the enemy from rebuilding" (RiptheBlood). Also "make sure to leave some Averts or Obviates units at the destroyed cores… move units to the core tiles and surround them with walls" (FW/Stronghold) and "Remember to place 1 unit on the core zones to avoid rebuilding" (FWC/Origin, 2025-07-26). The Siege player who took 26 hours spent "10h to figure out I should place turrets and walls so that ennemy doesn't claim it back" (FWC/Siege).
- **[Q]** Order of conquest matters. "Started clockwise but from the southern central cores, was wondering why i kept losing for 4 tries, then tried from north to south and it worked" (FWC/Origin). Also "Save one of the enemy Bastion Cores to destroy last… This makes sure that the strengthened retaliation waves do not overcome your defenses" (FW/Origin). [I] Take the weak, high-value bases first (unit production, resources). Leave anything whose destruction triggers a script until last.

### 3.4 Composition by stage

**[Q]** synthesised from FW unit and sector pages and the comments above:
- **T1.** Stell for ground pushes. Elude for raids and cheap defence; it can "cheese the breach turrets with this. Manually control them and dodge" (FWC/Elude). Merui to cross thin walls (Intersect, Atlas).
- **T2.** Locus as the tank and Cleroi as the damage dealer. "use the Locus to tank and Cleroi or other tier 1 units to deal massive amounts of damage. Dive everything into one of the Citadel core entrances" (FW/Basin). Averts as raiders and AA "when stuff hit the fan" (YT/mPNfxlZJcik comment).
- **T3.** Anthicus spam: "it's a wall of splash projectiles and the numbers are so big that it doesn't even matter they're destructible" (YT/RoUAMK8-Seo comment). Obviate for diving cores, Precept for the front line.
- **T4.** Vanquish (Siege rush), Quell (Crossroads, Origin; "cheap", only the factory needs carbide), Tecta.
- **T5.** Collaris for range from the flank and Disrupt as a Quell follow-up. Players often skip Disrupt: "keep producing Quells as they're significantly cheaper" (FW/Origin).
- **PvP (Eviction hex servers, v139-v146).** "Elude rush. Learn to defend it or lose every game" (2022). Later the mix was Elude and Locus with early surge Afflicts. One comment on Titan defence: "a titan… should cover a 2 core wide choke point… and you should full wall with tungsten… the flat reduction on tungsten walls reduces locus damage to around 60%" (YT/SE6zBVjIKH0 comment). The phase layout of Larofeticus' 2023 tutorial (YT/yO0AcY1akmM chapters): "Early Game Economy 0:00 → Unit Production 11:55 → First Enemy Contact 17:36 → More Unit Production 18:35 → Main Conflict 23:39". These are video timestamps and may be edited, so only roughly game time [I].

---

## 4. Campaign sectors: how players beat them, what goes wrong

Map content is v7-era but still present in v8 (see §0). Speedrun times are from SRC, mostly v141 (2023), and give a sense of how long each sector takes when played well.

| Sector | Winning approach [Q] | What goes wrong [Q] |
|---|---|---|
| **The Onset** (SRC 11:23) | Follow the objectives; "place Breaches followed by Tank Fabricators to make it impossible to lose"; ~5 Stells, then 10+ (FW/The_Onset) | — |
| **Aegis** (4:41) | Start 300 Be / 300 graphite; Impact Drill and tungsten; quick T1 push | — |
| **Lake** (7:29) | Elude-only, since the enemy island is cut off by slag. "It's technically possible to beat this level through tanks only… carrying beam nodes and then building radars" (FWC/Lake) | "it's the breaches that the malis keep rebuilding" (ST/4031348734033862074); "elude's dps is absolute …" (ST/3810657379793594901) |
| **Intersect** | Build the frontline with a Breach and a Diffuse; Impact Drill in wave 1; no early second core; Merui over the NW natural wall to kill the core. One player: "Build a mech factory early. Destroy the core before the 2nd wave." (FWC/Intersect, 2025) | Merui walk over walls ("walls are basically an illusion"); Diffuse ammo drains graphite; Averts on wave 8 (PLMMJ, ST/3810657379793594901, 2023) |
| **Atlas** (15:37) | "Expand your base and focus on manufacturing Loci. Then, blitzkrieg to the 2 Southern Core", then Merui (FW/Atlas) | Fully walled base → AI holds back and floods you later (ST/4030221179840180386) |
| **Split** | Tutorial; Merui moved by Payload Mass Driver | — |
| **Basin** (34:01) | Wall the choke and use 3 watered Breaches; Eludes kill the NE base (cuts Stell production); mine tungsten for Locus; **don't destroy the second (NW) core until the army is at max cap and a new core can be placed at once**; the nuke lands 6 min later; then rush the 4 new cores (FW/Basin; YT/Y9b3ov0yFK8, 2022: "not destroying the second core and stockpiling units"). Before the nuke "enemy sends only t1 units" (Chukki, ST/4136060426843421871) | Not knowing about the nuke; "All my previous defenses are WORTHLESS" after it; Averts cut Breach supply; T2 Locus overwhelm Breaches (ST/592900478058115306, ST/3810657379793594901) |
| **Marsh** (15:53) | "make yourself a solid defense first, then getting the Chemical Combustion Chambers… to expand your beryllium mining"; a T2 Locus/Cleroi swarm (FW/Marsh) | Rushing objectives |
| **Peaks** (38:16) | Air only ("anything but Air Units… is a waste"); vent condenser + electrolyzers + CCC; build defences before Averts (FW/Peaks). Cheese: delay the objective (patched by about 2024 per FWC/Peaks) | "instantly throwing 5 bazillion averts on you"; "time limit… way too short"; power split across two cavities (ST/5738190785824510361; FWC/Peaks) |
| **Ravine** (26:35, survival) | 2 oxidation chambers + electrolyzer at once → boosted bores → surge → Afflict before wave 1 (FW/Ravine). "Make ozone based defenses towards the ground, and just regular anti air at the top" (ST/4852156227492977993) | Air waves breaking the core on high difficulty; "build your own units… invest in surge alloy" (ST/590650717026394794, 2025) |
| **Caldera** (19:33) | Eludes defend, Averts attack, hit the ammo containers; copy the enemy power block; Cleroi can cross a 1-block gap (FW/Caldera; FWC/Caldera) | "Lake 2 - more hellish hellhole"; Elude swarms (ST/3810657379793594901; YT/ZAsHOh_uadA) |
| **Stronghold** (29:40) | Two Merui over the ledge to kill the first core and Mech Fabricator; build all fab/refabs; **don't kill the trigger core** until your economy and Sublimate/tungsten-wall defence are up; Disperse before Obviates; Averts snipe weak cores (FW/Stronghold). "I recommend ONLY building the defense line, the turrets AFTER you reach the bottom core with the thorium… look out for your starter core, this is the first core where t3 flyers will come for" (KarinFysh, ST/592900478058115306) | Surprise Obviates ("Everything was going so well, then everything changed when the Obviate attacked", YT/RoUAMK8-Seo); attacking the second core before massing; building a Citadel instead of a Bastion; attacking the Precept base before the Obviate base (YT/RoUAMK8-Seo comment) |
| **Crevice** (survival) | "rush surge, built afflicts, and ignore carbide" (YT/iizNPSnqgOU); Eludes scout and defend; Sublimates near spawn; at least 2 Disperse covering everything (FW/Crevice). Alternative: "some Afflicts and electric heaters would suffice" instead of Disperse and tungsten lines (FWC/Crevice) | Front defence breached before the guardian Tectas, "So glad i made a backup defense line" (FWC/Crevice, 2025); hardest Eradication sector for one player, "over 6 hours" |
| **Siege** (36:58) | Vanquish rush from stockpiled carbide, or Afflict defence plus T2 then thorium; the side with more slag is defended with Afflicts while the other is attacked (FW/Siege). "4 Sublimates at choke points with tungsten walls and regen projectors" (ST/3810656958854938509) | Spending the carbide loadout on research ("deplete your [Carbide] paying to unlock stuff", lonesh33p); Scathe covers the thorium area; slow T2 chip attacks; 26-32 hour runs (FWC/Siege) |
| **Crossroads** (17:04) | Quell or Avert raids; kill the Obviate core early or have Afflicts; cut shield power; ignore the coreless Cleroi base (FW/Crossroads, …/Strategies) | Forgetting to cut shield power; Smite blocks distractions (ST/3714936444653765872) |
| **Karst** (25:25, survival) | Scout with the first Avert; barricades at each ground path (Sublimates + 3 layers of large tungsten walls), AA nests of 4 watered Disperses, done wave by wave; extra core diverts air (FW/Karst/Strategies) | Unexpected unit volume; Collaris guardian on wave 9 |
| **Origin** (19:50 in 2024; 38:43 in 2026: "Tank spam is good. I need to tank spam more.") | Eludes take the north base before its timer → forward core → Quells; leave one weak core for last to avoid the final-core retaliation waves (FW/Origin; Zolokhan, ST/3714936444652157170). "6 t1 ship => destroy bastion core => build quell => destroy Citadel core => Thorium…" (FWC/Crossroads, 2026-09-07) | Order of attack (FWC/Origin); retaliation waves from the central core; a "maxed out t5 army of a single unit type isn't enough" (ST/4360121202644066132) |

General campaign notes [Q]:
- Erekir enemies are scripted. They stay passive until a flag fires, such as a core being destroyed: "you pretty much get infinite grace time before the enemy begins to attack you until you take the second core down" (KarinFysh, ST/592900478058115306).
- The AI sends more at the side you have pushed further forward: "the AI tends to attack the side that you've pushed up farther" (Venorik Barra, ST/3810656958854938509).
- "I found the enemy focuses more to reclaim territory than to attack, or at least splits its forces" (RiptheBlood).
- "The AI isn't at all intelligent, they just take the shortest path, you can even build mazes for them" (ST/4360121202644066132).

---

## 5. Common mistakes players report

1. **Power.** Swapping Turbine for Vent Condensers before combustion power exists; Electric Heaters instead of oxidation or slag heat; not grabbing every vent → "perpetual brownout". A brownout stops logistics such as unloading and mass drivers, not just factories (FW/Power; ST/3788128816418203722; ST/4360121202644066132).
2. **Turret-first spending.** Breaches are "surprisingly expensive, so you should reinforce your mining before building many" (FW/Breach). Turrets are a "noob trap" early in PvP. Breaches get overrun by T2, and Averts outrange them to cut their beryllium (ST/3810657379793594901).
3. **Wrong ammo economics.** Diffuse "drains too much Graphite for ammo, crippling your building and production" (PLMMJ, same thread). Long, exposed tungsten ducts to a Disperse (Steam guide 2968050682).
4. **Walls as the answer to everything.** Mechs walk over them, air ignores them, and fully sealed walls make the AI stockpile and then flood (ST/3810657379793594901; ST/4030221179840180386). Counter-view: "Wall spamming is too cheap and too powerful of an exploit" (Gnord). Walls are strong against tanks and as ammo sponges.
5. **Triggering escalation before you are ready.** Basin's nuke; Stronghold's first core (which starts the countdown); Origin's central and final cores ("bait and switch", ST/3714936444652157170).
6. **Attacking below threshold, or one base at a time with no defence behind.** "I get through the wall, and then several locuses come out and demolish my entire army and my base is practically defenseless" (ST/4136060426843421871). Also "You immediately attack the 2nd core available at start (you need to make a lot of units … before attacking)" (YT/RoUAMK8-Seo comment).
7. **Not holding captured ground.** The enemy rebuilds turrets and cores (ST/4031348734033862074 Lake; ST/3714936444653765872; FWC/Siege "10h").
8. **Building a Citadel at a forward core.** It is slow (one player gives about 32 s for a Bastion vs 160 s for a Citadel, ST/586181727714565373). Also leaving the new core undefended.
9. **Spending research stock in the sector you need it in** (Siege carbide; FW/Siege "If you do not have the 130 total carbide needed, abandon and then re-launch").
10. **No mobile reserve.** Air or Elude raids on power, drills and core while every unit is away (RiptheBlood; Mind Gardener; Gnord "Air units… approach your base in unpredictable ways").
11. **Clumping against splash and pierce.** Erekir turrets pierce, so "smartly directing our units so they don't get slaughtered by a single turret" matters (Kenuda). Unit traffic jams at chokes: "large groups of units push each other around and stop each other from progressing. Tanks in particular are very bad with this" (ST/4030221179840180386).
12. **Ignoring fog and spawn locations.** Building in the wrong direction and losing the first attempt (Kyrros; YT/iizNPSnqgOU comment).
13. **Spreading turrets thin vs concentrating them.** In Karst the winning plan has exactly 3 barricades and 2 AA nests, each built up over time (FW/Karst/Strategies). [I] Players concentrate defence at a few chosen chokes and cover the rest with units.

---

## 6. Implications for an AI

Each rule is [I], built from the sources above. These are proposed top-level decision rules for an AI that plays under player rules.

1. **Phase machine, not a flat priority list.**
   - BOOTSTRAP: vents, bores, furnace, first fabricator.
   - BUILD-UP: fill the unit cap behind a stable defence, while improving extraction.
   - STRIKE: concentrated push on one target.
   - CONSOLIDATE: claim the core, garrison it, rebuild, repair.

   Go back to BUILD-UP after each strike.
2. **First fabricator before first turret.** Build the turbines, bores and furnace, then a Tank (or Ship/Mech, by map) Fabricator, then the first Breach or Diffuse. Exception: a known enemy force already on its way.
3. **Power gate.** Every reachable vent gets a Turbine Condenser before any Vent Condenser. Build no Electric Heater until combustion or pyrolysis power exists. Keep a power margin of at least one furnace (300/s) before adding a crafter. Use spare early power to fill beam nodes as a buffer.
4. **Unit cap is the main attack trigger.** Against a fortified enemy core, attack when the army is at least 80-100% of the cap, plus a power-of-composition check (`RtsAI.estimateStats`-style time-to-kill). Send small squads of 5-15 only at targets scored "exposed": no turret coverage, or only turrets whose ammo or power is cut.
5. **Grow the cap by claiming cores.** When a core zone is free or freshly cleared, build a Bastion (never a Citadel first). Wall it and put one turret or unit there at once. Treat "+15 cap" as a high-value build.
6. **Factory count scales to income, and units come first.** Add a fabricator whenever silicon or beryllium sits in the core above a buffer and power allows (about 3 T1 fabricators per furnace). In BUILD-UP, put about 60-70% of spending into units and the rest into defence and economy. More than that on turrets is the "noob trap".
7. **Tech-up triggers, not a fixed ladder.** Unlock or build the next tier only when one of these holds:
   - the player shows a threat the current tier can't answer (air → Disperse or Afflict; massed T2 → Sublimate or Afflict; missiles → Afflict);
   - the next tier outranges the player's turrets;
   - the cap is full and income is saturated.

   Otherwise mass the cheapest effective unit. Late T1 spam is viable.
8. **Defence = few strong chokes + a mobile reserve.** Pick 1-3 chokes on the player's likely approach. Put watered turrets there, with walls 2-3 layers thick and ammo buffers next to them. Always keep 2-4 units (or about 10-20% of the army) at home near drills, power and core as a quick-reaction force. Units leave the reserve only on a full-cap strike.
9. **Never fully seal the base.** Leave a deliberate open, turret-covered path. This keeps your own units' exits clear and avoids the "stockpile then flood" pattern if the player ever exploits pathing.
10. **Put turrets next to their ammo.** Place a turret where its ammo or liquid is already flowing (on the trunk line or beside the electrolyzer), or keep a local buffer (container or tank). Never run a long, exposed single line to a turret. For AA near the economy, use Unit Cargo Loader supply when available.
11. **Target selection on attack, in order:**
    1. the player's unit factories and their inputs;
    2. power links and generators, plus arkycite and ozone lines;
    3. ammo and liquid lines feeding turrets;
    4. drills;
    5. turrets;
    6. the core when its no-build zone is reachable.

    Re-score as fog lifts. Prefer targets off the turret-coverage map.
12. **Drain before you breach.** Against a well-defended line, first send one bulky unit or a cheap decoy (or have the core unit drop walls) to make turrets spend ammo. Hit supply lines from a flank while the main army waits outside range. Commit only once the turrets are starved.
13. **Strike, heal, strike.** Retreat damaged units to a repair point or Regen Projector before they die. Re-engage at about 70% army HP. Don't fight to the last unit, because losing the army leaves the home base open.
14. **Spread out against splash.** When the target has Titan, Afflict or Scathe, move in a loose formation and approach from two directions. Use a lead tank to absorb Scathe or Titan shells.
15. **Hold what you take.** After clearing a turret cluster or a base, leave 1-2 units in range, or place a cheap wall or turret, so the player cannot rebuild for free. Occupy freed core zones.
16. **Interrupt priority when attacked.** Reserve engages → reconnect power → reconnect ammo and resource lines → rebuild production → add a permanent defence at the breach. Air raids on the economy trigger an AA build near the economy, not at the front.
17. **Scout early and keep scouting.** The first fast unit (Elude, or the first Avert) goes to scout the player's base and approach paths. Radars at outposts. Re-scout before each strike, because players change defences between waves.
18. **Use modules sized to ratios, with room to grow.** Build production as tileable cells: furnace + crushers + bores, electrolyzer ×3 + CCC ×6 + vent condenser (the Caldera block), fabricator ×3 on a split line. Reserve the next cell's footprint next to the current one so new factories connect to existing trunks.
19. **Outposts on vents.** Every captured or contested vent gets a Turbine Condenser + Radar. Later it gets an electrolyzer + Sublimate + walls. This gives power, early warning and a point defence in one package.
20. **Don't be the "easy" scripted AI.** Things human players exploit today:
    - passive enemy until a flag fires;
    - shortest-path ground units;
    - waiting while the player seals their walls;
    - rebuilding blindly into an occupied zone;
    - turrets dropping ammo on decoys.

    The new AI should pressure continuously at a level matched to its own build-up. It should path around mazes, re-route to weak points, and stop feeding resources into a site the player holds.

---

## 7. Sources tried

**Worked**
- Fandom API wikitext: sector pages, `/Strategies` subpages (Crossroads, Karst), Power, Radar, Schematic, PvP, Attack.
- Fandom article comments (`wikia.php` ArticleCommentsController): new route.
- Steam discussions: about 35 threads, found through `/discussions/search/?q=`.
- The Steam guide "The Turrets of Erekir: A Defense Guide" (sharedfiles id 2968050682, 2023-04-30). It loaded this time; it was HTTP 429 last pass.
- YouTube search pages, descriptions and chapters, and comments via `youtubei/v1/next`.
- speedrun.com API (runs and comments).
- newreleases.io mirror of the GitHub release notes.

**Failed**
- Reddit: `old.reddit.com/.json` and `www.reddit.com/.json` → 403 "blocked due to a network policy"; `api.reddit.com` same; WebFetch refuses reddit.com; search engines returned no reddit.com results even with `site:`. The Karst guide by Loose-Leadership8907 (r/Mindustry/comments/1chd0pb) is known only through its Fandom adaptation.
- web.archive.org: connection reset by the proxy, and WebFetch refuses it; `archive.org/wayback/available` returned no snapshot for the Reddit post.
- YouTube captions and transcripts:
  - watch pages redirect to a Google "sorry" page;
  - `youtubei/v1/player` returns LOGIN_REQUIRED for WEB, MWEB and embedded clients;
  - `get_transcript` returns FAILED_PRECONDITION;
  - invidious (inv.nadeko.net) lists tracks but returns an empty VTT; yewtu.be shows a captcha; piped is bot-blocked; youtubetranscript.com says it is blocked by YouTube.
- Miraheze wiki API: non-JSON (Cloudflare) response.
- GitHub release pages and API from the shell: blocked by the session proxy (repo not attached; anonymous git read only).
- Mindustry GitHub "discussions": 404 (the repo has none).
- planet.freegamedev.net v8 notes: HTTP 503.
- itch.io comments: reachable, but nothing relevant.
- Discord: not attempted (no public archive found).
- Steam search: rate-limited to empty pages after about 30 requests.

**Caveats**
- Most qualitative campaign advice is from v7 (2022-2025). Exact heat, ammo and cost numbers in quotes may be pre-b156.
- Larofeticus' PvP descriptions are v139-v146.
- Speedrun times are v141-era, except the Origin 2026 runs and Stronghold 2026-09-18 (35:36, ARCYDE, likely v8).
