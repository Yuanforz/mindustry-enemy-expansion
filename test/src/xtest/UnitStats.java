package xtest;

import mindustry.*;
import mindustry.content.*;
import mindustry.type.*;

/** For choosing what to make: each Erekir unit's damage a second, health, armour, range, speed, whether it hits air and ground, and whether it flies or drowns. */
public class UnitStats{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(UnitType u : Vars.content.units()){
            if(!u.isOnPlanet(Planets.erekir) || u.internal) continue;
            System.out.println(String.format("%-10s dps %6.1f health %6.0f armor %4.1f range %5.1f tiles speed %4.2f air %b ground %b flying %b hover %b drowns %b",
                u.name, u.dpsEstimate, u.health, u.armor, u.range / 8f, u.speed, u.targetAir, u.targetGround, u.flying, u.hovering, expansion.army.Reach.drowns(u)));
        }
        System.exit(0);
    }
}
