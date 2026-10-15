package xtest;

import arc.math.*;
import expansion.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.ItemTurret.*;

/**
 * How well quick units get through a turret's fire: a breach of the other side, kept loaded, and a few ships of the
 * mod's side sent against it, steered the way the mod steers them or the plain way (to the edge of their range, and
 * stand there firing). Says how much of their health they lost and how much of the turret's they took.
 * Usage: DodgeLab data-dir blueprint-dir mod|stand [units] [seconds] [unit]
 */
public class DodgeLab{
    /** The plain way: to the edge of its range, and there it stands and fires. */
    static class StandAI extends AIController{
        Teamc aim;

        @Override
        public void updateMovement(){
            if(aim != null) moveTo(aim, unit.range() * Float.parseFloat(System.getProperty("lab.stand", "0.8")));
        }

        @Override
        public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground){
            return aim;
        }

        @Override
        public Teamc findTarget(float x, float y, float range, boolean air, boolean ground){
            return aim;
        }
    }

    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        //the units are steered here and nowhere else: the mod does not lead them as an army
        arc.Core.settings.put("expansion-enabled", false);
        String mode = args[2];
        int count = args.length > 3 ? Integer.parseInt(args[3]) : 6, seconds = args.length > 4 ? Integer.parseInt(args[4]) : 40;
        mindustry.type.UnitType type = Vars.content.unit(args.length > 5 ? args[5] : "elude");
        Bench.world();
        //water round the turret, as on the sector the ships are for; the turret stands on a patch of ground
        if(System.getProperty("lab.land") == null) for(Tile each : Vars.world.tiles){
            if(Math.abs(each.x - 12) > 2 || Math.abs(each.y - 35) > 2) each.setFloor((mindustry.world.blocks.environment.Floor)Blocks.deepwater);
        }
        //the side that has the core is the mod's; the turret is the other side's, with a core of its own so that it stands
        Team own = Bench.team, enemy = own == Team.sharded ? Team.crux : Team.sharded;
        Vars.world.tile(4, 4).setBlock(Blocks.coreBastion, enemy, 0);
        Vars.state.rules.waveTeam = own == Team.sharded ? enemy : own;
        Vars.state.rules.defaultTeam = own == Team.sharded ? own : enemy;
        Brain.side = own;
        Tile tile = Vars.world.tile(12, 35);
        tile.setBlock(Blocks.breach, enemy, 0);
        ItemTurretBuild breach = (ItemTurretBuild)tile.build;
        float before = 0f;
        Unit[] units = new Unit[count];
        for(int i = 0; i < count; i++){
            Unit unit = type.create(own);
            unit.set(48 * 8f, (28 + i * 3) * 8f);
            unit.add();
            if(mode.equals("mod")){
                FighterAI ai = new FighterAI(ExpansionMod.brain);
                unit.controller(ai);
                ai.attack(breach);
            }else{
                StandAI ai = new StandAI();
                unit.controller(ai);
                ai.aim = breach;
            }
            units[i] = unit;
            before += unit.health;
        }
        for(int second = 0; second < seconds; second++){
            while(breach.isValid() && breach.acceptItem(null, Items.beryllium)) breach.handleItem(null, Items.beryllium);
            //-Dlab.cool: the turret has water, as the other side's turrets on the third sector have, and fires two and a half times as fast
            if(System.getProperty("lab.cool") != null && breach.isValid() && breach.liquids != null) breach.liquids.add(Liquids.water, Math.max(breach.block.liquidCapacity - breach.liquids.get(Liquids.water), 0f));
            Boot.frames(60);
            if(System.getProperty("lab.trace") != null && second % 3 == 0){
                StringBuilder out = new StringBuilder("  t=" + second);
                for(Unit unit : units) if(unit.isValid()) out.append(String.format(" | %d,%d hp%d%s%s", unit.tileX(), unit.tileY(), (int)unit.health, unit.isShooting ? " firing" : "", unit.mounts.length > 0 && unit.mounts[0].target != null ? " at" : ""));
                out.append(" || breach hp" + (int)breach.health + " ammo" + breach.totalAmmo + (breach.target != null ? " aiming" : ""));
                System.out.println(out);
            }
            if(!breach.isValid()) break;
        }
        int alive = 0;
        float left = 0f;
        for(Unit unit : units){
            if(unit.isValid() && !unit.dead){
                alive++;
                left += unit.health;
            }
        }
        System.out.println("range " + units[0].range() / 8f + " tiles, maxRange " + type.maxRange / 8f + ", weapon bullet range " + type.weapons.first().bullet.range / 8f);
        System.out.println(mode + ": " + count + " " + type.name + ", " + alive + " left with " + Mathf.round(left) + " of " + Mathf.round(before) + " health; the breach " + (breach.isValid() ? "stands at " + Mathf.round(breach.health) + " of " + Mathf.round(breach.maxHealth) : "is down"));
        System.exit(0);
    }
}
