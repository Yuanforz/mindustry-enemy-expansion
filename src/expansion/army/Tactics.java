package expansion.army;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;

/**
 * The army's tactics, searched for, not set by thresholds: Portfolio Greedy Search (Churchill & Buro, "Portfolio Greedy
 * Search and Simulation for Large-Scale Combat in StarCraft", 2013) over squads, as Stratified Strategy Selection (Lelis
 * 2017) groups units that move alike so that one script leads them all.
 *
 * Every few seconds: our fighting units are put into squads (those that stand together and move the same way: ground,
 * naval, air), and so are the other side's. Each of our squads has a portfolio of scripts: hold the core; intercept one
 * of the other side's groups that is near anything of ours; attack one of the other side's buildings that matter (its
 * turrets, yards, cores; the nearest few it can get to). Each of their groups has its own: go on as it is going, go for
 * our core, go home. A choice of a script for every squad of both sides is played forward a minute in an abstract fight
 * (groups move at their speed, fire at the nearest enemy within reach, take damage in proportion so that a group's
 * fire falls as it loses members, as Lanchester's square law has it; turrets fire from where they stand; a building
 * with no one to shield it is taken down), and scored by what each side lost, weighted by worth (cores most, then
 * yards and turrets, then units by their health, then the rest), with a little for an attack under way that is
 * strong enough for what guards its target. The search: from the scripts each squad had (or holding the core), each of
 * our squads in turn takes the script that scores best with the rest held; then each of their groups the one that
 * scores worst for us (their best reply); twice over. A squad that changes its script pays for it in the score (units
 * turned round lose the way they made: what kept them from going back and forth).
 *
 * Nothing here is about a map or a side.
 */
public final class Tactics{
    final Army army;
    /** Seconds the fights are played forward, and the step. */
    public static float horizon = Float.parseFloat(System.getProperty("army.tactics.horizon", "60")), dt = 2f;
    /** What a squad turning to another script costs, as a part of its health (-Darmy.tactics.switch). */
    public static float switchCost = 0f;
    /** Tiles within which units are one squad; tiles from anything of ours within which a group of theirs is one to meet. */
    public static float squadRadius = 10f, nearOurs = 50f;
    /** How many of the other side's buildings a squad weighs attacking, the nearest it can get to. */
    public static int targets = 4;
    /** How many times its worth a building the map's tasks ask to have destroyed counts (-Darmy.tactics.asked). */
    public static float askedWorth = Float.parseFloat(System.getProperty("army.tactics.asked", "5"));
    /** Seconds of walk past the horizon over which an attack's leaf value falls to 1/e (-Darmy.tactics.leaf). */
    public static float leafDiscount = Float.parseFloat(System.getProperty("army.tactics.leaf", "90"));
    /** Worth of a hit point by what it is on. */
    static final float worthCore = 6f, worthYard = 3f, worthTurret = 2f, worthUnit = 1f, worthOther = 0.3f;

    /** Each unit's script at the last decision (what it was sent to: a key of the target), for the cost of turning it. */
    final IntMap<String> goals = new IntMap<>();
    /** For the report. */
    public String state = "";
    public int decisions, turned;
    final ObjectMap<UnitType, boolean[]> reach = new ObjectMap<>();
    float reachAt = -1f;

    public Tactics(Army army){
        this.army = army;
    }

    /** One group of units (ours or theirs) in the abstract fight, with its script and its state in the playout. */
    static final class Group{
        final Seq<Unit> units = new Seq<>();
        final boolean ours, air;
        float x, y, dps, health, speed, range;
        /** The script: 0 hold home, 1 go for a group, 2 go for a building; for theirs 0 go on, 1 our core, 2 home. */
        int script;
        Group foeGroup;
        Site foeSite;
        //playout state
        float px, py, ph, hx, hy;

        Group(boolean ours, boolean air){
            this.ours = ours;
            this.air = air;
        }

        /** The group as a body (Force): its kinds and what it has left of their health, for the one combat model. */
        final expansion.ops.Force force = new expansion.ops.Force();

