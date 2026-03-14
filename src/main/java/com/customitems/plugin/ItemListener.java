package com.customitems.plugin;

import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
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

    // ── Valor Dagger melee damage — fixed to 10 display HP (2 vanilla) ────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onValorDaggerHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!ValorDagger.isValorDagger(held)) return;
        event.setDamage(ValorDagger.ATTACK_DAMAGE); // exactly 2 vanilla = 10 display HP
    }

    // ── Bow shoot ─────────────────────────────────────────────────────────────
    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!EaglesEyeBow.isEaglesEyeBow(event.getBow())) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;

        // Zero out vanilla damage — we control it entirely
        arrow.setDamage(0);
        EaglesEyeBow.onArrowShoot(player, arrow, event.getForce(), plugin);
    }

    // ── Arrow hits entity ─────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onArrowHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!(event.getEntity() instanceof LivingEntity hit)) return;

        // Only intercept arrows we tagged — ignores all other arrows
        if (!arrow.hasMetadata(EaglesEyeBow.META_EAGLES_EYE)) return;

        event.setCancelled(true);
        EaglesEyeBow.onArrowHitEntity(arrow, hit, shooter, plugin);
    }
}
