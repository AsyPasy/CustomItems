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

    // Right-click
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

    // Valor Dagger melee damage
    @EventHandler(priority = EventPriority.HIGH)
    public void onValorDaggerHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!ValorDagger.isValorDagger(held)) return;
        event.setDamage(ValorDagger.calculateDamage(held, ValorDagger.isCriticalHit(player)));
    }

    // Eagle's Eye Bow shoot
    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getBow();
        if (!EaglesEyeBow.isEaglesEyeBow(bow)) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;
        arrow.setDamage(0);
        EaglesEyeBow.onArrowShoot(player, arrow, event.getForce(), bow, plugin);
    }

    // Eagle's Eye arrow hits entity
    //
    // WHY 1-TICK DELAY:
    // In Paper 1.20, event.setDamage(BASE) is unreliable because Paper pre-calculates
    // velocity-based modifier slots independently of BASE. Setting BASE=16 leaves
    // those modifier values intact, making final damage = 16 + modifiers > 16.
    //
    // Fix: cancel the vanilla event, then fire hit.damage(damage, shooter) 1 tick later.
    // That is a fresh EntityDamageByEntityEvent with Player as damager and our exact value.
    // - Our arrow handler ignores it (damager is not an Arrow)
    // - ValorDagger handler ignores it (player not holding a dagger)
    // Result: precise damage every time, and player gets proper kill credit.
    @EventHandler(priority = EventPriority.HIGH)
    public void onArrowHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;
        if (!(event.getEntity() instanceof LivingEntity hit)) return;
        if (!arrow.hasMetadata(EaglesEyeBow.META_EAGLES_EYE)) return;

        event.setCancelled(true);

        // Read damage and handle all side effects BEFORE removing the arrow
        double damage = EaglesEyeBow.calculateArrowDamage(arrow, hit, shooter, plugin);
        arrow.remove();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (hit.isValid() && !hit.isDead()) {
                hit.damage(damage, shooter);
            }
        }, 1L);
    }

    // Crafting: Valor Dagger
    // [ ] [N] [ ]
    // [N] [G*12] [N]
    // [ ] [S] [ ]
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

    // Re-apply flags after enchanting table
    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        ItemStack item = event.getItem();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (ValorDagger.isValorDagger(item))   ValorDagger.applyMeta(item);
            if (EaglesEyeBow.isEaglesEyeBow(item)) EaglesEyeBow.applyMeta(item);
        }, 1L);
    }

    // Re-apply flags after anvil
    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null) return;
        if (ValorDagger.isValorDagger(result))   ValorDagger.applyMeta(result);
        if (EaglesEyeBow.isEaglesEyeBow(result)) EaglesEyeBow.applyMeta(result);
    }

    // Pattern validators

    private boolean isValorDaggerPattern(ItemStack[] m) {
        return empty(m[0]) && is(m[1], Material.IRON_NUGGET) && empty(m[2])
            && is(m[3], Material.IRON_NUGGET)
            && isGoldStack(m[4], 12)
            && is(m[5], Material.IRON_NUGGET)
            && empty(m[6]) && is(m[7], Material.STICK) && empty(m[8]);
    }

    private boolean isEaglesBowPattern(ItemStack[] m) {
        return empty(m[0]) && isEye(m[1])  && empty(m[2])
            && is(m[3], Material.STRING)
            && is(m[4], Material.BOW)
            && is(m[5], Material.STRING)
            && empty(m[6]) && isEye(m[7])  && empty(m[8]);
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
