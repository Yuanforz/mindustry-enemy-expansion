package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;

/** Pictures of a stretch of the map as it stands, for looking at a fight frame by frame. */
public class Frames{
    static final int scale = 6;

    /** Floor grey, walls dark, ore walls by item, water blue; buildings of the side played green, the other side's red, turrets lighter; units white and orange; turrets with nothing to fire crossed in black; what is being built hatched. */
    public static void write(Fi file, int x0, int y0, int w, int h, Team own){
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w * scale, h * scale, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int x = x0; x < x0 + w; x++) for(int y = y0; y < y0 + h; y++){
            Tile t = Vars.world.tile(x, y);
            int c;
            if(t == null) c = 0;
            else if(t.build != null && t.build.team != Team.derelict){
                boolean mine = t.build.team == own;
                if(t.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) c = mine ? 0x60a060 : 0xc06060;
                else if(t.build.block instanceof mindustry.world.blocks.storage.CoreBlock) c = mine ? 0x00ffff : 0xffff00;
                else if(t.build.block instanceof mindustry.world.blocks.defense.turrets.Turret) c = mine ? 0x80ff80 : 0xff6060;
                else if(!mine) c = 0x902020;
                else if(!Boolean.getBoolean("xp.kinds")) c = 0x208020;
                else{
                    //by what it is, for looking at how a base is laid out
                    var blk = t.build.block;
                    if(blk instanceof mindustry.world.blocks.distribution.Duct || blk instanceof mindustry.world.blocks.distribution.DuctRouter || blk instanceof mindustry.world.blocks.distribution.OverflowDuct || blk instanceof mindustry.world.blocks.distribution.DuctJunction) c = 0x30b030;
                    else if(blk instanceof mindustry.world.blocks.distribution.DirectionBridge || blk instanceof mindustry.world.blocks.distribution.BufferedItemBridge) c = 0xa0ff40;
                    else if(blk instanceof mindustry.world.blocks.liquid.LiquidBlock || blk instanceof mindustry.world.blocks.liquid.LiquidBridge || blk instanceof mindustry.world.blocks.liquid.Conduit) c = 0x4080ff;
                    else if(blk instanceof mindustry.world.blocks.power.BeamNode || blk instanceof mindustry.world.blocks.power.PowerNode) c = 0xe0e040;
                    else if(blk instanceof mindustry.world.blocks.production.BeamDrill || blk instanceof mindustry.world.blocks.production.Drill || blk instanceof mindustry.world.blocks.production.WallCrafter) c = 0x8a5a2a;
                    else if(blk instanceof mindustry.world.blocks.units.UnitFactory || blk instanceof mindustry.world.blocks.units.Reconstructor) c = 0xff8000;
                    else if(blk instanceof mindustry.world.blocks.production.GenericCrafter) c = 0xc040ff;
                    else if(blk instanceof mindustry.world.blocks.defense.Wall) c = 0x9090a0;
                    else if(blk instanceof mindustry.world.blocks.power.PowerGenerator) c = 0xffffff;
                    else c = 0x105010;
                }
            }else if(t.solid() && t.wallDrop() != null) c = 0x40c0a0;
            else if(t.solid()) c = 0x504040;
            else if(t.floor().isDeep()) c = 0x103070;
            else if(t.floor().isLiquid) c = 0x3060b0;
            else c = 0x707070;
            for(int i = 0; i < scale; i++) for(int j = 0; j < scale; j++) img.setRGB((x - x0) * scale + i, (h - 1 - (y - y0)) * scale + j, c);
        }
        for(Building b : Groups.build){
            if(!(b instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild t) || t.totalAmmo > 0) continue;
            int bx = b.tileX(), by = b.tileY();
            if(bx < x0 || by < y0 || bx >= x0 + w || by >= y0 + h) continue;
            for(int i = -scale * 2; i <= scale * 2; i++){
                int px = (bx - x0) * scale + scale / 2 + i, py = (h - 1 - (by - y0)) * scale + scale / 2;
                if(px >= 0 && px < w * scale) img.setRGB(px, py, 0);
            }
        }
        for(Unit unit : Groups.unit){
            int ux = unit.tileX(), uy = unit.tileY();
            if(ux < x0 || uy < y0 || ux >= x0 + w || uy >= y0 + h) continue;
            int c = unit.team == own ? 0xffffff : unit.spawnedByCore ? 0xff00ff : 0xff9900;
            for(int i = -2; i <= 2; i++) for(int j = -2; j <= 2; j++){
                int px = (ux - x0) * scale + scale / 2 + i, py = (h - 1 - (uy - y0)) * scale + scale / 2 + j;
                if(px >= 0 && py >= 0 && px < w * scale && py < h * scale) img.setRGB(px, py, c);
            }
        }
        try{
            file.parent().mkdirs();
            javax.imageio.ImageIO.write(img, "png", file.file());
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }
}
