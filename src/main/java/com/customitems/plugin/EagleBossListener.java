package com.customitems.plugin;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.*;

public class EagleBossListener implements Listener {

    private final CustomItemsPlugin    plugin;
    private final Map<UUID, EagleBoss> activeBosses  = new HashMap<>();
    private final Set<UUID>            spawnCooldowns = new HashSet<>();
    private final Random               random         = new Random();

    // Mountain biomes - eagle nests generate here
    private static final Set<Biome> MOUNTAIN_BIOMES = Set.of(
        Biome.MEADOW,
        Biome.GROVE,
        Biome.SNOWY_SLOPES,
        Biome.FROZEN_PEAKS,
        Biome.JAGGED_PEAKS,
        Biome.STONY_PEAKS,
        Biome.WINDSWEPT_HILLS,
        Biome.WINDSWEPT_GRAVELLY_HILLS,
        Biome.WINDSWEPT_FOREST
    );

    public EagleBossListener(CustomItemsPlugin plugin) {
        this.plugin = plugin;
    }

    // Natural boss spawn: 1/2000 chance above Y 150 (overworld only)
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        // Block Nether and End
        if (event.getPlayer().getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (event.getTo().getY() < 150) return;
        if (event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        Player player = event.getPlayer();
        if (spawnCooldowns.contains(player.getUniqueId())) return;

        if (random.nextInt(2000) == 0) {
            spawnBoss(player.getLocation().clone().add(0, 10, 0));
            spawnCooldowns.add(player.getUniqueId());
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> spawnCooldowns.remove(player.getUniqueId()),
                20L * 60 * 5);
        }
    }

    // Eagle Nest chunk generation: 1/300 in freshly generated mountain chunks (overworld only)
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        // Block Nether and End
        if (event.getChunk().getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (random.nextInt(300) != 0) return;

        Chunk chunk = event.getChunk();
        if (!isMountainChunk(chunk)) return;

        Location loc = findSurfaceLocation(chunk);
        if (loc == null) return;

        int eggCount = 1 + random.nextInt(4);
        EagleNest.build(loc, eggCount);
        EagleNest.registerNest(loc);
    }

    // Turtle egg break in a nest: 1/50 chance to hatch the Eagle Boss (overworld only)
    @EventHandler(priority = EventPriority.HIGH)
    public void onNestEggBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.TURTLE_EGG) return;
        // Block Nether and End
        if (block.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (!EagleNest.isNestEgg(block.getLocation())) return;

        EagleNest.removeNestEgg(block.getLocation());

        if (random.nextInt(50) == 0) {
            Location bLoc = block.getLocation();
            for (Player nearby : block.getWorld().getPlayers()) {
                if (nearby.getLocation().distanceSquared(bLoc) <= 150 * 150) {
                    nearby.sendMessage(
                        "\u00a74\u00a7l\u26a0 \u00a7e\u00a7lSomething hatches from the eagle nest\u2026 \u00a74\u00a7l\u26a0");
                }
            }
            block.getWorld().playSound(bLoc, Sound.ENTITY_WITHER_SPAWN, 1f, 1.8f);
            block.getWorld().strikeLightningEffect(bLoc);
            block.getWorld().spawnParticle(
                Particle.EXPLOSION_LARGE, bLoc.clone().add(0, 1, 0),
                5, 0.5, 0.5, 0.5, 0);

            Location spawnLoc = bLoc.clone().add(0, 5, 0);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> spawnBoss(spawnLoc), 5L);
        }
    }

    // Manual spawn (command) - overworld only as a safety net
    public void spawnBoss(Location loc) {
        // Final safety net: never spawn in Nether or End regardless of call origin
        if (loc.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        EagleBoss boss = new EagleBoss(plugin, loc);
        activeBosses.put(boss.getPhantom().getUniqueId(), boss);
    }

    // Register existing boss (server restart reattach)
    public void registerBoss(EagleBoss boss) {
        activeBosses.put(boss.getPhantom().getUniqueId(), boss);
    }

    // Feather hits block - remove it
    @EventHandler
    public void onFeatherHitBlock(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.hasMetadata(EagleBoss.META_EAGLE_FEATHER)) return;
        if (event.getHitBlock() != null) arrow.remove();
    }

    // Boss death - vanilla HP hits 0
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) return;
        if (!phantom.hasMetadata(EagleBoss.META_EAGLE_BOSS)) return;

        EagleBoss boss = activeBosses.remove(phantom.getUniqueId());
        if (boss == null) return;

        boss.die();
        event.getDrops().clear();
        event.setDroppedExp(500);
        event.getDrops().add(EaglesEyeItem.create());
        event.getDrops().add(EaglesEyeItem.create());

        Bukkit.broadcastMessage(
            "\u00a76\u00a7lEagle's Baby has been slain! " +
            "\u00a7e\u00a7lEagle's Eyes have dropped!");
    }

    // Cleanup
    public void cleanup() {
        activeBosses.values().forEach(EagleBoss::cleanup);
        activeBosses.clear();
        spawnCooldowns.clear();
        EagleNest.clearAll();
    }

    // Helper: does this chunk contain at least one mountain biome sample?
    private boolean isMountainChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int   baseX = chunk.getX() * 16;
        int   baseZ = chunk.getZ() * 16;
        for (int dx = 4; dx <= 12; dx += 4) {
            for (int dz = 4; dz <= 12; dz += 4) {
                if (MOUNTAIN_BIOMES.contains(world.getBiome(baseX + dx, 128, baseZ + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    // Helper: find a valid surface location inside the chunk
    private Location findSurfaceLocation(Chunk chunk) {
        World world = chunk.getWorld();
        int   baseX = chunk.getX() * 16;
        int   baseZ = chunk.getZ() * 16;

        int lx = 3 + random.nextInt(10);
        int lz = 3 + random.nextInt(10);
        int x  = baseX + lx;
        int z  = baseZ + lz;

        int   y       = world.getHighestBlockYAt(x, z);
        Block surface = world.getBlockAt(x, y, z);

        if (!surface.getType().isSolid()) return null;
        if (y < 60)                       return null;

        return new Location(world, x, y + 1, z);
    }
}
