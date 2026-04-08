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

    // ── Damage constants (display HP; divide by 5 for vanilla HP) ─────────────
    private static final double BASE_NORMAL_MIN = 4.7;
    private static final double BASE_NORMAL_MID = 11.0;
    private static final double BASE_NORMAL_MAX = 16.0;

    // Gaze shots deal exactly 2× the equivalent normal draw damage
    private static final double GAZE_MULTIPLIER = 2.0;

    // +0.5 display HP per Power level on each tier (Power V → +2.5 HP)
    private static final double POWER_BONUS = 0.5;

    // ── Timing constants ───────────────────────────────────────────────────────
    private static final long GAZE_COOLDOWN_MS = 60 * 1000L;
    private static final long MARK_DURATION_MS = 10 * 1000L;
    private static final long ARM_WINDOW_TICKS  = 200L;

    // ── Metadata keys ──────────────────────────────────────────────────────────
    public static final String META_EAGLES_EYE    = "eagles_eye_arrow";
    public static final String META_GAZE_ARROW    = "gaze_arrow";
    public static final String META_HOMING_ARROW  = "homing_arrow";
    // Pre-computed vanilla-HP damage stored on normal arrows
    public static final String META_NORMAL_DAMAGE = "eagles_eye_damage";
    // Raw draw force (float 0-1) and power level (int) stored separately on
    // gaze/homing arrows so damage is computed cleanly on hit — no encode hacks
    public static final String META_ARROW_FORCE   = "eagles_eye_force";
    public static final String META_ARROW_POWER   = "eagles_eye_power";

    // ── State maps ─────────────────────────────────────────────────────────────
    private static final Map<UUID, Long>         gazeCooldowns = new HashMap<>();
    private static final Map<UUID, Boolean>      armed         = new HashMap<>();
    private static final Map<UUID, LivingEntity> markedTargets = new HashMap<>();
    private static final Map<UUID, Long>         markExpiry    = new HashMap<>();
    private static final Set<UUID>               homingArrows  = new HashSet<>();

    // ── Item creation ──────────────────────────────────────────────────────────
    public static ItemStack createEaglesEyeBow() {
        ItemStack item = new ItemStack(Material.BOW);
        applyMeta(item);
        return item;
    }

    public static void applyMeta(ItemStack item) {
        // Read current Power level BEFORE touching meta
        int power = item.getEnchantmentLevel(Enchantment.ARROW_DAMAGE);

        // Per-power tier values (display HP)
        double nMin = BASE_NORMAL_MIN + (power * POWER_BONUS);
        double nMid = BASE_NORMAL_MID + (power * POWER_BONUS);
        double nMax = BASE_NORMAL_MAX + (power * POWER_BONUS);
        double gMin = nMin * GAZE_MULTIPLIER;
        double gMid = nMid * GAZE_MULTIPLIER;
        double gMax = nMax * GAZE_MULTIPLIER;

        String powerNote = power > 0 ? " \u00a77(Power " + power + ")" : "";

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName(BOW_NAME);
        meta.setCustomModelData(293001);
        meta.setUnbreakable(true);
        meta.setLore(List.of(
            // Matches: "Damage: +4.7 (+16 max draw)"
            "\u00a77Damage: \u00a7a+" + ValorDagger.fmt(nMin)
                + " \u00a77(\u00a7a+" + ValorDagger.fmt(nMax) + "\u00a77 max draw)" + powerNote,
            "",
            // Matches: "Scales with draw-back:"
            "\u00a77Scales with draw-back:",
            // Matches: "Min: 4.7 HP  Mid: 11 HP  Full: 16 HP"
            "\u00a77Min: \u00a7a" + ValorDagger.fmt(nMin)
                + " HP  \u00a77Mid: \u00a7a" + ValorDagger.fmt(nMid)
                + " HP  \u00a77Full: \u00a7a" + ValorDagger.fmt(nMax) + " HP",
            "",
            // Matches ability block layout from screenshot
            "\u00a75Ability: \u00a76Eagle\u2019s Gaze \u00a7e\u00a7lSNEAK + RIGHT CLICK",
            "\u00a77Mark your next target. For \u00a7610 seconds\u00a77",
            "\u00a77all arrows home in on the marked target.",
            // Gaze damage = 2× normal, e.g. 9.4 / 22 / 32 HP at base
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

    // ── Damage scaling ─────────────────────────────────────────────────────────
    // Returns vanilla HP (display HP ÷ 5). force is 0.0–1.0.
    private static double scaleDamage(float force, double minDisplay, double midDisplay,
                                      double maxDisplay) {
        double display;
        if (force < 0.5f) {
            display = minDisplay + (midDisplay - minDisplay) * (force / 0.5);
        } else {
            display = midDisplay + (maxDisplay - midDisplay) * ((force - 0.5) / 0.5);
        }
        return display / 5.0;
    }

    // Normal vanilla-HP damage from force + Power level
    private static double normalDamage(float force, int power) {
        double nMin = BASE_NORMAL_MIN + (power * POWER_BONUS);
        double nMid = BASE_NORMAL_MID + (power * POWER_BONUS);
        double nMax = BASE_NORMAL_MAX + (power * POWER_BONUS);
        return scaleDamage(force, nMin, nMid, nMax);
    }

    // Gaze damage is always exactly 2× normal for the same draw force
    private static double gazeDamage(float force, int power) {
        return normalDamage(force, power) * GAZE_MULTIPLIER;
    }

    // ── Eagle's Gaze ability activation (SNEAK + RIGHT CLICK) ─────────────────
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

    // ── Arrow shoot ────────────────────────────────────────────────────────────
    public static void onArrowShoot(Player player, Arrow arrow, float force,
                                    ItemStack bow, CustomItemsPlugin plugin) {
        UUID uuid  = player.getUniqueId();
        int  power = bow.getEnchantmentLevel(Enchantment.ARROW_DAMAGE);

        arrow.setMetadata(META_EAGLES_EYE,
                new FixedMetadataValue(plugin, uuid.toString()));

        // ── Case 1: Gaze-marking shot ─────────────────────────────────────────
        if (Boolean.TRUE.equals(armed.get(uuid))) {
            armed.remove(uuid);
            arrow.setMetadata(META_GAZE_ARROW,
                    new FixedMetadataValue(plugin, uuid.toString()));
            arrow.setMetadata(META_ARROW_FORCE,
                    new FixedMetadataValue(plugin, force));
            arrow.setMetadata(META_ARROW_POWER,
                    new FixedMetadataValue(plugin, power));
            player.sendMessage("\u00a76Gaze arrow fired! Hit a target to mark them.");
            return;
        }

        // ── Case 2: Homing shot (target already marked) ───────────────────────
        LivingEntity target = markedTargets.get(uuid);
        if (target != null && !target.isDead()
                && System.currentTimeMillis() < markExpiry.getOrDefault(uuid, 0L)) {
            arrow.setMetadata(META_HOMING_ARROW,
                    new FixedMetadataValue(plugin, uuid.toString()));
            arrow.setMetadata(META_ARROW_FORCE,
                    new FixedMetadataValue(plugin, force));
            arrow.setMetadata(META_ARROW_POWER,
                    new FixedMetadataValue(plugin, power));
            homingArrows.add(arrow.getUniqueId());
            startHomingTask(arrow, target, plugin);
            return;
        }

        // ── Case 3: Normal scaled shot ────────────────────────────────────────
        double damage = normalDamage(force, power);
        arrow.setMetadata(META_NORMAL_DAMAGE,
                new FixedMetadataValue(plugin, damage));
    }

    // ── Arrow hits entity ──────────────────────────────────────────────────────
    public static void onArrowHitEntity(Arrow arrow, LivingEntity hit,
                                        Player shooter, CustomItemsPlugin plugin) {
        UUID uuid = shooter.getUniqueId();

        // ── Gaze-marking hit ──────────────────────────────────────────────────
        if (arrow.hasMetadata(META_GAZE_ARROW)) {
            arrow.remove();

            float force = (float) arrow.getMetadata(META_ARROW_FORCE).get(0).asFloat();
            int   power = arrow.getMetadata(META_ARROW_POWER).get(0).asInt();

            markedTargets.put(uuid, hit);
            markExpiry.put(uuid, System.currentTimeMillis() + MARK_DURATION_MS);
            gazeCooldowns.put(uuid, System.currentTimeMillis());

            hit.addPotionEffect(new PotionEffect(
                    PotionEffectType.GLOWING,
                    (int)(MARK_DURATION_MS / 50L), 0, false, false));

            hit.damage(gazeDamage(force, power), shooter);

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

        // ── Homing arrow hit ──────────────────────────────────────────────────
        if (arrow.hasMetadata(META_HOMING_ARROW)) {
            homingArrows.remove(arrow.getUniqueId());
            arrow.remove();

            float force = (float) arrow.getMetadata(META_ARROW_FORCE).get(0).asFloat();
            int   power = arrow.getMetadata(META_ARROW_POWER).get(0).asInt();

            hit.damage(gazeDamage(force, power), shooter);
            return;
        }

        // ── Normal arrow hit ──────────────────────────────────────────────────
        if (arrow.hasMetadata(META_NORMAL_DAMAGE)) {
            double damage = arrow.getMetadata(META_NORMAL_DAMAGE).get(0).asDouble();
            arrow.remove();
            hit.damage(damage, shooter);
        }
    }

    // ── Homing task ────────────────────────────────────────────────────────────
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

    // ── Cleanup on plugin disable ──────────────────────────────────────────────
    public static void cleanup() {
        gazeCooldowns.clear();
        armed.clear();
        markedTargets.clear();
        markExpiry.clear();
        homingArrows.clear();
    }
}
