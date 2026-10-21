# Algorithms for an adaptive Mindustry (Erekir) blueprint generator — survey and recommendation

Scope: given a small terrain region and a production goal, output a working layout of machines,
ducts/bridges/routers, conduits and beam nodes. Build time and cost are ignored. The hard
constraint is space; every flow must be routed with enough capacity. No implementation code here.

---

## 0. TL;DR

* **Treat it as an EDA (chip-design) problem.** Machines are *cells* with *pins* (accepting/dumping
  sides). Item/liquid flows are *nets* that carry a throughput demand. Ducts, bridges and conduits
  are *routing resources* on a single-layer grid. Beam-node power is a second "layer": it is a
  connectivity problem, not a capacity problem. Unintended adjacency (a machine dumping into a
  duct next to it) is a *design-rule violation*, the same kind of thing as a short circuit.
* **Main pipeline (recommended, all pure Java):**
  integer balance model (master) → site-restricted **simulated-annealing placement** with a
  congestion estimate → **PathFinder negotiated-congestion routing** on a direction-expanded tile
  graph (bridges as jump edges, item-type purity and dump-adjacency as negotiated resources) →
  power and liquids routed last on the leftover tiles → **static flow verification** (+ optional
  headless simulation) → **logic-based Benders feedback**. Failures go back to the master as
  *cut-condition cuts* (the demand crossing a bottleneck cut must fit its capacity) and as no-goods.
  This is the family that has actually scaled: FPGA routers (VPR), CGRA mappers (SPR) and the
  Factorio work all converge on it.
* **Second pipeline (complement):** **exact CP/SAT** on small windows: precomputed "macro-cells"
  (a machine with its local I/O stubs and node spots), plus exact repair of the windows where
  PathFinder fails to converge. Published exact joint place-and-route on grids tops out at about
  **100 tiles in minutes** (Factorio, Patterson et al. 2023). So exact search belongs on
  sub-problems, not on the whole map.
* **Riskiest part:** keeping the routing model faithful to the game's transfer semantics (machines
  dumping into every adjacent acceptor, router back-flow, mixed-item jams, beam lasers blocked by
  buildings), and closing the loop so a failed route turns into a *useful* master cut rather than a
  bare no-good.

---

## 1. Restating the problem in EDA terms

| Mindustry concept | EDA / OR analogue |
|---|---|
| Machine (1..5 tiles, fixed footprint, maybe needs a vent or ore) | Macro cell with a restricted placement domain (like FPGA site types: DSP/BRAM columns) |
| Item flow of type *k* from producers to consumers at rate *d* | A multi-terminal *net* (Steiner tree) with bandwidth demand; integer multicommodity flow |
| Duct (directional, capacity *c*) | Directed unit-capacity routing track; a tile holds one block |
| Duct bridge (jumps ≤ r tiles over anything) | A "via"/long wire: a jump edge whose endpoints use tiles but whose span doesn't |
| Duct router / overflow duct | A Steiner/branch point with splitting semantics |
| Mixed items forbidden | Net purity: one tile is used by one commodity (like a wire being on one net) |
| Machine dumps into *any* adjacent acceptor | Implicit edges created by adjacency, which acts as a design rule (spacing/short) |
| Beam node lasers (axis-aligned, range-limited, stop at the first building) | A visibility graph whose edges depend on the placement: a connectivity (Steiner) problem |
| Conduits / liquid bridges | A second commodity class on the same tile resource |

Facts about our instances that drive the choice of algorithm:

1. **Tiny grids.** Hundreds of tiles, tens of machines, ~5–40 nets. Exact methods become thinkable
   at this size, but only just (see §4). Heuristic routers are trivially fast here.
2. **Placement domains are heavily restricted.** Drills go on ore, some machines go on vents, and
   only buildable floor counts. This prunes CP search a lot and also makes SA moves cheap: swap
   among legal sites.
3. **Single routing layer.** There is no second metal layer: crossings must use bridges (jump
   edges), so planarity limits are real. That is the main way our problem differs from VLSI global
   routing, which has many layers. It resembles **single-layer routing**: PCB escape routing,
   field-coupled-nanocomputing tile layouts, microfluidic chips, Factorio belts.
4. **Throughput is discrete.** The master already decides machine counts. Each net's rate divided
   by duct capacity gives an integer number of parallel tracks or bridge chains. Routing therefore
   reduces to routing *integer track bundles*. That is good news: PathFinder and SAT both handle
   unit-capacity resources natively.

---

## 2. Routing algorithms (closest to proven, most important)

### 2.1 Maze routing: Lee / A* (base primitive)
* Lee 1961, "An algorithm for path connections and its applications". Hadlock 1977 and A* variants.
* Solves: one shortest path for one net on a grid with obstacles. It is the inner kernel of
  everything below.
* **Fit:** essential. For directional ducts, search a *state-expanded graph*: state = (tile,
  heading). Edges: go straight, turn (only where the duct type allows input from that side),
  bridge jump (up to range r, landing on a free tile, with the bridge's own input/output side
  rules), enter a sink pin (a tile adjacent to a consumer that accepts item k). Costs are additive,
  so Dijkstra/A* stays exact per net.
* **Pitfall:** sequential maze routing is order-dependent. The first nets take the good tiles and
  later nets fail. That is exactly what negotiation fixes.

