package expansion.army;

import arc.files.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import expansion.defense.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

import java.util.*;

/**
 * A2c, turret creep: turrets used to attack. When the front is stuck (its units keep dying at the same place and the
 * other side loses nothing), it pushes the front forward by building turrets a step at a time towards the other side.
 * Each new post goes down where the posts already standing cover it, on the ground way to the other side's core, out of
 * reach of the other side's turrets while it is built; the army waits just in front of the newest post, so that what the
 * other side sends at it comes into the turrets' fire. Once the next step would be in reach of the other side's turrets,
 * the post goes there only with enough of its own units round it to hold it while it goes up, walls first: from then on
 * it trades with what it reaches.
 *
 * What it builds is one of the defence package's modules (blueprints/defense/modules.txt), chosen by the defence
 * package's measured capacities (blueprints/defense/capacity.txt, open ground): the one that holds the other side's army
 * as it stands for the least it costs. The core unit builds it, paying from the core. Each module has a port where its
 * supply line ends; here that is a duct, which the economy has to feed (in the duel the tester feeds it, as it feeds the
 * bases' lines).
 */
public class Creep{
    /** One post: the module, where its parts go, and whether it is up. */
    public static class Post{
        public Battery module;
        public List<Battery.Part> parts;
        public int x, y, turns;
        /** The duct where its supply line ends, and what it takes. */
        public int portX = -1, portY = -1;
        public @Nullable Item item;
        public boolean up;
        public float began;
        /** For the tester: how much it is owed of its supply. */
        public float owed;
    }

    final Army army;
    public final Seq<Post> posts = new Seq<>();
    public @Nullable Post building;
    /** At most this many posts, this far apart along the way (tiles). */
    public static int most = Integer.getInteger("army.creepmost", 6), step = Integer.getInteger("army.creepstep", 14);
    /** What it did, for the report. */
    public int begun, built, stuckTimes;
    public boolean stuck;

    static @Nullable List<Battery> modules;
    static @Nullable Capacity capacity;
    /** The modules it may creep with: fed by items alone, no power, no liquid needed. */
    static final String[] usable = {"breach-pair-direct", "breach-pair", "diffuse-pair"};

    /** Ground distance (tiles) to the other side's cores, worked out now and then. */
    int[] dist;
    float distAt = -1e9f;
    /** When its units died lately and where, and when the other side last lost a building. */
    final FloatSeq deaths = new FloatSeq();
    float lastEnemyLoss, holdingSince = -1f;

    public Creep(Army army){
        this.army = army;
        load();
    }

    static void load(){
        if(modules != null) return;
        try{
            Fi dir = null;
            var mod = Vars.mods == null ? null : Vars.mods.getMod(expansion.ExpansionMod.class);
            if(mod != null) dir = mod.root.child("blueprints").child("defense");
            if(dir == null || !dir.exists()) dir = new Fi("blueprints/defense");
            modules = Battery.parse(dir.child("modules.txt").readString());
            capacity = Capacity.parse(dir.child("capacity.txt").readString());
        }catch(Throwable e){
            Log.warn("[EnemyExpansion] army: the defence modules could not be read (@); no turret creep.", e.toString());
            modules = new ArrayList<>();
            capacity = new Capacity();
        }
    }

    /** One of its own units lost, at a place (from the army's event listener). */
    void lost(Unit unit){
        if(unit.spawnedByCore || Bait.coreUnit(unit)) return;
        deaths.add(army.clock);
    }

    /** The other side lost a building. */
    void enemyLost(Building build){
        if(build.block instanceof mindustry.world.blocks.defense.Wall) return;
        lastEnemyLoss = army.clock;
    }

