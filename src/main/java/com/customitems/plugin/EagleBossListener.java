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

    // ── Natural spawn ─────────────────────────────────────────────────────────
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

    // ── Cancel ALL damage to the phantom — we handle HP ourselves ─────────────
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBossDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Phantom p)) return;
        if (!p.hasMetadata(EagleBoss.META_EAGLE_BOSS)) return;

        EagleBoss boss = activeBosses.get(p.getUniqueId());
        if (boss == null) return;

        // Cancel the vanilla damage so phantom never actually loses HP
        event.setCancelled(true);

        // Only count player-dealt damage toward virtual HP
        if (event instanceof EntityDamageByEntityEvent edbe) {
            Entity damager = edbe.getDamager();
            double vanillaDamage = edbe.getDamage();

            // Arrow shot by player
            if (damager instanceof Arrow arrow
                    && arrow.getShooter() instanceof Player) {
                if (boss.applyVirtualDamage(vanillaDamage)) {
                    triggerDeath(p, boss);
                }
                return;
            }

            // Melee by player
            if (damager instanceof Player) {
                if (boss.applyVirtualDamage(vanillaDamage)) {
                    triggerDeath(p, boss);
                }
            }
        }
    }

    // ── Feather arrow hits entity ─────────────────────────────────────────────
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

    // ── Feather hits block ────────────────────────────────────────────────────
    @EventHandler
    public void onFeatherHitBlock(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.hasMetadata(EagleBoss.META_EAGLE_FEATHER)) return;
        if (event.getHitBlock() != null) arrow.remove();
    }

    // ── Trigger death manually ────────────────────────────────────────────────
    private void triggerDeath(Phantom phantom, EagleBoss boss) {
        activeBosses.remove(phantom.getUniqueId());
        boss.die();

        // Drop Eagle's Eye and remove phantom
        phantom.getWorld().dropItemNaturally(
            phantom.getLocation(), EaglesEyeItem.create());
        phantom.getWorld().dropItemNaturally(
            phantom.getLocation(), EaglesEyeItem.create());
        phantom.remove();

        Bukkit.broadcastMessage(
            "\u00a76\u00a7lEagle's Baby Boss has been slain! " +
            "\u00a7e\u00a7lAn Eagle's Eye has dropped!");
    }

    // ── Boss death via other means (fallback) ─────────────────────────────────
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Phantom phantom)) return;
        if (!phantom.hasMetadata(EagleBoss.META_EAGLE_BOSS)) return;

        EagleBoss boss = activeBosses.remove(phantom.getUniqueId());
        if (boss != null) {
            boss.die();
            event.getDrops().clear();
            event.getDrops().add(EaglesEyeItem.create());
            Bukkit.broadcastMessage(
                "\u00a76\u00a7lEagle's Baby Boss has been slain! " +
                "\u00a7e\u00a7lAn Eagle's Eye has dropped!");
        }
    }

    public void cleanup() {
        activeBosses.values().forEach(EagleBoss::cleanup);
        activeBosses.clear();
        spawnCooldowns.clear();
    }
}
