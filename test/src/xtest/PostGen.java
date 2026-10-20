package xtest;

import arc.files.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.heat.*;
import mindustry.world.consumers.*;

import java.util.*;

/**
 * Turret posts of our own: a turret with what it must have to fire laid round it (heaters for one that needs heat, the
 * ports its ammunition and liquids come in at), written as designs the planner can aim at: the library in
 * blueprints/posts, each with a row in its modules.tsv. A post "makes" a stand-in unit named after its turret, one a
 * second while it is fed (units_per_min turret=60), so the planner's aim "malign:1" is a post built and fed, and the
 * balance gives it makers for all it takes, as for any machine.
 *
 * What a post takes is worked out here, not measured (a turret takes ammunition only while it fires): its ammunition at
 * a share of the time spent firing ({@link #duty}), its liquids all the time, the heaters' phase fabric, its power.
 *
 * Usage: PostGen data-dir out-dir
 */
public class PostGen{
    static final int E = 0, N = 1, W = 2, S = 3;
    /** The share of the time a turret is taken to fire, for what its ammunition line must bring. */
    static final float duty = 0.3f;
    /** A port's line brings at least this much a second (so a store fills between fights). */
    static final float least = 0.25f;

    public static void main(String[] args) throws Exception{
        Boot.boot(args[0]);
        Fi dir = new Fi(args[1]);
        dir.mkdirs();
        List<Post> posts = List.of(titan(), scathe(), afflict(), malign());
        StringBuilder tsv = new StringBuilder("id\tfile\tname\tsize\tblocks\tcost\tpower\tinputs\toutputs\tunits_per_min\tmachines\tstable\tpower_made\tgrids\tsource\n");
        StringBuilder pictures = new StringBuilder();
        for(Post post : posts){
            DesignGen.Plan p = post.plan;
            try{
                DesignGen.checkLines(p);
                checkHeat(p, post.turret);
            }catch(IllegalStateException e){
                System.out.println("NOT WRITTEN: " + e.getMessage());
                continue;
            }
            DesignGen.power(p);
            Schematic s = p.schematic();
            Schematics.write(s, dir.child(p.id + ".msch"));
            //ports, in the schematic's own coordinates
            int[] b = p.bounds();
            StringBuilder in = new StringBuilder();
            for(Stile t : p.tiles){
                if(t.block != Blocks.itemSource && t.block != Blocks.liquidSource) continue;
                String what = ((UnlockableContent)t.config).name;
                float rate = post.rates.getOrDefault(t.x + "," + t.y, least);
                if(in.length() > 0) in.append(';');
                in.append(what).append('@').append(t.x - b[0]).append(',').append(t.y - b[1]).append(':').append(t.rotation).append('=').append(Strings.autoFixed(rate, 3));
            }
            Map<String, Integer> cost = new TreeMap<>();
            int blocks = 0;
            float power = 0f;
            for(Stile t : p.tiles){
                if(DesignGen.isPort(t.block)) continue;
                blocks++;
                for(ItemStack st : t.block.requirements) cost.merge(st.item.name, st.amount, Integer::sum);
                for(Consume c : t.block.consumers) if(c instanceof ConsumePower cp) power += cp.usage * 60f;
            }
            StringBuilder c = new StringBuilder();
            for(var e : cost.entrySet()) c.append(c.length() > 0 ? " " : "").append(e.getKey()).append(' ').append(e.getValue());
            tsv.append(String.join("\t", p.id, p.id + ".msch", p.id, s.width + "x" + s.height, String.valueOf(blocks), c.toString(), Strings.autoFixed(power, 1),
                in.toString(), "", post.turret.name + "=60", post.turret.name + " x1", "settled", "0", String.valueOf(DesignGen.grids(p)), "generated: " + p.pattern)).append('\n');
            String picture = p.id + "  " + p.pattern + "\n" + p.picture();
            dir.child(p.id + ".txt").writeString(picture);
            pictures.append(picture).append('\n');
            System.out.println(p.id + ": " + p.pattern + "; takes " + in + "; power " + power);
        }
        dir.child("modules.tsv").writeString(tsv.toString());
        dir.child("pictures.txt").writeString(pictures.toString());
        System.exit(0);
    }

    static final class Post{
        final DesignGen.Plan plan;
        final Turret turret;
        /** What each port's line brings a second, by the port's place (x,y as drawn). */
        final Map<String, Float> rates = new HashMap<>();

        Post(String id, Turret turret){
            this.plan = new DesignGen.Plan(id, turret.name, "");
            this.turret = turret;
        }

        void in(UnlockableContent what, int x, int y, int dir, float rate){
            plan.in(what, x, y, dir);
            rates.put(x + "," + y, Math.max(least, rate));
        }
    }

    /** Ammunition a second while it fires, of this item. */
    static float ammo(ItemTurret t, Item item){
        var bullet = t.ammoTypes.get(item);
        float shots = 60f / t.reload * Math.max(1, t.shoot.shots);
        return shots * t.ammoPerShot / Math.max(1f, bullet.ammoMultiplier);
    }

    static float liquid(Block b, Liquid l){
        float sum = 0f;
        for(Consume c : b.consumers) if(c instanceof ConsumeLiquid cl && cl.liquid == l && !cl.optional && !cl.booster) sum += cl.amount * 60f;
        return sum;
    }

    /** A titan: thorium in from behind, hydrogen beside it. */
    static Post titan(){
        ItemTurret t = (ItemTurret)Blocks.titan;
        Post p = new Post("post-titan", t);
        p.plan.put(t, 0, 0);
        p.in(Items.thorium, -1, 1, E, ammo(t, Items.thorium) * duty);
        p.in(Liquids.hydrogen, -1, 2, E, liquid(t, Liquids.hydrogen));
        p.plan.pattern = "titan with thorium from behind and hydrogen beside it";
        return p;
    }

