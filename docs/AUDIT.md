# Audit of the Operations AI (2026-10-21)

A read-through of the code for logic errors and modelling flaws, before any more fixing one game at a time. Findings
are grouped by layer; each says what the code does, why it is wrong, and the fix direction. Hand-set thresholds that act
as rules are listed too (the rule: none; model or measure the quantity they stand for).

## 1. The predictor: the command layer's model (ops/Command, Forecast, Force)

1.1 **The value of what is past the horizon.** Play stopped at 12 minutes; a game not decided by then was worth the log
of the army standing (0-5 points against ±100-1000 for a decision). Whatever pays off later (a defence and then a second
tier, more economy) was invisible, and choices turned on whichever rush the model saw decided inside 12 minutes. *Fix
(done):* play to three discount time scales (30 minutes, a twentieth by the discount), production going on as the models
have it (our Timeline keeps its factories running; the forecast its yards and waves); the one-sided "lost past the
horizon" leaf removed.

1.2 **Their army teleported.** Once sent, it fought at our core the same step; ours walked. *Fix (done):* it walks the same
time ours does.

1.3 **Their yards' pace undercounted.** Only units alive at a weighing were counted as made; those our turrets killed in
between (410 on aegis) were not. *Fix (done):* counted as they are made (UnitCreateEvent).

1.4 **Our economy's lag fixed at 1 and 1.15.** The game ran 1.2-2x the model on many maps (first unit 4:30 in the model,
7:12 after layout, none by 7:00 in the game), and never at all on a fifth of the stages. *Fix (done):* the lag scenarios
are the median and 85th percentile of what was seen this game (first unit of each chosen stage against the model; a stage
left before its first unit counts as at least the time it ran), with 1 and 1.15 as two prior observations.

1.5 **The core's health reused in every fight at home.** fight() puts the core's health into our side's pool (it takes
hits), keeps our units' share of the losses, and leaves coreHp as it was: the next fight gets the whole core again. Home
defence is overrated. *Fix:* the core takes its share of the losses.

1.6 **The other side's timing treated as chance.** sendAt (when it sends its army: at half, equal, twice what holds our
home) is mixed into the scenarios with the economy's lag and the mirror, and all are put together by the posture's risk
measure. When it attacks is the other side's choice: it should be the one worst for us (a min over its options), and only
what is chance (our lag, whether it builds) aggregated by the posture. *Fix:* min over sendAt within each nature scenario.

1.7 **The mirror ignores reach.** The other side "building as fast as we could" sends our fastest stage's units at us
whether or not that kind can get from its base to ours (peaks: no ground way; the mirrored eludes still came). *Fix:*
only the kinds that reach our core from theirs.

1.8 **Their base's guard static.** The push was weighed against the turrets standing at the weighing; at the landing
there were none, the model took their core at 9:40, and three breaches later the army stood at home all game. *Fix
(done):* the guard grows at the pace seen (loaded turrets about their cores, gained a second since first seen). At the
first weighing nothing is known: the mirror should then also build a defence, as we could (open).

1.9 **Strength not by the game's numbers.** Unit health + armor x 10; a guardian "counted as two" (boss: health x3,
damage x1.3); the team's and the difficulty's health and damage multipliers left out; turret damage from its first
ammunition in Force, from another estimate in Threat. *Fix:* one strength function: health and damage times the team's
rules and status, armor against the attacker's damage a hit; a turret by the ammunition it is fed.

1.10 **Walk times.** Every kind walks at a stell's speed, the way as the crow flies times 1.35. *Fix:* each kind's speed;
the way's length from the flow field (or the reach region's BFS distance).

1.11 **The plan weighed is not the plan built.** The choice is made on dry plans (first rollout, no layout); the stage
then built is planned again in full and laid out, and is often much slower (4:30 against 7:12). *Fix:* the measured lag
(1.4) covers it on average; better, the best few ways laid out and weighed again before one is taken (open).

1.12 **What is planned on top of a defence.** Only the best few stages alone were planned again on top of each defence;
a higher tier, weighed worst alone (its first unit after any rush), never was, and "a defence then a second tier" came
out with its first unit at twenty minutes. *Fix (done):* the strongest kinds that can be had too.

## 2. Execution (econ/Hand, Watch; xtest/Match)

2.1 **Repairs deadlock a stage.** Putting back what went missing comes before new jobs and is never put off: on siege the
core unit saved for 250 tungsten nothing brought in, all game. *Fix:* a repair whose cost nothing brings in waits like a
job.