    /** Once a second. */
    public void update(){
        Building core = army.team.core();
        if(core == null) return;
        if(army.clock - distAt > 60f * 60f || dist == null){
            distAt = army.clock;
            distances();
        }
        //stuck: in the last two minutes, six or more of its units died and the other side lost nothing; or it has been
        //waiting a minute and a half for odds it is not getting
        float window = 60f * 120f;
        while(deaths.size > 0 && deaths.first() < army.clock - window) deaths.removeIndex(0);
        boolean trading = deaths.size >= 6 && army.clock - lastEnemyLoss > window;
        if(army.flags.allot && !army.allot.go){
            if(holdingSince < 0f) holdingSince = army.clock;
        }else holdingSince = -1f;
        boolean waiting = holdingSince >= 0f && army.clock - holdingSince > 60f * 90f;
        boolean now = trading || waiting;
        if(now && !stuck) stuckTimes++;
        stuck = now;

        if(building != null){
            if(standing(building)){
                building.up = true;
                posts.add(building);
                built++;
                building = null;
            }else if(army.clock - building.began > 60f * 150f){
                //it never went up (shot down as it went up, or no way to it): let be, the next is chosen afresh
                building = null;
            }
        }
        posts.removeAll(p -> !alive(p));
        if(building == null && stuck && posts.size < most) plan(core);
        rally();
    }

    /** Whether a post's turrets all stand. */
    boolean standing(Post post){
        for(Battery.Part part : post.parts){
            if(!part.block.equals(post.module.main().block)) continue;
            Building build = Vars.world.build(part.x, part.y);
            if(build == null || build.team != army.team || !(build.block instanceof Turret)) return false;
        }
        return true;
    }

    boolean alive(Post post){
        for(Battery.Part part : post.parts){
            if(!part.block.equals(post.module.main().block)) continue;
            Building build = Vars.world.build(part.x, part.y);
            if(build != null && build.team == army.team && build.block instanceof Turret) return true;
        }
        return false;
    }

    /** The army waits just in front of the newest post while it is not attacking. */
    void rally(){
        if(posts.isEmpty() || !army.flags.allot || army.allot.go) return;
        Post last = posts.peek();
        Tile ahead = downhill(Vars.world.tile(last.x, last.y), 5);
        if(ahead == null) return;
        float rx = ahead.worldx(), ry = ahead.worldy();
        for(int i = 0; i < army.team.data().units.size; i++){
            Unit unit = army.team.data().units.get(i);
            if(Bait.coreUnit(unit) || !unit.type.canAttack || !(unit.controller() instanceof mindustry.ai.types.CommandAI ai)) continue;
            if(ai.hasCommand() || ai.isAttacking() || army.ops.jobs.containsKey(unit.id)) continue;
            if(!unit.within(rx, ry, Vars.tilesize * 10f)) ai.commandPosition(new arc.math.geom.Vec2(rx + Mathf.range(Vars.tilesize * 4f), ry + Mathf.range(Vars.tilesize * 4f)));
        }
    }

    /** Ground distance in tiles from every tile to the nearest of the other side's cores, over what is not rock or deep. */
    void distances(){
        int w = Vars.world.width(), h = Vars.world.height();
        dist = new int[w * h];
        Arrays.fill(dist, -1);
        IntQueue queue = new IntQueue();
        for(Teams.TeamData data : Vars.state.teams.active){
            if(data.team == army.team || data.team == Team.derelict) continue;
            for(var core : data.cores){
                int at = core.tileX() + core.tileY() * w;
                dist[at] = 0;
                queue.addLast(at);
            }
        }
        while(!queue.isEmpty()){
            int at = queue.removeFirst(), x = at % w, y = at / w;
            for(var d : arc.math.geom.Geometry.d4){
                int nx = x + d.x, ny = y + d.y;
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int next = nx + ny * w;
                if(dist[next] >= 0) continue;
                Tile tile = Vars.world.tile(nx, ny);
                //buildings are walked round or broken through; rock and deep water are not
                if(tile.block().isStatic() || tile.floor().isDeep()) continue;
                dist[next] = dist[at] + 1;
                queue.addLast(next);
            }
        }
    }

    /** The tile some steps further along the ground way to the other side's cores. */
    @Nullable Tile downhill(@Nullable Tile from, int steps){
        if(from == null || dist == null) return null;
        int w = Vars.world.width();
        Tile at = from;
        for(int i = 0; i < steps; i++){
            int best = dist[at.x + at.y * w];
            if(best <= 0) break;
            Tile next = null;
            for(var d : arc.math.geom.Geometry.d8){
                Tile t = Vars.world.tile(at.x + d.x, at.y + d.y);
                if(t == null) continue;
                int v = dist[t.x + t.y * w];
                if(v >= 0 && v < best){
                    best = v;
                    next = t;
                }
            }
            if(next == null) break;
            at = next;
        }
        return at;
    }