    /** A scathe: carbide in from behind. */
    static Post scathe(){
        ItemTurret t = (ItemTurret)Blocks.scathe;
        Post p = new Post("post-scathe", t);
        p.plan.put(t, 0, 0);
        p.in(Items.carbide, -1, 1, E, ammo(t, Items.carbide) * duty);
        p.plan.pattern = "scathe with carbide from behind";
        return p;
    }

    /** An afflict (20 heat): seven electric heaters against it, two on each of three sides and one on the fourth. */
    static Post afflict(){
        Turret t = (Turret)Blocks.afflict;
        Post p = new Post("post-afflict", t);
        DesignGen.Plan d = p.plan;
        d.put(t, 0, 0);
        Block h = Blocks.electricHeater;
        d.put(h, -2, 0, E);
        d.put(h, -2, 2, E);
        d.put(h, 0, -2, N);
        d.put(h, 2, -2, N);
        d.put(h, 0, 4, S);
        d.put(h, 2, 4, S);
        d.put(h, 4, 0, W);
        d.pattern = "afflict heated by 7 electric heaters (" + Strings.autoFixed(DesignGen.heat(d, d.at(0, 0), new HashSet<>()), 1) + " heat of " + Strings.autoFixed(t.heatRequirement, 0) + ")";
        return p;
    }

    /**
     * A malign (144 heat): a bank of three phase heaters and a heat redirector against each of three sides (45 each),
     * two phase heaters straight on the fourth (15 each), 165 in all. Each bank's phase fabric comes in at a port of its
     * own and goes round its heaters by duct routers; the two straight on share a router between them.
     */
    static Post malign(){
        Turret t = (Turret)Blocks.malign;
        Post p = new Post("post-malign", t);
        DesignGen.Plan d = p.plan;
        d.put(t, 0, 0);
        float bank = 3 * phaseRate();
        phaseBank(p, -3, 1, E, bank);
        phaseBank(p, 1, 5, S, bank);
        phaseBank(p, 5, 1, W, bank);
        d.put(Blocks.phaseHeater, 0, -2, N);
        d.put(Blocks.phaseHeater, 3, -2, N);
        p.in(Items.phaseFabric, 2, -2, N, 2 * phaseRate());
        d.pattern = "malign heated by 3 banks of 3 phase heaters through heat redirectors and 2 phase heaters straight on (" + Strings.autoFixed(DesignGen.heat(d, d.at(0, 0), new HashSet<>()), 1) + " heat of " + Strings.autoFixed(t.heatRequirement, 0) + ")";
        return p;
    }

    /** Phase fabric a second one phase heater burns. */
    static float phaseRate(){
        HeatProducer h = (HeatProducer)Blocks.phaseHeater;
        return 60f / h.craftTime;
    }

    /**
     * DesignGen's phase bank (a heat redirector with phase heaters on its two sides and behind it, fed from one port by
     * duct routers), turned to face dir, its redirector's lower-left corner at (ax, ay). Drawn facing east it is:
     * redirector 0,0; heaters 0,3 (facing down), 0,-2 (up), -2,0 (east); port -1,4 facing down; routers -1,3 and -1,2
     * down, -1,-1 east; ducts -2,2 west, -3,2 -3,1 -3,0 down, -3,-1 -2,-1 east.
     */
    static void phaseBank(Post post, int ax, int ay, int dir, float rate){
        Object[][] parts = {
            {Blocks.heatRedirector, 0, 0, E},
            {Blocks.phaseHeater, 0, 3, S}, {Blocks.phaseHeater, 0, -2, N}, {Blocks.phaseHeater, -2, 0, E},
            {Blocks.ductRouter, -1, 3, S}, {Blocks.ductRouter, -1, 2, S}, {Blocks.ductRouter, -1, -1, E},
            {Blocks.duct, -2, 2, W}, {Blocks.duct, -3, 2, S}, {Blocks.duct, -3, 1, S}, {Blocks.duct, -3, 0, S}, {Blocks.duct, -3, -1, E}, {Blocks.duct, -2, -1, E},
        };
        for(Object[] o : parts){
            Block b = (Block)o[0];
            int[] at = turn(b.size, (Integer)o[1], (Integer)o[2], dir);
            post.plan.put(b, ax + at[0], ay + at[1], ((Integer)o[3] + dir) % 4);
        }
        int[] port = turn(1, -1, 4, dir);
        post.in(Items.phaseFabric, ax + port[0], ay + port[1], (S + dir) % 4, rate);
    }

    /** The lower-left corner of a block of this size drawn at (x, y) in a bank facing east, once the bank is turned to dir (about the redirector's middle, 1,1). */
    static int[] turn(int size, int x, int y, int dir){
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for(int dx = 0; dx < size; dx++) for(int dy = 0; dy < size; dy++){
            int cx = x + dx, cy = y + dy;
            for(int r = 0; r < dir; r++){
                int nx = 2 - cy, ny = cx;
                cx = nx;
                cy = ny;
            }
            minX = Math.min(minX, cx);
            minY = Math.min(minY, cy);
        }
        return new int[]{minX, minY};
    }

    static void checkHeat(DesignGen.Plan p, Turret t){
        if(t.heatRequirement <= 0f) return;
        float got = DesignGen.heat(p, p.at(0, 0), new HashSet<>());
        if(got < t.heatRequirement - 0.01f) throw new IllegalStateException(p.id + ": " + t.name + " gets " + got + " heat of " + t.heatRequirement);
    }
}
