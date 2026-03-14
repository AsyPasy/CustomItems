package com.customitems.plugin;

import org.bukkit.*;
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

    public static final String BOW_NAME = "\u00a79\u00a7lEagle\u2019s Eye";

    // RPG HP → vanilla: divide by 5 (20 hearts = 100 HP → 1 vanilla = 5 display)
    private static final double MIN_DAMAGE  = 14.0 / 5.0;
    private static final double MID_DAMAGE  = 25.0 / 5.0;
    private static final double MAX_DAMAGE  = 36.0 / 5.0;
    private static final double GAZE_DAMAGE = 72.0 / 5.0;

    private static final long GAZE_COOLDOWN_MS = 20 * 1000L;
    private static final long MARK_DURATION_MS = 10 * 1000L;
    private static final long ARM_WINDOW_TICKS = 200L; // 10 seconds

    public static final String META_GAZE_ARROW   = "gaze_arrow";
    public static final String META_HOMING_ARROW = "homing_arrow";
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
            "\u00a77Gear Score: \u00a7e420",
            "\u00a77Damage: \u00a7a+14 \u00a77(\u00a7a+36\u00a77 max draw)",
            "\u00a77Ferocity: \u00a7c+20",
            "\u00a77Intelligence: \u00a75+80",
            "",
            "\u00a77Scales with draw-back:",
            "\u00a77Min: \u00a7a14 HP  \u00a77Mid: \u00a7a25 HP  \u00a77Full: \u00a7a36 HP",
            "",
            "\u00a75Ability: \u00a79Eagle\u2019s Gaze \u00a7e\u00a7lSNEAK + RIGHT CLICK",
            "\u00a77Mark your next target. For \u00a7610 seconds\u00a77",
            "\u00a77all arrows home in and deal \u00a7a+100%\u00a77 damage.",
            "\u00a7a\u00a7lCooldown: \u00a7220s",
            "",
            "\u00a77This item can be reforged!",
            "\u00a79\u00a7lLEGENDARY BOW"
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
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
        player.sendMessage("\u00a79\u00a7lEagle\u2019s Gaze \u00a7farmed! Shoot a target to mark them.");
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 2f);

        // Disarm if player never shoots within 10s
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
    public static void onArrowShoot(Player player, Arrow arrow,
                                    float force, CustomItemsPlugin plugin) {
        UUID uuid = player.getUniqueId();

        // Gaze marking shot
        if (Boolean.TRUE.equals(armed.get(uuid))) {
            armed.remove(uuid);
            arrow.setMetadata(META_GAZE_ARROW,
                    new FixedMetadataValue(plugin, uuid.toString()));
            player.sendMessage("\u00a79Gaze arrow fired! Hit a target to mark them.");
            return;
        }

        // Homing shot — target is already marked
        LivingEntity target = markedTargets.get(uuid);
        if (target != null && !target.isDead()
                && System.currentTimeMillis() < markExpiry.getOrDefault(uuid, 0L)) {
            arrow.setMetadata(META_HOMING_ARROW,
                    new FixedMetadataValue(plugin, uuid.toString()));
            homingArrows.add(arrow.getUniqueId());
            startHomingTask(arrow, target, plugin);
            return;
        }

        // Normal scaled shot
        double damage;
        if (force < 0.5f) {
            damage = MIN_DAMAGE + (MID_DAMAGE - MIN_DAMAGE) * (force / 0.5);
        } else {
            damage = MID_DAMAGE + (MAX_DAMAGE - MID_DAMAGE) * ((force - 0.5) / 0.5);
        }
        arrow.setMetadata(META_NORMAL_DAMAGE,
                new FixedMetadataValue(plugin, damage));
    }

    // ── Arrow hit entity ──────────────────────────────────────────────────────
    public static void onArrowHitEntity(Arrow arrow, LivingEntity hit,
                                        Player shooter, CustomItemsPlugin plugin) {
        UUID uuid = shooter.getUniqueId();

        // Gaze marking arrow landed
        if (arrow.hasMetadata(META_GAZE_ARROW)) {
            arrow.remove();
            markedTargets.put(uuid, hit);
            markExpiry.put(uuid, System.currentTimeMillis() + MARK_DURATION_MS);
            gazeCooldowns.put(uuid, System.currentTimeMillis());

            hit.addPotionEffect(new PotionEffect(
                    PotionEffectType.GLOWING,
                    (int)(MARK_DURATION_MS / 50L), 0, false, false));
            hit.damage(GAZE_DAMAGE, shooter);

            shooter.sendMessage("\u00a79\u00a7lTarget marked! \u00a7fAll arrows home for 10 seconds.");
            shooter.getWorld().playSound(shooter.getLocation(),
                    Sound.ENTITY_ARROW_HIT, 1f, 0.5f);

            // Expire the mark after 10s
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

        // Homing arrow landed
        if (arrow.hasMetadata(META_HOMING_ARROW)) {
            homingArrows.remove(arrow.getUniqueId());
            arrow.remove();
            hit.damage(GAZE_DAMAGE, shooter);
            return;
        }

        // Normal arrow landed
        if (arrow.hasMetadata(META_NORMAL_DAMAGE)) {
            double dmg = (double) arrow.getMetadata(META_NORMAL_DAMAGE).get(0).value();
            arrow.remove();
            hit.damage(dmg, shooter);
        }
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
