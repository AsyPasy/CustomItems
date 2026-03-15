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

    // ── Intercept ALL damage to boss — cancel vanilla, apply to virtual HP ────
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBossDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Phantom p)) return;
        if (!p.hasMetadata(EagleBoss.META_EAGLE_BOSS)) return;

        EagleBoss boss = activeBosses.get(p.getUniqueId());
        if (boss == null) return;

        // Cancel vanilla HP loss — we control virtual HP
        event.setCancelled(true);

        // Only player damage counts
        if (event instanceof EntityDamageByEntityEvent edbe) {
            Entity damager  = edbe.getDamager();
            double damage   = edbe.getDamage();

            boolean isPlayerArrow  = damager instanceof Arrow a
                                  && a.getShooter() instanceof Player;
            boolean isPlayerMelee  = damager instanceof Player;

            if (isPlayerArrow || isPlayerMelee) {
                if (boss.applyVirtualDamage(damage)) {
                    triggerDeath(p, boss);
                }
            }
        }
    }

    // ── Feather item hits player — deal 5 display HP (1 vanilla HP) ──────────
    // Since Item entities don't fire EntityDamageByEntityEvent, we handle
    // proximity in EagleBoss.trackFeather() — this event is a safety fallback
    @EventHandler(priority = EventPriority.HIGH)
    public void onFeatherHitBlock(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.hasMetadata(EagleBoss.META_EAGLE_FEATHER)) return;
        if (event.getHitBlock() != null) arrow.remove();
    }

    // ── Trigger death ─────────────────────────────────────────────────────────
    private void triggerDeath(Phantom phantom, EagleBoss boss) {
        activeBosses.remove(phantom.getUniqueId());
        boss.die();

        // Drop 2 Eagle's Eyes
        phantom.getWorld().dropItemNaturally(
            phantom.getLocation(), EaglesEyeItem.create());
        phantom.getWorld().dropItemNaturally(
            phantom.getLocation(), EaglesEyeItem.create());
        phantom.remove();

        Bukkit.broadcastMessage(
            "\u00a76\u00a7lEagle's Baby Boss has been slain! " +
            "\u00a7e\u00a7lEagle's Eyes have dropped!");
    }

    // ── Fallback death (e.g. killed by other means) ───────────────────────────
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) return;
        if (!phantom.hasMetadata(EagleBoss.META_EAGLE_BOSS)) return;

        EagleBoss boss = activeBosses.remove(phantom.getUniqueId());
        if (boss == null) return;
        boss.die();
        event.getDrops().clear();
        event.getDrops().add(EaglesEyeItem.create());
        event.getDrops().add(EaglesEyeItem.create());
        Bukkit.broadcastMessage(
            "\u00a76\u00a7lEagle's Baby Boss has been slain! " +
            "\u00a7e\u00a7lEagle's Eyes have dropped!");
    }

    public void cleanup() {
        activeBosses.values().forEach(EagleBoss::cleanup);
        activeBosses.clear();
        spawnCooldowns.clear();
    }
}
