package expansion;

import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

import static mindustry.Vars.*;

/** Reports every item that physically goes into or out of a core, whatever brought it there. */
public class Intake{

    public interface Listener{
        /** @param byLine whether a block handed it in, and not a unit */
        void arrived(Team team, Item item, int amount, boolean byLine);
        void taken(Team team, Item item, int amount);
    }

    /** Has to run before any world is loaded: cores that already exist keep the building they were made with. */
    public static void install(Listener listener){
        for(Block block : content.blocks()){
            //a core that brings its own building class keeps it
            if(block instanceof CoreBlock core && core.buildType != null && core.buildType.get().getClass() == CoreBlock.CoreBuild.class){
                core.buildType = () -> new CountingCore(core, listener);
            }
        }
    }

    static class CountingCore extends CoreBlock.CoreBuild{
        private final CoreBlock core;
        private final Listener listener;

        CountingCore(CoreBlock core, Listener listener){
            core.super();
            this.core = core;
            this.listener = listener;
        }

        /** Some cores burn items that nothing can be built from; those never arrive. */
        private boolean kept(Item item){
            return !(core.incinerateNonBuildable && !item.buildable);
        }

        @Override
        public void handleItem(Building source, Item item){
            super.handleItem(source, item);
            if(kept(item)) listener.arrived(team, item, 1, true);
        }

        @Override
        public void handleStack(Item item, int amount, Teamc source){
            super.handleStack(item, amount, source);
            if(amount > 0 && kept(item)) listener.arrived(team, item, amount, false);
        }

        @Override
        public void itemTaken(Item item){
            super.itemTaken(item);
            listener.taken(team, item, 1);
        }
    }
}
