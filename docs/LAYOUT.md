# The layout generator (bp.Gen)

A problem (a small map, what stands on it, what is asked for) in; a layout put down on it out, checked by the game's
own rules (bp.Check) and run in the real engine (xtest.BpBench). Every step is an optimisation; the game's rules are
read off its code (v160.7), its numbers off its objects, nothing written in by hand.

    sh bp/build.sh
    sh bp/run.sh bp.Gen build/bp-data test/bp/gen-mixed.prob [out.txt] [seed]   # writes out.txt and out.png
    java -cp "../Mindustry.jar:build/classes:build/test-classes" xtest.BpBench build/test-data out.txt

## Steps

1. **Balance** (Balance, SCIP): which machines, how many, how much each works; heat packed face by face; goals may be
   wildcards (`scathe:*+coolant`).
2. **Bodies**: a machine, or a machine that needs heat with its heaters and redirectors (Heat, CP-SAT: every way a heater
   or redirector stands whole-face against the machine or a redirector, two levels; no overlap; a redirector gives at most
   what faces it; the need met; tiles left free against the machine for its lines, and against each heater for its own
   lines and power).
3. **Placing** (Placer, annealing over places and turns). Costs: overlap, where a block cannot stand (Build.validPlace),
   solid blocks on a beam's way; the gap from each taker to its nearest maker (tiles of carrier); line ends matched to free
   tiles one each (a tile holds one block; a liquid that comes out of one side only on that side; power and drains count;
   Kuhn's augmenting paths); crowding (RUDY); a maker handing the core what is no goal.
4. **Who feeds whom**: a transportation problem per thing (GLOP), least rate x gap; a source gives at most what lines
   from its sides carry. Each maker's share is a line or more, of ducts (15/s) or surge conveyors (25/s, 46/s powered),
   whichever costs less for the rate; carriers and rates from Carriers (found by class among Erekir's blocks, rates
   measured in the engine).
5. **Lines** (Router, negotiated congestion, PathFinder). A* over tile x entry direction x feed; ducts, armoured ducts,
   bridges (ends solid, a span of up to 4), routers and liquid routers as taps on a line's straight runs, surge lines
   (only the first tile loads), reinforced conduits and bridge conduits. What goes wrong is priced and negotiated: two
   blocks on a tile; a neighbour handing a line something else (Duct, ArmoredDuct, DuctRouter, DuctBridge,
   ArmoredConduit, DirectionLiquidBridge accept rules; a bridge end that reaches another bridge takes as a start); a
   bridge reaching one unmeant (same-product merges and chains allowed); a surge line handing its stack to another; a
   path over itself. Sharing grows dearer slowly and stops; what stays fought over grows dearer for good.
6. **Drains**: a liquid a generator must be rid of (ConsumeGenerator.explodeOnFull: neoplasm) goes into a void if one
   stands, else out of a conduit's open end (Conduit.leaks; a bridge end does not leak) onto the tile that harms least:
   none on a liquid floor it cannot lie on (Liquid.canStayOn: neoplasm vanishes on slag); on ground, a field of puddles
   (as much as a puddle hands its neighbours over what it loses, about 40 tiles for neoplasm) costed by every block in it
   holding the liquid its puddles spread into (CellLiquid.spreadTarget).
7. **Power** (Power): Steiner tree of beam nodes by shortest paths over the game's touching rule
   (Building.getPowerConnections) and beam rule (BeamNode: the first block with power along each axis within range, not a
   power node; nodes are solid, so not on a beam's way). Surge conveyors use and pass power.
8. **Mending**: lines that could not be laid, tiles still fought over: the parts about them ask for more room and the
   placing goes on from where it is, cool; the best of the tries is kept.

## Rules found on the way (from the source)

- A beam drill's beams stop at the first solid tile: ducts, conduits, routers, surge conveyors pass, bridge ends and beam
  nodes do not.
- Surge conveyor (StackConveyor, outputRouter off): the first tile of a line takes from behind and the sides from
  anything, the rest take nothing; the last hands ahead; a line of one tile loads nothing.
- A bridge end that reaches another bridge ahead within range links on, and then takes from about it like a start.
- Neoplasm on the ground: puddles cap at 70, spread 0.3 a tick to each neighbour, go under blocks, turn the water of
  blocks next to them into neoplasm.
- The flux reactor grows unstable and blows up within seconds when short of cyanogen at its heat; a layout that feeds it
  must be whole.

## The bench

`prime` in a spec: every block starts with its stores of what it uses full, and runs 30 s under the team's cheat rule
(everything works as if fed) before measuring, so what is measured is the layout going by itself; how it is started is
left out for now. Blocks lost are reported with what they held.

## Not yet

Ground sites (drills, pumps, vents: enumerated by the game's own yield functions, exclusive in the balance, drills
contending with the factory for ground); unit yards (a fabricator's front onto its refabricator); mixed lines with
sorters (left out for now).