        void add(Unit u){
            units.add(u);
            force.team = u.team;
            force.add(u);
            dps = force.dps;
            health = force.health;
            range = Math.max(range, u.range());
            speed = speed == 0f ? u.speed() : Math.min(speed, u.speed());
        }

        void center(){
            x = y = 0f;
            for(Unit u : units){
                x += u.x;
                y += u.y;
            }
            x /= units.size;
            y /= units.size;
        }

        float worth(){
            return worthUnit;
        }
    }

    /** A building that fights or is worth taking or keeping: a turret, a yard, a core, or a stretch of other buildings. */
    static final class Site{
        final Building build;
        final boolean ours;
        final float x, y, health, dps, range;
        float worth;
        final boolean hitsAir, hitsGround;
        float ph;
        /** Whether the map's tasks ask for it to be destroyed (worth more: the map opens up when it is). */
        boolean asked;
        /** It as a body (Force): a turret with its ammunition, or a building that takes hits. */
        final expansion.ops.Force force;

        Site(Building build, boolean ours, float x, float y, float health, float dps, float range, float worth, boolean air, boolean ground, expansion.ops.Force force){
            this.force = force;
            this.build = build;
            this.ours = ours;
            this.x = x;
            this.y = y;
            this.health = health;
            this.dps = dps;
            this.range = range;
            this.worth = worth;
            this.hitsAir = air;
            this.hitsGround = ground;
        }
    }

    final Seq<Group> ours = new Seq<>(), theirs = new Seq<>();
    final Seq<Site> sites = new Seq<>();
    Building home;

