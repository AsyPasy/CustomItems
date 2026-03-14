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

    // Normal shots — display HP ÷ 5 = vanilla HP
    private static final double NORMAL_MIN = 14.0 / 5.0;
    private static final double NORMAL_MID = 25.0 / 5.0;
    private static final double NORMAL_MAX = 36.0 / 5.0;

    // Gaze shots — display HP ÷ 5 = vanilla HP
    private static final double GAZE_MIN   = 28.0 / 5.0;
    private static final double GAZE_MID   = 50.0 / 5.0;
    private static final double GAZE_MAX   = 72.0 / 5.0;

    // Per Power level: +1 display HP = +0.2 vanilla HP
    private static final double POWER_BONUS_PER_LEVEL = 0.2;

    private static final long GAZE_COOLDOWN_MS = 60 * 1000L; // 60 seconds
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
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(BOW_NAME);
        meta.setCustomModelData(293001);
        meta.setUnbreakable(true);
        meta.setLore(List.of(
            "\u00a77Damage: \u00a7a+14 \u00a77(\u00a7a+36\u00a77 max draw)",
            "",
            "\u00a77Scales with draw-back:",
            "\u00a77Min: \u00a7a14 HP  \u00a77Mid: \u00a7a25 HP  \u00a77Full: \u00a7a36 HP",
            "",
            "\u00a75Ability: \u00a76Eagle\u2019s Gaze \u00a7e\u00a7lSNEAK + RIGHT CLICK",
            "\u00a77Mark your next target. For \u00a7610 seconds\u00a77",
            "\u00a77all arrows home in on the marked target.",
            "\u00a77Gaze damage scales: \u00a7a28 / 50 / 72 HP",
            "\u00a7a\u00a7lCooldown: \u00a7260s",
            "",
            "\u00a76\u00a7lLEGENDARY BOW"
        ));
        // Hide vanilla attributes AND enchant names from tooltip
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isEaglesEyeBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName()
                && meta.getDisplayName().equals(BOW_NAME);
    }

    // ── Damage scaling ────────────────────────────────────────────────────────
    private static double scaleDamage(float force, double min, double mid,
                                      double max, int powerLevel) {
        double base;
        if (force < 0.5f) {
            base = min + (mid - min) * (force / 0.5);
        } else {
            base = mid + (max - mid) * ((force - 0.5) / 0.5);
        }
        // +1 display HP (+0.2 vanilla HP) per Power level
        return base + (powerLevel * POWER_BONUS_PER_LEVEL);
    }

    // ── Eagle's Gaze ability ──────────────────────────────────────────────────
    public static void activateGaze(Player player, CustomItemsPlugin plugin) {
        UUID uuid = player.getUniqueId();

        if (gazeCooldowns.containsKey(uuid)) {
            long remaining = GAZE_COOLDOWN_MS - (System.currentTimeMillis() - gazeCooldowns.get(uuid));
            if (remaining > 0) {
                player.sendMessage("\u00a7c" + (remaining / 1000) + "s remaining on Eagle\u2019s Gaze cooldown!");
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
    // bow parameter passed in so we can read Power enchant level
    public static void onArrowShoot(Player player, Arrow arrow, float force,
                                    ItemStack bow, CustomItemsPlugin plugin) {
        UUID uuid = player.getUniqueId();
        int powerLevel = bow.getEnchantmentLevel(Enchantment.ARROW_DAMAGE);

        arrow.setMetadata(META_EAGLES_EYE,
                new FixedMetadataValue(plugin, uuid.toString()));

        // Gaze marking shot
        if (Boolean.TRUE.equals(armed.get(uuid))) {
            armed.remove(uuid);
            arrow.setMetadata(META_GAZE_ARROW,
                    new FixedMetadataValue(plugin, uuid.toString()));
            // Store force + power level for damage calculation on hit
            arrow.setMetadata(META_NORMAL_DAMAGE,
                    new FixedMetadataValue(plugin, encodeForcePower(force, powerLevel)));
            player.sendMessage("\u00a76Gaze arrow fired! Hit a target to mark them.");
            return;
        }

        // Homing shot
        LivingEntity target = markedTargets.get(uuid);
        if (target != null && !target.isDead()
                && System.currentTimeMillis() < markExpiry.getOrDefault(uuid, 0L)) {
            arrow.setMetadata(META_HOMING_ARROW,
                    new FixedMetadataValue(plugin, uuid.toString()));
            arrow.setMetadata(META_NORMAL_DAMAGE,
                    new FixedMetadataValue(plugin, encodeForcePower(force, powerLevel)));
            homingArrows.add(arrow.getUniqueId());
            startHomingTask(arrow, target, plugin);
            return;
        }

        // Normal shot
        double damage = scaleDamage(force, NORMAL_MIN, NORMAL_MID, NORMAL_MAX, powerLevel);
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
            double damage = scaleDamage(force, GAZE_MIN, GAZE_MID, GAZE_MAX, power);
            hit.damage(damage, shooter);

            shooter.sendMessage("\u00a76\u00a7lTarget marked! \u00a7fAll arrows home for 10 seconds.");
            shooter.getWorld().playSound(shooter.getLocation(),
                    Sound.ENTITY_ARROW_HIT, 1f, 0.5f);

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
            double damage = scaleDamage(force, GAZE_MIN, GAZE_MID, GAZE_MAX, power);
            arrow.remove();
            hit.damage(damage, shooter);
            return;
        }

        if (arrow.hasMetadata(META_NORMAL_DAMAGE)) {
            double damage = (double) arrow.getMetadata(META_NORMAL_DAMAGE).get(0).value();
            arrow.remove();
            hit.damage(damage, shooter);
        }
    }

    // ── Encode force + power level into one double for metadata storage ────────
    // format: force (0.0–1.0) + powerLevel (integer) stored as force + powerLevel * 10
    private static double encodeForcePower(float force, int powerLevel) {
        return force + powerLevel * 10.0;
    }

    private static float decodedForce(double encoded) {
        return (float)(encoded % 10.0);
    }

    private static int decodedPower(double encoded) {
        return (int)(encoded / 10.0);
    }

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
                        .subtract(arrow.getLocation().toVector())
                        .normalize();
                Vector newDir = current.normalize().multiply(0.6)
                        .add(toTarget.multiply(0.4))
                        .normalize()
                        .multiply(speed + 0.1);
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
