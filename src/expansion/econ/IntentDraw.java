package expansion.econ;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.Lines;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import arc.scene.ui.layout.Scl;
import expansion.Options;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.ui.*;

/**
 * Draws the {@link Intent} being shown over the map while the mod's debug option is on: what each module means to do.
 * Shapes are always drawn; the words of a shape show when the pointer is over it (the words of text marks always),
 * and a panel lists the notes of the layers, so a glance shows the plan and a look at a thing says why it is there.
 */
public final class IntentDraw{
    private IntentDraw(){}

    static boolean installed;

    /** Hooks the drawing in; once, and only where there is a screen. */
    public static void install(){
        if(installed || Vars.headless) return;
        installed = true;
        Events.run(Trigger.draw, IntentDraw::draw);
    }

    static final Color tmp = new Color();

    static void draw(){
        Intent in = Intent.current;
        if(in == null || !Options.debug.on()) return;
        Draw.z(Layer.overlayUI);
        float t = Vars.tilesize;
        Vec2 mouse = Core.input.mouseWorld();
        int mx = Math.round(mouse.x / t), my = Math.round(mouse.y / t);
        String hovered = null;
        for(Intent.Mark m : in.marks()){
            if(!in.shown.contains(m.layer)) continue;
            Draw.color(tmp.set(m.color));
            Lines.stroke(m.layer == Intent.Layer.fields && m.shape == Intent.Shape.rect && m.label == null ? 0.5f : 1.2f);
            float x0 = m.x0 * t, y0 = m.y0 * t, x1 = m.x1 * t, y1 = m.y1 * t;
            switch(m.shape){
                case rect -> {
                    if(m.label == null && m.x0 == m.x1 && m.y0 == m.y1){
                        Fill.rect(x0, y0, t, t);
                    }else{
                        Lines.rect(x0 - t / 2f, y0 - t / 2f, x1 - x0 + t, y1 - y0 + t);
                    }
                    if(m.label != null && mx >= m.x0 && mx <= m.x1 && my >= m.y0 && my <= m.y1) hovered = m.label;
                }
                case cross -> {
                    Lines.line(x0 - 3, y0 - 3, x0 + 3, y0 + 3);
                    Lines.line(x0 - 3, y0 + 3, x0 + 3, y0 - 3);
                }
                case line -> Lines.line(x0, y0, x1, y1);
                case arrow -> {
                    float a = Angles.angle(x0, y0, x1, y1), len = Mathf.dst(x0, y0, x1, y1);
                    float ex = x0 + Angles.trnsx(a, len * 0.8f), ey = y0 + Angles.trnsy(a, len * 0.8f);
                    Lines.line(x0, y0, ex, ey);
                    Fill.poly(ex, ey, 3, 1.6f, a);
                    if(m.label != null && Math.abs(mx - m.x0) <= 0 && Math.abs(my - m.y0) <= 0) hovered = m.label;
                }
                case dot -> {
                    Fill.circle(x0, y0, 2.2f);
                    if(m.label != null && mx == m.x0 && my == m.y0) hovered = m.label;
                }
                case text -> {
                    if(m.label != null) label(m.label, x0, y0, tmp, 0.18f);
                }
            }
        }
        if(hovered != null) label(hovered, mouse.x, mouse.y + 6f, Color.white, 0.22f);
        Draw.reset();
    }

    /** The words for the panel: what the hand is doing, what the watch sees, and what the checker found. */
    public static String panel(){
        Intent in = Intent.current;
        if(in == null) return "";
        StringBuilder b = new StringBuilder("[accent]economy executor[]");
        for(Intent.Layer l : new Intent.Layer[]{Intent.Layer.hand, Intent.Layer.watch}){
            int k = 0;
            for(String s : in.notes(l)){
                if(k++ >= 8) break;
                b.append("\n[lightgray]").append(s.length() > 90 ? s.substring(0, 88) + "…" : s).append("[]");
            }
        }
        for(String s : in.notes(Intent.Layer.jobs)) if(s.startsWith("checker")) b.append("\n[scarlet]").append(s).append("[]");
        return b.toString();
    }

    static void label(String text, float x, float y, Color color, float scale){
        Font font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(scale / Scl.scl(1f));
        font.setColor(color);
        font.draw(text, x, y, 0, Align.center, false);
        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
    }
}