    /** Every couple of seconds: squads formed, scripts searched for, orders given. */
    public void update(){
        Team team = army.team;
        home = team.core();
        if(home == null) return;
        if(army.clock - reachAt > 60f * 30f){
            reach.clear();
            reachAt = army.clock;
        }
        ours.clear();
        theirs.clear();
        sites.clear();
        Seq<Unit> mine = new Seq<>();
        for(int i = 0; i < team.data().units.size; i++){
            Unit u = team.data().units.get(i);
            if(Bait.coreUnit(u) || !u.type.canAttack || u.isPlayer() || u.dead() || !(u.controller() instanceof CommandAI)) continue;
            mine.add(u);
        }
        if(mine.isEmpty()){
            state = "no units";
            return;
        }
        Seq<Unit> foes = new Seq<>();
        for(var data : Vars.state.teams.active){
            if(data.team == team || data.team == Team.derelict) continue;
            for(Unit e : data.units) if(e.type.canAttack && !Bait.coreUnit(e) && !e.dead()) foes.add(e);
        }
        squads(mine, ours, true);
        squads(foes, theirs, false);
        sitesOf(team);
        //what the map's tasks ask to have destroyed (set on the push by whoever reads the tasks)
        for(Site t : sites) if(!t.ours && t.build != null && army.push.asked.contains(t.build, true)){
            t.asked = true;
            t.worth *= askedWorth;
        }
        //the other side's groups that are near anything of ours (the ones worth meeting)
        Seq<Group> near = new Seq<>();
        for(Group g : theirs) if(nearAnything(g)) near.add(g);

        //the portfolio of each squad: hold, intercept each group near ours, attack the nearest buildings of theirs it can get to
        Seq<Seq<Object>> options = new Seq<>();
        for(Group s : ours){
            Seq<Object> o = new Seq<>();
            o.add(home);
            for(Group g : near) if(canReach(s, g.x, g.y) && (g.air ? hitsAir(s) : hitsGround(s))) o.add(g);
            Seq<Site> worth = new Seq<>();
            for(Site t : sites) if(!t.ours && canReach(s, t.x, t.y)) worth.add(t);
            worth.sort(t -> Mathf.dst2(t.x, t.y, s.x, s.y));
            for(int i = 0; i < Math.min(targets, worth.size); i++) o.add(worth.get(i));
            //(and what the map's tasks ask to have destroyed, wherever it is among them)
            for(Site t : worth) if(t.asked && !o.contains(t, true)) o.add(t);
            options.add(o);
        }
        //from what each squad was doing (the scripts its units were sent on), or holding
        for(int i = 0; i < ours.size; i++){
            Group s = ours.get(i);
            Object was = previous(s, options.get(i));
            set(s, was == null ? home : was);
        }
        for(Group g : theirs) g.script = 0;
        float base = playout();
        for(int round = 0; round < 2; round++){
            //(no price for turning a squad: the playout walks it from where it is, which is what turning costs)
            for(int i = 0; i < ours.size; i++){
                Group s = ours.get(i);
                Object best = target(s);
                float bestScore = playout();
                for(Object o : options.get(i)){
                    set(s, o);
                    float v = playout();
                    if(v > bestScore + 1e-3f){
                        bestScore = v;
                        best = o;
                    }
                }
                set(s, best);
            }
            //together: every squad that can get to a target going for it at once (the square law makes a body of all of them
            //far stronger than its parts one after another)
            float now = playout();
            Seq<Object> was = new Seq<>();
            for(Group s : ours) was.add(target(s));
            Seq<Site> together = new Seq<>();
            for(Seq<Object> o : options) for(Object t : o) if(t instanceof Site site && !together.contains(site, true)) together.add(site);
            for(Site site : together){
                for(int i = 0; i < ours.size; i++) set(ours.get(i), options.get(i).contains(site, true) ? site : was.get(i));
                float v = playout();
                if(v > now + 1e-3f){
                    now = v;
                    was.clear();
                    for(Group s : ours) was.add(target(s));
                }
            }
            for(int i = 0; i < ours.size; i++) set(ours.get(i), was.get(i));
            for(Group g : theirs){
                int best = g.script;
                float worst = playout();
                for(int k = 0; k < 3; k++){
                    //(their going for our core only if they can get there)
                    if(k == 1 && !theyReach(g)) continue;
                    g.script = k;
                    float v = playout();
                    if(v < worst - 1e-3f){
                        worst = v;
                        best = k;
                    }
                }
                g.script = best;
            }
        }
        float end = playout();
        decisions++;
        //(-Darmy.tactics.debug: each squad's options as they score with the rest as chosen)
        StringBuilder why = new StringBuilder();
        if(Boolean.getBoolean("army.tactics.debug")){
            for(int i = 0; i < ours.size; i++){
                Group s = ours.get(i);
                Object chosen = target(s);
                why.append(String.format("\n      squad %d (%d units, %.0f dps, %.0f hp) at %d,%d:", i, s.units.size, s.dps, s.health, (int)(s.x / 8), (int)(s.y / 8)));
                for(Object o : options.get(i)){
                    set(s, o);
                    float v = playout();
                    String name = o instanceof Building b ? "hold" : o instanceof Group g ? "meet " + g.units.size + "@" + (int)(g.x / 8) + "," + (int)(g.y / 8) : o instanceof Site t ? (t.build == null ? "site" : t.build.block.name) + "@" + (int)(t.x / 8) + "," + (int)(t.y / 8) : String.valueOf(o);
                    why.append(String.format(" %s %.0f%s;", name, v, o == chosen ? "*" : ""));
                }
                set(s, chosen);
            }
        }
        //the orders
        int holding = 0, meeting = 0, attacking = 0;
        for(Group s : ours){
            Object t = target(s);
            if(t instanceof Building) holding++;
            else if(t instanceof Group) meeting++;
            else attacking++;
            order(s, t);
        }
        state = String.format("%d squads (%d hold, %d meet, %d attack) against %d groups (%d near ours); playout %.0f -> %.0f; turned %d", ours.size, holding, meeting, attacking, theirs.size, near.size, base, end, turned) + why;
    }

