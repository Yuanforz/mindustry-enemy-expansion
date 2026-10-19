package expansion.army;

import arc.math.*;
import arc.struct.*;
import mindustry.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;

/**
 * A1, allotment: when its units go out, and when they wait. It does not lead them: it sets the game's squad AI's own
 * knobs (how many make a squad that may attack, how good the odds must be), and the squad AI does the rest.
 *
 * The estimate is Lanchester's square law: two forces that all fire at once wear each other down so that what decides
 * it is damage a second times health, of each side all together. Its own units against the other side's units and the
 * turrets that guard what they would go for. A side that attacks into the other's turrets loses to the same number
 * waiting at home (seen in the first games between equals: the squads that went out first were lost to the last unit), so it waits until its
 * strength is clearly the greater, or until it cannot grow any more (at the unit cap, waiting only wastes what comes
 * out), and goes at once when the other side's army has just been beaten (the other side's units are few and its own
 * many: the time to go is before the other side has made more).
 */
public class Allot{
    final Army army;
    /** How much stronger than the other side it must be to go, in Lanchester strength (damage a second times health; twice is about half as many again). */
    public static float edge = Float.parseFloat(System.getProperty("army.edge", "2"));
    /** The last decision, for the report: whether it let its units go, and how often it changed its mind. */
    public boolean go = true;
    public int changes;
    public float lastRatio;

    public Allot(Army army){
        this.army = army;
    }

    public void update(){
        Team team = army.team;
        var rules = team.rules();
        float dps = 0f, health = 0f;
        int count = 0;
        for(int i = 0; i < team.data().units.size; i++){
            Unit unit = team.data().units.get(i);
            if(unit.spawnedByCore || !unit.type.canAttack) continue;
            dps += unit.type.dpsEstimate;
            health += unit.health;
            count++;
        }
        Threat threat = army.threat;
        //the other side's units, and its turrets standing guard (all of them: its units come home to fight under them)
        float theirDps = threat.enemyDps, theirHealth = threat.enemyHealth;
        float turretDps = 0f, turretHealth = 0f;
        for(Threat.Gun gun : threat.guns){
            if(!gun.loaded) continue;
            turretDps += gun.dps;
            turretHealth += gun.health;
        }
        //the turrets are spread over the map: about a third of them stand where any one fight is
        float ours = dps * health, theirs = (theirDps + turretDps / 3f) * (theirHealth + turretHealth / 3f);
        float ratio = theirs <= 0f ? 100f : ours / theirs;
        lastRatio = ratio;
        int cap = Units.getCap(team);
        boolean full = count >= cap - 2;
        //the other side's army is small next to its own: beaten, or never made
        boolean window = threat.enemyUnits * 3 <= count && count >= 6;
        boolean now = full || window || ratio >= edge;
        if(now != go){
            go = now;
            changes++;
        }
        if(go){
            rules.rtsMinSquad = army.baseMinSquad;
            rules.rtsMinWeight = army.baseMinWeight;
        }else{
            //no squad is big enough to attack; squads still come home to defend (the squad AI does that whatever this is)
            rules.rtsMinSquad = 1000;
        }
    }
}
