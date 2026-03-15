package com.customitems.plugin;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class EagleBoss {

    public static final String META_EAGLE_BOSS    = "eagle_boss";
    public static final String META_EAGLE_FEATHER = "eagle_feather";

    // 500 display HP = 100 vanilla HP
    private static final double MAX_HP_DISPLAY = 500.0;
    private static final double MAX_HP_VANILLA = MAX_HP_DISPLAY / 5.0;

    private final CustomItemsPlugin plugin;
    private final Phantom phantom;
    private boolean alive = false;
    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private enum State { IDLE, CHARGING, WIND_BURST, SLICER, ASCENDING }
    private State state = State.IDLE;

    // Cooldowns in ticks — set randomly on construction
    private int ticksUntilWindBurst;
    private int ticksUntilSlicer;
    private int idleTicks = 0;

    public EagleBoss(CustomItemsPlugin plugin, Location spawnLoc) {
        this.plugin = plugin;
        this.phantom = spawnPhantom(spawnLoc);
        ticksUntilWindBurst = (10 + random.nextInt(21)) * 20;
        ticksUntilSlicer    = (30 + random.nextInt(11)) * 20;
        startTasks();
        broadcastNearby(spawnLoc, 100,
            "\u00a74\u00a7l\u26a0 Eagle's Baby Boss has descended upon you! \u26a0");
        spawnLoc.getWorld().playSound(spawnLoc,
            Sound.ENTITY_PHANTOM_AMBIENT, 2f, 2f);
    }

    // ── Spawn ─────────────────────────────────────────────────────────────────
    private Phantom spawnPhantom(Location loc) {
        Phantom p = (Phantom) loc.getWorld().spawnEntity(loc, EntityType.PHANTOM);
        p.setMetadata(META_EAGLE_BOSS, new FixedMetadataValue(plugin, true));
        p.setCustomName("\u00a76\u00a7lEagle's Baby Boss");
        p.setCustomNameVisible(true);
        p.setAI(false);
        p.setGravity(false);
        p.setSize(3);

        AttributeInstance maxHp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHp != null) maxHp.setBaseValue(MAX_HP_VANILLA);
        p.setHealth(MAX_HP_VANILLA);

        alive = true;
        return p;
    }

    // ── Main task ─────────────────────────────────────────────────────────────
    private void startTasks() {
        tasks.add(new BukkitRunnable() {
            @Override public void run() {
                if (!alive || phantom == null || phantom.isDead()) {
                    cancel();
                    return;
                }
                updateName();
                if (state == State.IDLE) mainTick();
            }
        }.runTaskTimer(plugin, 1L, 1L));
    }

    private void mainTick() {
        Player target = getNearestPlayer(100);
        if (target == null) return;

        if (ticksUntilWindBurst > 0) ticksUntilWindBurst--;
        if (ticksUntilSlicer    > 0) ticksUntilSlicer--;

        // Slicer takes priority over wind burst
        if (ticksUntilSlicer <= 0) {
            ticksUntilSlicer = (30 + random.nextInt(11)) * 20;
            performSlicer(target);
            return;
        }

        if (ticksUntilWindBurst <= 0) {
            ticksUntilWindBurst = (10 + random.nextInt(21)) * 20;
            performWindBurst(target);
            return;
        }

        // Default attack — talon charge with a 2s idle gap between charges
        idleTicks++;
        if (idleTicks >= 40) {
            idleTicks = 0;
            performTalonCharge(target);
        }
    }

    // ── Talon Charge ──────────────────────────────────────────────────────────
    // Boss teleports 50 blocks above the player, then dives with simulated
    // 9.8 m/s² freefall. Damage = 15 display HP × seconds of fall.
    // Partial horizontal tracking (10% correction per tick, capped 0.5 b/t)
    // so the player can dodge by moving quickly.
    private void performTalonCharge(final Player target) {
        state = State.CHARGING;

        Location above = target.getLocation().clone().add(0, 50, 0);
        phantom.teleport(above);
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 1.5f, 2f);

        new BukkitRunnable() {
            double velocityY  = 0.0;
            int    ticksFall  = 0;
            boolean done      = false;

            @Override public void run() {
                if (!alive || phantom.isDead() || done) {
                    if (!done) { done = true; state = State.IDLE; }
                    cancel();
                    return;
                }

                Location pLoc = phantom.getLocation();
                Location tLoc = target.getLocation();

                // Gradual horizontal correction toward player (dodgeable)
                double dx = Math.max(-0.5, Math.min(0.5,
                        (tLoc.getX() - pLoc.getX()) * 0.1));
                double dz = Math.max(-0.5, Math.min(0.5,
                        (tLoc.getZ() - pLoc.getZ()) * 0.1));

                // Freefall: add 9.8/20 per tick downward
                velocityY -= 9.8 / 20.0;
                ticksFall++;

                Location newLoc = pLoc.clone().add(dx, velocityY, dz);

                // Face toward player
                Vector toPlayer = tLoc.toVector().subtract(newLoc.toVector());
                if (toPlayer.length() > 0) newLoc.setDirection(toPlayer);

                phantom.teleport(newLoc);
                phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    pLoc, 3, 0.3, 0.3, 0.3, 0);

                // Hit check
                if (phantom.getLocation().distance(tLoc) < 2.5) {
                    done = true;
                    double secs       = ticksFall / 20.0;
                    double displayDmg = 15.0 * secs;
                    target.damage(displayDmg / 5.0, phantom);
                    target.sendMessage("\u00a74\u00a7lEagle's talons struck you for \u00a7c"
                        + String.format("%.0f", displayDmg) + " HP\u00a74\u00a7l!");
                    phantom.getWorld().playSound(phantom.getLocation(),
                        Sound.ENTITY_PHANTOM_BITE, 1.5f, 1f);
                    phantom.getWorld().spawnParticle(Particle.CRIT,
                        tLoc.add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
                    ascend();
                    cancel();
                    return;
                }

                // Missed — fell past player
                if (newLoc.getY() < tLoc.getY() - 5) {
                    done = true;
                    ascend();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L));
    }

    // ── Wind Burst ────────────────────────────────────────────────────────────
    // Boss hovers 15 blocks above player and shoots 10-30 feather arrows
    // at the player's CURRENT position (not predicted) with 1-tick interval.
    // Each feather deals 10 display HP (2 vanilla HP).
    private void performWindBurst(final Player target) {
        state = State.WIND_BURST;

        // Float above player
        Location airPos = target.getLocation().clone().add(0, 15, 0);
        phantom.teleport(airPos);

        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 1.5f, 2f);
        broadcastNearby(phantom.getLocation(), 100,
            "\u00a75\u00a7lWind Burst!");

        int featherCount = 10 + random.nextInt(21);

        new BukkitRunnable() {
            int fired = 0;
            @Override public void run() {
                if (!alive || phantom.isDead()) {
                    cancel();
                    state = State.IDLE;
                    return;
                }
                if (fired >= featherCount) {
                    cancel();
                    state = State.IDLE;
                    return;
                }

                // Shoot at player's current position — not predicted
                Location from = phantom.getEyeLocation();
                Location to   = target.getEyeLocation();
                Vector dir = to.toVector()
                        .subtract(from.toVector())
                        .normalize()
                        .multiply(1.5);

                Arrow arrow = phantom.getWorld().spawn(from, Arrow.class);
                arrow.setVelocity(dir);
                arrow.setDamage(0);
                arrow.setShooter(phantom);
                arrow.setGlowing(true);
                arrow.setFireTicks(0);
                arrow.setMetadata(META_EAGLE_FEATHER,
                    new FixedMetadataValue(plugin, true));

                // Auto-remove after 3 seconds if it doesn't hit anything
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!arrow.isDead()) arrow.remove();
                }, 60L);

                phantom.getWorld().spawnParticle(Particle.CLOUD,
                    from, 2, 0.1, 0.1, 0.1, 0.05);

                fired++;
            }
        }.runTaskTimer(plugin, 0L, 1L)); // 1 tick = 0.05 seconds
    }

    // ── Slicer ────────────────────────────────────────────────────────────────
    // Teleports to front of player → slashes (30 HP)
    // Then behind → slashes (30 HP)
    // Then front again → slashes (30 HP)
    // Then immediately ascends.
    // Only dodgeable via ender pearls / instant teleportation.
    private void performSlicer(final Player target) {
        state = State.SLICER;

        broadcastNearby(phantom.getLocation(), 100,
            "\u00a7c\u00a7lSlicer!");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_BITE, 1.5f, 0.8f);

        // Phase 1: front slash — immediate
        slash(target, true);

        // Phase 2: back slash — 10 ticks (0.5s) later
        new BukkitRunnable() {
            @Override public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                slash(target, false);
            }
        }.runTaskLater(plugin, 10L);

        // Phase 3: front slash — 20 ticks (1.0s) later, then ascend
        new BukkitRunnable() {
            @Override public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                slash(target, true);
                new BukkitRunnable() {
                    @Override public void run() {
                        if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                        ascend();
                    }
                }.runTaskLater(plugin, 5L);
            }
        }.runTaskLater(plugin, 20L);
    }

    private void slash(Player target, boolean front) {
        if (!alive || phantom.isDead()) return;

        Location tLoc = target.getLocation();
        Vector dir = tLoc.getDirection().clone().normalize();
        if (dir.length() < 0.01) dir = new Vector(1, 0, 0);

        // Front = in front of player, back = behind player (from player's perspective)
        Location slashLoc = front
            ? tLoc.clone().add(dir.clone().multiply(3))
            : tLoc.clone().add(dir.clone().multiply(-3));
        slashLoc.setY(tLoc.getY());

        phantom.teleport(slashLoc);

        // 30 display HP = 6.0 vanilla HP per slash
        if (phantom.getLocation().distance(tLoc) < 4.0) {
            target.damage(6.0, phantom);
            target.sendMessage("\u00a7c\u00a7lSliced for \u00a7430 HP\u00a7c\u00a7l!");
        }

        phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
            phantom.getLocation().clone().add(0, 1, 0), 15, 1, 0.5, 1, 0.1);
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 1.2f);
    }

    // ── Ascend ────────────────────────────────────────────────────────────────
    private void ascend() {
        state = State.ASCENDING;
        Player target = getNearestPlayer(100);
        double targetY = (target != null ? target.getLocation().getY() : phantom.getLocation().getY()) + 20;

        new BukkitRunnable() {
            @Override public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }
                Location loc = phantom.getLocation();
                if (loc.getY() >= targetY) { state = State.IDLE; cancel(); return; }
                phantom.teleport(loc.clone().add(0, 1.5, 0));
            }
        }.runTaskTimer(plugin, 1L, 1L));
    }

    // ── Death ─────────────────────────────────────────────────────────────────
    public void die() {
        alive = false;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (phantom != null && !phantom.isDead()) {
            phantom.getWorld().playSound(phantom.getLocation(),
                Sound.ENTITY_PHANTOM_DEATH, 2f, 1.5f);
            phantom.getWorld().spawnParticle(Particle.EXPLOSION,
                phantom.getLocation(), 5, 1, 1, 1, 0);
        }
    }

    public void cleanup() {
        alive = false;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void updateName() {
        int current = (int) Math.ceil(phantom.getHealth() * 5);
        phantom.setCustomName(String.format(
            "\u00a76\u00a7lEagle's Baby Boss \u00a7e[%d/%d HP]",
            current, (int) MAX_HP_DISPLAY));
    }

    private Player getNearestPlayer(double radius) {
        Player nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Entity e : phantom.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof Player p) || p.isDead()) continue;
            double d = e.getLocation().distanceSquared(phantom.getLocation());
            if (d < minDist) { minDist = d; nearest = p; }
        }
        return nearest;
    }

    private void broadcastNearby(Location loc, double radius, String msg) {
        for (Entity e : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (e instanceof Player p) p.sendMessage(msg);
        }
    }

    public Phantom getPhantom() { return phantom; }
    public boolean isAlive()    { return alive; }
}
