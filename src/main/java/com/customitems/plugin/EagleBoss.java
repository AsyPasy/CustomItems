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

    private static final double MAX_HP_DISPLAY = 5120.0; // 1024 vanilla HP × 5 — Spigot's hard cap
    private static final double MAX_HP_VANILLA = 1024.0; // maximum allowed by Spigot 1.20.x
    
    private final CustomItemsPlugin plugin;
    private final Phantom phantom;
    private boolean alive = false;
    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private enum State { IDLE, CHARGING, WIND_BURST, SLICER, ASCENDING }
    private State state = State.IDLE;

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
            "\u00a74\u00a7l\u26a0 Eagle's Baby Boss has descended! \u26a0");
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

    // ── Tasks ─────────────────────────────────────────────────────────────────
    private void startTasks() {
        BukkitTask t = new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom == null || phantom.isDead()) {
                    cancel();
                    return;
                }
                updateName();
                if (state == State.IDLE) {
                    mainTick();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        tasks.add(t);
    }

    // ── Main tick ─────────────────────────────────────────────────────────────
    private void mainTick() {
        Player target = getNearestPlayer(100);
        if (target == null) return;

        if (ticksUntilWindBurst > 0) ticksUntilWindBurst--;
        if (ticksUntilSlicer > 0)    ticksUntilSlicer--;

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
        idleTicks++;
        if (idleTicks >= 40) {
            idleTicks = 0;
            performTalonCharge(target);
        }
    }

    // ── Talon Charge ──────────────────────────────────────────────────────────
    private void performTalonCharge(final Player target) {
        state = State.CHARGING;
        phantom.teleport(target.getLocation().clone().add(0, 50, 0));
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 1.5f, 2f);

        new BukkitRunnable() {
            double  velocityY = 0.0;
            int     ticksFall = 0;
            boolean done      = false;

            @Override
            public void run() {
                if (!alive || phantom.isDead() || done) {
                    if (!done) {
                        done = true;
                        state = State.IDLE;
                    }
                    cancel();
                    return;
                }

                Location pLoc = phantom.getLocation();
                Location tLoc = target.getLocation();

                double dx = Math.max(-0.5, Math.min(0.5, (tLoc.getX() - pLoc.getX()) * 0.1));
                double dz = Math.max(-0.5, Math.min(0.5, (tLoc.getZ() - pLoc.getZ()) * 0.1));
                velocityY -= 9.8 / 20.0;
                ticksFall++;

                Location newLoc = pLoc.clone().add(dx, velocityY, dz);
                Vector toPlayer = tLoc.toVector().subtract(newLoc.toVector());
                if (toPlayer.length() > 0) newLoc.setDirection(toPlayer);
                phantom.teleport(newLoc);
                phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    pLoc, 3, 0.3, 0.3, 0.3, 0);

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
                        tLoc.clone().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
                    ascend();
                    cancel();
                    return;
                }

                if (newLoc.getY() < tLoc.getY() - 5) {
                    done = true;
                    ascend();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Wind Burst ────────────────────────────────────────────────────────────
    private void performWindBurst(final Player target) {
        state = State.WIND_BURST;
        phantom.teleport(target.getLocation().clone().add(0, 15, 0));
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 1.5f, 2f);
        broadcastNearby(phantom.getLocation(), 100, "\u00a75\u00a7lWind Burst!");

        final int featherCount = 10 + random.nextInt(21);

        new BukkitRunnable() {
            int fired = 0;

            @Override
            public void run() {
                if (!alive || phantom.isDead()) {
                    state = State.IDLE;
                    cancel();
                    return;
                }
                if (fired >= featherCount) {
                    state = State.IDLE;
                    cancel();
                    return;
                }

                Location from = phantom.getEyeLocation();
                Vector dir = target.getEyeLocation().toVector()
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

                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!arrow.isDead()) arrow.remove();
                }, 60L);

                phantom.getWorld().spawnParticle(Particle.CLOUD,
                    from, 2, 0.1, 0.1, 0.1, 0.05);
                fired++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Slicer ────────────────────────────────────────────────────────────────
    private void performSlicer(final Player target) {
        state = State.SLICER;
        broadcastNearby(phantom.getLocation(), 100, "\u00a7c\u00a7lSlicer!");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_BITE, 1.5f, 0.8f);

        slash(target, true);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                slash(target, false);
            }
        }.runTaskLater(plugin, 10L);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                slash(target, true);
                new BukkitRunnable() {
                    @Override
                    public void run() {
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

        Location slashLoc = tLoc.clone().add(dir.multiply(front ? 3 : -3));
        slashLoc.setY(tLoc.getY());
        phantom.teleport(slashLoc);

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
        final double targetY = (target != null
            ? target.getLocation().getY()
            : phantom.getLocation().getY()) + 20;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead()) {
                    state = State.IDLE;
                    cancel();
                    return;
                }
                Location loc = phantom.getLocation();
                if (loc.getY() >= targetY) {
                    state = State.IDLE;
                    cancel();
                    return;
                }
                phantom.teleport(loc.clone().add(0, 1.5, 0));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ── Death ─────────────────────────────────────────────────────────────────
    public void die() {
        alive = false;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (phantom != null && !phantom.isDead()) {
            phantom.getWorld().playSound(phantom.getLocation(),
                Sound.ENTITY_PHANTOM_DEATH, 2f, 1.5f);
            phantom.getWorld().spawnParticle(Particle.EXPLOSION_LARGE,
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
        double minDist  = Double.MAX_VALUE;
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
