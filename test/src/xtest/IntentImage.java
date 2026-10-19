package xtest;

import expansion.econ.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.world.*;

import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.util.List;

/**
 * Writes an {@link Intent} out as a picture, for a run without a window: the ground (rock, floor, vents, what stands)
 * dimmed, and over it every mark of the layers asked for, with their words, and a legend of the layers.
 */
public final class IntentImage{
    private IntentImage(){}

    public static void write(Intent in, Team team, int cx, int cy, File file){
        List<Intent.Mark> marks = in.marks();
        int x0 = cx - 30, y0 = cy - 30, x1 = cx + 30, y1 = cy + 30;
        for(Intent.Mark m : marks){
            if(!in.shown.contains(m.layer)) continue;
            //far marks count, up to a point: the picture stays readable
            if(Math.abs(m.x0 - cx) > 110 || Math.abs(m.y0 - cy) > 110) continue;
            x0 = Math.min(x0, Math.min(m.x0, m.x1) - 3);
            y0 = Math.min(y0, Math.min(m.y0, m.y1) - 3);
            x1 = Math.max(x1, Math.max(m.x0, m.x1) + 3);
            y1 = Math.max(y1, Math.max(m.y0, m.y1) + 3);
        }
        write(in, team, x0, y0, x1, y1, file);
    }