    /** Units into squads: those within the radius of one another and moving alike. */
    void squads(Seq<Unit> units, Seq<Group> out, boolean ours){
        float r2 = squadRadius * Vars.tilesize * squadRadius * Vars.tilesize;
        boolean[] done = new boolean[units.size];
        for(int i = 0; i < units.size; i++){
            if(done[i]) continue;
            Unit first = units.get(i);
            Group g = new Group(ours, first.isFlying());
            IntQueue queue = new IntQueue();
            queue.addLast(i);
            done[i] = true;
            while(!queue.isEmpty()){
                int k = queue.removeFirst();
                Unit u = units.get(k);
                g.add(u);
                for(int j = 0; j < units.size; j++){
                    if(done[j]) continue;
                    Unit v = units.get(j);
                    if(v.isFlying() != g.air || v.type.naval != first.type.naval) continue;
                    if(u.dst2(v) <= r2){
                        done[j] = true;
                        queue.addLast(j);
                    }
                }
            }
            g.center();
            out.add(g);
        }
    }

    /** Turrets, yards and cores of both sides; our other buildings as stretches of a few tiles near their groups. */
    void sitesOf(Team team){
        for(var data : Vars.state.teams.active){
            boolean mine = data.team == team;
            if(data.team == Team.derelict) continue;
            IntMap<float[]> stretches = new IntMap<>();
            for(Building b : data.buildings){
                if(b instanceof Turret.TurretBuild t){
                    Turret type = (Turret)b.block;
                    //(a turret without ammunition fires nothing: a body that only takes hits)
                    expansion.ops.Force f = t.hasAmmo() ? new expansion.ops.Force(data.team).addTurret(t) : new expansion.ops.Force(data.team).addBuilding(b);
                    sites.add(new Site(b, mine, b.x, b.y, b.health, f.dps, t.range(), worthTurret, type.targetAir, type.targetGround, f));
                }else if(b instanceof CoreBlock.CoreBuild){
                    sites.add(new Site(b, mine, b.x, b.y, b.health, 0f, 0f, worthCore, false, false, new expansion.ops.Force(data.team).addBuilding(b)));
                }else if(b.block instanceof UnitFactory || b.block instanceof Reconstructor){
                    sites.add(new Site(b, mine, b.x, b.y, b.health, 0f, 0f, worthYard, false, false, new expansion.ops.Force(data.team).addBuilding(b)));
                }else if(mine){
                    //(the rest of ours, in stretches of 12 tiles: what raiders take down)
                    int key = (b.tileX() / 12) + (b.tileY() / 12) * 1000;
                    float[] s = stretches.get(key);
                    if(s == null) stretches.put(key, s = new float[4]);
                    s[0] += b.x;
                    s[1] += b.y;
                    s[2] += b.health;
                    s[3]++;
                }
            }
            for(var e : stretches){
                float[] s = e.value;
                float x = s[0] / s[3], y = s[1] / s[3];
                boolean threatened = false;
                for(Group g : theirs) if(Mathf.dst(g.x, g.y, x, y) < nearOurs * Vars.tilesize) threatened = true;
                if(threatened) sites.add(new Site(null, true, x, y, s[2], 0f, 0f, worthOther, false, false,
                    new expansion.ops.Force(data.team).add(mindustry.content.Blocks.berylliumWall, s[2] / Math.max(1f, mindustry.content.Blocks.berylliumWall.health))));
            }
        }
    }

    boolean nearAnything(Group g){
        float r = nearOurs * Vars.tilesize;
        if(Mathf.dst(g.x, g.y, home.x, home.y) < r) return true;
        for(Site s : sites) if(s.ours && Mathf.dst(g.x, g.y, s.x, s.y) < r) return true;
        return false;
    }

    static boolean hitsAir(Group s){
        for(Unit u : s.units) if(u.type.targetAir) return true;
        return false;
    }

    static boolean hitsGround(Group s){
        for(Unit u : s.units) if(u.type.targetGround) return true;
        return false;
    }