### 2.2 Negotiated congestion — PathFinder (strongest candidate)
* McMurchie & Ebeling, "PathFinder: A Negotiation-Based Performance-Driven Router for FPGAs",
  FPGA 1995. Refined in VPR: Betz & Rose, "VPR: A new packing, placement and routing tool for FPGA
  research", FPL 1997; Swartz, Betz & Rose, "A fast routability-driven router for FPGAs", FPGA 1998.
  It is still the core of modern FPGA routers (e.g. RWRoute for Xilinx devices, 2021, and parallel
  variants such as arXiv 2407.00009, 2024).
* Mechanism: route every net with A*, temporarily allowing overuse. The cost of a resource *n* is
  `(b_n + h_n) · p_n`: base cost, plus a history term that grows on resources overused in earlier
  iterations, times a present-congestion term that grows with current sharing. Each iteration rips
  up and reroutes all nets (or only the congested ones) and raises the penalties. It stops when no
  resource is overused.
* **Fit to our problem: very good.**
  * Grid tiles with capacity 1 are exactly FPGA routing nodes with capacity 1.
  * Multiple commodities are free: every net competes for the same tile resources, so purity is
    automatic once a tile can hold only one duct.
  * Multi-terminal nets: route as a Steiner tree, as in VPR. Grow from the existing tree; a branch
    point becomes a router or a machine's own fan-out. Throughput bundles become *k* identical
    sub-nets, or one net whose tree edges get capacity *k*.
  * **Design rules become negotiable resources.** Make a "contact" resource for every (machine-side
    tile, accepting block) pair. A duct tile whose back or side faces a dumping machine creates an
    unintended edge. Price that pairing like an overused node, unless the dumped item is the one
    the duct is meant to carry. This is how PathFinder-style routers handle conflicts beyond simple
    node sharing. It keeps us within the "optimise, don't add hard rules" policy: the conflict is a
    cost that negotiation removes.
* **Scale:** routes FPGAs with 10^5–10^6 nets. Our instances are 4–5 orders of magnitude smaller,
  so 50–100 negotiation iterations take milliseconds to tens of ms in Java.
* **Implementation in Java:** easy (a few hundred lines): A* with a binary heap, per-node
  occupancy and history arrays, a net-ordering loop. No dependencies.
* **Pitfalls:** (a) It may *not converge* when the placement is truly unroutable. It then
  oscillates, which is what signals the need for placement or master feedback. The history term
  shows which tiles are oversubscribed, and that is exactly the information a Benders cut needs.
  (b) Single-layer planarity: on FPGAs, a detour around a congested area is always available; for
  us, the only way through a wall of ducts is a bridge. Bridges must therefore be cheap and
  well-modelled jump edges, or PathFinder will thrash. (c) Directional edges need careful pin
  modelling, e.g. a duct entering a machine must end *pointing into* it.

### 2.3 Rip-up and reroute, and Conflict-Based Search (multi-agent pathfinding)
* Classic RRR: Nair 1987, "A simple yet effective technique for global wiring". Detailed routers
  such as TritonRoute (Kahng et al., ICCAD 2018 / TCAD 2021) and Dr. CU (Chen et al., TCAD 2020)
  combine RRR with negotiation and design-rule costs.
* **CBS:** Sharon, Stern, Felner & Sturtevant, "Conflict-based search for optimal multi-agent
  pathfinding", AIJ 2015. It is optimal: branching on conflicts between paths.
