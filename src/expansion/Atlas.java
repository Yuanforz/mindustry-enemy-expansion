package expansion;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

import static mindustry.Vars.*;

/**
 * The world model: the map cut into zones, and for each zone what is in it and what goes on there, kept
 * up to date as the game is played. Nothing here decides anything; it is what decisions are to be made
 * from.
 *
 * A zone is a square of the map, a fixed number of tiles across, taken separately for each piece of
 * ground that can be walked on within it: ground on both sides of water in one square makes two zones.
 * For each zone it keeps:
 * <ul>
 * <li>what lies there to be taken up: faces of each ore in the walls, tiles of each ore in the floor,
 * faces that yield sand, vents; and how much of the zone its side has seen;</li>
 * <li>what stands there: what its own buildings cost, what the other side's cost, and how much of the
 * ground is built on;</li>
 * <li>what goes on there: the other side's damage a second seen in it, and its own that was destroyed in
 * it, both fading with time; the fire of the other side's turrets that reaches it, and of its own turrets
 * that have enough to fire.</li>
 * </ul>
 */
public class Atlas{
    /** How many tiles across the squares the map is cut into are. */
    public static final int cell = 16;
    /** How fast what was seen and lost in a zone fades: it halves every so many seconds. */
    public static final float fade = 120f;

    public static class Zone{
        public final int id, cellX, cellY;
        /** The piece of walkable ground it is part of, across the whole map. */
        public final int ground;
        public int tiles, sumX, sumY;
        /** Faces of each ore in the walls, by item id; tiles of each ore in the floor; faces of sand; vents. */
        public final IntIntMap wallOre = new IntIntMap(), floorOre = new IntIntMap();
        public int sand, vents;
        /** Tiles of it seen by its side, and what stands there, at the last look. */
        public int seen, builtOn;
        public float ownWorth, theirWorth;
        /** The other side's damage a second seen in it, and what of its own was destroyed in it, fading. */
        public float raid, lost;
        /** The most of the other side's units' damage a second and health seen in it at once lately, fading slowly: what a force sent there has to beat. */
        public float peakDps, peakHealth;
        float nowDps, nowHealth;
        /** The fire a second of the other side's turrets that reaches its middle, and of its own that have enough to fire. */
        public float theirFire, ownFire;
        /** Zones next to it over ground, and over what a bridge spans. */
        public final IntSet near = new IntSet(), bridged = new IntSet();

        Zone(int id, int cellX, int cellY, int ground){
            this.id = id;
            this.cellX = cellX;
            this.cellY = cellY;
            this.ground = ground;
        }

        public int x(){
            return tiles == 0 ? cellX * cell + cell / 2 : sumX / tiles;
        }

        public int y(){
            return tiles == 0 ? cellY * cell + cell / 2 : sumY / tiles;
        }
    }

    public final Seq<Zone> zones = new Seq<>();
    /** The zone of every tile, or -1 for tiles that can't be walked on. */
    private int[] zoneOf = {};
    private int width, height;
    private float lookClock = -1e9f;

    public void reset(){
        zones.clear();
        zoneOf = new int[0];
        lookClock = -1e9f;
    }

    public boolean ready(){
        return zoneOf.length == world.width() * world.height() && zoneOf.length > 0;
    }

    public int zoneAt(int x, int y){
        if(!ready() || x < 0 || y < 0 || x >= width || y >= height) return -1;
        return zoneOf[x + y * width];
    }

    public @arc.util.Nullable Zone zone(int x, int y){
        int id = zoneAt(x, y);
        return id < 0 ? null : zones.get(id);
    }

    private static boolean walkable(Tile tile){
        return !tile.floor().isDeep() && !tile.floor().solid && !(tile.build == null && tile.block().solid && tile.block().isStatic());
    }

