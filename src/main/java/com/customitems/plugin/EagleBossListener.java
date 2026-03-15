package com.customitems.plugin;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

public class EagleBossListener implements Listener {

    private final CustomItemsPlugin plugin;
    private final Map<UUID, EagleBoss> activeBosses    = new HashMap<>();
    private final Set<UUID>            spawnCooldowns  = new HashSet<>();
    private final Random random = new Random();

    public EagleBossListener(CustomItemsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Spawn: 1/2000 chance per Y-change while above Y 150 ──────────────────
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getTo().getY() < 150) return;
        // Only trigger when the block Y actually changes (performance)
        if (event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        Player player = event.getPlayer();
        if (spawnCooldowns.contains(player.getUniqueId())) return;

        if (random.nextInt(2000) == 0) {
            Location spawnLoc = player.getLocation().clone().add(0, 10, 0);
            spawnBoss(spawnLoc);

            // 5-minute per-player cooldown to prevent spam
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

    // ── Feather arrow hits entity — deal 10 display HP (2 vanilla HP) ─────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onFeatherHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!arrow.hasMetadata(EagleBoss.META_EAGLE_FEATHER)) return;
        event.setCancelled(true);
        if (event.getEntity() instanceof LivingEntity hit) {
            Entity shooter = arrow.getShooter() instanceof Entity e ? e : null;
            hit.damage(2.0, shooter); // 10 display HP
        }
        arrow.remove();
    }

    // ── Feather arrow hits block — remove it ─────────────────────────────────
    @EventHandler
    public void onFeatherHitBlock(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.hasMetadata(EagleBoss.META_EAGLE_FEATHER)) return;
        if (event.getHitBlock() != null) arrow.remove();
    }

    // ── Prevent feathers from damaging the boss itself ────────────────────────
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHitByOwnFeather(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Phantom p)) return;
        if (!p.hasMetadata(EagleBoss.META_EAGLE_BOSS)) return;
        if (event.getDamager() instanceof Arrow arrow
                && arrow.hasMetadata(EagleBoss.META_EAGLE_FEATHER)) {
            event.setCancelled(true);
        }
    }

    // ── Boss death — drop Eagle's Eye, server-wide broadcast ──────────────────
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) return;
        if (!phantom.hasMetadata(EagleBoss.META_EAGLE_BOSS)) return;

        EagleBoss boss = activeBosses.remove(phantom.getUniqueId());
        if (boss != null) boss.die();

        event.getDrops().clear();
        event.setDroppedExp(500);
        event.getDrops().add(EaglesEyeItem.create());

        Bukkit.broadcastMessage(
            "\u00a76\u00a7lEagle's Baby Boss has been slain! \u00a7e\u00a7lAn Eagle's Eye has dropped!");
    }

    public void cleanup() {
        activeBosses.values().forEach(EagleBoss::cleanup);
        activeBosses.clear();
        spawnCooldowns.clear();
    }
}
