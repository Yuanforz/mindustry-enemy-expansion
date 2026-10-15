package xtest;

import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.type.*;

import java.lang.reflect.*;

/** What the campaign on Erekir is made of: how hard each setting is, what each sector asks for, and in what order things unlock. Usage: CampaignInfo data blueprints map-dir */
public class CampaignInfo{
    static String fields(Object object){
        StringBuilder out = new StringBuilder();
        for(Field field : object.getClass().getFields()){
            if(Modifier.isStatic(field.getModifiers())) continue;
            try{
                Object value = field.get(object);
                if(value == null || value instanceof arc.func.Cons || value instanceof arc.func.Prov) continue;
                String text = value instanceof Object[] array ? java.util.Arrays.toString(array) : value instanceof int[] ints ? java.util.Arrays.toString(ints) : String.valueOf(value);
                if(text.length() > 70) text = text.substring(0, 70) + "…";
                if(text.equals("false") || text.equals("0") || text.equals("0.0") || text.equals("[]") || text.equals("")) continue;
                out.append(field.getName()).append('=').append(text).append(' ');
            }catch(Exception ignored){}
        }
        return out.toString().trim();
    }

    public static void main(String[] args) throws Exception{
        Sim.boot(args[0], args[1]);
        System.out.println("== difficulty");
        for(Difficulty difficulty : Difficulty.values()) System.out.println("  " + difficulty + ": " + fields(difficulty));
        System.out.println("== campaign rules of the planet: " + fields(Planets.erekir.campaignRules));
        for(Method method : CampaignRules.class.getDeclaredMethods()) System.out.println("  method " + method.getName() + java.util.Arrays.toString(method.getParameterTypes()));

        System.out.println("== sectors and what unlocks them");
        for(SectorPreset preset : Vars.content.sectors()){
            if(preset.planet != Planets.erekir) continue;
            StringBuilder needs = new StringBuilder();
            if(preset.techNode != null){
                for(var objective : preset.techNode.objectives) needs.append(objective.getClass().getSimpleName()).append('(').append(fields(objective)).append(") ");
            }
            System.out.println("  " + preset.name + " sector " + preset.sector.id + ": " + fields(preset).replaceAll("(description|details|localizedName|fullIcon|uiIcon|iconId|id|name|minfo|planet|sector|generator|techNode|techNodes|databaseTag|databaseCategory)=[^ ]* ?", "") + " | needs " + needs);
        }

        System.out.println("== maps");
        for(String name : new String[]{"onset", "aegis", "lake", "intersect", "atlas", "split", "basin", "marsh", "peaks", "ravine", "caldera-erekir", "stronghold", "crevice", "siege", "crossroads", "karst", "origin"}){
            Fi file = new Fi(args[2]).child(name + ".msav");
            if(!file.exists()){ System.out.println("  " + name + ": no map file"); continue; }
            Vars.logic.reset();
            SaveIO.load(file);
            Vars.state.set(State.playing);
            Boot.frames(1);
            Rules r = Vars.state.rules;
            int processors = 0;
            for(var build : Vars.world.tiles) if(build.build != null && build.isCenter() && build.block() instanceof mindustry.world.blocks.logic.LogicBlock) processors++;
            System.out.println("  " + name + " " + Vars.world.width() + "x" + Vars.world.height() + ": attack=" + r.attackMode + " waves=" + r.waves + " winWave=" + r.winWave + " waveTimer=" + r.waveTimer
                + " limited=" + r.limitMapArea + " cores mine/theirs=" + r.defaultTeam.cores().size + "/" + r.waveTeam.cores().size + " processors=" + processors
                + " enemy rules: " + fields(r.teams.get(r.waveTeam)) + " | loadout " + r.loadout);
            for(var objective : r.objectives.all){
                StringBuilder parents = new StringBuilder();
                for(var parent : objective.parents) parents.append(r.objectives.all.indexOf(parent)).append(',');
                System.out.println("      " + r.objectives.all.indexOf(objective) + " " + objective.getClass().getSimpleName() + " after[" + parents + "] " + fields(objective).replaceAll("(editorX|editorY|markers|parents)=[^ ]* ?", ""));
            }
        }
        System.exit(0);
    }
}
