package xtest;

import mindustry.content.*;
import mindustry.type.*;

/** Which numbered sector each of the planet's maps is. */
public class Sectors{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(Sector sector : Planets.erekir.sectors){
            if(sector.preset != null) System.out.println(sector.id + " " + sector.preset.name);
        }
        System.exit(0);
    }
}
