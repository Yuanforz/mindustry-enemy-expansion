package tools;

import arc.*;
import arc.files.*;
import arc.mock.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.net.Net;

/** Starts enough of the game, without a window, to load content, maps and schematics. */
public class Boot{
    public static class Gfx extends MockGraphics{
        public float dt = 1f / 60f; public long frame;
        @Override public float getDeltaTime(){ return dt; }
        @Override public long getFrameId(){ return frame; }
    }
    public static Gfx graphics = new Gfx();

    public static void boot(String dataDir){
        Core.app = new MockApplication();
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
        Vars.content.createBaseContent();
        Vars.logic = new Logic();
        Vars.netServer = new NetServer();
        Vars.content.init();
    }

    public static void frames(int count){
        for(int i = 0; i < count; i++){
            graphics.frame++;
            Time.updateGlobal();
            Vars.asyncCore.begin();
            Vars.logic.update();
            Vars.asyncCore.end();
        }
    }
}