    /** Where the front stands: its turret nearest the other side's cores along the ground way. */
    @Nullable Building front(){
        if(dist == null) return null;
        Building best = null;
        int bestDist = Integer.MAX_VALUE;
        int w = Vars.world.width();
        for(Building build : army.team.data().buildings){
            if(!(build.block instanceof Turret)) continue;
            int d = dist[build.tileX() + build.tileY() * w];
            if(d >= 0 && d < bestDist){
                bestDist = d;
                best = build;
            }
        }
        return best;
    }

    /** The module to creep with: of those it can build and feed, the one that holds the other side's army for least. */
    @Nullable Battery choose(){
        Map<String, Integer> units = new HashMap<>();
        for(Unit foe : army.threat.foes) units.merge(foe.type.name, 1, Integer::sum);
        Battery best = null;
        float bestScore = Float.MAX_VALUE;
        for(Battery module : modules){
            if(!Arrays.asList(usable).contains(module.name)) continue;
            Item item = portItem(module);
            if(item == null || !buildable(module)) continue;
            Capacity.Config config = capacity.configs.get(module.name + " " + item.name + " dry");
            if(config == null) continue;
            //how much of what the other side has one post holds, in open ground; with nothing seen yet, all count the same
            float load = units.isEmpty() ? 1f : config.load(units, false);
            if(Float.isInfinite(load)) load = 1000f;
            float score = load * cost(module);
            if(score < bestScore){
                bestScore = score;
                best = module;
            }
        }
        return best;
    }

    static @Nullable Item portItem(Battery module){
        for(Battery.Part part : module.parts) if(part.block.equals("item-source") && part.config != null) return Vars.content.item(part.config);
        return null;
    }

    /** Whether its team has what every part costs, with some to spare. */
    boolean buildable(Battery module){
        Building core = army.team.core();
        if(core == null) return false;
        ObjectIntMap<Item> need = new ObjectIntMap<>();
        for(Battery.Part part : module.parts){
            Block block = block(part);
            if(block == null) return part.optional || part.port();
            for(ItemStack stack : block.requirements) need.increment(stack.item, Math.round(stack.amount * Vars.state.rules.buildCostMultiplier));
        }
        for(var entry : need) if(core.items.get(entry.key) < entry.value + 50) return false;
        return true;
    }

    static float cost(Battery module){
        float sum = 0f;
        for(Battery.Part part : module.parts){
            Block block = block(part);
            if(block == null) continue;
            for(ItemStack stack : block.requirements) sum += stack.amount;
        }
        return sum;
    }

    /** The block a part stands for: the port is a duct the supply line ends in; optional parts (water) are left out. */
    static @Nullable Block block(Battery.Part part){
        if(part.optional) return null;
        if(part.port()) return part.liquidPort() ? null : Blocks.duct;
        return Vars.content.block(part.block);
    }

    /** The next post: some tiles on from the front along the way, as near as that to where its turrets cover. */
    void plan(Building core){
        Building front = front();
        Battery module = choose();
        if(front == null || module == null) return;
        int range = (int)(((Turret)front.block).range / Vars.tilesize);
        for(int s : new int[]{step, step * 2 / 3, step / 3}){
            Tile at = downhill(front.tile, Math.min(s, range - 4));
            if(at == null || at == front.tile) continue;
            Tile beyond = downhill(at, 10);
            int turns = beyond == null ? 0 : Mathf.mod(Math.round(Angles.angle(at.x, at.y, beyond.x, beyond.y) / 90f), 4);
            for(int side : new int[]{0, 3, -3, 6, -6}){
                int ox = turns % 2 == 0 ? 0 : side, oy = turns % 2 == 0 ? side : 0;
                Post post = place(module, at.x + ox, at.y + oy, turns);
                if(post != null){
                    building = post;
                    post.began = army.clock;
                    begun++;
                    return;
                }
            }
        }
    }

