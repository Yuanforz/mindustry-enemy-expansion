package bp;

import mindustry.world.*;

/**
 * What a tile of ground, an item of cost and a second of building weigh against one another: the user's three costs of a
 * layout, the same in every step (the balance, the heaters, the lines, the power).
 */
public record Weights(float tile, float item, float second){
    /** What a block costs: its tiles, its items, its seconds to build at a build speed of one. */
    public double cost(Block b){
        int items = 0;
        for(var st : b.requirements) items += st.amount;
        return tile * b.size * b.size + item * items + second * b.buildTime / 60f;
    }
}
