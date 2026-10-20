package xtest;

import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;

/**
 * The player's side of a generated map played as a raiding person plays it, for judging the enemy against one: from a
 * given minute a stell and an elude are turned out at the player's core every forty and forty-five seconds (what a tank
 * yard and a ship yard each make), up to fifteen of each standing, and the game's own squad AI sends them out. The
 * player's core is no longer kept standing.
 */
public class Raid implements Runnable{
    final Team enemy, player;
    final float from;
    float seconds;
    int stells, eludes;
    /** The minute the enemy's last core fell, or the player's did; -1 while both stand. */
    int enemyFell = -1, playerFell = -1;
    final ObjectIntMap<String> peak = new ObjectIntMap<>();
    int enemyBuildingsPeak, killedOfPlayer;
    /** Minute by minute: the enemy's fighting units / the player's, the enemy core's health where hurt, its turrets. */
    final StringBuilder timeline = new StringBuilder();

    Raid(Team enemy, float from){
        this.enemy = enemy;
        this.from = from;
        player = Vars.state.rules.defaultTeam;
        player.rules().blockHealthMultiplier = 1f;
        player.rules().rtsAi = true;
        player.rules().rtsMaxSquad = 15;
        player.rules().rtsMinSquad = 4;
        player.rules().rtsMinWeight = 1.2f;
    }

    @Override
    public void run(){
        seconds++;
        float minute = seconds / 60f;
        if(enemyFell < 0 && enemy.cores().isEmpty()) enemyFell = (int)minute;
        if(playerFell < 0 && player.cores().isEmpty()) playerFell = (int)minute;
        enemyBuildingsPeak = Math.max(enemyBuildingsPeak, enemy.data().buildings.size);
        if(seconds % 60 == 0 && enemyFell < 0 && playerFell < 0){
            var core = enemy.core();
            int turrets = enemy.data().buildings.count(b -> b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild);
            timeline.append(String.format(" %d:%d/%d%s%s", (int)minute, enemy.data().units.count(u -> !u.spawnedByCore && !(u.controller() instanceof expansion.ExpansionAI)), player.data().units.count(u -> !u.spawnedByCore),
                core != null && core.health < core.maxHealth ? "c" + (int)(100 * core.health / core.maxHealth) : "", turrets > 0 ? "t" + turrets : ""));
        }
        if(minute < from || player.cores().isEmpty() || enemyFell >= 0) return;
        var core = player.core();
        int at = (int)(seconds - from * 60f);
        if(at % 40 == 0 && player.data().countType(UnitTypes.stell) < 15){
            UnitTypes.stell.spawn(player, core.x + 30f, core.y);
            stells++;
        }
        if(at % 45 == 0 && player.data().countType(UnitTypes.elude) < 15){
            UnitTypes.elude.spawn(player, core.x - 30f, core.y);
            eludes++;
        }
    }

    String report(){
        int standing = player.data().units.count(unit -> !unit.spawnedByCore);
        return (enemyFell >= 0 ? "ENEMY FELL at minute " + enemyFell : playerFell >= 0 ? "PLAYER FELL at minute " + playerFell : "both stand")
            + " | player's units sent " + (stells + eludes) + ", standing " + standing + ", lost " + (stells + eludes - standing)
            + " | enemy buildings now " + enemy.data().buildings.size + " (most " + enemyBuildingsPeak + "), enemy units " + enemy.data().units.count(unit -> !unit.spawnedByCore)
            + "\n  RAIDLINE" + timeline;
    }
}