2.2 **Put off after 60 s of nothing coming in** (Hand.stallTime): a rule. *Fix:* put off when nothing that stands brings
the item in (no machine of ours making it that is working, the core unit can't dig it); wait while something does.

2.3 **The threat the defence planner is asked to hold** (Match.defence): "4 to 10 early, 2 to 6 later, what it has times
1.5, three minutes of its yards": rules, and a second predictor beside the Forecast. *Fix:* ask it for what the Forecast
has at our core by the time the defence is up, in the worst scenario.

2.4 **The defence budget** (Match.defenceAsk): at least beryllium 900, graphite 600, silicon 700, tungsten 150, or what
comes in over three minutes: rules. *Fix:* the economy model says what can be paid for by when; the budget is that.

2.5 **Guards against raids** (Match, Guard): a turret put down where raiders take things, if it costs no more than what
it guards, the stage's hand paused for up to four minutes: rules outside the command layer. *Fix:* a guard is a way the
command layer weighs (its cost against the losses the raids are seen to cost).

2.6 **A stage passed over for units that can't reach** (Match, after the command layer chose it): the command layer
already counts reach; the second test only disagrees with it. *Fix:* drop it in command mode.

2.7 **A plan the layout could not feed is built anyway.** After the rounds of makers of their own, machines still unfed
stay in the plan and the model counts their output; the stage is begun even when, laid out, its unit never comes (onset:
a tank fabricator no silicon line reached, forty minutes). *Fix:* what is still unfed gives nothing in the model; a
stage whose laid-out model never makes its unit is not begun, and the next way the command layer weighed is planned.

2.8 **The laid-out plan's model still pools every flow.** The Plant shares out what is made among all who take it, as if
every maker reached every taker; the layout then lays nets taker by taker and some are not laid. Even after the rounds
the model of the plan being built counts flows no line carries. *Fix:* after the layout, the plant is solved over the nets
as laid (a taker gets only what the makers its laid nets join it to send, the core only what reaches it), so that the plan
the hand builds has an honest model, its first unit included.

2.9 **Layout constants** (Layout.nets): a maker built after its taker counts as 25 tiles further; what a maker has over
half its output spare goes to the core. Rules standing for the time a line waits and for what is worth a line. *Fix:* the
wait from the order (the taker's begin against the maker's in the model), the line to the core when what it carries is
worth its cost by the chains' prices.

## 3. Economy model (econ/*)

3.1 **Dry plans skip all the layout teaches** (Plan.make returns before the layout when dry): no unfed takers, failed
taps, cut-off grids, hauling, real costs, build seconds or draw shares; only the all-tech try; one rollout. Job costs are
1 beryllium a tile of straight line to the core and 8 a ten tiles of nodes (Planner.estimate): no bridges, docks, routers,
junctions, detours; crafters all at core + 8. Build time is the block alone (Timeline with buildSeconds NaN), the lines
and the flying along them left out (Layout.buildSeconds counts them). Every dry plan is cheaper and quicker than its laid
out self. *Fix:* lines estimated from a path field (Sites' reach BFS keeping path length and bridge hops: ducts, bridges,
nodes); build seconds by the layout's formula; and calibrated as the game goes (layout cost over estimate, by item and
ground, from every plan laid out).

