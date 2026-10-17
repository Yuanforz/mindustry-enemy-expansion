package xtest;

import arc.*;
import arc.files.*;
import arc.mock.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.net.Net;

/** Starts enough of the game, without a window, to load content and run a world. */
public class Boot{
    public static class Gfx extends MockGraphics{
        public long frame;
        @Override public float getDeltaTime(){ return 1f / 60f; }
        @Override public long getFrameId(){ return frame; }
    }
    public static Gfx graphics = new Gfx();

    /**
     * An application that runs what is posted to it on the main thread, at the start of the next frame,
     * the way the real one does. The stand-in that comes with the engine runs it at once on whichever
     * thread posted it, and the game's pathfinder posts from a thread of its own: its lists then get
     * changed from two threads at a time, which now and then brought a run down.
     */
    public static class App extends MockApplication{
        private final arc.struct.Seq<Runnable> posted = new arc.struct.Seq<>();

        @Override
        public void post(Runnable runnable){
            synchronized(posted){
                posted.add(runnable);
            }
        }

        void runPosted(){
            Runnable[] now;
            synchronized(posted){
                now = posted.toArray(Runnable.class);
                posted.clear();
            }
            for(Runnable runnable : now) runnable.run();
        }
    }
    static App app = new App();
    public static long slowest;
    /** Time the checks of a test took inside the frame that is being run: not the game's and not the mod's, so it is left out. */
    public static long checking;
    /** The longest frame with the mod's own share taken off, and that share for the longest frame. */
    public static long slowestGame, modShare;
    /** Since the start: frames run, and the time they took in all and in the mod. For saying how fast a run goes. */
    public static long framesRun, nanosRun, nanosMod;
    public static arc.func.Floatp modTime = () -> 0f;

    public static void boot(String dataDir){
        boot(dataDir, null);
    }

