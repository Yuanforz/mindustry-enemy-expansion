package bp;

import arc.files.*;
import bp.Board.*;
import mindustry.*;
import mindustry.world.*;

import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.util.List;
import java.util.*;

/**
 * What a grown base has committed, drawn on its map (Plot): every machine, every line with what it carries, every beam
 * node; the picture cut to the base and a margin about it.
 *
 * Usage: bp.Draw data-dir world.msav ledger out.png
 */
public final class Draw{
    public static void main(String[] args) throws IOException{
        Game.boot(args[0]);
        Problem prob = Problem.parse("world " + args[1] + "\n");
        Gen g = new Gen(prob, args[0]);
        Grow.Ledger ledger = Grow.Ledger.read(args[2]);
        List<Body> bodies = new ArrayList<>();
        List<Router.Net> nets = new ArrayList<>();
        List<Piece> power = new ArrayList<>();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for(Spec.Place p : prob.fixed){
            Block bl = Vars.content.block(p.block());
            Body b = new Body(-1, p.block(), true);
            b.add(bl, 0, 0, p.rotation(), null, 1f, p.config());
            b.put(Geo.lo(p.x(), bl.size), Geo.lo(p.y(), bl.size), 0);
            bodies.add(b);
        }
        int things = 0, blocks = 0, lines = 0, pieces = 0, nodes = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        for(Grow.Inst i : ledger.insts){
            if(i.removed) continue;
            if(i.kind.equals("line")){
                if(i.pieces.isEmpty()) continue;
                String res = (String)i.pieces.get(0)[8];
                Router.Net n = new Router.Net(i.id, res, 0, null);
                for(Object[] q : i.pieces){
                    Block bl = Vars.content.block((String)q[1]);
                    Piece pc = new Piece(Kind.valueOf((String)q[0]), bl, (Integer)q[2], (Integer)q[3], (Integer)q[4], i.id, res);
                    pc.from = (Integer)q[5];
                    pc.outs = (Integer)q[6];
                    pc.role = (Integer)q[7];
                    n.pieces.add(pc);
                    x0 = Math.min(x0, pc.x);
                    y0 = Math.min(y0, pc.y);
                    x1 = Math.max(x1, pc.x);
                    y1 = Math.max(y1, pc.y);
                }
                nets.add(n);
                lines++;
                pieces += i.pieces.size();
            }else if(i.kind.equals("nodes")){
                for(Object[] p : i.parts){
                    Block bl = Vars.content.block((String)p[0]);
                    power.add(new Piece(Kind.node, bl, Geo.at((Integer)p[1], bl.size), Geo.at((Integer)p[2], bl.size), 0, -1, "power"));
                    nodes++;
                }
            }else{
                Body b = new Body(i.id, i.unit == null ? i.kind : i.unit, true);
                for(Object[] p : i.parts){
                    Block bl = Vars.content.block((String)p[0]);
                    b.add(bl, (Integer)p[1], (Integer)p[2], (Integer)p[3], null, 1f, (String)p[7]);
                    kinds.merge(bl.name, 1, Integer::sum);
                }
                for(int[] c : i.clear) b.clearLocal.add(c.clone());
                b.put(0, 0, 0);
                for(Body.Part p : b.parts){
                    x0 = Math.min(x0, p.x0);
                    y0 = Math.min(y0, p.y0);
                    x1 = Math.max(x1, p.x1());
                    y1 = Math.max(y1, p.y1());
                }
                bodies.add(b);
                things++;
                blocks += b.parts.size();
            }
        }
        for(Body b : bodies) for(Body.Part p : b.parts){
            x0 = Math.min(x0, p.x0);
            y0 = Math.min(y0, p.y0);
            x1 = Math.max(x1, p.x1());
            y1 = Math.max(y1, p.y1());
        }
        String title = String.format(Locale.ROOT, "%s, step %d: %d things (%d blocks), %d lines (%d pieces), %d beam nodes", new Fi(args[2]).nameWithoutExtension(), ledger.step, things, blocks, lines, pieces, nodes);
        File full = new File(args[3] + ".full.png");
        Plot.draw(g.board, bodies, nets, power, title, full);
        //(cut to the base, the key kept)
        BufferedImage img = ImageIO.read(full);
        int T = Plot.T, w = g.board.w, h = g.board.h, m = 4;
        int cx0 = Math.max(0, x0 - m), cy0 = Math.max(0, y0 - m), cx1 = Math.min(w - 1, x1 + m), cy1 = Math.min(h - 1, y1 + m);
        int px = cx0 * T, py = 30 + (h - 1 - cy1) * T, pw = (cx1 - cx0 + 1) * T, ph = (cy1 - cy0 + 1) * T;
        int keyW = img.getWidth() - w * T;
        BufferedImage out = new BufferedImage(pw + keyW, Math.max(ph, 900) + 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D o = out.createGraphics();
        o.setColor(new Color(0x1e1e24));
        o.fillRect(0, 0, out.getWidth(), out.getHeight());
        o.drawImage(img.getSubimage(0, 0, Math.min(img.getWidth(), pw + keyW), 30), 0, 0, null);
        o.drawImage(img.getSubimage(px, py, pw, ph), 0, 30, null);
        o.drawImage(img.getSubimage(w * T, 30, keyW, Math.min(img.getHeight() - 30, out.getHeight() - 30)), pw, 30, null);
        o.dispose();
        ImageIO.write(out, "png", new File(args[3]));
        full.delete();
        System.out.println(title);
        System.out.println("  blocks by kind: " + kinds);
        System.out.println("  cut to " + cx0 + "," + cy0 + " .. " + cx1 + "," + cy1 + " of " + w + "x" + h + "; written " + args[3]);
        System.exit(0);
    }
}