* **Directly relevant precedent:** Burniston (Univ. of York MEng thesis, 2024, "Factorio Blueprint
  Layout Optimisation") used SA placement plus CBS belt routing with A* low level, on 5×5, 10×10 and
  15×15 blueprints. **CBS became too slow at 15×15** for the harder configurations. The thesis also
  hit a semantic bug: belts connected into the sides of undergrounds, which is illegal in game.
  Lesson: optimal CBS is the wrong tool, because we need feasibility, not optimal path sums.
  PathFinder is the "soft" version of CBS that scales.

### 2.4 Multicommodity-flow / ILP routing
* Global routing as integer multicommodity flow: Carden, Li & Cheng 1996 (TCAD); Albrecht,
  "Global routing by new approximation algorithms for multicommodity flow", TCAD 2001;
  BoxRouter (Cho & Pan, DAC 2006) mixes ILP with negotiation. BonnRoute (Gester, Müller,
  Nieberg, Panten, Schulte & Vygen, "BonnRoute: Algorithms and data structures for fast and good
  VLSI routing", TODAES 2013) uses min-max resource sharing (Müller, Radke & Vygen, Math. Prog.
  2011), with provable approximation.
* Path-based formulation with **column generation / branch-and-price**: Barnhart, Hane & Vance,
  "Using branch-and-price-and-cut to solve origin-destination integer multicommodity flow
  problems", Operations Research 2000.
* **Fit:** conceptually perfect: integer commodity flows with edge and node capacities are exactly
  our nets. Practically, edge-based ILP on directed state graphs with ~10 commodities × 400 tiles ×
  4 headings means tens of thousands of binaries. That is solvable by a good MIP solver, but pure-
  Java MIP (ojAlgo) will struggle, and the LP relaxation of unsplittable multicommodity flow is
  weak. Path-based column generation is elegant (the pricing problem is the same A*), but
  branch-and-price is a significant engineering project.
* **What to take from it:** the **cut condition**. For any tile cut *S* that separates terminals,
  the total track demand of nets crossing *S* must be ≤ the cut's capacity, counting bridge jumps
  over it. It is a necessary condition for multicommodity feasibility, and it is linear in the
  master's machine counts. That makes it an ideal Benders feasibility cut (§5).

### 2.5 SAT / SMT routing
* Nam, Sakallah & Rutenbar, "A new FPGA detailed routing approach via search-based Boolean
  satisfiability", TCAD 2002: exact detailed routing, which can *prove* unroutability.
* **MonoSAT** (Bayless, Bayless, Hoos & Hu, "SAT Modulo Monotonic Theories", AAAI 2015; Bayless's
  UBC PhD thesis 2017): SAT with built-in graph reachability and max-flow predicates. Applied to
  multi-layer escape routing for ball-grid-array PCBs and used in production at AWS. It is the most
  natural *exact* formulation for "route these flows on a grid", because flow and reachability are
  native rather than bit-blasted.
* **Fit:** good for proofs of infeasibility and for small exact repairs. MonoSAT is C++ (native
  library, GPL/MIT), so it is not a pure-Java dependency. Sat4j (pure Java) can run a plain CNF
  encoding, at roughly an order of magnitude below state-of-the-art CDCL solvers in speed.

---

## 3. Placement algorithms

### 3.1 Simulated annealing (TimberWolf, VPR)
* Sechen & Sangiovanni-Vincentelli, "The TimberWolf placement and routing package", IEEE JSSC 1985.
  VPR's adaptive annealing schedule (Betz & Rose 1997) is the de-facto standard for FPGA
  placement, and still state of the art for small and medium devices.
* **Fit: very good.** Moves: relocate a machine to another legal site (ore, vent and floor domains
  respected), swap two machines of compatible size, rotate. The objective is a routability proxy:
  half-perimeter wirelength weighted by track count, plus a **congestion estimate** such as RUDY
  (Spindler & Johannes, DATE 2007: rectangular uniform wire density) or a probabilistic
  per-tile demand, plus a dump-adjacency penalty, plus power reachability.
* **Routing in the loop:** our instances are so small that a *real* PathFinder route can be run
  every N moves, or at each temperature step. It can be incremental: rip up only nets touching the
  moved machines. This makes it "routability-driven placement", and SPR does exactly this for CGRAs
  (Friedman, Carroll, Van Essen, Ylvisaker, Ebeling & Hauck, "SPR: an architecture-adaptive CGRA
  mapping tool", FPGA 2009). Burniston (2024) did SA + routing for Factorio, and SA escaped plateaus
  that a hill climber could not.
* **Java:** trivial with no dependencies. Timefold Solver (formerly OptaPlanner, pure Java) offers
  generic local search with incremental scoring, but a hand-written SA is simpler and faster here.
* **Pitfalls:** tuning the move mix; making cost evaluation incremental; the congestion proxy can
  disagree with the router on a single-layer grid. Mitigation: periodically calibrate the proxy
  against real routes (a measured estimate, not a hand-set constant).

### 3.2 Analytical placement (quadratic / electrostatic)
* SimPL (Kim, Lee & Markov, TCAD 2012), ePlace/RePlAce (Lu et al., TODAES 2015; Cheng et al., TCAD
  2019), DREAMPlace (Lin et al., DAC 2019).
* **Fit: poor.** These methods shine at 10^5–10^7 standard cells, where a continuous relaxation is
  meaningful. With 10–30 macros on a few hundred tiles, irregular legal-site masks (ore, vents,
  walls) and placement legality that is everything, the legalisation step does all the work. Skip,
  or at most use for a quick initial spread.

### 3.3 Facility layout problem (FLP) / QAP
* QAP: Koopmans & Beckmann 1957. Unequal-area FLP MILP: Montreuil 1990 ("a modelling framework for
  integrating layout design and flow network design"); Meller, Narayanan & Vance, "Optimal
  facility layout design", Operations Research Letters 1998; Sherali, Fraticelli & Meller,
  "Enhanced model formulations for optimal facility layout", Operations Research 2003. Survey:
  Anjos & Vieira, "Mathematical optimization approaches for facility layout problems: the
  state-of-the-art and future research directions", EJOR 2017.
* Layout with I/O points and aisles: Smith, Norman & Arapoglu, "Integrated facilities design using
  a contour distance metric", IIE Transactions 2001; Kim & Goetschalckx (IERC 2003): concurrent
  block layout, I/O points and aisle design, which beats doing them in sequence.
* **Fit: moderate as a concept, poor as a solver.** FLP minimises flow × distance on continuous
  coordinates. Exact unequal-area MILPs solve only about **11–15 departments** to optimality, even
  with commercial solvers and strong cuts. They do not model single-layer track capacity, so
  distance is a weak proxy for routability on a tiny grid. Useful idea: the flow × distance term
  (weighted by track count) is the right base cost in SA (§3.1). The I/O-point literature confirms
  that **placing ports together with the blocks** beats placing blocks first.

### 3.4 Rectangle packing (CP)
* Huang & Korf, "Optimal rectangle packing: an absolute placement approach", JAIR 2013; Simonis &
  O'Sullivan, "Search strategies for rectangle packing", CP 2008; Beldiceanu & Carlsson's
  `diffn`/geost constraints; OR-Tools CP-SAT `NoOverlap2D`.
* **Fit:** this is how to place machines *with reserved routing channels*. Inflate each machine
  by a halo for its pins, or add virtual "channel rectangles". CP with `diffn` plus domain
  restrictions (ore/vent masks via table constraints) finds dense packings of a few dozen
  rectangles in milliseconds to seconds. Choco-solver (pure Java) has `diffN` and cumulative
  constraints.
* **Pitfall:** packing alone ignores the *topology* of the routes. A halo makes packing
  conservative, which wastes the space we care about most, or optimistic, so routing then fails.
  Packing therefore makes sense only as the inner step of a loop that learns halo sizes or channel
  demands from routing failures (Patterson et al. do exactly this; §4).

---

## 4. Exact joint models (CP / SAT / SMT / ASP / MILP) and game-specific prior work

### 4.1 Factorio / Minecraft / game work (all found)
| Work | Approach | Scale reached | Lessons |
|---|---|---|---|
| **Patterson, Espasa, Chang & Hoffmann**, "Towards Automatic Design of Factorio Blueprints", arXiv 2310.01505 (2023, St Andrews) | Essence Prime → Savile Row (CP/SAT). **Three stages, "Benders-inspired":** (1) recipe/assembler counts, (2) bin-packing of assemblers and inserters, ignoring item types, (3) conveyor and inserter routing with a directional encoding, an acyclicity encoding like SAT-modulo-graphs, one item type per tile. If a stage is infeasible, the previous stage produces a new solution; earlier ones are stored as no-goods | 3×5 to 8×8; **~100 tiles in several minutes; beyond that "runtimes increase rapidly and soon become intractable"** | A unified model failed because of state explosion when flow rates are represented. Rates are not modelled in the layout stage, so it can miss starvation. **Weak information sharing between stages causes repeated failures**, which argues for informative cuts rather than no-goods |
| **Reid, Miralavy, Kelly, Banzhaf & Gondro**, "The Factory Must Grow: Automation in Factorio", arXiv 2102.04871 (GECCO 2021 submission) | Single source → sink belt routing as an IP; benchmarks SA, genetic programming and evolutionary RL | Up to 12×12 | Single net only; metaheuristics are fine for one path, but the problem is not representative of a full factory |
| **Burniston** (York MEng 2024, archive.org "Factorio Blueprint Layout Optimisation") | Recipe pre-processing → SA placement → CBS + A* belt routing | 5×5 to 15×15 | CBS is the bottleneck; semantic errors in the routing model produced layouts that do not work in game |
| **R-O-C-K-E-T/Factorio-SAT** (GitHub) | Pure SAT tile encoding (input dir, output dir, underground state, splitter id, colour) for belt balancers | Practical balancers up to roughly 8–12 lanes; some pairs still unsolved | Per-tile direction + commodity-colour encoding works well for *dense* tiny designs; exponential in the number of splitters |
| **G. Venturini**, "Learning Solver Design: Automating Factorio Balancers" (blog, 2024) | Compares MIP and SAT for balancers | Small | SAT beat MIP for these combinatorial tile problems |
| **alegnani/verifactory** (GitHub) | SAT/z3 *verification* of blueprint flow properties | 64×64 balancer verified in < 1 s | Verifying a layout is cheap; build a verifier |
| **kevinburke/factorio-layout-optimizer** | OR-Tools to place pre-made blocks minimising belt length | Small | Does not model routing space or rotation ("rotation blew up complexity") |
| **PERSHING / dewey** (Q. Nguyen, Minecraft redstone P&R, 2018) | Classic VLSI flow: SA-style placement + maze routing of logic cells | Small circuits | Standard EDA flow transplants to game grids |
| **Factorio Learning Environment** (Hopkins et al., arXiv 2503.09617, 2025); "Develop AI Agents for System Engineering in Factorio" (arXiv 2502.01492) | LLM agents | — | Not a layout algorithm; LLMs are poor at spatial routing. Not recommended |
| Production calculators (Kirk McDonald's calculator, Helmod, YAFC) | LP for rates | — | Confirms the balance master is a plain LP/ILP; reportedly YAFC uses OR-Tools' LP |

### 4.2 Closest academic analogues outside games
* **Field-coupled nanocomputing (QCA/SiDB) physical design.** Tiles on a grid, signals flow in one
  direction per tile, crossings are limited, the layout must be tiny. Walter, Wille, Große, Sill
  Torres & Drechsler, "An exact method for design exploration of quantum-dot cellular automata",
  DATE 2018 (SMT-based exact P&R); "Placement & routing for tile-based field-coupled nanocomputing
  circuits is NP-complete", ACM JETC 2019; Walter et al., "Scalable design for field-coupled
  nanocomputing circuits" (*ortho*, orthogonal-graph-drawing heuristic), ASP-DAC 2019; the
  open-source C++ framework **fiction** (Walter et al., arXiv 1905.02477; book "Design Automation
  for Field-coupled Nanotechnologies", Springer 2022). Findings: the exact SAT/SMT method finds
  *minimum-area* layouts but only for small functions (tens of gates). For larger ones the group
  switched to scalable constructive heuristics, and later to A*/RL-based placers. **This is the
  best-documented "tiny directional grid, exact vs heuristic" trade-off, and it matches ours.**
* **Flow-based microfluidic biochips.** Columba (Tseng, Li, Ho & Schlichtmann, DAC 2016), Columba 2.0
  (TCAD 2018), Columba S (DAC 2018): MILP co-layout of components and channels. Like the FLP results,
  the MILP stops scaling, and Columba S moved to an architectural template with straight-channel
  routing. Lesson: **restrict the layout architecture** (templates, channels) to make exact methods
  scale.
* **CGRA mapping.** Chin & Anderson, "An architecture-agnostic integer linear programming approach
  to CGRA mapping", DAC 2018; Walker & Anderson, "Generic connectivity-based CGRA mapping via
  integer linear programming", FCCM 2019 (arXiv 1901.11129): **joint P&R ILP takes > 20 min on most
  benchmarks**, and relaxed-placement-then-route works better. SPR (2009) uses SA + PathFinder and
  scales. Same message again.
* **ASP:** Erdem, Lifschitz & Wong, "Wire routing and satisfiability planning", CL 2000; Smith &
  Mateas, "Answer set programming for procedural content generation", IEEE TCIAIG 2011. ASP
  (clingo) is pleasant for writing rules, but it is a native C++ tool and offers nothing that a
  CP-SAT/SAT model does not, for us.

### 4.3 How an exact model for our problem would look (and its size)
Per tile *t*, a block-type choice (empty / duct·dir / bridge·dir / router / conduit… / part of
machine *m* at anchor *a*). Per tile and commodity *k*, a carry variable. Flow and acyclicity: the
"incremental distance" encoding (Patterson) or MonoSAT reachability. Capacity: integer flow per
directed tile edge ≤ duct rate. Adjacency rules as clauses. With 400 tiles × ~15 block states ×
~8 commodities, that is about 10^4–10^5 Booleans plus flow integers. **CP-SAT would likely solve
instances of about 10×10–15×15 in seconds to minutes. 20×20 with full chains is beyond reliable
reach**, consistent with Patterson's ~100-tile ceiling, which was measured on a weaker toolchain.

### 4.4 Java feasibility of solvers
| Library | Pure Java? | Use | Notes |
|---|---|---|---|
| **Choco-solver** | Yes | CP placement (`diffN`, tables), small exact repair; graph-variable extension (choco-graph) for path/tree constraints | Solid, Maven-only. Clause learning weaker than CP-SAT; expect 10–100× slower on routing-heavy models |
| **Sat4j** | Yes | CNF exact routing windows, infeasibility proofs | Slower than CaDiCaL/Kissat, fine for windows of ~100 tiles |
| **ojAlgo** | Yes | LP/small MIP for the balance master | Fine for tens of integer variables; not for routing ILPs |
| Timefold (OptaPlanner) | Yes | Generic local search | Heavier than a custom SA; optional |
| **OR-Tools (CP-SAT, GLOP)** | **No:** JNI. `ortools-java` pulls `ortools-linux-x86-64` jars with `libjniortools.so`/`libortools.so` | Best exact solver available | Works without root (natives load from the jar), but brings platform-specific natives into a game mod (Android/macOS/ARM clients, version clashes). **Use offline only** (macro-cell library generation, benchmarking), not in the shipped AI |
| MonoSAT, clingo, SCIP, HiGHS | No (native) | — | Offline experiments only |

---

## 5. Decomposition: coupling the balance master with spatial feasibility

### 5.1 Logic-based Benders decomposition (LBBD)
* Hooker & Ottosson, "Logic-based Benders decomposition", Math. Programming 2003; Hooker,
  "Planning and scheduling by logic-based Benders decomposition", Operations Research 2007 (MILP
  master + CP subproblem); Fazel-Zarandi & Beck, "Using logic-based Benders decomposition to solve
  the capacity- and distance-constrained plant location problem", INFORMS JOC 2012; branch-and-check
  (Thorsteinsson, CP 2001); combinatorial Benders cuts (Codato & Fischetti, Operations Research
  2006); survey: Rahmaniani, Crainic, Gendreau & Rei, EJOR 2017; Hooker's large-scale LBBD survey
  arXiv 1910.11944.
* **Fit: this is the right outer frame.** Master: integer production model (recipe choice, machine
  counts per type, number of track bundles per net, generator count, which sites are used). The
  objective is goal throughput minus footprint, under multiple economic scenarios if desired.
  Subproblem: place and route.
* **Cuts, ordered by strength (the key design decision):**
  1. **Area / resource cut** (linear, always valid). Machine footprints + estimated routing tiles
     ≤ free tiles. The routing-tile coefficient per track unit should be *measured* from previous
     routes (a regression over solved instances), not hand-set.
  2. **Cut-condition cut** (multicommodity-flow theory). When PathFinder fails, take the tiles with
     the highest history cost (persistent overuse) and find the smallest tile cut *S* around them.
     The capacity of a cut is the number of tiles where a duct or bridge landing can cross it. Then
     add Σ_nets (tracks crossing S as a function of master variables) ≤ cap(S). This is valid only
     *for the current placement class*. Make it robust by stating it for the region (e.g. "machines
     fed from the vent pocket at (x,y)") rather than for exact coordinates.
  3. **Site cuts:** "these site assignments cannot all hold at once", generated from a minimal
     conflicting subset (deletion filter: drop nets or machines until routing succeeds, then the
     kept set is the conflict). Like an unsat core, this is the "minimal infeasible subset" idea of
     combinatorial Benders.
  4. **No-good** on the full configuration: last resort. Patterson et al. show that relying on it
     gives poor information sharing between stages.
* **Pitfall:** the subproblem is a *heuristic* (SA + PathFinder), so a "failure" is not a proof of
  infeasibility, and cuts may cut off feasible configurations. Options: give cuts a validity scope
  (they hold within this solve and can be revisited), or certify the important ones with an exact
  window solve (Sat4j/Choco) before adding them. This is the standard "heuristic Benders"
  compromise.

### 5.2 Column generation
Two places where it helps: (a) path-based routing (prices are PathFinder's congestion costs;
Barnhart et al. 2000); (b) **layout patterns as columns.** Each column is a pre-solved "macro-cell"
(machine + local ducts + node position + port positions), produced exactly offline or on demand.
The master selects and places columns. This is how Columba S and FCN template-based flows regain
scalability, and it fits Mindustry well: a tank fabricator with its silicon/tungsten feeds and
power node is a natural standard cell.

---

## 6. Recommended pipelines

### Pipeline A (primary, online, pure Java)

1. **Balance master (ILP).** The exact integer rate model the project already has. Solve with
   ojAlgo or own enumeration (it is tiny). Output: machine counts by type and recipe, per-net
   demand in tracks (rate ÷ duct capacity, ceilinged), power demand → generator/node needs, plus
   accumulated Benders cuts. Optionally keep the *K best* master solutions, so that on failure the
   next one is tried without re-solving.
2. **Site domains.** Precompute legal anchors per machine type (vent coverage, ore count under the
   drill or facing ore walls, buildable floor). These domains are the placement search space. For
   drills, the anchor choice also fixes the rate, which feeds back to the master.
3. **Placement by SA.** Cost = Σ_nets tracks × HPWL + λ_cong · (RUDY overflow) + λ_adj · (dump
   conflicts) + λ_pow · (unpowerable machines) + λ_area · (bounding area). Every few hundred moves,
   run an incremental PathFinder and replace the estimate with real overuse. λ weights are
   normalised by measured scales, so there are no hand thresholds.
4. **Item routing by PathFinder** on the state-expanded graph:
   * nodes (tile, heading); edges for duct straight/turn as the duct's input-side rules allow;
     bridge jump edges up to range r (both endpoints consume tiles; the bridge's input rules respected);
     router nodes as Steiner branch points;
   * resources: tile occupancy (cap 1), "contact" resources for dump adjacency and for ducts feeding
     wrong-item acceptors;
   * nets: (commodity, producers, consumers, k tracks). Route multi-sink nets as Steiner trees grown
     from the source; split via routers or overflow ducts. Fan-in from several producers merges
     onto one trunk (allowed: same commodity);
   * cost includes a small per-tile base cost (space is what we minimise) and a bridge cost
     calibrated against detour length.
5. **Liquids** (conduits, bridge conduits) as a second net class in the *same* negotiation, sharing
   tile resources. Liquids must not be routed afterwards on scraps: on cramped maps the water line
   is often the binding constraint.
6. **Power.** Once the occupancy is known, compute the beam-node visibility graph (axis rays, range,
   stopping at the first building) over candidate free tiles. Solve a node-weighted Steiner tree
   that connects all consumers to generators (exact by small ILP or Dijkstra-based Steiner
   heuristics). Because lasers are blocked by buildings, include power in the PathFinder loop as
   one more net: its "path" is a chain of node tiles and its edges are visibility rays. This lets
   it compete for tiles instead of failing afterwards.
7. **Verification (sign-off).** (a) Static: build the actual transfer graph from the layout,
   applying the game's dump/accept rules for every adjacent pair, check purity, and solve a max-flow
   per commodity against the planned rates. (b) Dynamic: run the layout in a headless game instance
   for a few minutes of simulated time and compare measured output to plan (the project already runs
   headless matches). Verifiers are cheap (verifactory: 64×64 in < 1 s) and catch the semantic bugs
   that sank Burniston's layouts.
8. **Feedback.** If PathFinder does not converge, or verification fails: first re-place (SA restart
   seeded with history costs as placement penalties), then send Benders cuts (§5.1: area, cut
   condition, minimal conflict set) to the master and take its next solution. Tag cuts with scope
   so that heuristic failures don't permanently exclude configurations.

**Why this pipeline:** every component is proven at far larger scales on grid routing with
capacities (PathFinder/VPR, SPR for CGRAs, TimberWolf). Each works incrementally and anytime. It is
pure Java and dependency-free. It keeps the "optimise, don't hard-code" principle: conflicts are
costs, and space limits become master constraints learned from the subproblem.

### Pipeline B (complement: exact windows + macro-cell library)

* **Offline:** CP-SAT (OR-Tools, native is fine offline) or Choco/Sat4j generates, per machine type
  and orientation, a library of *minimal macro-cells*: footprint + I/O pin positions + node spot +
  feeds for k tracks. It also yields exact dense sub-layouts for recurring motifs (e.g. a silicon
  arc furnace with its graphite/sand feeds). These are columns for the master (§5.2) and cells for
  SA.
* **Online:** when PathFinder keeps failing in a small region (high history cost concentrated in
  ≤ ~100 tiles), freeze everything outside a window and solve the window exactly with Sat4j/Choco:
  per-tile direction + commodity encoding as in Factorio-SAT / Patterson, boundary pins fixed by
  the outside routes. It either repairs the window or returns a *proof* of infeasibility, which
  then becomes a certified Benders cut. This is the "exact where small, heuristic where large"
  split that the FCN community converged on.

---

## 7. Risks and pitfalls (ranked)

1. **Semantic fidelity of the routing graph (highest risk).** Machines dump into *every* adjacent
   acceptor. Routers can push items back the way they came. Ducts accept from specific sides only.
   Bridges have input/output direction rules. A consumer accepting several item types can fill with
   the wrong one when a line is mixed. Beam lasers are blocked by buildings. Every published game
   attempt was bitten by this (Burniston's sideways underground entries; Patterson's one item per
   tile simplification). Mitigation: generate the edge rules from the game's own `acceptItem`/dump
   logic where possible, and **always verify** (static max-flow + headless simulation).
2. **Feedback quality.** With only no-goods, the master/sub loop thrashes (Patterson). The
   cut-condition and minimal-conflict cuts are the proposed fix, but deriving them reliably from a
   non-converged PathFinder run is research-grade work. Plan to iterate.
3. **Single-layer routing.** Without bridges, planar crossing limits make some placements
   unroutable no matter what. PathFinder may oscillate. Bridges must be first-class edges with
   calibrated costs. Watch bridge landings colliding with other bridges' ranges.
4. **Throughput realism.** Ceiling (rate ÷ capacity) tracks assumes steady flow. Real ducts and
   routers have bursty and back-pressure effects; router splits are greedy, not proportional. The
   verifier must measure, and the master should take capacities from measurement (consistent with
   "measured estimates, not hand thresholds").
5. **Power-placement coupling.** Lasers depend on what is placed between nodes, so power is not
   separable. Routing it inside the negotiation (step 6) mitigates this, but adds a new kind of edge
   (rays) whose validity changes as tiles fill. Re-check after every iteration.
6. **Heuristic incompleteness.** SA + PathFinder cannot prove infeasibility. Without Pipeline B's
   exact windows, the generator may give up on layouts that do fit. The tiny test maps are exactly
   where this matters most.
7. **Native dependencies.** OR-Tools/MonoSAT natives in a mod are fragile across client platforms.
   Keep them offline.

---

## 8. References (selection)

Routing / placement (EDA, FPGA, CGRA)
- C. Y. Lee, "An algorithm for path connections and its applications", IRE Trans. Electronic Computers, 1961.
- L. McMurchie, C. Ebeling, "PathFinder: A Negotiation-Based Performance-Driven Router for FPGAs", FPGA 1995. Summary: https://sites.lafayette.edu/cadapps/main-page/pathfinder-fpga-routing-algorithm/
- V. Betz, J. Rose, "VPR: A new packing, placement and routing tool for FPGA research", FPL 1997.
- J. Swartz, V. Betz, J. Rose, "A fast routability-driven router for FPGAs", FPGA 1998.
- "An Open-Source Fast Parallel Routing Approach for Commercial FPGAs", arXiv 2407.00009 (2024): https://arxiv.org/html/2407.00009v1
- C. Sechen, A. Sangiovanni-Vincentelli, "The TimberWolf placement and routing package", IEEE JSSC 1985.
- P. Spindler, F. Johannes, "Fast and accurate routing demand estimation for efficient routability-driven placement" (RUDY), DATE 2007.
- R. Nair, "A simple yet effective technique for global wiring", IEEE TCAD 1987.
- C. Albrecht, "Global routing by new approximation algorithms for multicommodity flow", IEEE TCAD 2001.
- M. Cho, D. Pan, "BoxRouter: a new global router based on box expansion and progressive ILP", DAC 2006.
- M. Gester et al., "BonnRoute: Algorithms and data structures for fast and good VLSI routing", ACM TODAES 2013; D. Müller, K. Radke, J. Vygen, "Faster min-max resource sharing in theory and practice", Math. Prog. Comp. 2011.
- A. Kahng et al., "TritonRoute: the open-source detailed router", IEEE TCAD 2021; G. Chen et al., "Dr. CU: detailed routing by sparse grid graph and minimum-area-captured path search", IEEE TCAD 2020.
- G.-J. Nam, K. Sakallah, R. Rutenbar, "A new FPGA detailed routing approach via search-based Boolean satisfiability", IEEE TCAD 2002.
- S. Bayless, N. Bayless, H. Hoos, A. Hu, "SAT Modulo Monotonic Theories", AAAI 2015: https://www.cs.ubc.ca/labs/isd/Projects/monosat/smmt.pdf ; S. Bayless, PhD thesis, UBC 2017: https://www.cs.ubc.ca/labs/isd/Projects/monosat/sam_bayless_thesis_2017.pdf
- M.-C. Kim, D.-J. Lee, I. Markov, "SimPL", IEEE TCAD 2012; Y. Lin et al., "DREAMPlace", DAC 2019.
- S. Friedman et al., "SPR: an architecture-adaptive CGRA mapping tool", FPGA 2009: https://people.ece.uw.edu/hauck/publications/SPR.pdf
- S. A. Chin, J. Anderson, "An architecture-agnostic integer linear programming approach to CGRA mapping", DAC 2018; M. Walker, J. Anderson, "Generic connectivity-based CGRA mapping via ILP", FCCM 2019: https://arxiv.org/pdf/1901.11129
- G. Sharon, R. Stern, A. Felner, N. Sturtevant, "Conflict-based search for optimal multi-agent pathfinding", Artificial Intelligence 2015.

Facility layout / packing
- R. Meller, V. Narayanan, P. Vance, "Optimal facility layout design", Operations Research Letters 1998.
- H. Sherali, B. Fraticelli, R. Meller, "Enhanced model formulations for optimal facility layout", Operations Research 2003.
- M. Anjos, M. Vieira, "Mathematical optimization approaches for facility layout problems: the state-of-the-art and future research directions", EJOR 2017.
- J. M. Smith, B. Norman, R. Arapoglu, "Integrated facilities design using a contour distance metric", IIE Transactions 2001; Kim & Goetschalckx, IERC 2003: https://www2.isye.gatech.edu/~mgoetsch/cali/ierc_2003_portland/Detailed%20Layout%20with%20Contour%20Distances%20IERC%202003%20Final.pdf
- Zone-based dynamic facility layout with embedded I/O points, arXiv 2309.02453: https://arxiv.org/pdf/2309.02453
- E. Huang, R. Korf, "Optimal rectangle packing: an absolute placement approach", JAIR 2013; H. Simonis, B. O'Sullivan, "Search strategies for rectangle packing", CP 2008.

Decomposition
- J. Hooker, G. Ottosson, "Logic-based Benders decomposition", Math. Programming 2003; J. Hooker, "Planning and scheduling by logic-based Benders decomposition", Operations Research 2007; Hooker, "Logic-based Benders decomposition for large-scale optimization", arXiv 1910.11944: https://arxiv.org/pdf/1910.11944
- M. Fazel-Zarandi, J. C. Beck, "Using logic-based Benders decomposition to solve the capacity- and distance-constrained plant location problem", INFORMS J. Computing 2012.
- G. Codato, M. Fischetti, "Combinatorial Benders' cuts for mixed-integer linear programming", Operations Research 2006; E. Thorsteinsson, "Branch-and-check", CP 2001.
- R. Rahmaniani, T. Crainic, M. Gendreau, W. Rei, "The Benders decomposition algorithm: a literature review", EJOR 2017.
- C. Barnhart, C. Hane, P. Vance, "Using branch-and-price-and-cut to solve origin-destination integer multicommodity flow problems", Operations Research 2000.

Games and tile-based analogues
- S. Patterson, J. Espasa, M. S. Chang, R. Hoffmann, "Towards Automatic Design of Factorio Blueprints", arXiv 2310.01505 (2023): https://arxiv.org/abs/2310.01505
- K. Reid, I. Miralavy, S. Kelly, W. Banzhaf, C. Gondro, "The Factory Must Grow: Automation in Factorio", arXiv 2102.04871 (2021): https://arxiv.org/abs/2102.04871
- T. Burniston, "Factorio Blueprint Layout Optimisation", MEng report, Univ. of York, 2024: https://archive.org/details/report_20240930
- Factorio-SAT (R-O-C-K-E-T): https://github.com/R-O-C-K-E-T/Factorio-SAT ; VeriFactory: https://github.com/alegnani/verifactory ; factorio-layout-optimizer: https://github.com/kevinburke/factorio-layout-optimizer
- G. Venturini, "Learning Solver Design: Automating Factorio Balancers" (2024), mirrored at https://gwern.net/doc/www/gianlucaventurini.com/2d487379c670fff6c166f4aca4dac8544022a2fc.html
- Q. Nguyen, PERSHING / dewey (Minecraft redstone P&R), 2018: https://www.ocf.berkeley.edu/~qmn/assets/nguyen_dewey_2018.pdf , https://github.com/qmn/dewey
- "Develop AI Agents for System Engineering in Factorio", arXiv 2502.01492 (2025); Hopkins et al., "Factorio Learning Environment", arXiv 2503.09617 (2025).
- M. Walter, R. Wille, D. Große, F. Sill Torres, R. Drechsler, "An exact method for design exploration of quantum-dot cellular automata", DATE 2018: https://www.cda.cit.tum.de/files/eda/2018_date_exact_design_exploration_qca.pdf ; "Placement & routing for tile-based field-coupled nanocomputing circuits is NP-complete", ACM JETC 2019; "Scalable design for field-coupled nanocomputing circuits", ASP-DAC 2019; fiction framework, arXiv 1905.02477; M. Walter et al., "SAT-based exact physical design for field-coupled nanocomputing technologies", IWLS 2020.
- T.-M. Tseng, M. Li, T.-Y. Ho, U. Schlichtmann, "Columba: co-layout synthesis for continuous-flow microfluidic biochips", DAC 2016; Columba 2.0, IEEE TCAD 2018; Columba S, DAC 2018: https://portal.fis.tum.de/en/publications/columba-s-a-scalable-co-layout-design-automation-tool-for-microfl/
- E. Erdem, V. Lifschitz, M. Wong, "Wire routing and satisfiability planning", CL 2000; A. Smith, M. Mateas, "Answer set programming for procedural content generation", IEEE TCIAIG 2011.

Libraries
- OR-Tools Java packaging (JNI natives in `ortools-linux-x86-64`): https://developers.google.com/optimization/install/java/pkg_linux ; Choco-solver (https://choco-solver.org), Sat4j (https://sat4j.org), ojAlgo (https://www.ojalgo.org), Timefold (https://timefold.ai).

*Caveats:* scale figures for FLP MILPs (~11–15 departments) and Sat4j's relative speed are
well-known orders of magnitude from the literature, not re-measured here. Mindustry-specific
mechanics (duct side rules, bridge ranges, laser blocking) should be checked against the v160.7
source before the routing graph is fixed.
