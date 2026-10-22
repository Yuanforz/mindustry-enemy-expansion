package bp;

import bp.Board.*;
import mindustry.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.heat.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.storage.*;

import javax.imageio.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.util.List;
import java.util.*;

/**
 * The layout drawn as a picture: the ground, every machine (by kind, named), every line in the colour the game gives
 * what it carries (Item.color, Liquid.color) with arrows the way it goes, bridges as their ends and a dashed span, beam
 * nodes as dots, and a key.
 */
public final class Plot{
    static final int T = 18;

    static Color of(arc.graphics.Color c){
        return new Color(c.r, c.g, c.b);
    }

    static Color kind(Block b){
        if(b instanceof CoreBlock) return new Color(0x8a8f99);
        if(b instanceof ItemSource || b instanceof LiquidSource || b instanceof PowerSource || b instanceof LiquidVoid) return new Color(0x4caf50);
        if(b instanceof Turret) return new Color(0x9c4dcc);
        if(b instanceof HeatProducer || b instanceof HeatConductor) return new Color(0xd9534f);
        if(b instanceof PowerGenerator) return new Color(0xf0ad4e);
        return new Color(0x3f7fbf);
    }

    /** Short names, one for each block, made longer where two would be the same. */
    static final Map<String, String> names = new HashMap<>();

    static String abbrev(String name){
        String got = names.get(name);
        if(got != null) return got;
        String[] w = name.split("-");
        for(int len = 1; len <= name.length(); len++){
            StringBuilder s = new StringBuilder();
            for(int i = 0; i < w.length; i++) if(!w[i].isEmpty()) s.append(i == 0 ? w[i].substring(0, Math.min(len, w[i].length())) : w[i].substring(0, 1));
            String a = s.substring(0, 1).toUpperCase() + s.substring(1);
            if(a.length() > 4) a = a.substring(0, 4);
            if(!names.containsValue(a)){
                names.put(name, a);
                return a;
            }
        }
        names.put(name, name);
        return name;
    }