    /** Only the tiles from (x0, y0) to (x1, y1): a close-up. */
    public static void write(Intent in, Team team, int x0, int y0, int x1, int y1, File file){
        List<Intent.Mark> marks = in.marks();
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(Vars.world.width() - 1, x1);
        y1 = Math.min(Vars.world.height() - 1, y1);
        int span = Math.max(x1 - x0 + 1, y1 - y0 + 1);
        int s = Math.max(4, Math.min(24, 2000 / span));
        int w = (x1 - x0 + 1) * s, h = (y1 - y0 + 1) * s, legend = 18 * (Intent.Layer.values().length + 1);
        BufferedImage img = new BufferedImage(w, h + legend, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        //the ground, dimmed
        for(int y = y0; y <= y1; y++){
            for(int x = x0; x <= x1; x++){
                Tile t = Vars.world.tile(x, y);
                Color c;
                //(a run without a window has no map colours: the ground is coloured by kind)
                if(t.build != null) c = t.team() == team ? new Color(70, 80, 105) : new Color(110, 40, 40);
                else if(t.wallDrop() != null) c = dim(t.wallDrop().color, 0.5f);
                else if(t.block().isStatic() || t.solid()) c = t.block().attributes.get(mindustry.world.meta.Attribute.sand) > 0 ? new Color(92, 80, 58) : new Color(58, 58, 62);
                else if(t.floor().attributes.get(mindustry.world.meta.Attribute.steam) > 0) c = new Color(30, 70, 70);
                else if(t.floor().isDeep() || t.floor().isLiquid) c = new Color(22, 30, 52);
                else if(t.drop() != null) c = dim(t.drop().color, 0.32f);
                else c = new Color(26, 26, 29);
                g.setColor(c);
                g.fillRect(px(x, x0, s), py(y, y1, s), s, s);
            }
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        placed.clear();
        for(Intent.Mark m : marks){
            if(!in.shown.contains(m.layer)) continue;
            g.setColor(color(m.color));
            int ax = px(m.x0, x0, s) + s / 2, ay = py(m.y0, y1, s) + s / 2, bx = px(m.x1, x0, s) + s / 2, by = py(m.y1, y1, s) + s / 2;
            switch(m.shape){
                case rect -> {
                    int rx = px(Math.min(m.x0, m.x1), x0, s), ry = py(Math.max(m.y0, m.y1), y1, s);
                    int rw = (Math.abs(m.x1 - m.x0) + 1) * s, rh = (Math.abs(m.y1 - m.y0) + 1) * s;
                    if(m.label == null && m.x0 == m.x1 && m.y0 == m.y1) g.fillRect(rx, ry, rw, rh);
                    else{
                        g.setStroke(new BasicStroke(Math.max(1.5f, s / 4f)));
                        g.drawRect(rx, ry, rw - 1, rh - 1);
                        if(m.label != null) text(g, m.label, rx + 2, ry - 2, color(m.color));
                    }
                }
                case cross -> {
                    g.setStroke(new BasicStroke(1.2f));
                    g.drawLine(ax - s / 3, ay - s / 3, ax + s / 3, ay + s / 3);
                    g.drawLine(ax - s / 3, ay + s / 3, ax + s / 3, ay - s / 3);
                }
                case line -> {
                    g.setStroke(new BasicStroke(Math.max(1f, s / 6f)));
                    g.drawLine(ax, ay, bx, by);
                }
                case arrow -> {
                    g.setStroke(new BasicStroke(Math.max(1.5f, s / 4f)));
                    double a = Math.atan2(by - ay, bx - ax), len = Math.hypot(bx - ax, by - ay) * 0.75;
                    int ex = (int)(ax + Math.cos(a) * len), ey = (int)(ay + Math.sin(a) * len);
                    g.drawLine(ax, ay, ex, ey);
                    int hx = (int)(Math.cos(a + 2.5) * s / 3), hy = (int)(Math.sin(a + 2.5) * s / 3);
                    int kx = (int)(Math.cos(a - 2.5) * s / 3), ky = (int)(Math.sin(a - 2.5) * s / 3);
                    g.fillPolygon(new int[]{ex, ex + hx, ex + kx}, new int[]{ey, ey + hy, ey + ky}, 3);
                    if(m.label != null) text(g, m.label, ax + s, ay - s / 2, color(m.color));
                }
                case dot -> {
                    int r = Math.max(4, s / 2);
                    g.fillOval(ax - r / 2, ay - r / 2, r, r);
                }
                case text -> {
                    if(m.label != null) text(g, m.label, ax - s, ay, color(m.color));
                }
            }
        }
        //the legend
        g.setColor(new Color(20, 20, 24));
        g.fillRect(0, h, w, legend);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int row = 0;
        for(Intent.Layer l : Intent.Layer.values()){
            g.setColor(in.shown.contains(l) ? Color.white : Color.gray);
            g.drawString((in.shown.contains(l) ? "[x] " : "[ ] ") + l.title, 8, h + 16 + 18 * row++);
        }
        g.setColor(Color.lightGray);
        g.drawString(String.format("tiles %d,%d to %d,%d; %d px a tile", x0, y0, x1, y1, s), 8, h + 16 + 18 * row);
        g.dispose();
        try{
            file.getParentFile().mkdirs();
            ImageIO.write(img, "png", file);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /** Where words went already: words that would land on others move down a line, or are left out. */
    static final java.util.List<Rectangle> placed = new java.util.ArrayList<>();

    static void text(Graphics2D g, String s, int x, int y, Color c){
        if(s.length() > 46) s = s.substring(0, 44) + "…";
        FontMetrics fm = g.getFontMetrics();
        Rectangle r = new Rectangle(x - 1, y - fm.getAscent(), fm.stringWidth(s) + 2, fm.getHeight());
        boolean free = false;
        for(int k = 0; k < 4 && !free; k++){
            free = true;
            for(Rectangle o : placed) if(o.intersects(r)){ free = false; break; }
            if(!free){
                r.y += fm.getHeight();
                y += fm.getHeight();
            }
        }
        if(!free) return;
        placed.add(r);
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(x - 1, y - fm.getAscent(), fm.stringWidth(s) + 2, fm.getHeight());
        g.setColor(c);
        g.drawString(s, x, y);
    }

    static int px(int x, int x0, int s){
        return (x - x0) * s;
    }

    static int py(int y, int y1, int s){
        return (y1 - y) * s;
    }

    static Color dim(arc.graphics.Color c, float f){
        return new Color(Math.min(255, (int)(c.r * 255 * f)), Math.min(255, (int)(c.g * 255 * f)), Math.min(255, (int)(c.b * 255 * f)));
    }

    static Color color(int rgba){
        return new Color((rgba >>> 24) & 0xff, (rgba >>> 16) & 0xff, (rgba >>> 8) & 0xff, rgba & 0xff);
    }
}
