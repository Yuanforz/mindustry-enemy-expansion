package bp;

import arc.*;
import arc.files.*;
import arc.mock.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.net.Net;

/** The game's content loaded without a window (blocks, items, units with all their numbers), for reading it off. */
public final class Game{
    private Game(){
    }

    static boolean up;

    public static void boot(String dataDir){
        if(up) return;
        up = true;
        Core.app = new MockApplication();
        Core.files = new MockFiles();
        Core.audio = new MockAudio();
        Core.graphics = new MockGraphics();
        Core.input = new MockInput();
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(dataDir));
        Core.bundle = I18NBundle.createEmptyBundle();
        Vars.headless = true;
        Vars.loadLocales = false;
        Vars.net = new Net(null);
        Vars.init();
        Vars.content.createBaseContent();
        Vars.logic = new Logic();
        Vars.content.init();
    }
}