    /** Cuts the map into zones and counts what lies in each. Done once a map. */
    public void build(Survey survey){
        width = world.width();
        height = world.height();
        zones.clear();
        zoneOf = new int[width * height];
        java.util.Arrays.fill(zoneOf, -1);
        //the pieces of walkable ground
        int[] groundOf = new int[width * height];
        java.util.Arrays.fill(groundOf, -1);
        IntQueue queue = new IntQueue();
        int grounds = 0;
        for(int start = 0; start < width * height; start++){
            if(groundOf[start] != -1 || !walkable(world.tiles.geti(start))) continue;
            groundOf[start] = grounds;
            queue.addLast(start);
            while(!queue.isEmpty()){
                int at = queue.removeFirst(), ax = at % width, ay = at / width;
                for(int d = 0; d < 4; d++){
                    int x = ax + Geometry.d4x(d), y = ay + Geometry.d4y(d);
                    if(x < 0 || y < 0 || x >= width || y >= height) continue;
                    int next = x + y * width;
                    if(groundOf[next] != -1 || !walkable(world.tiles.geti(next))) continue;
                    groundOf[next] = grounds;
                    queue.addLast(next);
                }
            }
            grounds++;
        }
        //a zone for each piece of ground in each square
        IntIntMap key = new IntIntMap();
        for(int i = 0; i < width * height; i++){
            if(groundOf[i] < 0) continue;
            int x = i % width, y = i / width, cx = x / cell, cy = y / cell;
            int k = (cx + cy * (width / cell + 1)) * 4096 + groundOf[i] % 4096;
            int id = key.get(k, -1);
            if(id == -1){
                id = zones.size;
                key.put(k, id);
                zones.add(new Zone(id, cx, cy, groundOf[i]));
            }
            zoneOf[i] = id;
            Zone zone = zones.get(id);
            zone.tiles++;
            zone.sumX += x;
            zone.sumY += y;
        }
        //which are next to which, over ground and over what a bridge spans
        for(int i = 0; i < width * height; i++){
            int id = zoneOf[i];
            if(id < 0) continue;
            int x = i % width, y = i / width;
            for(int d = 0; d < 4; d++){
                for(int k = 1; k <= 4; k++){
                    int nx = x + Geometry.d4x(d) * k, ny = y + Geometry.d4y(d) * k;
                    if(nx < 0 || ny < 0 || nx >= width || ny >= height) break;
                    int other = zoneOf[nx + ny * width];
                    if(other < 0) continue;
                    if(other != id){
                        if(k == 1) zones.get(id).near.add(other);
                        else zones.get(id).bridged.add(other);
                    }
                    break;
                }
            }
        }
        //what lies in each to be taken up: a face counts in the zone of the open tile in front of it
        if(survey.ready()){
            for(Item item : content.items()){
                IntSeq faces = survey.ore(item);
                for(int i = 0; i < faces.size; i++){
                    Zone zone = zone(survey.x(faces.get(i)), survey.y(faces.get(i)));
                    if(zone != null) zone.wallOre.increment(item.id);
                }
                IntSeq floor = survey.floor(item);
                for(int i = 0; i < floor.size; i++){
                    Zone zone = zone(Point2.x(floor.get(i)), Point2.y(floor.get(i)));
                    if(zone != null) zone.floorOre.increment(item.id);
                }
            }
            IntSeq sand = survey.sand();
            for(int i = 0; i < sand.size; i++){
                Zone zone = zone(survey.x(sand.get(i)), survey.y(sand.get(i)));
                if(zone != null) zone.sand++;
            }
        }
        for(Tile tile : world.tiles){
            if(tile.floor().attributes.get(mindustry.world.meta.Attribute.steam) > 0f){
                Zone zone = zone(tile.x, tile.y);
                if(zone != null) zone.vents++;
            }
        }
    }

    /** What the other side's units do in a zone, seen this second: their damage a second, where they are. */
    public void sighted(float x, float y, float dps, float health){
        Zone zone = zone((int)(x / tilesize), (int)(y / tilesize));
        if(zone == null) return;
        zone.raid += dps / fade;
        zone.nowDps += dps;
        zone.nowHealth += health;
    }

    /** Something of its own, worth so much, was destroyed at a tile. */
    public void lost(int x, int y, float worth){
        Zone zone = zone(x, y);
        if(zone != null) zone.lost += worth;
    }

