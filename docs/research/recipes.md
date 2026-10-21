# Erekir production blocks, per second (game data v160.7)

| block | size | rates |
|---|---|---|
| plasma-bore | 2 | wall-ore beam drill tier 3, reach 5 tiles, 0.375/s per facing ore tile; boost x2.5 with hydrogen 0.25 (boost); power 9/s |
| impact-drill | 4 | floor drill tier 6, per ore tile 60/(720.0+50*hardness)/s; inputs water 10.00, ozone 3.00 (boost); power 160/s |
| large-plasma-bore | 3 | wall-ore beam drill tier 5, reach 6 tiles, 0.600/s per facing ore tile; boost x2.5 with hydrogen 0.50, nitrogen 3.00 (boost); power 48/s |
| eruption-drill | 5 | floor drill tier 7, per ore tile 60/(281.25+50*hardness)/s; inputs hydrogen 4.00, cyanogen 0.75 (boost); power 360/s |
| large-cliff-crusher | 3 | wall crafter: sand 1.250/s per unit of sand on its face; inputs graphite 1.00 (boost), hydrogen 1.00; power 60/s |
| turbine-condenser | 3 | generator 20/s; inputs - |
| vent-condenser | 3 | crafter, cycle 2.00s. in: - | out: water 30.00 | power 30/s; attribute steam base 0.0 |
| chemical-combustion-chamber | 3 | generator 550/s; inputs ozone 1.98, arkycite 40.02 |
| pyrolysis-generator | 3 | generator 1400/s; inputs slag 19.98, arkycite 40.02 |
| flux-reactor | 5 | generator 18000/s; inputs cyanogen 9.00 |
| neoplasia-reactor | 5 | generator 8400/s; inputs phase-fabric 1.00, arkycite 80.00, water 10.00 |
| cliff-crusher | 2 | wall crafter: sand 0.545/s per unit of sand on its face; inputs -; power 11/s |
| silicon-arc-furnace | 3 | crafter, cycle 0.83s. in: graphite 1.20, sand 4.80 | out: silicon 4.80 | power 300/s |
| electrolyzer | 3 | crafter, cycle 0.17s. in: water 10.00 | out: ozone 4.02, hydrogen 6.00 | power 60/s; liquid out dirs [1, |
| oxidation-chamber | 3 | crafter, cycle 2.00s. in: beryllium 0.50, ozone 2.00 | out: oxide 0.50 | power 30/s; heat out 5.0 |
| surge-crucible | 3 | crafter, cycle 0.75s. in: silicon 4.00, slag 160.00 | out: surge-alloy 1.33 | power 90/s; heat needed 40.0 |
| electric-heater | 2 | crafter, cycle 1.33s. in: - | out: - | power 100/s; heat out 3.0 |
| slag-heater | 3 | crafter, cycle 1.33s. in: slag 40.00 | out: - | power 0/s; heat out 8.0 |
| atmospheric-concentrator | 3 | crafter, cycle 1.33s. in: - | out: nitrogen 16.02 | power 120/s; heat needed 24.0 |
| cyanogen-synthesizer | 3 | crafter, cycle 0.33s. in: graphite 3.00, arkycite 160.00 | out: cyanogen 12.00 | power 120/s; heat needed 20.0 |
| carbide-crucible | 3 | crafter, cycle 0.56s. in: tungsten 3.56, graphite 5.33 | out: carbide 1.78 | power 120/s; heat needed 40.0 |
| phase-synthesizer | 3 | crafter, cycle 0.50s. in: thorium 4.00, sand 12.00, ozone 8.00 | out: phase-fabric 2.00 | power 480/s; heat needed 32.0 |
| phase-heater | 2 | crafter, cycle 8.00s. in: phase-fabric 0.12 | out: - | power 0/s; heat out 15.0 |
| tank-fabricator | 3 | unit factory: stell[beryllium40+silicon50 2100.0t]; power 90/s |
| ship-fabricator | 3 | unit factory: elude[graphite50+silicon70 2400.0t]; power 90/s |
| mech-fabricator | 3 | unit factory: merui[beryllium50+silicon70 2400.0t]; power 90/s |
| tank-refabricator | 3 | refab: time=1800.0 upgrades=stell>locus; per unit inputs silicon 40.00, tungsten 30.00, hydrogen 3.00; power 180/s |
| mech-refabricator | 3 | refab: time=2700.0 upgrades=merui>cleroi; per unit inputs silicon 50.00, tungsten 40.00, hydrogen 3.00; power 150/s |
| ship-refabricator | 3 | refab: time=3000.0 upgrades=elude>avert; per unit inputs silicon 60.00, tungsten 40.00, hydrogen 3.00; power 150/s |
| prime-refabricator | 5 | refab: time=3600.0 upgrades=locus>precept cleroi>anthicus avert>obviate; per unit inputs thorium 80.00, silicon 100.00, nitrogen 10.00; power 270/s |
| tank-assembler | 5 | assembler: areaSize=13 droneType=assembly-drone drones=4 plan[vanquish time=3000.0 needs=stellx4,tungsten-wall-largex10,] plan[conquer time=10800.0 needs=locusx6,carbide-wall-largex20,] |
| ship-assembler | 5 | assembler: areaSize=13 droneType=assembly-drone drones=4 plan[quell time=3600.0 needs=eludex4,beryllium-wall-largex12,] plan[disrupt time=10800.0 needs=avertx6,carbide-wall-largex20,] |
| mech-assembler | 5 | assembler: areaSize=13 droneType=assembly-drone drones=4 plan[tecta time=4200.0 needs=meruix5,tungsten-wall-largex12,] plan[collaris time=10800.0 needs=cleroix6,carbide-wall-largex20,] |
