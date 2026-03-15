package com.customitems.plugin;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

public class EagleBossListener implements Listener {

    private final CustomItemsPlugin plugin;
    private final Map<UUID, EagleBoss> activeBosses   = new HashMap<>();
    private final Set<UUID>            spawnCooldowns = new HashSet<>();
    private final Random random = new Random();

    public EagleBossListener(CustomItemsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Natural spawn: 1/2000 above Y 150 ────────────────────────────────────
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
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

    // ── Manual spawn ──────────────────────────────────────────────────────────
    public void spawnBoss(Location loc) {
        EagleBoss boss = new EagleBoss(plugin, loc);
        activeBosses.put(boss.getPhantom().getUniqueId(), boss);
    }

    // ── Register an existing boss (used on server restart reattach) ───────────
    public void registerBoss(EagleBoss boss) {
        activeBosses.put(boss.getPhantom().getUniqueId(), boss);
    }

    // ── Feather item hits player (proximity handled in EagleBoss.trackFeather)
    // This handler only cleans up feathers that hit blocks ────────────────────
    @EventHandler
    public void onFeatherHitBlock(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.hasMetadata(EagleBoss.META_EAGLE_FEATHER)) return;
        if (event.getHitBlock() != null) arrow.remove();
    }

    // ── Boss death — vanilla HP hits 0, we handle drops ───────────────────────
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

    // ── Cleanup on plugin disable ─────────────────────────────────────────────
    public void cleanup() {
        activeBosses.values().forEach(EagleBoss::cleanup);
        activeBosses.clear();
        spawnCooldowns.clear();
    }
}