    /** Lets what was seen and lost fade, a second at a time, and every few seconds looks the zones over again. */
    public void update(Team team, float clock){
        if(!ready()) return;
        float keep = (float)Math.pow(0.5, 1.0 / fade), slow = (float)Math.pow(0.5, 1.0 / (fade * 4f));
        for(Zone zone : zones){
            zone.raid *= keep;
            zone.lost *= keep;
            zone.peakDps = Math.max(zone.peakDps * slow, zone.nowDps);
            zone.peakHealth = Math.max(zone.peakHealth * slow, zone.nowHealth);
            zone.nowDps = zone.nowHealth = 0f;
        }
        if(clock - lookClock < 60f * 10f) return;
        lookClock = clock;
        for(Zone zone : zones){
            zone.ownWorth = zone.theirWorth = 0f;
            zone.builtOn = zone.seen = 0;
        }
        for(int i = 0; i < width * height; i++){
            int id = zoneOf[i];
            if(id < 0) continue;
            Zone zone = zones.get(id);
            int x = i % width, y = i / width;
            if(!state.rules.fog || fogControl.isDiscovered(team, x, y)) zone.seen++;
            Tile tile = world.tiles.geti(i);
            if(tile.build != null) zone.builtOn++;
        }
        for(TeamData data : state.teams.active){
            for(Building build : data.buildings){
                Zone zone = zone(build.tileX(), build.tileY());
                if(zone == null) continue;
                float worth = 0f;
                for(ItemStack stack : build.block.requirements) worth += stack.amount;
                if(data.team == team) zone.ownWorth += worth;
                else if(data.team != Team.derelict) zone.theirWorth += worth;
            }
        }
        for(Zone zone : zones){
            float wx = zone.x() * tilesize, wy = zone.y() * tilesize;
            float[] own = {0f}, theirs = {0f};
            for(TeamData data : state.teams.active){
                if(data.team == Team.derelict) continue;
                boolean mine = data.team == team;
                indexer.eachBlock(data.team, wx, wy, tilesize * 40f, build -> build instanceof Turret.TurretBuild turret && turret.hasAmmo(), build -> {
                    if(!build.within(wx, wy, ((Turret)build.block).range)) return;
                    float dps = Brain.turretDps((Turret.TurretBuild)build);
                    if(mine) own[0] += dps;
                    else theirs[0] += dps;
                });
            }
            zone.ownFire = own[0];
            zone.theirFire = theirs[0];
        }
    }

    /** The zones, one to a line, that hold anything or see anything go on, for looking into how it sees the map. */
    public String describe(){
        StringBuilder out = new StringBuilder();
        for(Zone zone : zones){
            boolean any = zone.wallOre.size > 0 || zone.floorOre.size > 0 || zone.vents > 0 || zone.ownWorth > 0 || zone.theirWorth > 0 || zone.raid > 0.5f || zone.lost > 1f;
            if(!any || zone.tiles < 8) continue;
            out.append(String.format("  zone %d at %d,%d: %d tiles, ground %d, seen %d%%, built %d%%", zone.id, zone.x(), zone.y(), zone.tiles, zone.ground, zone.seen * 100 / Math.max(zone.tiles, 1), zone.builtOn * 100 / Math.max(zone.tiles, 1)));
            if(zone.wallOre.size > 0){
                out.append(", in walls");
                for(var entry : zone.wallOre) out.append(' ').append(content.item(entry.key).name).append('=').append(entry.value);
            }
            if(zone.floorOre.size > 0){
                out.append(", in floor");
                for(var entry : zone.floorOre) out.append(' ').append(content.item(entry.key).name).append('=').append(entry.value);
            }
            if(zone.sand > 0) out.append(", sand faces ").append(zone.sand);
            if(zone.vents > 0) out.append(", vent tiles ").append(zone.vents);
            if(zone.ownWorth > 0) out.append(", own worth ").append((int)zone.ownWorth);
            if(zone.theirWorth > 0) out.append(", theirs ").append((int)zone.theirWorth);
            if(zone.raid > 0.5f) out.append(", raided ").append((int)zone.raid).append("/s");
            if(zone.peakDps > 1f) out.append(", up to ").append((int)zone.peakDps).append("/s with ").append((int)zone.peakHealth).append(" health at once");
            if(zone.lost > 1f) out.append(", lost ").append((int)zone.lost);
            if(zone.ownFire > 0) out.append(", own fire ").append((int)zone.ownFire);
            if(zone.theirFire > 0) out.append(", their fire ").append((int)zone.theirFire);
            out.append(", next to ").append(zone.near.size).append(zone.bridged.size > 0 ? " (+" + zone.bridged.size + " over a bridge)" : "").append('\n');
        }
        return out.toString();
    }
}
