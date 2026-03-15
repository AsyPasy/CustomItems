package com.customitems.plugin;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class EagleBoss {

    public static final String META_EAGLE_BOSS    = "eagle_boss";
    public static final String META_EAGLE_FEATHER = "eagle_feather";

    // 1250 vanilla HP = 6250 display HP — tracked virtually
    private static final double VIRTUAL_HP_MAX = 6250.0;
    // Spigot hard cap — phantom's real HP is locked here always
    private static final double VANILLA_HP_CAP  = 1024.0;

    // Movement speeds (blocks per tick, 20 ticks = 1 second)
    private static final double ASCEND_SPEED  = 1.0;  // 20 blocks/s upward
    private static final double DIVE_SPEED    = 0.5;  // 10 blocks/s dive
    private static final double PATROL_SPEED  = 0.3;  // 6 blocks/s general movement

    private final CustomItemsPlugin plugin;
    private final Phantom phantom;
    private boolean alive = false;
    private double virtualHP = VIRTUAL_HP_MAX;
    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final BossBar bossBar;

    private enum State { IDLE, ASCENDING_FOR_CHARGE, DIVING, WIND_BURST, SLICER, ASCENDING }
    private State state = State.IDLE;

    private int ticksUntilWindBurst;
    private int ticksUntilSlicer;
    private int idleTicks = 0;

    public EagleBoss(CustomItemsPlugin plugin, Location spawnLoc) {
        this.plugin = plugin;

        bossBar = Bukkit.createBossBar(
            "\u00a76\u00a7lEagle's Baby Boss",
            BarColor.YELLOW,
            BarStyle.SEGMENTED_10
        );

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
        p.setRemoveWhenFarAway(false);

        AttributeInstance maxHp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHp != null) maxHp.setBaseValue(VANILLA_HP_CAP);
        p.setHealth(VANILLA_HP_CAP);

        p.addPotionEffect(new PotionEffect(
            PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 255, false, false));

        alive = true;
        return p;
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────
    private void startTasks() {
        BukkitTask main = new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom == null || phantom.isDead()) {
                    cancel();
                    return;
                }
                // Suppress fire every tick
                phantom.setFireTicks(0);
                phantom.setVisualFire(false);
                // Keep vanilla HP at cap so Bukkit never kills it
                if (phantom.getHealth() < VANILLA_HP_CAP) {
                    phantom.setHealth(VANILLA_HP_CAP);
                }
                updateBossBar();
                updateBossBarPlayers();

                if (state == State.IDLE) {
                    mainTick();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        tasks.add(main);
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────
    private void updateBossBar() {
        double progress = Math.max(0.0, Math.min(1.0, virtualHP / VIRTUAL_HP_MAX));
        bossBar.setProgress(progress);
        bossBar.setTitle(String.format(
            "\u00a76\u00a7lEagle's Baby Boss \u00a7e%d\u00a77/\u00a7e%d HP",
            (int) Math.ceil(virtualHP), (int) VIRTUAL_HP_MAX));
        if (progress > 0.5)       bossBar.setColor(BarColor.YELLOW);
        else if (progress > 0.25) bossBar.setColor(BarColor.RED);
        else                      bossBar.setColor(BarColor.PURPLE);
    }

    private void updateBossBarPlayers() {
        List<Player> nearby = getPlayersInRange(100);
        Set<Player> current = new HashSet<>(bossBar.getPlayers());
        for (Player p : nearby)  if (!current.contains(p)) bossBar.addPlayer(p);
        for (Player p : current) if (!nearby.contains(p))  bossBar.removePlayer(p);
    }

    // ── Virtual damage ────────────────────────────────────────────────────────
    public boolean applyVirtualDamage(double vanillaDamage) {
        if (!alive) return false;
        virtualHP -= vanillaDamage * 5.0;
        if (virtualHP <= 0) { virtualHP = 0; return true; }
        return false;
    }

    public double getVirtualHP() { return virtualHP; }

    // ── Main tick ─────────────────────────────────────────────────────────────
    private void mainTick() {
        Player target = getNearestPlayer(300);
        if (target == null) return;

        if (ticksUntilWindBurst > 0) ticksUntilWindBurst--;
        if (ticksUntilSlicer    > 0) ticksUntilSlicer--;

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
            startTalonCharge(target);
        }
    }

    // ── Talon Charge — Phase 1: Fly up 50 blocks above player ─────────────────
    private void startTalonCharge(final Player target) {
        state = State.ASCENDING_FOR_CHARGE;
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 1.5f, 2f);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }

                Location cur  = phantom.getLocation();
                Location dest = target.getLocation().clone().add(0, 50, 0);

                // Move horizontally toward above-player XZ, and upward
                double dx = clamp((dest.getX() - cur.getX()) * 0.15, ASCEND_SPEED);
                double dy = clamp((dest.getY() - cur.getY()) * 0.15, ASCEND_SPEED * 2);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.15, ASCEND_SPEED);

                movePhantom(cur, dx, dy, dz, target.getLocation());

                // Reached target height — begin dive
                if (cur.distance(dest) < 3.0) {
                    cancel();
                    startTalonDive(target);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Talon Charge — Phase 2: Dive at 10 blocks/s ───────────────────────────
    // Damage = 15 display HP × seconds in dive (3 vanilla HP/s)
    private void startTalonDive(final Player target) {
        state = State.DIVING;

        new BukkitRunnable() {
            int ticksFall = 0;

            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }

                ticksFall++;
                Location cur  = phantom.getLocation();
                Location tLoc = target.getLocation();

                // Move directly toward player at DIVE_SPEED blocks/tick
                Vector toTarget = tLoc.toVector().subtract(cur.toVector());
                double dist = toTarget.length();

                if (dist < 2.5) {
                    // Direct hit
                    double seconds    = ticksFall / 20.0;
                    double displayDmg = 15.0 * seconds;         // 15 display HP per second
                    double vanillaDmg = displayDmg / 5.0;       // convert to vanilla
                    target.damage(vanillaDmg, phantom);
                    target.sendMessage("\u00a74\u00a7lEagle's talons struck you for \u00a7c"
                        + String.format("%.0f", displayDmg) + " HP\u00a74\u00a7l!");
                    phantom.getWorld().playSound(phantom.getLocation(),
                        Sound.ENTITY_PHANTOM_BITE, 1.5f, 1f);
                    phantom.getWorld().spawnParticle(Particle.CRIT,
                        tLoc.clone().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
                    cancel();
                    smoothAscend();
                    return;
                }

                // Move DIVE_SPEED blocks toward player each tick
                Vector step = toTarget.normalize().multiply(DIVE_SPEED);
                Location next = cur.clone().add(step);
                next.setDirection(toTarget);
                phantom.teleport(next);

                phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    cur, 2, 0.2, 0.2, 0.2, 0);

                // Missed (timeout 15s)
                if (ticksFall > 300) {
                    cancel();
                    smoothAscend();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Wind Burst — swarm all feathers at once ───────────────────────────────
    // Feathers = dropped FEATHER items with velocity (visually spin through air)
    // Each feather deals 1 vanilla HP (5 display HP)
    private void performWindBurst(final Player target) {
        state = State.WIND_BURST;

        // Move to 15 blocks above player smoothly first
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }

                Location cur  = phantom.getLocation();
                Location dest = target.getLocation().clone().add(0, 15, 0);

                double dx = clamp((dest.getX() - cur.getX()) * 0.2, PATROL_SPEED * 2);
                double dy = clamp((dest.getY() - cur.getY()) * 0.2, ASCEND_SPEED);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.2, PATROL_SPEED * 2);

                movePhantom(cur, dx, dy, dz, target.getLocation());

                if (cur.distance(dest) < 4.0) {
                    cancel();
                    fireFeatherSwarm(target);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void fireFeatherSwarm(final Player target) {
        broadcastNearby(phantom.getLocation(), 100, "\u00a75\u00a7lWind Burst!");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 1.5f, 2f);

        int featherCount = 10 + random.nextInt(21);
        Location from = phantom.getLocation().clone().add(0, 1, 0);

        for (int i = 0; i < featherCount; i++) {
            // Aim at player's current position with random spread
            Vector base = target.getEyeLocation().toVector()
                    .subtract(from.toVector()).normalize();
            Vector spread = new Vector(
                (random.nextDouble() - 0.5) * 0.4,
                (random.nextDouble() - 0.5) * 0.4,
                (random.nextDouble() - 0.5) * 0.4
            );
            Vector vel = base.add(spread).normalize().multiply(0.9);

            // Drop a FEATHER item — visually appears as a spinning feather
            Item feather = phantom.getWorld().dropItem(from, new ItemStack(Material.FEATHER));
            feather.setVelocity(vel);
            feather.setPickupDelay(Integer.MAX_VALUE);
            feather.setGlowing(true);
            feather.setMetadata(META_EAGLE_FEATHER,
                new FixedMetadataValue(plugin, true));

            trackFeather(feather);
        }

        // Return to idle after burst
        new BukkitRunnable() {
            @Override
            public void run() { state = State.IDLE; }
        }.runTaskLater(plugin, 60L);
    }

    // Track a feather item — deal damage on proximity, auto-remove after 3s
    private void trackFeather(final Item feather) {
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks++;
                if (feather.isDead() || !feather.isValid() || ticks > 60) {
                    feather.remove();
                    cancel();
                    return;
                }
                // Hit check — within 1 block of any player
                for (Entity e : feather.getNearbyEntities(1.0, 1.0, 1.0)) {
                    if (e instanceof Player p && !p.isDead()) {
                        p.damage(1.0); // 1 vanilla HP = 5 display HP
                        feather.getWorld().spawnParticle(Particle.CRIT,
                            feather.getLocation(), 3, 0.1, 0.1, 0.1, 0.05);
                        feather.remove();
                        cancel();
                        return;
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ── Slicer ────────────────────────────────────────────────────────────────
    private void performSlicer(final Player target) {
        state = State.SLICER;
        broadcastNearby(phantom.getLocation(), 100, "\u00a7c\u00a7lSlicer!");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_BITE, 1.5f, 0.8f);

        // Fly to in front of player first, then slash sequence
        flyToAndSlash(target, true, () ->
            new BukkitRunnable() {
                @Override public void run() {
                    if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                    // Back slash
                    flyToAndSlash(target, false, () ->
                        new BukkitRunnable() {
                            @Override public void run() {
                                if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                                // Front slash again
                                flyToAndSlash(target, true, () ->
                                    new BukkitRunnable() {
                                        @Override public void run() {
                                            if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                                            smoothAscend();
                                        }
                                    }.runTaskLater(plugin, 5L)
                                );
                            }
                        }.runTaskLater(plugin, 5L)
                    );
                }
            }.runTaskLater(plugin, 5L)
        );
    }

    // Fly smoothly to front/back of player, then execute callback
    private void flyToAndSlash(final Player target, final boolean front, final Runnable after) {
        new BukkitRunnable() {
            int timeout = 0;
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }
                timeout++;

                Location tLoc = target.getLocation();
                Vector dir = tLoc.getDirection().clone().normalize();
                if (dir.length() < 0.01) dir = new Vector(1, 0, 0);

                Location dest = tLoc.clone().add(dir.multiply(front ? 3 : -3));
                dest.setY(tLoc.getY() + 1);

                Location cur = phantom.getLocation();
                double dist  = cur.distance(dest);

                if (dist < 1.5 || timeout > 60) {
                    cancel();
                    doSlash(target);
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                        after, 3L);
                    return;
                }

                double dx = clamp((dest.getX() - cur.getX()) * 0.3, 0.8);
                double dy = clamp((dest.getY() - cur.getY()) * 0.3, 0.8);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.3, 0.8);
                movePhantom(cur, dx, dy, dz, tLoc);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void doSlash(Player target) {
        if (!alive || phantom.isDead()) return;
        if (phantom.getLocation().distance(target.getLocation()) < 5.0) {
            target.damage(6.0, phantom); // 30 display HP
            target.sendMessage("\u00a7c\u00a7lSliced for \u00a7430 HP\u00a7c\u00a7l!");
        }
        phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
            phantom.getLocation().clone().add(0, 1, 0), 15, 1, 0.5, 1, 0.1);
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 1.2f);
    }

    // ── Smooth ascend ─────────────────────────────────────────────────────────
    private void smoothAscend() {
        state = State.ASCENDING;
        Player target = getNearestPlayer(300);
        final double targetY = (target != null
            ? target.getLocation().getY()
            : phantom.getLocation().getY()) + 20;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }
                Location loc = phantom.getLocation();
                if (loc.getY() >= targetY) { state = State.IDLE; cancel(); return; }
                // Move up at ASCEND_SPEED blocks/tick
                double dy = Math.min(ASCEND_SPEED, targetY - loc.getY());
                phantom.teleport(loc.clone().add(0, dy, 0));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ── Movement helper ───────────────────────────────────────────────────────
    // Small incremental teleport — looks like flying at 20 TPS
    private void movePhantom(Location from, double dx, double dy, double dz,
                             Location facingTarget) {
        Location next = from.clone().add(dx, dy, dz);
        if (facingTarget != null) {
            Vector dir = facingTarget.toVector().subtract(next.toVector());
            if (dir.length() > 0) next.setDirection(dir);
        }
        phantom.teleport(next);
    }

    // Clamp a value to [-max, +max]
    private double clamp(double val, double max) {
        return Math.max(-max, Math.min(max, val));
    }

    // ── Death ─────────────────────────────────────────────────────────────────
    public void die() {
        alive = false;
        bossBar.removeAll();
        bossBar.setVisible(false);
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
        bossBar.removeAll();
        bossBar.setVisible(false);
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Player getNearestPlayer(double radius) {
        Player nearest = null;
        double minDist  = Double.MAX_VALUE;
        // Scan all online players in same world — reliable even at high altitude
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            if (!p.getWorld().equals(phantom.getWorld())) continue;
            double d = p.getLocation().distanceSquared(phantom.getLocation());
            if (d < minDist) { minDist = d; nearest = p; }
        }
        return nearest;
    }

    private List<Player> getPlayersInRange(double radius) {
        List<Player> result = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(phantom.getWorld())) continue;
            if (p.getLocation().distance(phantom.getLocation()) <= radius) result.add(p);
        }
        return result;
    }

    private void broadcastNearby(Location loc, double radius, String msg) {
        for (Player p : getPlayersInRange(radius)) p.sendMessage(msg);
    }

    public Phantom getPhantom() { return phantom; }
    public boolean isAlive()    { return alive; }
}
