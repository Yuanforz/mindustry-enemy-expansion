package maptools;
import mindustry.*;
import mindustry.content.*;
import mindustry.world.*;
public class Ranges{
    public static void main(String[] a) throws Exception{
        xtest.Boot.boot(a[0]);
        for(String n : new String[]{"payload-mass-driver", "large-payload-mass-driver", "duct-bridge", "reinforced-bridge-conduit", "beam-node", "beam-tower", "breach", "diffuse", "sublimate", "titan", "disperse", "afflict", "lustre", "scathe", "smite", "malign", "radar", "regen-projector", "build-tower"}){
            Block b = Vars.content.block(n);
            StringBuilder s = new StringBuilder(n + ":");
            for(String f : new String[]{"range", "laserRange", "fogRadius", "reload"}){
                try{
                    java.lang.reflect.Field fl = null;
                    for(Class<?> c = b.getClass(); c != null && fl == null; c = c.getSuperclass()){ try{ fl = c.getDeclaredField(f); }catch(NoSuchFieldException e){} }
                    if(fl == null) continue;
                    fl.setAccessible(true);
                    Object v = fl.get(b);
                    if(f.equals("range") || f.equals("laserRange")) s.append(" ").append(f).append("=").append(v).append(v instanceof Float fv && f.equals("range") && fv > 30 ? " (" + fv / 8f + " tiles)" : "");
                    else s.append(" ").append(f).append("=").append(v);
                }catch(Exception e){}
            }
            System.out.println(s);
        }
        for(mindustry.type.UnitType u : Vars.content.units()){
            if(u.isHidden() || u.internal) continue;
            if(!u.shownPlanets.contains(mindustry.content.Planets.erekir)) continue;
            System.out.println("unit " + u.name + " hp=" + u.health + " armor=" + u.armor + " speed=" + u.speed + " flying=" + u.flying + " hover=" + u.hovering + " legs=" + u.allowLegStep + " range=" + (u.maxRange / 8f) + " hitSize=" + (u.hitSize / 8f) + " pathCost=" + (u.pathCost == null ? "-" : u.pathCost.getClass().getSimpleName()));
        }
        System.exit(0);
    }
}
