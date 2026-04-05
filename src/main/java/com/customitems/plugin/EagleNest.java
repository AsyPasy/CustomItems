package com.customitems.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.TurtleEgg;

import java.util.*;

/**
 * Eagle Nest — a hay-bale + fence structure that generates naturally in
 * mountain biomes. Turtle eggs placed in the centre have a 1/50 chance
 * to hatch into the Eagle's Baby boss when broken.
 *
 * Egg locations are tracked at runtime only; eggs that survive a server
 * restart will no longer trigger the boss roll (acceptable trade-off).
 */
public class EagleNest {

    // ── Runtime egg tracking ──────────────────────────────────────────────────
    // Key format: "worldName:bx:by:bz"
    private static final Set<String> nestEggLocations = new HashSet<>();
    private static final Random      random           = new Random();

    // ── Nest shape ─────────────────────────────────────────────────────────────
    // [dx, dy, dz, type]
    //   type 0 = HAY_BLOCK  (axis randomised per block for a tousled look)
    //   type 1 = OAK_FENCE  (the "sticks" binding the nest together)
    //
    // dy = 0 is the nest origin (the first open block above terrain surface).
    // The centre hay bale sits at (0,0,0); the turtle egg sits at (0,1,0)
    // — i.e. on top of the centre hay bale.
    private static final int[][] NEST_BLOCKS = {

        // ── Ground ring (dy = 0) ──────────────────────────────────────────────
        {-2, 0,  0, 0}, { 2, 0,  0, 0},   // left / right cardinal hay bales
        { 0, 0, -2, 0}, { 0, 0,  2, 0},   // front / back cardinal hay bales
        {-1, 0, -1, 0}, { 1, 0, -1, 0},   // front diagonal hay bales
        {-1, 0,  1, 0}, { 1, 0,  1, 0},   // back  diagonal hay bales
        { 0, 0,  0, 0},                    // centre base hay bale (egg goes on top)

        // ── Inner fence "sticks" at ground level (dy = 0) ────────────────────
        {-1, 0,  0, 1}, { 1, 0,  0, 1},
        { 0, 0, -1, 1}, { 0, 0,  1, 1},

        // ── Elevated hay bales (dy = 1, asymmetric for a natural look) ────────
        {-2, 1,  0, 0}, { 2, 1,  0, 0},
        {-1, 1, -1, 0}, { 1, 1, -1, 0},
        { 1, 1,  1, 0},
    };

    // ── Build ──────────────────────────────────────────────────────────────────
    /**
     * Places the nest structure in the world.
     *
     * @param origin   the first open block above the terrain surface (dy = 0)
     * @param eggCount how many turtle eggs to place in the centre (1–4)
     */
    public static void build(Location origin, int eggCount) {
        World world = origin.getWorld();
        int cx = origin.getBlockX();
        int cy = origin.getBlockY();
        int cz = origin.getBlockZ();

        // ── Structural blocks ──────────────────────────────────────────────────
        for (int[] entry : NEST_BLOCKS) {
            Block block = world.getBlockAt(cx + entry[0], cy + entry[1], cz + entry[2]);

            if (entry[3] == 0) {
                // Hay bale — randomise axis so adjacent bales don't look cloned
                block.setType(Material.HAY_BLOCK);
                if (block.getBlockData() instanceof Orientable orientable) {
                    Axis[] axes = {Axis.X, Axis.Y, Axis.Z};
                    orientable.setAxis(axes[random.nextInt(axes.length)]);
                    block.setBlockData(orientable);
                }
            } else {
                // Oak fence post ("nest stick")
                block.setType(Material.OAK_FENCE);
            }
        }

        // ── Turtle eggs — on top of the centre hay bale ───────────────────────
        Block eggBlock = world.getBlockAt(cx, cy + 1, cz);
        eggBlock.setType(Material.TURTLE_EGG);
        if (eggBlock.getBlockData() instanceof TurtleEgg eggData) {
            eggData.setEggs(Math.max(1, Math.min(4, eggCount)));
            eggBlock.setBlockData(eggData);
        }
        nestEggLocations.add(key(eggBlock.getLocation()));

        // ── Ambience on generation ────────────────────────────────────────────
        world.playSound(origin, Sound.ENTITY_PHANTOM_FLAP, 1f, 1.8f);
        world.spawnParticle(Particle.CLOUD,
                origin.clone().add(0, 2, 0), 20, 1.5, 1.0, 1.5, 0.04);
    }

    // ── Egg location tracking ─────────────────────────────────────────────────
    public static boolean isNestEgg(Location loc) {
        return nestEggLocations.contains(key(loc));
    }

    public static void removeNestEgg(Location loc) {
        nestEggLocations.remove(key(loc));
    }

    /** Called on plugin disable to release all tracked locations. */
    public static void clearAll() {
        nestEggLocations.clear();
    }

    // ── Internal ──────────────────────────────────────────────────────────────
    private static String key(Location loc) {
        return loc.getWorld().getName()
             + ":" + loc.getBlockX()
             + ":" + loc.getBlockY()
             + ":" + loc.getBlockZ();
    }
}
