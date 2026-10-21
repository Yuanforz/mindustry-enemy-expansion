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
    public static float switchCost = Float.parseFloat(System.getProperty("army.tactics.switch", "0.15"));
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

        void add(Unit u){
            units.add(u);
            dps += u.type.dpsEstimate;
            health += u.health + u.shield;
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

        Site(Building build, boolean ours, float x, float y, float health, float dps, float range, float worth, boolean air, boolean ground){
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
            for(int i = 0; i < ours.size; i++){
                Group s = ours.get(i);
                Object best = target(s);
                float bestScore = playout() - switchPenalty(s, best);
                for(Object o : options.get(i)){
                    set(s, o);
                    float v = playout() - switchPenalty(s, o);
                    if(v > bestScore + 1e-3f){
                        bestScore = v;
                        best = o;
                    }
                }
                set(s, best);
            }
            for(Group g : theirs){
                int best = g.script;
                float worst = playout();
                for(int k = 0; k < 3; k++){
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
                    float v = playout() - switchPenalty(s, o);
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
                    float dps = t.hasAmmo() ? Threat.gun(t, type).dps : 0f;
                    sites.add(new Site(b, mine, b.x, b.y, b.health, dps, t.range(), worthTurret, type.targetAir, type.targetGround));
                }else if(b instanceof CoreBlock.CoreBuild){
                    sites.add(new Site(b, mine, b.x, b.y, b.health, 0f, 0f, worthCore, false, false));
                }else if(b.block instanceof UnitFactory || b.block instanceof Reconstructor){
                    sites.add(new Site(b, mine, b.x, b.y, b.health, 0f, 0f, worthYard, false, false));
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
                if(threatened) sites.add(new Site(null, true, x, y, s[2], 0f, 0f, worthOther, false, false));
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
        return Reach.near(r, mindustry.core.World.toTile(x), mindustry.core.World.toTile(y), 4);
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
                float fire = g.dps * (g.ph / g.health) * dt;
                int tg = -1, ts = -1;
                float best = Float.MAX_VALUE, reach2 = (g.range + 2f * Vars.tilesize) * (g.range + 2f * Vars.tilesize);
                for(int j = 0; j < ours.size + theirs.size; j++){
                    Group e = j < ours.size ? ours.get(j) : theirs.get(j - ours.size);
                    if(e.ours == g.ours || e.ph <= 0f) continue;
                    if(e.air ? !hits(g, true) : !hits(g, false)) continue;
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
                if(tg >= 0) hitG[tg] += fire;
                else if(ts >= 0) hitS[ts] += fire * 0.25f; //(units hit buildings at a quarter of their estimate: measured on cores)
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
            //turrets fire at the nearest enemy group in reach
            for(Site s : sites){
                if(s.dps <= 0f || s.ph <= 0f) continue;
                int tg = -1;
                float best = Float.MAX_VALUE, r2 = s.range * s.range;
                for(int j = 0; j < ours.size + theirs.size; j++){
                    Group e = j < ours.size ? ours.get(j) : theirs.get(j - ours.size);
                    if(e.ours == s.ours || e.ph <= 0f || (e.air ? !s.hitsAir : !s.hitsGround)) continue;
                    float d = Mathf.dst2(s.x, s.y, e.px, e.py);
                    if(d < r2 && d < best){
                        best = d;
                        tg = j;
                    }
                }
                if(tg >= 0) hitG[tg] += s.dps * (s.ph / s.health) * dt;
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
        //an attack still on its way at the end is valued at its leaf, as a search does past its horizon: the fight at its
        //target by Lanchester's square law (its target's turrets and the groups of theirs about it), what it stands to win
        //less what it stands to lose, discounted by the walk still left
        for(Group g : ours){
            if(g.script != 2 || g.ph <= 0f || g.foeSite.ph <= 0f) continue;
            float dist = Mathf.dst(g.px, g.py, g.foeSite.x, g.foeSite.y);
            if(dist < g.range + 2f * Vars.tilesize) continue;
            float gDps = 0f, gHp = 0f, gWorth = 0f;
            for(Site t : sites) if(!t.ours && t.ph > 0f && t.dps > 0f && Mathf.dst(t.x, t.y, g.foeSite.x, g.foeSite.y) < t.range + 4f * Vars.tilesize){
                gDps += t.dps * t.ph / t.health;
                gHp += t.ph;
                gWorth += t.ph * t.worth;
            }
            for(Group e : theirs) if(e.ph > 0f && Mathf.dst(e.px, e.py, g.foeSite.x, g.foeSite.y) < 20f * Vars.tilesize){
                gDps += e.dps * e.ph / e.health;
                gHp += e.ph;
                gWorth += e.ph * e.worth();
            }
            float ours = g.dps * (g.ph / g.health) * g.ph, guard = gDps * gHp;
            float arrive = dist / Math.max(1f, g.speed * 60f);
            float d = (float)Math.exp(-arrive / leafDiscount);
            if(ours > guard){
                //won: the guard and the target taken, ours left with the root of the difference
                float lost = g.ph * (1f - (float)Math.sqrt(1f - guard / ours));
                score += d * (gWorth + g.foeSite.ph * g.foeSite.worth - lost * g.worth());
            }else{
                //lost: all of it, and what it takes with it
                float took = guard <= 0f ? 0f : gWorth * (1f - (float)Math.sqrt(1f - ours / guard));
                score += d * (took - g.ph * g.worth());
            }
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