    public static void draw(Board board, List<Body> bodies, List<Router.Net> nets, List<Piece> power, String title, File out) throws IOException{
        int w = board.w, h = board.h, keyW = 300;
        names.clear();
        BufferedImage img = new BufferedImage(w * T + keyW, Math.max(h * T, 400) + 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1e1e24));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.white);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString(title, 8, 20);
        g.translate(0, 30);
        //the ground
        for(int y = 0; y < h; y++) for(int x = 0; x < w; x++){
            int i = board.idx(x, y);
            var f = board.floorAt[i];
            Color c = board.solidGround[i] ? new Color(0x4a3f3a) : f.isLiquid && f.liquidDrop != null ? of(f.liquidDrop.color).darker() : new Color(0x2c2c34);
            g.setColor(c);
            g.fillRect(x * T, (h - 1 - y) * T, T, T);
            g.setColor(new Color(0x34343d));
            g.drawRect(x * T, (h - 1 - y) * T, T, T);
        }
        //machines
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        Map<String, Color> key = new LinkedHashMap<>();
        Set<String> kinds = new LinkedHashSet<>();
        for(Body body : bodies) for(Body.Part p : body.parts){
            int px = p.x0 * T, py = (h - 1 - p.y1()) * T, s = p.size * T;
            g.setColor(kind(p.block));
            g.fillRect(px + 1, py + 1, s - 2, s - 2);
            g.setColor(Color.black);
            g.drawRect(px + 1, py + 1, s - 2, s - 2);
            g.setColor(Color.white);
            String a = abbrev(p.block.name);
            if(p.size >= 2 || a.length() == 1) g.drawString(a, px + 3, py + 12);
            if(p.config != null){
                var it = Vars.content.item(p.config);
                var lq = Vars.content.liquid(p.config);
                Color c = it != null ? of(it.color) : lq != null ? of(lq.color) : null;
                if(c != null){
                    g.setColor(c);
                    g.fillOval(px + 4, py + 4, s - 8, s - 8);
                }
            }
            //(which way a turned block faces: its heaters toward what they heat)
            if(p.block.rotate){
                g.setColor(new Color(255, 255, 255, 160));
                arrow(g, px + s / 2f, py + s / 2f, p.rot, s * 0.3f);
            }
            kinds.add(abbrev(p.block.name) + "  " + p.block.name);
        }
        //lines
        for(Router.Net n : nets){
            var it = Vars.content.item(n.res);
            var lq = Vars.content.liquid(n.res);
            Color c = it != null ? of(it.color) : lq != null ? of(lq.color) : Color.white;
            key.put(n.res + (lq != null ? " (liquid)" : ""), c);
            for(int i = 0; i < n.pieces.size(); i++){
                Piece q = n.pieces.get(i);
                int px = q.x * T, py = (h - 1 - q.y) * T;
                if(q.kind == Kind.leak){
                    g.setColor(c);
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawOval(px + 3, py + 3, T - 6, T - 6);
                    continue;
                }
                boolean liquid = q.liquidCarrier();
                g.setColor(liquid ? c.darker() : c);
                switch(q.kind){
                    case bridgeIn, bridgeOut, lbridgeIn, lbridgeOut -> {
                        g.fillRect(px + 3, py + 3, T - 6, T - 6);
                        g.setColor(Color.black);
                        g.drawRect(px + 3, py + 3, T - 6, T - 6);
                        if((q.kind == Kind.bridgeIn || q.kind == Kind.lbridgeIn) && i + 1 < n.pieces.size()){
                            Piece e = n.pieces.get(i + 1);
                            g.setColor(c);
                            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 3f}, 0f));
                            g.draw(new Line2D.Float(px + T / 2f, py + T / 2f, e.x * T + T / 2f, (h - 1 - e.y) * T + T / 2f));
                            g.setStroke(new BasicStroke(1f));
                        }
                        if(q.kind == Kind.bridgeOut || q.kind == Kind.lbridgeOut){
                            g.setColor(Color.black);
                            arrow(g, px + T / 2f, py + T / 2f, q.rot, T * 0.3f);
                        }
                    }
                    case router, lrouter -> {
                        g.fillOval(px + 2, py + 2, T - 4, T - 4);
                        g.setColor(Color.black);
                        g.drawString("+", px + 6, py + 13);
                    }
                    default -> {
                        int inset = liquid ? 5 : 4;
                        g.fillRect(px + inset, py + inset, T - 2 * inset, T - 2 * inset);
                        g.setColor(q.kind == Kind.surge ? Color.white : Color.black);
                        arrow(g, px + T / 2f, py + T / 2f, q.rot, T * 0.32f);
                        if(q.kind == Kind.armored){
                            g.setColor(Color.white);
                            g.drawRect(px + inset, py + inset, T - 2 * inset, T - 2 * inset);
                        }
                    }
                }
            }
        }
        //power
        for(Piece q : power){
            int px = q.x * T, py = (h - 1 - q.y) * T;
            g.setColor(new Color(0xffe066));
            if(q.kind == Kind.power) g.fillRect(px + 3, py + 3, T - 6, T - 6);
            else g.fillOval(px + 5, py + 5, T - 10, T - 10);
            g.setColor(Color.black);
            g.drawOval(px + 5, py + 5, T - 10, T - 10);
        }
        //the key
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        int kx = w * T + 10, ky = 12;
        g.setColor(Color.white);
        g.drawString("lines (colour of what they carry):", kx, ky);
        ky += 16;
        for(var e : key.entrySet()){
            g.setColor(e.getValue());
            g.fillRect(kx, ky - 9, 12, 10);
            g.setColor(Color.white);
            g.drawString(e.getKey(), kx + 18, ky);
            ky += 14;
        }
        ky += 8;
        g.drawString("machines:", kx, ky);
        ky += 14;
        for(String[] k : new String[][]{{"crafter", "3f7fbf"}, {"heater / redirector", "d9534f"}, {"generator", "f0ad4e"}, {"turret", "9c4dcc"}, {"source / core", "4caf50"}}){
            g.setColor(new Color(Integer.parseInt(k[1], 16)));
            g.fillRect(kx, ky - 9, 12, 10);
            g.setColor(Color.white);
            g.drawString(k[0], kx + 18, ky);
            ky += 14;
        }
        ky += 6;
        for(String k : kinds){
            g.drawString(k, kx, ky);
            ky += 13;
        }
        ky += 6;
        g.drawString("square = bridge end, dashed = its span", kx, ky);
        ky += 13;
        g.drawString("ring = leak tile;  yellow dot = beam node", kx, ky);
        ky += 13;
        g.drawString("white edge = armoured duct; + = router", kx, ky);
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    static void arrow(Graphics2D g, float cx, float cy, int rot, float r){
        double a = rot * Math.PI / 2;
        double dx = Math.cos(a), dy = -Math.sin(a);
        Path2D p = new Path2D.Float();
        p.moveTo(cx + dx * r, cy + dy * r);
        p.lineTo(cx - dx * r * 0.6 - dy * r * 0.6, cy - dy * r * 0.6 + dx * r * 0.6);
        p.lineTo(cx - dx * r * 0.6 + dy * r * 0.6, cy - dy * r * 0.6 - dx * r * 0.6);
        p.closePath();
        g.fill(p);
    }
}