    /** Whether a squad can walk to a place (flying: anywhere), by the region its slowest kind reaches from our core. */
    boolean canReach(Group s, float x, float y){
        if(s.air) return true;
        UnitType type = s.units.first().type;
        boolean[] r = reach.get(type);
        if(r == null){
            r = Reach.region(type, army.team, home.tileX(), home.tileY());
            reach.put(type, r == null ? new boolean[0] : r);
        }
        if(r == null || r.length == 0) return false;
        //(near enough to fire on it: ground it can stand on within its range of the place, as units fire across a gap)
        return Reach.near(r, mindustry.core.World.toTile(x), mindustry.core.World.toTile(y), Math.max(2, (int)(s.range / Vars.tilesize) - 1));
    }

    void set(Group s, Object o){
        s.foeGroup = null;
        s.foeSite = null;
        if(o instanceof Group g){
            s.script = 1;
            s.foeGroup = g;
        }else if(o instanceof Site t){
            s.script = 2;
            s.foeSite = t;
        }else s.script = 0;
    }

    Object target(Group s){
        return s.script == 1 ? s.foeGroup : s.script == 2 ? s.foeSite : home;
    }

    /** Where a script sends a squad now. */
    float[] goal(Object o){
        if(o instanceof Group g) return new float[]{g.x, g.y};
        if(o instanceof Site t) return new float[]{t.x, t.y};
        return new float[]{home.x, home.y};
    }

    /** What a script is aimed at, as a key that stays while the target moves: home, a group (by its first unit), a building. */
    String key(Object o){
        if(o instanceof Group g){
            int least = Integer.MAX_VALUE;
            for(Unit u : g.units) least = Math.min(least, u.id);
            return "g" + least;
        }
        if(o instanceof Site t) return t.build == null ? "s" + (int)t.x + "," + (int)t.y : "b" + t.build.pos();
        return "h";
    }

    /** Of the squad's options, the one most of its units were on last time, or null. */
    Object previous(Group s, Seq<Object> options){
        Object best = null;
        int bestN = 0;
        for(Object o : options){
            String k = key(o);
            int n = 0;
            for(Unit u : s.units) if(k.equals(goals.get(u.id))) n++;
            //(a group of theirs is known by its first unit: one whose members came apart is matched by any of them)
            if(o instanceof Group g) for(Unit u : s.units){
                String was = goals.get(u.id);
                if(was != null && was.startsWith("g") && g.units.contains(e -> ("g" + e.id).equals(was))) n++;
            }
            if(n > bestN){
                bestN = n;
                best = o;
            }
        }
        return best;
    }

    /** The target if it is the last core of its side (taking it ends the game), else null. */
    Building foeCore(Site t){
        if(!(t.build instanceof CoreBlock.CoreBuild c)) return null;
        return c.team.cores().size <= 1 ? c : null;
    }

    /** Whether a group of theirs can walk to our core (flyers anywhere). */
    boolean theyReach(Group g){
        if(g.air) return true;
        UnitType type = g.units.first().type;
        float[] d = Reach.distance(type, g.units.first().team, home.tileX(), home.tileY());
        if(d == null || d.length == 0) return false;
        int w = Vars.world.width(), h = Vars.world.height(), tx = mindustry.core.World.toTile(g.x), ty = mindustry.core.World.toTile(g.y);
        for(int dx = -3; dx <= 3; dx++) for(int dy = -3; dy <= 3; dy++){
            int x = tx + dx, y = ty + dy;
            if(x >= 0 && y >= 0 && x < w && y < h && !Float.isInfinite(d[x + y * w])) return true;
        }
        return false;
    }

    /** What turning a squad to this option costs: a part of the health of each unit that was on another script. */
    float switchPenalty(Group s, Object o){
        String k = key(o);
        float cost = 0f;
        for(Unit u : s.units){
            String was = goals.get(u.id);
            if(was != null && !was.equals(k)) cost += (u.health + u.shield) * switchCost * worthUnit;
        }
        return cost;
    }

