package com.customitems.plugin;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class ValorDagger {

    public static final String VALOR_DAGGER_NAME = "\u00a77\u00a7lValor Dagger";

    private static final int    BASE_TICKS        = 200;
    private static final double BUFF_RADIUS       = 10.0;
    private static final double HP_BONUS_PERCENT  = 0.05;
    private static final long   COOLDOWN_MS       = 60 * 1000L;

    // 2 vanilla hearts   = 4 vanilla HP
    // 2.5 vanilla hearts = 5 vanilla HP
    public static final double BASE_DAMAGE     = 4.0;
    public static final double CRIT_DAMAGE     = 5.0;
    public static final double SHARPNESS_BONUS = 0.2;

    private static final Map<UUID, Long>   cooldowns  = new HashMap<>();
    private static final Map<UUID, Double> hpBonusMap = new HashMap<>();

    // ── Item ──────────────────────────────────────────────────────────────────
    public static ItemStack createValorDagger() {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        applyMeta(item);
        return item;
    }

    public static void applyMeta(ItemStack item) {
        int sharpness = item.getEnchantmentLevel(Enchantment.DAMAGE_ALL);
        double displayBase = BASE_DAMAGE + (sharpness * SHARPNESS_BONUS);
        double displayCrit = CRIT_DAMAGE + (sharpness * SHARPNESS_BONUS);
        String sharpNote = sharpness > 0 ? " \u00a77(Sharpness " + sharpness + ")" : "";

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName(VALOR_DAGGER_NAME);
        meta.setCustomModelData(292387);
        meta.setUnbreakable(true);
        meta.setLore(List.of(
            "\u00a77Damage: \u00a7a+" + fmt(displayBase)
                + " \u00a77(\u00a7a+" + fmt(displayCrit) + "\u00a77 crit)" + sharpNote,
            "",
            "\u00a77Normal: \u00a7a" + fmt(displayBase)
                + " HP  \u00a77Crit: \u00a7a" + fmt(displayCrit) + " HP",
            "",
            "\u00a75Ability: \u00a7fRally \u00a7e\u00a7lRIGHT CLICK",
            "\u00a77Inspire yourself and nearby allies,",
            "\u00a77granting \u00a7fSpeed I\u00a77, \u00a7fJump Boost I\u00a77,",
            "\u00a77and \u00a7fStrength I \u00a77in a \u00a7f10 block\u00a77 radius.",
            "\u00a7a\u00a7lCooldown: \u00a7260s",
            "",
            "\u00a77\u00a7lSTARTER DAGGER"
        ));

        meta.removeAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE);
        meta.removeAttributeModifier(Attribute.GENERIC_ATTACK_SPEED);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE,
                new AttributeModifier(UUID.randomUUID(),
                        "valor_dmg", 0,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlot.HAND));
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED,
                new AttributeModifier(UUID.randomUUID(),
                        "valor_spd", 0,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlot.HAND));

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
    }

    public static boolean isValorDagger(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_SWORD) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName()
                && meta.getDisplayName().equals(VALOR_DAGGER_NAME);
    }

    // ── Damage calculation ────────────────────────────────────────────────────
    public static double calculateDamage(ItemStack item, boolean isCrit) {
        int sharpness = item.getEnchantmentLevel(Enchantment.DAMAGE_ALL);
        double base = isCrit ? CRIT_DAMAGE : BASE_DAMAGE;
        return base + (sharpness * SHARPNESS_BONUS);
    }

    public static boolean isCriticalHit(Player player) {
        return player.getFallDistance() > 0.0f
                && !player.isOnGround()
                && !player.isSprinting()
                && !player.hasPotionEffect(PotionEffectType.BLINDNESS);
    }

    // ── Activation ────────────────────────────────────────────────────────────
    public static void activate(Player player, CustomItemsPlugin plugin) {
        UUID uuid = player.getUniqueId();
        if (cooldowns.containsKey(uuid)) {
            long remaining = COOLDOWN_MS - (System.currentTimeMillis() - cooldowns.get(uuid));
            if (remaining > 0) {
                player.sendMessage("\u00a7c" + (remaining / 1000) + "s remaining on Rally cooldown!");
                return;
            }
        }
        cooldowns.put(uuid, System.currentTimeMillis());

        List<Player> targets = new ArrayList<>();
        targets.add(player);
        for (Entity e : player.getNearbyEntities(BUFF_RADIUS, BUFF_RADIUS, BUFF_RADIUS)) {
            if (e instanceof Player p) targets.add(p);
        }

        for (Player target : targets) applyBuffs(target);
        applyHPBuff(player, plugin);
        spawnRadiusParticles(player, plugin);

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1.2f);
        player.sendMessage("\u00a7f\u00a7lRally! \u00a7fYour allies are inspired!");
    }

    private static void applyBuffs(Player target) {
        PotionEffectType[] types = {
            PotionEffectType.SPEED,
            PotionEffectType.JUMP,
            PotionEffectType.INCREASE_DAMAGE
        };
        for (PotionEffectType type : types) {
            PotionEffect existing = target.getPotionEffect(type);
            int duration;
            int amplifier;
            if (existing != null) {
                long fullMinutes = ((long) existing.getDuration() * 50L) / 60000L;
                duration  = BASE_TICKS + (int)(fullMinutes * 200L);
                amplifier = 1;
            } else {
                duration  = BASE_TICKS;
                amplifier = 0;
            }
            target.addPotionEffect(new PotionEffect(type, duration, amplifier, false, true, true));
        }
    }

    private static void applyHPBuff(Player player, CustomItemsPlugin plugin) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) return;
        double originalMax = attr.getValue();
        double bonus       = originalMax * HP_BONUS_PERCENT;
        double newMax      = originalMax + bonus;
        attr.setBaseValue(newMax);
        player.setHealth(Math.min(newMax, player.getHealth() + bonus));
        hpBonusMap.put(player.getUniqueId(), originalMax);
        tryRefreshRPGHealth(player);
        new BukkitRunnable() {
            @Override public void run() {
                AttributeInstance a = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (a == null) return;
                a.setBaseValue(originalMax);
                if (player.getHealth() > originalMax) player.setHealth(originalMax);
                hpBonusMap.remove(player.getUniqueId());
                tryRefreshRPGHealth(player);
            }
        }.runTaskLater(plugin, BASE_TICKS);
    }

    private static void tryRefreshRPGHealth(Player player) {
        try {
            Object manager = player.getClass().getMethod("getHealthManager").invoke(player);
            Object data    = manager.getClass().getMethod("getData").invoke(manager);
            data.getClass().getMethod("updateDisplay").invoke(data);
        } catch (Exception ignored) {}
    }

    private static void spawnRadiusParticles(Player player, CustomItemsPlugin plugin) {
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (tick >= 40) { cancel(); return; }
                for (int i = 0; i < 20; i++) {
                    double a = (Math.PI * 2.0 / 20) * i + (tick * 0.1);
                    Location loc = player.getLocation().clone()
                            .add(Math.cos(a) * BUFF_RADIUS, 1.0, Math.sin(a) * BUFF_RADIUS);
                    player.getWorld().spawnParticle(Particle.TOTEM, loc, 1, 0, 0, 0, 0);
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    static String fmt(double val) {
        if (val == Math.floor(val)) return String.valueOf((int) val);
        return String.format("%.1f", val);
    }

    public static void cleanup() {
        cooldowns.clear();
        hpBonusMap.clear();
    }
}