    /** @param early run before the game makes its blocks and items, which is when the game loads mods */
    public static void boot(String dataDir, Runnable early){
        Core.app = app;
        Core.files = new MockFiles();
        Core.audio = new MockAudio();
        Core.graphics = graphics;
        Core.input = new MockInput();
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(dataDir));
        Core.bundle = I18NBundle.createEmptyBundle();
        Vars.headless = true;
        Vars.loadLocales = false;
        Vars.net = new Net(null);
        Vars.init();
        if(early != null) early.run();
        Vars.content.createBaseContent();
        Vars.logic = new Logic();
        Vars.netServer = new NetServer();
        Vars.content.init();
        //the game starts its path-finding thread on every map; where that is to be done a step a frame, it is stopped again
        arc.Events.on(mindustry.game.EventType.WorldLoadEvent.class, e -> {
            holdPathThread();
            holdFogThreads();
        });
        expansion.Clock.virtual = Boolean.getBoolean("xp.virtual");
    }

    /**
     * Whether the game's path-finding is done on the main thread, a step a frame, instead of on a thread of
     * its own. On its own thread how far it has got when a unit asks for a way depends on how busy the
     * machine is, and two runs from the same start part ways at the first fight. Done a step a frame,
     * and each step to the end of what it has to do, a run comes out the same every time.
     */
    public static boolean syncPath = Boolean.getBoolean("xp.syncpath");
    private static java.lang.reflect.Method stopPath, updateTargets, updateFrontier;
    private static java.lang.reflect.Field pathThread, pathQueue, pathList, fieldDirty, fieldFrontier;

    /** Stops the path-finding thread the game starts on every map, if path-finding is to be done a step a frame. */
    static void holdPathThread(){
        if(!syncPath) return;
        try{
            if(stopPath == null){
                Class<?> type = mindustry.ai.Pathfinder.class, field = Class.forName("mindustry.ai.Pathfinder$Flowfield");
                stopPath = type.getDeclaredMethod("stop");
                updateTargets = type.getDeclaredMethod("updateTargets", field);
                updateFrontier = type.getDeclaredMethod("updateFrontier", field, long.class);
                pathThread = type.getDeclaredField("thread");
                pathQueue = type.getDeclaredField("queue");
                pathList = type.getDeclaredField("threadList");
                fieldDirty = field.getDeclaredField("dirty");
                fieldFrontier = field.getDeclaredField("frontier");
                for(java.lang.reflect.AccessibleObject member : new java.lang.reflect.AccessibleObject[]{stopPath, updateTargets, updateFrontier, pathThread, pathQueue, pathList, fieldDirty, fieldFrontier}) member.setAccessible(true);
            }
            if(pathThread.get(Vars.pathfinder) != null) stopPath.invoke(Vars.pathfinder);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    /** What the path-finding thread does each time round, done here, each field to the end. */
    static void stepPath(){
        if(!syncPath || Vars.pathfinder == null || !Vars.state.isPlaying()) return;
        try{
            if(pathThread.get(Vars.pathfinder) != null) stopPath.invoke(Vars.pathfinder);
            ((arc.util.TaskQueue)pathQueue.get(Vars.pathfinder)).run();
            for(Object field : (arc.struct.Seq<?>)pathList.get(Vars.pathfinder)){
                if(fieldDirty.getBoolean(field) && ((arc.struct.IntQueue)fieldFrontier.get(field)).size == 0){
                    updateTargets.invoke(Vars.pathfinder, field);
                    fieldDirty.setBoolean(field, false);
                }
                updateFrontier.invoke(Vars.pathfinder, field, Long.MAX_VALUE);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Whether what each side has seen under the fog is worked out on the main thread, every frame, instead of on two threads
     * of the game's own. On those, how far it has got when the mod asks what it has seen depends on how busy the machine is,
     * as path-finding did: the same build lost five of sixteen openings of the third sector in a suite run alongside other
     * runs, and none of twenty-four by itself.
     */
    public static boolean syncFog = Boolean.getBoolean("xp.syncfog");
    private static java.lang.reflect.Method fogStatic, fogDynamic;
    private static java.lang.reflect.Field fogStaticThread, fogDynamicThread;
    private static final arc.struct.Bits fogCleared = new arc.struct.Bits(256);

    /** Puts idle threads where the game keeps its fog threads, so that it never starts its own. */
    static void holdFogThreads(){
        if(!syncFog) return;
        try{
            if(fogStatic == null){
                Class<?> type = mindustry.game.FogControl.class;
                fogStatic = type.getDeclaredMethod("updateStatic");
                fogDynamic = type.getDeclaredMethod("updateDynamic", arc.struct.Bits.class);
                fogStaticThread = type.getDeclaredField("staticFogThread");
                fogDynamicThread = type.getDeclaredField("dynamicFogThread");
                for(java.lang.reflect.AccessibleObject member : new java.lang.reflect.AccessibleObject[]{fogStatic, fogDynamic, fogStaticThread, fogDynamicThread}) member.setAccessible(true);
            }
            Object fog = Vars.fogControl;
            if(fog == null) return;
            Thread running = (Thread)fogStaticThread.get(fog);
            if(running != null && running.isAlive()) throw new IllegalStateException("the game's fog thread is running already");
            if(fogStaticThread.get(fog) == null) fogStaticThread.set(fog, new Thread("held static fog"));
            if(fogDynamicThread.get(fog) == null) fogDynamicThread.set(fog, new Thread("held dynamic fog"));
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    /** What the fog threads would have done, done here, after the frame. */
    static void stepFog(){
        if(!syncFog || Vars.fogControl == null || !Vars.state.isPlaying() || !Vars.state.rules.fog) return;
        try{
            if(Vars.state.rules.staticFog) fogStatic.invoke(Vars.fogControl);
            fogDynamic.invoke(Vars.fogControl, fogCleared);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    public static void frames(int count){
        for(int i = 0; i < count; i++){
            graphics.frame++;
            app.runPosted();
            stepPath();
            Time.updateGlobal();
            checking = 0;
            long start = System.nanoTime();
            if(syncPath){
                //what the game works out on other threads alongside the frame (units pushing one another) done first, by itself
                Vars.asyncCore.begin();
                Vars.asyncCore.end();
                Vars.logic.update();
            }else{
                Vars.asyncCore.begin();
                Vars.logic.update();
                Vars.asyncCore.end();
            }
            stepFog();
            long took = System.nanoTime() - start - checking, mod = (long)(modTime.get() * 1e6f);
            if(took > slowest){
                slowest = took;
                modShare = mod;
            }
            slowestGame = Math.max(slowestGame, took - mod);
            framesRun++;
            nanosRun += took;
            nanosMod += mod;
        }
    }
}