    /**
     * The choice of scripts as it stands, played forward: what the other side loses less what we lose, by worth, and a
     * part of what an attack under way stands to take.
     */
    float playout(){
        for(Group g : ours){
            g.px = g.x;
            g.py = g.y;
            g.ph = g.health;
        }
        for(Group g : theirs){
            g.px = g.x;
            g.py = g.y;
            g.ph = g.health;
            //(where theirs go home to: their nearest core)
            Building c = Geometry.findClosest(g.x, g.y, g.units.first().team.cores());
            g.hx = c == null ? g.x : c.x;
            g.hy = c == null ? g.y : c.y;
        }
        for(Site s : sites) s.ph = s.health;
        int steps = Math.max(1, Math.round(horizon / dt));
        float[] hitG = new float[ours.size + theirs.size];
        float[] hitS = new float[sites.size];
        for(int step = 0; step < steps; step++){
            java.util.Arrays.fill(hitG, 0f);
            java.util.Arrays.fill(hitS, 0f);
            //groups: fire at the nearest enemy in reach, else move on to where their script sends them
            for(int i = 0; i < ours.size + theirs.size; i++){
                Group g = i < ours.size ? ours.get(i) : theirs.get(i - ours.size);
                if(g.ph <= 0f) continue;
                //(what it has left fires: its fire falls as it loses members)
                float left = g.ph / g.health * dt;
                int tg = -1, ts = -1;
                float best = Float.MAX_VALUE, reach2 = g.range * g.range;
                for(int j = 0; j < ours.size + theirs.size; j++){
                    Group e = j < ours.size ? ours.get(j) : theirs.get(j - ours.size);
                    if(e.ours == g.ours || e.ph <= 0f) continue;
                    if(g.force.dpsAgainst(e.force) <= 0f) continue;
                    float d = Mathf.dst2(g.px, g.py, e.px, e.py);
                    if(d < reach2 && d < best){
                        best = d;
                        tg = j;
                    }
                }
                if(tg < 0) for(int k = 0; k < sites.size; k++){
                    Site s = sites.get(k);
                    if(s.ours == g.ours || s.ph <= 0f) continue;
                    float d = Mathf.dst2(g.px, g.py, s.x, s.y);
                    if(d < reach2 && d < best){
                        best = d;
                        ts = k;
                    }
                }
                //(by the one combat model: armour against each hit, a unit on a building firing the share of the time measured)
                if(tg >= 0){
                    Group e = tg < ours.size ? ours.get(tg) : theirs.get(tg - ours.size);
                    hitG[tg] += g.force.dpsAgainst(e.force) * left;
                }else if(ts >= 0) hitS[ts] += g.force.dpsAgainst(sites.get(ts).force) * left;
                else{
                    float[] to = goalOf(g);
                    float d = Mathf.dst(g.px, g.py, to[0], to[1]);
                    float move = g.speed * 60f * dt;
                    if(d > 2f * Vars.tilesize){
                        float f = Math.min(1f, move / d);
                        g.px += (to[0] - g.px) * f;
                        g.py += (to[1] - g.py) * f;
                    }
                }
            }
            //turrets fire at the nearest enemy group in reach, at full till they fall
            for(Site s : sites){
                if(s.dps <= 0f || s.ph <= 0f) continue;
                int tg = -1;
                float best = Float.MAX_VALUE, r2 = s.range * s.range;
                for(int j = 0; j < ours.size + theirs.size; j++){
                    Group e = j < ours.size ? ours.get(j) : theirs.get(j - ours.size);
                    if(e.ours == s.ours || e.ph <= 0f || s.force.dpsAgainst(e.force) <= 0f) continue;
                    float d = Mathf.dst2(s.x, s.y, e.px, e.py);
                    if(d < r2 && d < best){
                        best = d;
                        tg = j;
                    }
                }
                if(tg >= 0){
                    Group e = tg < ours.size ? ours.get(tg) : theirs.get(tg - ours.size);
                    hitG[tg] += s.force.dpsAgainst(e.force) * dt;
                }
            }
            for(int j = 0; j < ours.size + theirs.size; j++){
                Group e = j < ours.size ? ours.get(j) : theirs.get(j - ours.size);
                e.ph = Math.max(0f, e.ph - hitG[j]);
            }
            for(int k = 0; k < sites.size; k++) sites.get(k).ph = Math.max(0f, sites.get(k).ph - hitS[k]);
        }
        float score = 0f;
        for(Group g : ours) score -= (g.health - g.ph) * g.worth();
        for(Group g : theirs) score += (g.health - g.ph) * g.worth();
        for(Site s : sites) score += (s.ours ? -1f : 1f) * (s.health - s.ph) * s.worth;
        //every attack not yet decided at the end (on its way, or begun) is valued at its leaf, as a search does past its
        //horizon: the squads making for one target together, against its guard (the turrets that reach it and the groups of
        //theirs about it) and itself, by the one combat model's square law; what it stands to win less what it stands to
        //lose, discounted by the walk still left and the fight's own time, at the command layer's time preference
        ObjectMap<Site, Seq<Group>> going = new ObjectMap<>();
        for(Group g : ours){
            if(g.script != 2 || g.ph <= 0f || g.foeSite.ph <= 0f) continue;
            if(!going.containsKey(g.foeSite)) going.put(g.foeSite, new Seq<>());
            going.get(g.foeSite).add(g);
        }
        //(what a side has in all, by worth: what it loses with its core, the game)
        float ourStake = 0f, theirStake = 0f;
        for(Site t : sites) if(t.ph > 0f){
            if(t.ours) ourStake += t.ph * t.worth;
            else theirStake += t.ph * t.worth;
        }
        for(Group g : ours) ourStake += g.ph * g.worth();
        for(Group g : theirs) theirStake += g.ph * g.worth();
        //the fight at our home at the leaf, as at theirs: what of theirs makes for our core, against what of ours holds it
        //(the squads holding, our turrets about the core, the core); if it falls, so does all we have
        {
            expansion.ops.Force coming = new expansion.ops.Force(), holding = new expansion.ops.Force(army.team);
            float walk = 0f;
            for(Group g : theirs) if(g.ph > 0f && g.script == 1){
                coming.team = g.force.team;
                coming.add(g.force.copy().scl(g.ph / Math.max(1f, g.health)));
                walk = Math.max(walk, Math.max(0f, Mathf.dst(g.px, g.py, home.x, home.y) - g.range) / Math.max(1f, g.speed * 60f));
            }
            if(!coming.empty()){
                for(Group g : ours) if(g.ph > 0f && (g.script == 0 || g.script == 1 && g.foeGroup != null && g.foeGroup.script == 1)) holding.add(g.force.copy().scl(g.ph / Math.max(1f, g.health)));
                for(Site t : sites) if(t.ours && t.ph > 0f && (t.build == home || t.dps > 0f && Mathf.dst(t.x, t.y, home.x, home.y) < t.range + nearOurs * Vars.tilesize / 2f)) holding.add(t.force.copy().scl(t.ph / Math.max(1f, t.health)));
                float[] fight = coming.fight(holding);
                float d = (float)Math.exp(-(walk + (Float.isInfinite(fight[2]) ? 0f : fight[2])) / expansion.ops.Command.discount);
                if(fight[0] > 0f) score -= d * ourStake;
            }
        }
        for(var e : going){
            Site target = e.key;
            expansion.ops.Force mine = new expansion.ops.Force(army.team);
            float walk = 0f, myWorth = 0f;
            for(Group g : e.value){
                mine.add(g.force.copy().scl(g.ph / Math.max(1f, g.health)));
                float dist = Math.max(0f, Mathf.dst(g.px, g.py, target.x, target.y) - g.range);
                walk = Math.max(walk, dist / Math.max(1f, g.speed * 60f));
                myWorth += g.ph * g.worth();
            }
            expansion.ops.Force guard = target.force.copy().scl(target.ph / Math.max(1f, target.health));
            //(their core taken is the game won: all they have)
            float guardWorth = target.build instanceof CoreBlock.CoreBuild && target.build == foeCore(target) ? theirStake : target.ph * target.worth;
            for(Site t : sites) if(t != target && !t.ours && t.ph > 0f && t.dps > 0f && Mathf.dst(t.x, t.y, target.x, target.y) < t.range){
                guard.add(t.force.copy().scl(t.ph / Math.max(1f, t.health)));
                guardWorth += t.ph * t.worth;
            }
            for(Group g : theirs) if(g.ph > 0f && Mathf.dst(g.px, g.py, target.x, target.y) < g.range + target.range + 8f * Vars.tilesize){
                guard.add(g.force.copy().scl(g.ph / Math.max(1f, g.health)));
                guardWorth += g.ph * g.worth();
            }
            float[] fight = mine.fight(guard);
            float d = (float)Math.exp(-(walk + (Float.isInfinite(fight[2]) ? 0f : fight[2])) / expansion.ops.Command.discount);
            if(fight[0] > 0f) score += d * (guardWorth - myWorth * (1f - fight[1]));
            else if(fight[0] < 0f) score += d * (guardWorth * (1f - fight[1]) - myWorth);
        }
        return score;
    }

