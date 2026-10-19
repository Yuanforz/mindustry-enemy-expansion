package expansion.econ;

import java.util.*;

/**
 * What each module of the executor means to do, and why, in a form that can be shown: marks on the map (tiles kept,
 * places chosen, lines with what they carry, docks and the machines they feed, nodes and their beams, whose job each
 * part is, what the hand is after, how each machine runs) and notes in words, layer by layer.
 *
 * With the mod's debug option on, the game draws it over the map ({@link IntentDraw}); a run without a window can write it
 * out as a picture and as text. Nothing here changes what is built: it only tells.
 */
public final class Intent{
    /** The one being shown in the game, if any. */
    public static volatile Intent current;

    public enum Layer{
        fields("L2a extraction: places packed, fields kept for later"),
        cells("L2b cells: where machines stand, and why"),
        lines("L2c lines: buses, docks, spurs, taps"),
        power("L2d power: nodes and their beams"),
        jobs("L1/L3 jobs: the order, whose job each part is"),
        hand("L3 hand: what the builder is doing now"),
        watch("L4 watch: how each machine runs");

        public final String title;

        Layer(String title){
            this.title = title;
        }
    }

    public enum Shape{rect, cross, line, arrow, text, dot}

    /** One thing to draw: a shape over tiles, with a colour (rgba) and maybe words. */
    public static final class Mark{
        public final Layer layer;
        public final Shape shape;
        /** Tiles: a rectangle from (x0, y0) to (x1, y1), or a line from the one to the other. */
        public final int x0, y0, x1, y1;
        public final int color;
        public final String label;

        Mark(Layer layer, Shape shape, int x0, int y0, int x1, int y1, int color, String label){
            this.layer = layer;
            this.shape = shape;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.color = color;
            this.label = label;
        }
    }

    final List<Mark> marks = new ArrayList<>();
    /** Marks that change as the game goes on (the hand, the watch), drawn after the others and replaced each time. */
    volatile List<Mark> live = new ArrayList<>();
    final Map<Layer, List<String>> notes = new EnumMap<>(Layer.class);
    /** Which layers are shown. */
    public final EnumSet<Layer> shown = EnumSet.allOf(Layer.class);

    public List<Mark> marks(){
        List<Mark> out = new ArrayList<>(marks);
        out.addAll(live);
        return out;
    }

    public void rect(Layer l, int x0, int y0, int x1, int y1, int color, String label){
        marks.add(new Mark(l, Shape.rect, x0, y0, x1, y1, color, label));
    }

    /** A block's outline, by its centre and size. */
    public void block(Layer l, int x, int y, int size, int color, String label){
        int off = -(size - 1) / 2;
        rect(l, x + off, y + off, x + off + size - 1, y + off + size - 1, color, label);
    }

    public void cross(Layer l, int x, int y, int color, String label){
        marks.add(new Mark(l, Shape.cross, x, y, x, y, color, label));
    }

    public void line(Layer l, int x0, int y0, int x1, int y1, int color, String label){
        marks.add(new Mark(l, Shape.line, x0, y0, x1, y1, color, label));
    }

    public void arrow(Layer l, int x0, int y0, int x1, int y1, int color, String label){
        marks.add(new Mark(l, Shape.arrow, x0, y0, x1, y1, color, label));
    }

    public void text(Layer l, int x, int y, int color, String label){
        marks.add(new Mark(l, Shape.text, x, y, x, y, color, label));
    }

    public void note(Layer l, String text){
        notes.computeIfAbsent(l, k -> new ArrayList<>()).add(text);
    }

    public List<String> notes(Layer l){
        return notes.getOrDefault(l, List.of());
    }

    /** Puts what changes as the game goes in place of what was there. */
    public void live(List<Mark> now, Map<Layer, List<String>> words){
        live = now;
        for(var e : words.entrySet()) notes.put(e.getKey(), e.getValue());
    }

    public static Mark liveMark(Layer l, Shape s, int x0, int y0, int x1, int y1, int color, String label){
        return new Mark(l, s, x0, y0, x1, y1, color, label);
    }

    /** Everything said, layer by layer. */
    public String report(){
        StringBuilder out = new StringBuilder();
        for(Layer l : Layer.values()){
            List<String> n = notes(l);
            if(n.isEmpty()) continue;
            out.append("  [").append(l.title).append("]\n");
            for(String s : n) out.append("    ").append(s).append('\n');
        }
        return out.toString();
    }

    /** Colours, as rgba. */
    public static final int white = 0xffffffff, grey = 0x9a9a9aff, red = 0xff4040ff, orange = 0xffa030ff, yellow = 0xffe040ff,
        green = 0x50e060ff, cyan = 0x40d0ffff, blue = 0x5080ffff, violet = 0xc070ffff, kept = 0x60c0ff60;

    public static int rgba(arc.graphics.Color c, float alpha){
        return ((int)(c.r * 255) << 24) | ((int)(c.g * 255) << 16) | ((int)(c.b * 255) << 8) | (int)(alpha * 255);
    }
}
