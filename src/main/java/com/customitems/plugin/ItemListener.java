package com.customitems.plugin;

import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
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

    // ── Valor Dagger melee ────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onValorDaggerHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!ValorDagger.isValorDagger(held)) return;
        event.setDamage(ValorDagger.calculateDamage(held, ValorDagger.isCriticalHit(player)));
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

    // ── Valor Dagger crafting — requires 12 gold ingots in center slot ────────
    // Pattern:
    //  _ N _      (N = Iron Nugget)
    //  N G N      (G = Gold Ingot ×12 in one slot)
    //  _ S _      (S = Stick)
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inv = event.getInventory();
        ItemStack[] matrix = inv.getMatrix();
        if (matrix.length != 9) return; // must be 3x3 crafting table

        if (isValorDaggerPattern(matrix)) {
            inv.setResult(ValorDagger.createValorDagger());
        } else if (wouldResultInValorDagger(inv.getResult())) {
            inv.setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || !ValorDagger.isValorDagger(result)) return;

        ItemStack[] matrix = event.getInventory().getMatrix();
        if (matrix.length != 9) return;

        // Center slot is index 4 — Bukkit auto-consumes 1, we remove 11 more
        ItemStack center = matrix[4];
        if (center != null && center.getType() == Material.GOLD_INGOT
                && center.getAmount() >= 12) {
            center.setAmount(center.getAmount() - 11);
            matrix[4] = center;
            event.getInventory().setMatrix(matrix);
        }
    }

    // ── Re-apply flags after enchanting table ─────────────────────────────────
    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        ItemStack item = event.getItem();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (ValorDagger.isValorDagger(item))    ValorDagger.applyMeta(item);
            if (EaglesEyeBow.isEaglesEyeBow(item))  EaglesEyeBow.applyMeta(item);
        }, 1L);
    }

    // ── Re-apply flags after anvil ────────────────────────────────────────────
    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null) return;
        if (ValorDagger.isValorDagger(result))   ValorDagger.applyMeta(result);
        if (EaglesEyeBow.isEaglesEyeBow(result)) EaglesEyeBow.applyMeta(result);
    }

    // ── Pattern helpers ───────────────────────────────────────────────────────
    private boolean isValorDaggerPattern(ItemStack[] m) {
        return isEmpty(m[0])
            && isType(m[1], Material.IRON_NUGGET)
            && isEmpty(m[2])
            && isType(m[3], Material.IRON_NUGGET)
            && isGoldStack(m[4], 12)
            && isType(m[5], Material.IRON_NUGGET)
            && isEmpty(m[6])
            && isType(m[7], Material.STICK)
            && isEmpty(m[8]);
    }

    private boolean wouldResultInValorDagger(ItemStack result) {
        return result != null && ValorDagger.isValorDagger(result);
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    private boolean isType(ItemStack item, Material type) {
        return item != null && item.getType() == type;
    }

    private boolean isGoldStack(ItemStack item, int minAmount) {
        return item != null
            && item.getType() == Material.GOLD_INGOT
            && item.getAmount() >= minAmount;
    }
}
