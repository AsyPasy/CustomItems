package com.customitems.plugin;

import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;

public class ItemListener implements Listener {

    private final CustomItemsPlugin plugin;

    public ItemListener(CustomItemsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Right-click ───────────────────────────────────────────────────────────
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (ValorDagger.isValorDagger(item)) {
            event.setCancelled(true);
            ValorDagger.activate(player, plugin);
            return;
        }
        if (EaglesEyeBow.isEaglesEyeBow(item) && player.isSneaking()) {
            event.setCancelled(true);
            EaglesEyeBow.activateGaze(player, plugin);
        }
    }

    // ── Valor Dagger melee damage — base + crit + Sharpness ──────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onValorDaggerHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!ValorDagger.isValorDagger(held)) return;
        boolean crit = ValorDagger.isCriticalHit(player);
        event.setDamage(ValorDagger.calculateDamage(held, crit));
    }

    // ── Bow shoot ─────────────────────────────────────────────────────────────
    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getBow();
        if (!EaglesEyeBow.isEaglesEyeBow(bow)) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;
        arrow.setDamage(0);
        EaglesEyeBow.onArrowShoot(player, arrow, event.getForce(), bow, plugin);
    }

    // ── Arrow hits entity ─────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onArrowHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!(event.getEntity() instanceof LivingEntity hit)) return;
        if (!arrow.hasMetadata(EaglesEyeBow.META_EAGLES_EYE)) return;
        event.setCancelled(true);
        EaglesEyeBow.onArrowHitEntity(arrow, hit, shooter, plugin);
    }

    // ── Re-apply item flags after enchanting table ────────────────────────────
    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        ItemStack item = event.getItem();
        if (ValorDagger.isValorDagger(item)) {
            // Schedule 1 tick later so enchantments are applied first
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                ValorDagger.applyMeta(item), 1L);
        }
        if (EaglesEyeBow.isEaglesEyeBow(item)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                EaglesEyeBow.applyMeta(item), 1L);
        }
    }

    // ── Re-apply item flags after anvil ───────────────────────────────────────
    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null) return;
        if (ValorDagger.isValorDagger(result)) ValorDagger.applyMeta(result);
        if (EaglesEyeBow.isEaglesEyeBow(result)) EaglesEyeBow.applyMeta(result);
    }
}
