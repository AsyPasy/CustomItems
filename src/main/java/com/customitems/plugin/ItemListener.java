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

    // ── Valor Dagger melee damage ─────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onValorDaggerHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!ValorDagger.isValorDagger(held)) return;
        event.setDamage(ValorDagger.calculateDamage(held, ValorDagger.isCriticalHit(player)));
    }

    // ── Eagle's Eye Bow shoot ─────────────────────────────────────────────────
    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getBow();
        if (!EaglesEyeBow.isEaglesEyeBow(bow)) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;
        arrow.setDamage(0);
        EaglesEyeBow.onArrowShoot(player, arrow, event.getForce(), bow, plugin);
    }

    // ── Eagle's Eye arrow hits entity ─────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onArrowHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!(event.getEntity() instanceof LivingEntity hit)) return;
        if (!arrow.hasMetadata(EaglesEyeBow.META_EAGLES_EYE)) return;
        event.setCancelled(true);
        EaglesEyeBow.onArrowHitEntity(arrow, hit, shooter, plugin);
    }

    // ── Crafting: Valor Dagger (12 gold ingots) ───────────────────────────────
    // Pattern:  _ N _  /  N G N  /  _ S _
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inv = event.getInventory();
        ItemStack[] m = inv.getMatrix();
        if (m.length != 9) return;

        if (isValorDaggerPattern(m)) {
            inv.setResult(ValorDagger.createValorDagger());
        } else if (isEaglesBowPattern(m)) {
            inv.setResult(EaglesEyeBow.createEaglesEyeBow());
        } else {
            // Clear result if pattern almost matched but not quite
            if (wouldMatchEither(inv.getResult())) {
                inv.setResult(new ItemStack(Material.AIR));
            }
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null) return;
        ItemStack[] m = event.getInventory().getMatrix();
        if (m.length != 9) return;

        // Consume extra 11 gold ingots for Valor Dagger
        if (ValorDagger.isValorDagger(result)) {
            ItemStack center = m[4];
            if (center != null && center.getType() == Material.GOLD_INGOT
                    && center.getAmount() >= 12) {
                center.setAmount(center.getAmount() - 11);
                m[4] = center;
                event.getInventory().setMatrix(m);
            }
        }
    }

    // ── Re-apply flags after enchanting table ─────────────────────────────────
    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        ItemStack item = event.getItem();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (ValorDagger.isValorDagger(item))   ValorDagger.applyMeta(item);
            if (EaglesEyeBow.isEaglesEyeBow(item)) EaglesEyeBow.applyMeta(item);
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

    // ── Pattern validators ────────────────────────────────────────────────────

    // Valor Dagger:
    // [ ] [N] [ ]
    // [N] [G×12] [N]
    // [ ] [S] [ ]
    private boolean isValorDaggerPattern(ItemStack[] m) {
        return empty(m[0]) && is(m[1], Material.IRON_NUGGET)  && empty(m[2])
            && is(m[3], Material.IRON_NUGGET)
            && isGoldStack(m[4], 12)
            && is(m[5], Material.IRON_NUGGET)
            && empty(m[6]) && is(m[7], Material.STICK) && empty(m[8]);
    }

    // Eagle's Eye Bow:
    // [E] [S] [E]
    // [S] [ ] [S]
    // [E] [S] [E]
    // E = Eagle's Eye item, S = String
    private boolean isEaglesBowPattern(ItemStack[] m) {
        return isEye(m[0]) && is(m[1], Material.STRING) && isEye(m[2])
            && is(m[3], Material.STRING) && empty(m[4]) && is(m[5], Material.STRING)
            && isEye(m[6]) && is(m[7], Material.STRING) && isEye(m[8]);
    }

    private boolean wouldMatchEither(ItemStack result) {
        return result != null
            && (ValorDagger.isValorDagger(result) || EaglesEyeBow.isEaglesEyeBow(result));
    }

    private boolean empty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    private boolean is(ItemStack item, Material type) {
        return item != null && item.getType() == type;
    }

    private boolean isGoldStack(ItemStack item, int min) {
        return item != null && item.getType() == Material.GOLD_INGOT
                && item.getAmount() >= min;
    }

    private boolean isEye(ItemStack item) {
        return EaglesEyeItem.is(item);
    }
}