    /** A post of a module at a place, if every part can go there and the turrets' build is safe; else null. */
    @Nullable Post place(Battery module, int x, int y, int turns){
        //(the module's turrets are along +y from the first, facing +x: it is turned so that +x faces the way on)
        List<Battery.Part> parts = new ArrayList<>(module.at(x, y, turns));
        //a line of beryllium walls three tiles in front of the turrets, as wide as the module
        int fx = arc.math.geom.Geometry.d4x(turns), fy = arc.math.geom.Geometry.d4y(turns), lx = arc.math.geom.Geometry.d4x(turns + 1), ly = arc.math.geom.Geometry.d4y(turns + 1);
        int width = 0;
        for(Battery.Part part : module.parts) width = Math.max(width, part.y);
        for(int k = -1; k <= width + 1; k++) parts.add(0, new Battery.Part("beryllium-wall", x + fx * 3 + lx * k, y + fy * 3 + ly * k, 0, null, false));
        boolean covered = false;
        for(Battery.Part part : parts){
            Block block = block(part);
            if(block == null) continue;
            if(!Build.validPlace(block, army.team, part.x, part.y, part.rotation)) return null;
            if(army.threat.covered(part.x * Vars.tilesize, part.y * Vars.tilesize, false, Vars.tilesize * 2f)) covered = true;
        }
        //in reach of the other side's turrets: only with enough of its own units there to hold it while it goes up
        if(covered){
            int near = 0;
            for(int i = 0; i < army.team.data().units.size; i++){
                Unit unit = army.team.data().units.get(i);
                if(unit.type.canAttack && !Bait.coreUnit(unit) && unit.within(x * Vars.tilesize, y * Vars.tilesize, Vars.tilesize * 20f)) near++;
            }
            if(near < 6) return null;
        }
        Post post = new Post();
        post.module = module;
        post.parts = parts;
        post.x = x;
        post.y = y;
        post.turns = turns;
        post.item = portItem(module);
        for(Battery.Part part : parts){
            if(part.block.equals("item-source")){
                post.portX = part.x;
                post.portY = part.y;
            }
        }
        return post;
    }

    /**
     * The core unit's part: to the post going up, and its parts put in to be built, walls and the turrets first. A port's
     * duct faces what it feeds.
     * @return whether there is a post to build
     */
    public boolean work(Unit unit, mindustry.entities.units.AIController ai){
        Post post = building;
        if(post == null) return false;
        Building core = army.team.core();
        if(core == null) return false;
        Tile at = Vars.world.tile(post.x, post.y);
        if(at == null) return false;
        //it stands behind the post, a few tiles back along the way it came
        Building front = front();
        float sx = at.worldx(), sy = at.worldy();
        if(front != null){
            float angle = Angles.angle(at.worldx(), at.worldy(), front.x, front.y);
            sx += Angles.trnsx(angle, Vars.tilesize * 8f);
            sy += Angles.trnsy(angle, Vars.tilesize * 8f);
        }
        ai.moveTo(new arc.math.geom.Vec2(sx, sy), Vars.tilesize * 2f);
        if(unit.plans().size > 0 || !unit.within(at, unit.type.buildRange - Vars.tilesize * 4f)) return true;
        for(Battery.Part part : post.parts){
            Block block = block(part);
            if(block == null) continue;
            Building there = Vars.world.build(part.x, part.y);
            if(there != null && there.block == block && there.team == army.team) continue;
            int rotation = part.rotation;
            if(part.port()) rotation = portFacing(post, part);
            if(!Build.validPlace(block, army.team, part.x, part.y, rotation)) continue;
            Object config = part.config == null ? null : Vars.content.item(part.config);
            unit.addBuild(new BuildPlan(part.x, part.y, rotation, block, block.configurations.isEmpty() ? null : config));
        }
        return true;
    }

    /** Which way the port's duct faces: towards the part next to it that is not another port. */
    static int portFacing(Post post, Battery.Part port){
        for(int r = 0; r < 4; r++){
            int nx = port.x + arc.math.geom.Geometry.d4x(r), ny = port.y + arc.math.geom.Geometry.d4y(r);
            for(Battery.Part part : post.parts){
                if(part.port() || part.optional) continue;
                int s = part.size(), lx = Battery.low(part.x, s), ly = Battery.low(part.y, s);
                if(nx >= lx && nx < lx + s && ny >= ly && ny < ly + s) return r;
            }
        }
        return 0;
    }

    public String report(){
        return "creep stuck " + stuckTimes + " begun " + begun + " built " + built + " standing " + posts.size;
    }
}