3.2 **Supply pooled; what stands feeds new takers** (Plant: every maker reaches every user; Planner.balanceRest counts
field.existing's outputs as had). An Erekir core gives nothing out: a standing furnace sends to the core, and a new
fabricator gets its silicon only by a tap or a line of its own; only the laid-out path checks taps, and drops makers it
cannot tap. Dry plans and the Timeline have that silicon arrive at once. *Fix:* what stands goes to the core only; a tap is
a way of its own with its cost; better, the plant solved as a min-cost flow over clusters with path-length edges.

3.3 **The plant's make-up frozen once real costs are known**: it is sized on 3.1's costs, building needs only some source
(beryllium dug by hand: no bore for building), extras chosen on the optimistic costs; after the layout extras = 0 and no
moves, so with costs 1.5-3x the plan can only reorder, and the core unit saves for beryllium. *Fix:* building as a flow
(the order's cost over its time as a demand a second at the core), the make-up searched again on calibrated costs.

3.4 **Going on with a stage weighed otherwise** (Plan.continuing): no prune, no drain (the map's machines at full output),
against alternatives drained. *Fix:* the same reading for both.

3.5 **The core unit's time double-booked**: a hauled machine's output is a free flow while the Timeline has the unit
building or digging; dry plans count one-way flight, real plans that plus the round trip in buildSeconds. *Fix:* the
unit's place and activity (build, dig, haul) as the Timeline's state.

3.6 **A unit maker's first batch fixed once** (Timeline.ready computed while NaN only): built on a trickle it stays late
after a furnace comes; built alone with rivals coming later, it stays early. *Fix:* the items each maker holds as state.

3.7 **Unpowerable machines count as powered** (Layout.wire only logs; unfed() and cutOff() miss it; power pooled over
grids that may not join). *Fix:* reported like unfed takers; power solved a grid at a time.

3.8 **Phantom ammunition in a stage after a defence** (atop keeps the turret modules, inputs and all, as standing
machines; turrets modelled firing all the time). *Fix:* turrets and their feeders one closed unit; ammunition only in the
fight.

3.9 **Liquids counted twice** (Planner: every maker within 25 tiles counts in full for each taker; 25 tiles and 1.5x
cut-offs). *Fix:* takers assigned to sources as a transport problem, pipe cost a tile.

3.10 **An idle machine taken up costs nothing** (its lines uncharged) while its block's build time is charged. *Fix:* its
lines by the estimate, build time of the lines only.

3.11 **Chains' prices** (best yield per ground ignores whether the block fits; never falls as places are used; every item
1:1; time and lines ignored). *Fix:* items priced in core-unit seconds (build time and time to have each at the income),
shadow prices from the Timeline.

3.12 **Smaller**: the balance assumes takers get all of a flow while building gives them 1 - coreShare; one factory per
refabricator in link() (a second's units go out as first tier); hand digging at hardness 3 for everything (the game: 50 +
15 x hardness ticks; graphite and sand are hardness 0, dug 1.9x faster than counted); depths() by handDistance not
handMined; no warm-up (lines filling, first craft).

3.13 **Constants acting as rules**: Planner localLiquid 25 tiles and 1.5x, baseDistance 8, extras 16, maker(k, 0.1),
the turbine-or-condenser test; site distance weights rate x 2 (makerNear) against rate x 8 (bareMaker); Sites keep 40 and
d/40, prune cut-offs 0.1, 0.05, 0.9, producing() as a one-frame look; Plan depth gates {2, 3, all}, gateDepth 2 for
defences, payWithin 150 s, at most 6 payers and 8 feeders, radarAt 45 s; itemGoal 100; Hand stallTime 60 s.

(Measurement: Match.income reads the game's coreItemCount, which likely counts what the core unit digs too: the drain in
prune understated.)

## 4. Army and tactics (army/*)

Unit numbers used below (from the game): merui 680 hp, armour 4, 50 estimated dps, range 16.8 tiles; breach 1620 hp,
armour 0, 191 dps on beryllium, range 23.8 tiles.

4.1 **Damage to buildings by three numbers** (Tactics playout 0.25 of the estimate, "measured on cores"; its leaf 1.0;
Command 1.0 against turrets, 0.55 against the core). The 0.55 was measured with nothing else about, the quarter with our
units about it: the quarter holds fire spent on units, which the playout already models (groups shoot groups first), so
it is counted twice; armour ignored (cores 5/10/15, tungsten walls 14). On aegis at 20:00, 23 merui against 6 turrets and
3 stells: hold 0, attack -12615; at a quarter the turrets' 9700 health takes 34 s, at full 8.5 s and the attack wins.
*Fix:* damage a bullet against a block: max(d - armour, d x minArmorDamage) x buildingDamageMultiplier, splash apart;
one uptime factor a class of target, fitted to fights seen; the same in every model.

4.2 **A fight begun inside the 60 s gets no leaf; holding costs nothing** (Tactics leaf only for groups still walking).
Turrets outrange merui: losses come first, a core of 4500-16000 health never falls inside 60 s; a target 61 s off gets
the hopeful leaf, one 59 s off the playout, so far targets beat near and an attack loses its worth half way (turned 825
times in one game). Holding scores 0 though their guard and production grow. *Fix:* every fight unresolved at the
horizon valued by the same Lanchester solve, siege included; better, the leaf from the command layer's objective (their
core taken at t worth 300 e^(-t/600)), so that holding has its cost; the horizon "till the fights resolve".

4.3 **Turret damage wrong both ways.** Force.dps gives 0 for sublimate and lustre (ContinuousLiquidTurret is not a
LiquidTurret here); Threat.gun multiplies an estimate already per 100 ticks and 3 targets by shots/reload: sublimate 16200
a second, lustre 56700. Force takes the first ammunition of an unordered map, Threat the loaded one (diffuse 922 on
graphite, 2025 on oxide); neither applies reloadMultiplier (breach on carbide 1509 against about 300). With such turrets
about, Command pushes into nothing and Tactics, Push and Allot see a fortress. *Fix:* one function a turret and
ammunition: continuous bullets, reloadMultiplier, coolant, targets a shot.

4.4 **Squads cannot attack together.** Squads are units within 10 tiles; the search changes one squad's script at a time,
the rest held; the leaf weighs each group against the whole guard. Eleven merui weighed alone lose; twenty-seven together
were never weighed; under the square law (a + b)^2 is far more than a^2 + b^2. Command moves the whole army as one.
*Fix:* joint scripts ("every squad that can reach X attacks X", the command layer's push among them); groups making for
one target pooled in the leaf.

4.5 **Unit damage and health by three formulas.** dpsEstimate ignores armour and counts an artillery shell's direct hit
against units (merui, collides=false: 52.5 a shot, 30 of splash hits units; against stell's armour 6, 46% of the
estimate); no air/ground split in Force (merui counted against eludes); a Tactics group hits if any one unit can, then
with the whole group's damage; armour as +10 a point (Force), x(1 + a/20) (Muster), nothing (Force.add(Unit), Tactics).
*Fix:* a damage matrix by attacker, target, air and ground.

4.6 **Their best reply may do the impossible** (Tactics: their "go for our core" walks ground groups straight, no reach;
"go on" carries their velocity on for 60 s through terrain). On maps where the bases are parted, holding is valued for
attacks that cannot come. *Fix:* reach for their groups too; moves by path distance.

4.7 **Reach and walking.** canReach by the first unit's type of a mixed squad; a target counts only if ground ours reaches
lies within 4 tiles (Push size/2 + 2, Reach.can 3): units that fire across a gap (merui, precept) lose their targets.
Four walk models: Tactics own speed x 1.0; Forecast the type's x 1.35; Command stell's x 1.35; Push the slowest's x 2.0.
*Fix:* Reach.region with a BFS distance; a target reachable when reached ground lies within the unit's range of it;
path distance for every walk.

4.8 **The plan and its execution decide apart.** Command's push (when, how strong, at what) is never handed to the army.
The default army (army:defaults,push,hold) goes by Allot: whole-map strength, (units + turrets/3) >= 2x; full = count >=
cap - 2 never fires under an unlimited cap (aegis: cap 2147483647), the same in Push; Push.guard counts only anti-air
turrets if one flyer is in the wave; Hold adds two strengths (dps x hp products) instead of dps and health first, losing
the cross term, so we look weaker and fall back.

4.9 **Smaller playout flaws**: a turret's fire falls with its health (it fires at full till it dies); turrets never run dry
(Gun.ammo is there); units get 2 tiles more reach, turrets none; the switch cost (0.15 of health) on top of the walk the
playout already counts: for 23 merui a threshold of about 2000 in disguise.

4.10 **Constants acting as rules**: Tactics worths 6/3/2/1/0.3, squadRadius 10, nearOurs 50, targets 4, leafDiscount 90
(Command's discount 600), guard radii range + 4 and 20 tiles, askedWorth 5; Command pushEdge 2.5/2/1.4, sendAts
{0.5, 1, 2}, siegeEfficiency 0.55, 40-tile guard and home radii, walk 1.35; Push minWave 6, 75% gathered, 40% worn,
patience 60/90/180 s, reserve 0.2, at least 4 fighters; Allot edge 2, turrets/3, the 3x / >= 6 window; Hold near 35;
Muster refill 3 min, maxMakers 4, growth 2, room >= cap/3.

## 5. Defence planner (defense/Planner)

5.1 **Item prices by hand** (Planner.making: beryllium 55, silicon 110, tungsten 200, oxide 840 ...): what a module costs
to feed, and so which is chosen, by a table, not by what the item costs on this map. *Fix:* Chains' cost of each item.

5.2 **Site score by hand** (groundSites: min(cross, 40) + 20 if not one-wide + 0.12 a tile out - 10 a shared lane): a
weighted sum of made-up weights. *Fix:* the place where the module holds most for its cost, by the same fight model
(walls' length to close, the lanes it covers, how long they take to walk there).

5.3 **Safety 1.5, duty 0.3**: constants for how many times what the lab saw a module hold is asked of it, and the share
of the time turrets fire. *Fix:* the count is the command layer's choice (done: fixed modules); the duty can be measured
(our turrets' firing seconds over their standing seconds, Match counts dry seconds already).


5.4 **The lanes are not where the enemy walks.** Lanes.java costs a step 1 (a diagonal its length), +6 on a built tile; the
game's flow field costs 1 + 5 min(hp/40, 80), +2 by rock, +6 by shallow liquid, +30 on damaging floor, and ControlPathfinder
(the Brain's units) +6 by rock, +8 by liquid: a corridor three tiles wide, all of it by rock, costs them about 7x a tile, and
groundSites picks the narrowest crossing, the very one they avoid. Our base costs them up to 400 a tile against 6 in the
model. The Brain's units do not walk to our core: FighterAI goes for a building it chose, round our turrets' reach
(brain.safeWay), standing at 0.6-0.95 of its range where no other turret reaches. One shortest path a source; diverts()
checks only closed lines. *Fix:* the game's own costs over the terrain with planned and built blocks; placement as a game
(the attacker picks its target and least exposed way; we place against it; again till its best way runs through kill
zones): sites as a cheapest cut between where they come from and what we keep.

5.5 **What a module holds was measured with unlimited ammunition** (TurretLab caps: supply=0 is 4 items a tick, liquids
unlimited, stores full for 20 s); on the map a port gets max(0.25, firing x duty 0.3), feeders sized to that average: breach-
pair-direct used 6 beryllium a second in the lab, the map gives 1.8 and no store. In a long fight turrets go dry, and the
Brain fights without pause. Capacity.seconds and .used are parsed, never read. *Fix:* store and feed rate as choices with
their costs (store >= used - rate x fight seconds; refill what was used by the next push), or measure capacity at the rates
the economy gives.

5.6 **Turrets count as firing from when built**: Command.schedule adds a module's force at its finish in the dry timeline;
Plan.defend keeps modules the layout could not feed; Defence.fed() counts any duct router beside a turret as fed (every
module has its own), so one whose line was never laid or was cut is never fed again. *Fix:* a module's force from its first
ammunition (feeder up and line laid, in the timeline), at the rate it is fed; unfed after the layout, nothing.

5.7 **All the walls one machine** (Post.wallLine tags them module -1; one design defence:walls#-1 over every site, put
first): the first turret waits for every wall line. *Fix:* walls by site, ordered by the model.

5.8 **The kinds of module ranked wrong** (chooseFixed): dps by the best of all the gun's ammunition (breach on beryllium
valued at carbide, 3.1x); health of the turrets only; items 1:1; suits() never asks whether the gun hits the move (an air-
only disperse suits stells); ranked by module name, so breach-pair and breach-pair-direct take two ranks; the capacity table
not consulted. "Nothing we can build holds {stell=22}" there means nothing usable. *Fix:* by measured hold per cost with the
configuration's own ammunition, by turret; must hit the units.

5.9 **k modules at every site; sites by rules**: a site a (source, way of moving), an air site for any air kind in the
threat, any refabricator (even idle) adds its upgrade; the whole army of the other side given to each of its cores and summed
where lanes meet (two cores, twice the threat). *Fix:* the command layer sets a budget; modules spread over sites by leak
saved a cost (greedy, diminishing returns), the threat one pool split over ways by the attacker's best reply.

5.10 **The bill never checked**: defenceAsk's floors (tungsten 150 ...) whether or not anything makes them; blockDepth 0 if
the core holds one module's worth; payItself returns the plan with no maker found; walls never charged, and tungsten walls
preferred. *Fix:* the timeline's finish for the whole bill, walls in, chains for every item; never finishing, not offered.

5.11 **Smaller**: path cost used as a straight range (near = range + 10 + core size against a cost with +6 a built tile:
the wall line may stand within the enemy's range of the core), buildings outside the line not protected, arrives indexed on
the wrong lane; wet configurations built dry (the water port dropped as optional) and two configurations of one gun share the
last's ammunition; feeders on either side of the wall line (those in front die first); the defence's lag never measured
(expect() skips defences) and stage 0 builds one module where four were weighed; one flying loss makes a raid "air"; losses
in a main assault counted as raids; atStake compares a whole plan with a sum of item counts.

5.12 **Constants acting as rules**: Planner reach 90, 25 tiles from the source, roomy >= 40/81, lanes merged within 6, middle
within 8, wallReach 16, notch 3, air site at core size + 16, air sources within 50 degrees, power 0.4 a unit, heater 100,
heavy-only turrets for tier >= 3, open capacity = closed / 2 unmeasured; Post wall gap 2, depth 2, row gap 3, 24 steps, back
2/6; Defence leastRate 0.25, a refed turret 1/s, a fixed ammunition list; Raids 15 tiles, 120 s, 3 losses, 240 s, cover - 3
tiles; Guard 0.6/0.7 of reach, 4-tile shift, spacing 4/6, k = 4; Match 8-minute wave horizon (>= 3 waves), 3-minute budget.

## 7. Layout (econ/Layout, Router, Grid, Cells, Packing, Wiring)

Measured on one aegis stage: 42M A* states (34M in searches that succeeded; 46k a search with docks), 6 of 8 negotiations
never settled (all 14 rounds), re-routing contested nets 20 of 25 s, the stage laid out 4 times from scratch (43 s). Of
about 4300 "sand from cliff-crusher" failures 2331 expanded fewer than 50 states, of 7300 "hydrogen from electrolyzer" 5668:
sealed exits. The commonest drop at the final commit: "no line to the core: no way through what the other lines took".

7.1 **Lines of one item split into nets that may not share a tile** (Layout.nets, Router): sand (not storable) and every
liquid get a direct net a taker from whole makers; a dockable item a bus, plus a net a design, plus a spare-to-core net whose
makers are the bus's again (a second line to the core); the negotiation counts two nets on one tile a conflict whatever they
carry. In the game a maker hands its item to any line beside it. Crushers sealed in, negotiations that never settle, lines
doubled. *Fix:* each item routed as one flow network, pieces of one item shared, splits by docks and routers; makers to
takers by min-cost flow on path distances; no spare net when the bus ends in the core.

7.2 **The final commit in catalog order, the negotiation blind to what it checks**: committed by resource id (graphite,
sand, silicon, beryllium, ..., water, ozone, hydrogen), a refused net laid again against all committed; during the rounds no
net is on the grid, so clean() and bridgeClear() cannot see a duct pushing into another's side or bridges linking across
nets. Quiet tiles claimed like pieces (two docks sharing one count as contested); conduits refused on quiet tiles. Beryllium,
with the longest lines, commits after graphite, sand, silicon and loses. *Fix:* commit's constraints priced inside the
negotiation; a quiet tile conflicts only with what takes items; a last conflict settled by ripping up the net whose loss
costs least (flow x price x time).

7.3 **Placement keeps no exits open** (Cells, Packing.reserve, Layout): a ground maker's apron guarded only from other
ground sites; the room penalty a count of free tiles whether they lead anywhere; an electrolyzer's one hydrogen side snapped
against a refabricator ("8 states from 8 starts", "3 from 3"); the retry stress only doubles wire-length weight. *Fix:* exits
as capacity: each maker keeps an exit to open ground for every net it starts (a small BFS); power reach checked alike.

7.4 **Distances as the crow flies** (allocation, placement cost, dock tours, makerNear): nothing checks a line can get from
maker to taker; a crusher at 135,178 given to a furnace at 247,116, 174 tiles off, the search flooding a 12.8k-state pocket;
Grid.ground one flag for all blocks, excluding arkycite and slag where reinforced conduits may go. *Fix:* distance fields an
anchor on the tile graph with bridge hops (items, floating liquid), component labels; used in allocation, Cells, tours,
makerNear and the line estimate (3.1).

7.5 **Failures silent or misreported**: a branch that fails leaves the trunk and the taker marked fed (the model counts its
maker's output); a trunk cleared at commit keeps feeds, reasons go stale; drawShare ignores n.ok; makerNear uses the
planner's placeholder coordinates for cell machines ("a plasma bore of its own at 182,93" for a module standing at 207,81);
one failed tap drops every map maker of that item ("its 6 makers are left out" -> "first locus at never"); "no line to the
core" blames n.first and its site is given up whatever the cause; partial shortages only logged. *Fix:* after routing, what
each taker and the core really get over the laid nets (2.8), each deficit with its cause (sealed exit, other component,
congestion, spur) tied to its fix (re-place, re-assign, re-negotiate, a maker).

7.6 **Router cost**: minStep 1 a piece where a reinforced conduit costs 2 and reserved/lane/congestion terms inflate steps
(A* becomes Dijkstra); goals sampled (first 48 lines, every n-th join tile: inadmissible); no reachability test (unreachable
goals flood their region: 8.2M states); direct() tries makers one at a time; up to 5 re-searches for self-crossing; every
unfed round rebuilds the whole layout. *Fix:* a backward Dijkstra a goal a stage as the estimate, shared by every net to it;
component labels for an instant "no way"; one multi-source search a net; warm-started re-layouts.

7.7 **Bridges**: no chaining (N hops cost 2N bridges, no turn on landing, one-tile islands unusable); lines joining a
bridge end from the side (it accepts only its link: the branch delivers nothing, and check() misses it). *Fix:* a landed-
bridge search state; accepts() by whether the bridge has a link.

7.8 **Bus docks and spurs**: if the spine cannot pass every dock all takers go to spurs, which may start only at plain ducts
with a straight piece before them, within 60 tiles, on a spine laid without them; branches join only before the first dock.
"No spur found" for graphite to furnaces (3331 lines) and silicon and beryllium to fabricators (about 2600), the tank
fabricator no silicon reached among them. *Fix:* spurs from any spine tile at the cost of a dock; drop only the dock that
blocks; route the spine again with a fed-nothing taker as a stop; joins downstream where the flow covers later docks.

7.9 **Smaller**: port lanes exclusive to one item for 3 tiles, overwritten where two overlap, +6 a tile on their own line;
every building a permanent obstacle (derelict ones too); Works.java dead code.

7.10 **Constants acting as rules**: Router congestion 0.6 x 1.7 a round, 14 rounds, 250k states, turn 0.15, unseen +2, spur
radius 60 and 0.05, tap radius 120, tour 0.7, 5 crossing retries; Packing keep 3x the draw, reserved 3 a tile (1.5 on
aprons), lane 3 at +6, worth over 40 tiles, 1.5 s; Cells radius 55, first aim 0.6, 60 candidates, liquid x2, touching -2,
wrong neighbour 30, room 8(2n+1-free), one-sided liquid 40, 0.08 a tile, outside cover 40, missing partner 40 tiles,
annealing 20 -> 0.05 over 40000; Layout 3 tries, x2 stress; Wiring 0.01 a tile of beam, 0.1 x reserved, 200k states.
*Instead:* the router's own cost (items at chains' prices plus build and fly seconds) on path-distance fields; blocked
access as constraints or flow lost x price; reserved fields at the discounted demand to come; budgets by bounds and
convergence; unseen ground at its expected wait.

## 6. The overhaul, in order

A. **One combat model** (ops/Combat), used by Force, Threat, Forecast, Command.play, Tactics, Push, Hold, Allot: a unit's
or turret's damage a second against each class of target (unit, building; air, ground) with armour, the team's and the
difficulty's multipliers, status (boss), splash against direct, pierce, reload multiplier, continuous bullets; health
with armour once; walk time from a path-distance field a kind; one Lanchester resolver with an order of targets (units
first, then buildings) giving who wins, what is left and the time to take a core; uptime factors fitted to the fights
seen, in place of 0.25 and 0.55.

B. **The command layer's game** on it: the core's health shared in fights at home (1.5); the other side's timing its own
choice, the worst for us (1.6); the mirror only of kinds that reach (1.7) and with a defence of its own (1.8); its
decision handed down to the army as the push it counted on.

C. **Tactics** on the same model and the same objective: every unresolved fight valued at the horizon, holding at its
cost; joint attacks; their replies by reach and path; the switch cost dropped (the walk already counts).

D. **The economy model honest**: lines and build time estimated from the path field and calibrated against layouts;
what stands goes to the core only (taps as ways of their own); building as a demand a second; after the layout, the plant
solved over the nets as laid (what is not fed gives nothing); a stage whose laid-out model never makes its unit not
begun, the next way taken.

D'. **The layout**: one flow network an item (7.1), commit constraints in the negotiation (7.2), exits kept (7.3), path
distances everywhere (7.4), honest failure reports (7.5), a sharper and reachability-checked router (7.6), chained bridges
(7.7), spurs from anywhere on the spine (7.8).

D''. **The defence**: lanes by the game's own costs and the attacker's best reply (5.4); capacity at the supply the map
gives (5.5); a turret's force from its first ammunition (5.6); walls by site (5.7); kinds ranked by hold per cost with
their own ammunition (5.8); modules spread by leak saved (5.9); the bill checked (5.10).

E. **Execution without rules**: put off by whether anything that stands brings the item in, repairs alike; the defence
planner asked for what the Forecast has at our core, with a budget from the model.

F. **The constants of 3.13 and 4.10** replaced one by one by what they stand for, as each layer is reworked.

## 8. A scan for hidden errors (2026-10-21, after c17)

Five readers over the whole of Operations (economy model, layout, execution and Match, army and defence, the command
layer), each claim checked against the code and the game's bytecode before it was fixed. Fixed (commits 651143b, 0a24209
and the one after):

- **Model**: taps off the map's own lines never reached the flow model (a fabricator where every site was taken could not
  be fed in the model: onset made nothing in ninety minutes); a fed machine read as backed up (by all it holds); a unit
  maker's first batch filled at what was left after its own draw; a refabricator handing on to the next kept "holding";
  the place of an extra left free; makerNear without fits(); gated plans dropped what stands of blocks they may not build;
  the core's capacity of one core block; a liquid maker's place given back blank and first; the fog rule toggled by
  several planning threads at once.
- **Predictor**: yard pace by all yards at once and at the pace of those now standing (12% where it was full); health
  under the team's multiplier left out for what stands (hard campaign: 80%); waves without the difficulty's count and
  their shields; their units already on our side walked from their base; lightning's default damage; point-defence,
  repair, build and mining weapons counted as attacks; fights worn alike over kinds that cannot be hit; dry plans taken
  as they were weighed (now laid out best-first and weighed again on what they really cost).
- **Execution**: put-off steps waited on the first; steps on unseen ground kept a stage from ending; machines between
  deliveries read as idle (a stage given up 16 silicon short); earlier stages' losses counted again every two seconds;
  a guard's pause timed the stage's job out; other hands' build plans left on the unit; all sites in reach begun at once
  (now one at a time, as the game's builder); parts on wrongly facing blocks taken as built; deferred parts and cuts lost
  at a stage's end; repeated power mends; the audit's time counted twice; the campaign's last wave won early.
- **Layout**: a design's unit exits open to nodes and bridges; an unpowerable machine taken into the grid; a trunk duct a
  drill feeds from the side made a dock; a failed commit clearing another net's quiet tiles; the core line never cut when
  nothing later needs its item.
- **Army and defence**: two squad AIs on our side, and the game's taking the tactics search's units (it stands by now);
  holders' spots redrawn every two seconds; refabricator trips called back; power turrets loaded without power; anti-air
  turrets counted as none; the distance cache kept past a growing map area; walls and ground priced by the flow field
  against a control-pathfinder attacker (a million a tile: it goes round); attacks valued as if every squad arrived at
  once (now in order of arrival).

Left (checked, smaller or needing more than a fix): the hold and push layers (off under tactics) aim at the mean of all
foes and treat every order as theirs; the dodger's "stand still"; boats counted as reaching land; wall lines with gaps
over standing ducts; the no-build ring round enemy cores; Router bridges taken by the same net's own bridges and the
negotiation's unpriced starts and quiet tiles; the rules' build-time and unit multipliers; a large cliff crusher can be
chosen where its sites rank first though surge alloy can't be had (the site value by ground's first recipe hides it:
valued by the site's own block, gen maps planned nothing); walls before a turret take our units' fire in the game but not
in the tactics model (a single breach behind walls took twenty stells on gen-1).

## 9. Why the generated maps were lost to the Brain (2026-10-22, after c19)

c18 and c19 got back to c16's 18 of 38 against the Brain, but every game on gen-3 to gen-6 was lost, at seven to ten
minutes, to three or four stells at our core. Read off the logs, game by game:

- **The tactics' leaf counted a base race as both ends at once.** The home leaf took off what we stand to lose if our
  core falls and the attack leaf added what we stand to win if theirs does, both discounted alike; the game ends at the
  first last core to fall. With more of theirs at stake (turrets, yards) the sum favoured attacking while our own core went
  first. Now a race: each attack's time is worked out (walk and fight), the earlier of our home falling and their last core
  taken ends the game, and only what is decided before that counts; a last core falling within the playout ends it there.
- **The core unit never mended.** The core units' repair beams give a building back about 350 health a second (a tenth of
  that for five seconds after a hit); the Brain's core unit mends whatever is hurt and quiet, so a core our tanks took to a
  fifth was whole again before the next attack, while ours sat at 39% between attacks. Now the hand mends with the beam
  whenever that gives back more a second (a building's cost times the share of health given back, the way there counted)
  than its work would (what the job in hand puts up a second, or digging). Under fire only our core, and only while the
  tactics find our home falling: mended at the tenth under fire whenever it outweighed building, the core unit spent half
  the game mending and the second stage of gen-5 took all of it.
- **Their core unit in the tactics model.** In the playout each side's core units mend what is hurt and quiet; at an
  attack's leaf the target has what it is mended to by the time the first squad gets there, and their core unit puts up
  turrets meanwhile (of the kinds it has built, as many as the time and what it is taken to have pay for: our stock, the
  sides starting alike, and what it is seen to spend a second); at their core, what their yards turn out meanwhile joins
  the guard, as ours joins what holds our home.
- **Their ground units fire on our core alone.** The game's ground AI walks for the nearest core of the other side's and,
  once in reach, fires on it whatever stands about it; the Brain's tanks are led so. In the playout a group fires first on
  the building it is told to attack (ours on their target, theirs on our core); a group led by the ground AI has no choice
  of scripts (the worst reply had it go home, which it never does); and at the home leaf, when all that comes fires on the
  core alone, the core takes d·H/2D in all while ours wear them down (falling at the root of the quadratic if that is more
  than it has), not the square law that spread their fire over our units.
- **Execution: a step that got no further was tried again at once.** Put off after a minute without progress (a bore whose
  place a node had taken), it was affordable and so first again, a minute more, and so on: the rest of the stage waited
  eight minutes on marsh. Now it waits for the rest of the order, and one that gets no further again is given up (kept
  from the watch, that would put its parts back for ever).
- **Tried and left off: the mirror planned from their base** (-Dops.mirrortheirs=true). Our fastest plan in their place
  missed a side whose base had graphite and a vent near (gen-4: the model had their tanks two minutes late); planned from
  their base it saw them, but the prior pace (the faster quarter of the Brain's first unit against our own fastest plan) is
  far too fast against the Brain's own potential (marsh: a tank at 3:06 in its plan, the first after eleven minutes in the
  game), and the stages turned to defences first: gen-3 and gen-6 went from won to lost both ways. Needs the prior measured
  against the other side's own plan.

After c20 (not yet in a batch):
- **Fire on the way** at an attack's leaf: each squad loses what the turrets that do not guard the target fire on it over
  the stretch of its way within their reach (lone ships sent past two breaches at a core were lost one by one).
- **No stock taken for theirs**: their core unit's turrets are paid for at the pace they are seen to spend, not out of a
  stock taken to be ours (on siege, both starting with three thousand, eight breaches were counted by our arrival and
  sixteen units stood at home all game).
- **With no units of ours**, the tactics still play out the home fight, so the core unit mends the core under fire when
  it falls (ravine: one locus took a core with no unit of ours about).
- **The fog in the campaign** (ravine 636 of 646 seconds, crevice 416 of 446, lake and karst some 300, waiting on radars
  or on ground to be seen): a radar was taken to see in a fixed 45 s; now only once there is power (one of no power sees
  nothing), and only where ground seen lies within its reach of the place; the hand puts a radar nearest what has power
  and joins it at once; the urgent stage's next step on ground not seen no longer holds back the radar's node (they waited
  on each other all game); what has been seen is read off the side's own record, not through the rule other planning
  threads may have off.

Still open from these maps: gen-4 makes no unit by ten minutes (the nearest graphite and vent are 130 tiles off: lines of
300 to 450 beryllium each); split bans tank and ship yards and its only floor tungsten lies past a gap no line crosses (the
map's own base carried it by payload drivers, which cost tungsten): no side builds anything; on peaks only flyers reach the
Brain and avert wants tungsten flowing to its refabricator (the core holds 650, but Erekir's duct unloader can't unload a
core); intersect wants legged units (mech yard: 10 tungsten) and the layout could not feed sand to its furnace.
