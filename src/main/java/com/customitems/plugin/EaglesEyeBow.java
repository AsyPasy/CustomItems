package com.customitems.plugin;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class EaglesEyeBow {

    public static final String BOW_NAME = "\u00a76\u00a7lEagle\u2019s Eye";

    // Base normal damage (display HP)
    private static final double BASE_NORMAL_MIN = 14.0;
    private static final double BASE_NORMAL_MID = 25.0;
    private static final double BASE_NORMAL_MAX = 36.0;

    // Base gaze damage (display HP)
    private static final double BASE_GAZE_MIN   = 28.0;
    private static final double BASE_GAZE_MID   = 50.0;
    private static final double BASE_GAZE_MAX   = 72.0;

    // +1.0 display HP per Power level, applied to each tier
    // Power 0 → +0 HP  |  Power 5 → +5 HP (e.g. min damage 14→19 at Power V)
    private static final double POWER_BONUS     = 1.0;

    private static final long GAZE_COOLDOWN_MS = 60 * 1000L;
    private static final long MARK_DURATION_MS = 10 * 1000L;
    private static final long ARM_WINDOW_TICKS = 200L;

    public static final String META_EAGLES_EYE    = "eagles_eye_arrow";
    public static final String META_GAZE_ARROW    = "gaze_arrow";
    public static final String META_HOMING_ARROW  = "homing_arrow";
    public static final String META_NORMAL_DAMAGE = "eagles_eye_damage";

    private static final Map<UUID, Long>         gazeCooldowns = new HashMap<>();
    private static final Map<UUID, Boolean>      armed         = new HashMap<>();
    private static final Map<UUID, LivingEntity> markedTargets = new HashMap<>();
    private static final Map<UUID, Long>         markExpiry    = new HashMap<>();
    private static final Set<UUID>               homingArrows  = new HashSet<>();

    // ── Item ──────────────────────────────────────────────────────────────────
    public static ItemStack createEaglesEyeBow() {
        ItemStack item = new ItemStack(Material.BOW);
        applyMeta(item);
        return item;
    }

    public static void applyMeta(ItemStack item) {
        // Read current Power level BEFORE touching meta
        int power = item.getEnchantmentLevel(Enchantment.ARROW_DAMAGE);

        // Calculate display HP values for lore
        double nMin = BASE_NORMAL_MIN + (power * POWER_BONUS);
        double nMid = BASE_NORMAL_MID + (power * POWER_BONUS);
        double nMax = BASE_NORMAL_MAX + (power * POWER_BONUS);
        double gMin = BASE_GAZE_MIN   + (power * POWER_BONUS);
        double gMid = BASE_GAZE_MID   + (power * POWER_BONUS);
        double gMax = BASE_GAZE_MAX   + (power * POWER_BONUS);

        String powerNote = power > 0 ? " \u00a77(Power " + power + ")" : "";

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName(BOW_NAME);
        meta.setCustomModelData(293001);
        meta.setUnbreakable(true);
        meta.setLore(List.of(
            "\u00a77Damage: \u00a7a+" + ValorDagger.fmt(nMin)
                + " \u00a77(\u00a7a+" + ValorDagger.fmt(nMax) + "\u00a77 max draw)" + powerNote,
            "",
            "\u00a77Scales with draw-back:",
            "\u00a77Min: \u00a7a" + ValorDagger.fmt(nMin)
                + " HP  \u00a77Mid: \u00a7a" + ValorDagger.fmt(nMid)
                + " HP  \u00a77Full: \u00a7a" + ValorDagger.fmt(nMax) + " HP",
            "",
            "\u00a75Ability: \u00a76Eagle\u2019s Gaze \u00a7e\u00a7lSNEAK + RIGHT CLICK",
            "\u00a77Mark your next target. For \u00a7610 seconds\u00a77",
            "\u00a77all arrows home in on the marked target.",
            "\u00a77Gaze damage: \u00a7a" + ValorDagger.fmt(gMin)
                + " / " + ValorDagger.fmt(gMid)
                + " / " + ValorDagger.fmt(gMax) + " HP",
            "\u00a7a\u00a7lCooldown: \u00a7260s",
            "",
            "\u00a76\u00a7lLEGENDARY BOW"
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
    }

    public static boolean isEaglesEyeBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName()
                && meta.getDisplayName().equals(BOW_NAME);
    }

    // ── Damage scaling (display HP ÷ 5 = vanilla HP) ──────────────────────────
    private static double scaleDamage(float force, double minDisplay, double midDisplay,
                                      double maxDisplay) {
        double base;
        if (force < 0.5f) {
            base = minDisplay + (midDisplay - minDisplay) * (force / 0.5);
        } else {
            base = midDisplay + (maxDisplay - midDisplay) * ((force - 0.5) / 0.5);
        }
        return base / 5.0; // convert display HP to vanilla HP
    }

    // ── Eagle's Gaze ability ──────────────────────────────────────────────────
    public static void activateGaze(Player player, CustomItemsPlugin plugin) {
        UUID uuid = player.getUniqueId();
        if (gazeCooldowns.containsKey(uuid)) {
            long remaining = GAZE_COOLDOWN_MS - (System.currentTimeMillis() - gazeCooldowns.get(uuid));
            if (remaining > 0) {
                player.sendMessage("\u00a7c" + (remaining / 1000) + "s on Eagle\u2019s Gaze cooldown!");
                return;
            }
        }
        armed.put(uuid, true);
        player.sendMessage("\u00a76\u00a7lEagle\u2019s Gaze \u00a7farmed! Shoot a target to mark them.");
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 2f);
        new BukkitRunnable() {
            @Override public void run() {
                if (Boolean.TRUE.equals(armed.get(uuid))) {
                    armed.remove(uuid);
                    player.sendMessage("\u00a7cEagle\u2019s Gaze faded \u2014 no target was marked.");
                }
            }
        }.runTaskLater(plugin, ARM_WINDOW_TICKS);
    }

    // ── Arrow shoot ───────────────────────────────────────────────────────────
    public static void onArrowShoot(Player player, Arrow arrow, float force,
                                    ItemStack bow, CustomItemsPlugin plugin) {
        UUID uuid = player.getUniqueId();
        int power = bow.getEnchantmentLevel(Enchantment.ARROW_DAMAGE);

        arrow.setMetadata(META_EAGLES_EYE,
                new FixedMetadataValue(plugin, uuid.toString()));

        // Gaze marking shot
        if (Boolean.TRUE.equals(armed.get(uuid))) {
            armed.remove(uuid);
            arrow.setMetadata(META_GAZE_ARROW,
                    new FixedMetadataValue(plugin, uuid.toString()));
            arrow.setMetadata(META_NORMAL_DAMAGE,
                    new FixedMetadataValue(plugin, encodeForcePower(force, power)));
            player.sendMessage("\u00a76Gaze arrow fired! Hit a target to mark them.");
            return;
        }

        // Homing shot — target already marked
        LivingEntity target = markedTargets.get(uuid);
        if (target != null && !target.isDead()
                && System.currentTimeMillis() < markExpiry.getOrDefault(uuid, 0L)) {
            arrow.setMetadata(META_HOMING_ARROW,
                    new FixedMetadataValue(plugin, uuid.toString()));
            arrow.setMetadata(META_NORMAL_DAMAGE,
                    new FixedMetadataValue(plugin, encodeForcePower(force, power)));
            homingArrows.add(arrow.getUniqueId());
            startHomingTask(arrow, target, plugin);
            return;
        }

        // Normal scaled shot
        double nMin = BASE_NORMAL_MIN + (power * POWER_BONUS);
        double nMid = BASE_NORMAL_MID + (power * POWER_BONUS);
        double nMax = BASE_NORMAL_MAX + (power * POWER_BONUS);
        double damage = scaleDamage(force, nMin, nMid, nMax);
        arrow.setMetadata(META_NORMAL_DAMAGE,
                new FixedMetadataValue(plugin, damage));
    }

    // ── Arrow hits entity ─────────────────────────────────────────────────────
    public static void onArrowHitEntity(Arrow arrow, LivingEntity hit,
                                        Player shooter, CustomItemsPlugin plugin) {
        UUID uuid = shooter.getUniqueId();

        if (arrow.hasMetadata(META_GAZE_ARROW)) {
            arrow.remove();
            markedTargets.put(uuid, hit);
            markExpiry.put(uuid, System.currentTimeMillis() + MARK_DURATION_MS);
            gazeCooldowns.put(uuid, System.currentTimeMillis());
            hit.addPotionEffect(new PotionEffect(
                    PotionEffectType.GLOWING,
                    (int)(MARK_DURATION_MS / 50L), 0, false, false));

            double stored = (double) arrow.getMetadata(META_NORMAL_DAMAGE).get(0).value();
            float  force  = decodedForce(stored);
            int    power  = decodedPower(stored);
            double gMin = BASE_GAZE_MIN + (power * POWER_BONUS);
            double gMid = BASE_GAZE_MID + (power * POWER_BONUS);
            double gMax = BASE_GAZE_MAX + (power * POWER_BONUS);
            hit.damage(scaleDamage(force, gMin, gMid, gMax), shooter);

            shooter.sendMessage("\u00a76\u00a7lTarget marked! \u00a7fAll arrows home for 10 seconds.");
            shooter.getWorld().playSound(shooter.getLocation(), Sound.ENTITY_ARROW_HIT, 1f, 0.5f);

            new BukkitRunnable() {
                @Override public void run() {
                    markedTargets.remove(uuid);
                    markExpiry.remove(uuid);
                    if (!hit.isDead()) hit.removePotionEffect(PotionEffectType.GLOWING);
                    shooter.sendMessage("\u00a7cEagle\u2019s Gaze mark expired.");
                }
            }.runTaskLater(plugin, MARK_DURATION_MS / 50L);
            return;
        }

        if (arrow.hasMetadata(META_HOMING_ARROW)) {
            homingArrows.remove(arrow.getUniqueId());
            double stored = (double) arrow.getMetadata(META_NORMAL_DAMAGE).get(0).value();
            float  force  = decodedForce(stored);
            int    power  = decodedPower(stored);
            double gMin = BASE_GAZE_MIN + (power * POWER_BONUS);
            double gMid = BASE_GAZE_MID + (power * POWER_BONUS);
            double gMax = BASE_GAZE_MAX + (power * POWER_BONUS);
            arrow.remove();
            hit.damage(scaleDamage(force, gMin, gMid, gMax), shooter);
            return;
        }

        if (arrow.hasMetadata(META_NORMAL_DAMAGE)) {
            double damage = (double) arrow.getMetadata(META_NORMAL_DAMAGE).get(0).value();
            arrow.remove();
            hit.damage(damage, shooter);
        }
    }

    // ── Encode/decode force + power ───────────────────────────────────────────
    private static double encodeForcePower(float force, int power) { return force + power * 10.0; }
    private static float  decodedForce(double encoded) { return (float)(encoded % 10.0); }
    private static int    decodedPower(double encoded)  { return (int)(encoded / 10.0); }

    // ── Homing task ───────────────────────────────────────────────────────────
    private static void startHomingTask(Arrow arrow, LivingEntity target,
                                        CustomItemsPlugin plugin) {
        new BukkitRunnable() {
            @Override public void run() {
                if (arrow.isDead() || !arrow.isValid()
                        || target.isDead() || !target.isValid()) {
                    homingArrows.remove(arrow.getUniqueId());
                    cancel();
                    return;
                }
                Vector current  = arrow.getVelocity();
                double speed    = current.length();
                Vector toTarget = target.getLocation().add(0, 1, 0).toVector()
                        .subtract(arrow.getLocation().toVector()).normalize();
                Vector newDir = current.normalize().multiply(0.6)
                        .add(toTarget.multiply(0.4)).normalize().multiply(speed + 0.1);
                arrow.setVelocity(newDir);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public static void cleanup() {
        gazeCooldowns.clear();
        armed.clear();
        markedTargets.clear();
        markExpiry.clear();
        homingArrows.clear();
    }
}
