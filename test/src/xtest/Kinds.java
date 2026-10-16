package xtest;

import mindustry.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/** The numbers of this planet's units and turrets that a fight turns on. Usage: Kinds data */
public class Kinds{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(UnitType type : Vars.content.units()){
            if(!type.shownPlanets.contains(mindustry.content.Planets.erekir) && !type.name.matches("stell|locus|precept|vanquish|conquer|merui|cleroi|anthicus|tecta|collaris|elude|avert|obviate|quell|disrupt")) continue;
            float range = type.range, dps = type.estimateDps();
            StringBuilder w = new StringBuilder();
            for(Weapon weapon : type.weapons) w.append(weapon.name).append(weapon.bullet.collidesAir ? "+air" : "").append(weapon.bullet.collidesGround ? "+gnd" : "").append(" dmg").append((int)weapon.bullet.damage).append("/").append((int)weapon.bullet.splashDamage).append(" ");
            System.out.printf("%-10s hp%5.0f armor%4.1f speed%5.2f range%4.0f(%4.1f t) dps%5.0f fly %s hover %s legs %s | %s%n", type.name, type.health, type.armor, type.speed, range, range / 8f, dps, type.flying, type.hovering, type.allowLegStep, w);
        }
        for(Block block : Vars.content.blocks()){
            if(block instanceof mindustry.world.blocks.units.UnitFactory f && block.isOnPlanet(mindustry.content.Planets.erekir)){
                for(var plan : f.plans) System.out.println(block.name + " makes " + plan.unit.name + " in " + (int)(plan.time / 60) + " s from " + java.util.Arrays.toString(plan.requirements) + "; block costs " + java.util.Arrays.toString(block.requirements) + " power " + (block.consPower == null ? 0 : block.consPower.usage * 60));
            }
            if(block instanceof mindustry.world.blocks.units.Reconstructor r && block.isOnPlanet(mindustry.content.Planets.erekir)){
                StringBuilder up = new StringBuilder();
                for(var u : r.upgrades) up.append(u[0].name).append("->").append(u[1].name).append(' ');
                System.out.println(block.name + " " + up + "in " + (int)(r.constructTime / 60) + " s; block costs " + java.util.Arrays.toString(block.requirements) + " power " + (block.consPower == null ? 0 : block.consPower.usage * 60));
            }
            if(!(block instanceof Turret turret) || !block.isOnPlanet(mindustry.content.Planets.erekir)) continue;
            System.out.printf("%-12s hp%6.0f armor%4.1f range%5.0f(%4.1f t) reload%5.1f air %s gnd %s%n", block.name, block.health * 1f, block.armor, turret.range, turret.range / 8f, turret.reload, turret.targetAir, turret.targetGround);
        }
    }
}