    static boolean hits(Group g, boolean air){
        for(Unit u : g.units) if(air ? u.type.targetAir : u.type.targetGround) return true;
        return false;
    }

    /** Where a group's script takes it now in the playout. */
    float[] goalOf(Group g){
        if(g.ours){
            if(g.script == 1) return new float[]{g.foeGroup.px, g.foeGroup.py};
            if(g.script == 2) return new float[]{g.foeSite.x, g.foeSite.y};
            return new float[]{home.x, home.y};
        }
        if(g.script == 1) return new float[]{home.x, home.y};
        if(g.script == 2) return new float[]{g.hx, g.hy};
        //(going on: the way its units are going, a horizon's walk on)
        float vx = 0f, vy = 0f;
        for(Unit u : g.units){
            vx += u.vel.x;
            vy += u.vel.y;
        }
        vx /= g.units.size;
        vy /= g.units.size;
        return new float[]{g.x + vx * 60f * horizon, g.y + vy * 60f * horizon};
    }

    /** The squad's units sent on its script; those already going there left to it. */
    void order(Group s, Object t){
        float[] at = goal(t);
        Vec2 to = new Vec2(at[0], at[1]);
        String k = key(t);
        for(Unit u : s.units){
            if(!(u.controller() instanceof CommandAI ai)) continue;
            String was = goals.get(u.id);
            if(was != null && !was.equals(k)) turned++;
            goals.put(u.id, k);
            if(t instanceof Site site && site.build != null){
                if(ai.attackTarget != site.build) ai.commandTarget(site.build);
            }else if(ai.targetPos == null || !ai.targetPos.within(to, 3f * Vars.tilesize)){
                //(at home, spread round the core rather than all on one tile)
                Vec2 spot = t instanceof Building ? new Vec2(to.x + Mathf.range(4f * Vars.tilesize), to.y + Mathf.range(4f * Vars.tilesize)) : to;
                ai.commandPosition(spot);
            }
        }
    }

    public String report(){
        StringBuilder b = new StringBuilder("tactics: " + state + " (" + decisions + " decisions)");
        if(Boolean.getBoolean("army.tactics.debug")){
            for(int i = 0; i < army.team.data().units.size && i < 6; i++){
                Unit u = army.team.data().units.get(i);
                if(Bait.coreUnit(u) || !u.type.canAttack) continue;
                String c = u.controller() instanceof CommandAI ai ? (ai.attackTarget == null ? "no target" : "target " + ai.attackTarget) + (ai.targetPos == null ? "" : " pos " + (int)(ai.targetPos.x / 8) + "," + (int)(ai.targetPos.y / 8)) + " cmd " + ai.command : u.controller().getClass().getSimpleName();
                b.append(String.format("\n      %s at %d,%d: %s; goal %s", u.type.name, u.tileX(), u.tileY(), c, goals.get(u.id)));
            }
        }
        return b.toString();
    }
}
