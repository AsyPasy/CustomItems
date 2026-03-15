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

    private static final double MAX_HP       = 1250.0;
    private static final double CHARGE_HEIGHT = 15.0;

    private static final double ASCEND_SPEED = 1.2;
    private static final double DIVE_BASE    = 0.5;
    private static final double DIVE_ACCEL   = 0.025;
    private static final double DIVE_MAX     = 2.0;
    private static final double PATROL_SPEED = 0.4;

    private final CustomItemsPlugin plugin;
    private final Phantom phantom;
    private boolean alive = false;
    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final BossBar bossBar;

    private int phase = 1;

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
        resetAbilityCooldowns();
        startTasks();

        broadcastNearby(spawnLoc, 150,
            "\u00a74\u00a7l\u26a0 \u00a7e\u00a7lEagle's Baby Boss \u00a74\u00a7lhas descended! \u26a0");
        spawnLoc.getWorld().playSound(spawnLoc, Sound.ENTITY_PHANTOM_AMBIENT, 2f, 2f);
        spawnLoc.getWorld().playSound(spawnLoc, Sound.ENTITY_WITHER_SPAWN,    1f, 1.8f);
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
        p.setSize(4);

        AttributeInstance maxHp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHp != null) maxHp.setBaseValue(MAX_HP);
        p.setHealth(MAX_HP);

        p.addPotionEffect(new PotionEffect(
            PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 255, false, false));

        alive = true;
        return p;
    }

    // ── Cooldowns ─────────────────────────────────────────────────────────────
    private void resetAbilityCooldowns() {
        double mult = (phase >= 2) ? 0.5 : 1.0;
        ticksUntilWindBurst = (int)(((3 + random.nextInt(7))  * 20) * mult);
        ticksUntilSlicer    = (int)(((15 + random.nextInt(6)) * 20) * mult);
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
                // Suppress daylight fire every tick
                phantom.setFireTicks(0);
                phantom.setVisualFire(false);

                checkPhaseTransition();
                updateBossBar();
                updateBossBarPlayers();

                if (state == State.IDLE) mainTick();
            }
        }.runTaskTimer(plugin, 1L, 1L);
        tasks.add(main);
    }

    // ── Phase transitions ─────────────────────────────────────────────────────
    private void checkPhaseTransition() {
        double pct = phantom.getHealth() / MAX_HP;
        int newPhase = pct > 0.5 ? 1 : pct > 0.25 ? 2 : 3;
        if (newPhase > phase) {
            phase = newPhase;
            onPhaseChange();
        }
    }

    private void onPhaseChange() {
        Location loc = phantom.getLocation();
        String msg = phase == 2
            ? "\u00a7c\u00a7lEagle's Baby Boss enrages at half health! \u00a77(2\u00d7 speed)"
            : "\u00a74\u00a7l\u2762 FRENZY! Slicer only at 2\u00d7 speed! \u2762";
        broadcastNearby(loc, 150, msg);
        loc.getWorld().playSound(loc, Sound.ENTITY_PHANTOM_HURT, 2f, 0.5f);
        loc.getWorld().strikeLightningEffect(loc);
        resetAbilityCooldowns();
        if (phase == 3) {
            ticksUntilSlicer = 0;
        }
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────
    private void updateBossBar() {
        double hp       = phantom.getHealth();
        double progress = Math.max(0.0, Math.min(1.0, hp / MAX_HP));
        bossBar.setProgress(progress);
        bossBar.setTitle(String.format(
            "\u00a76\u00a7lEagle's Baby Boss \u00a7e%.0f\u00a77/\u00a7e%.0f HP",
            hp, MAX_HP));
        if (progress > 0.5)       bossBar.setColor(BarColor.YELLOW);
        else if (progress > 0.25) bossBar.setColor(BarColor.RED);
        else                      bossBar.setColor(BarColor.PURPLE);
    }

    private void updateBossBarPlayers() {
        List<Player> nearby  = getPlayersInRange(150);
        Set<Player>  current = new HashSet<>(bossBar.getPlayers());
        for (Player p : nearby)  if (!current.contains(p)) bossBar.addPlayer(p);
        for (Player p : current) if (!nearby.contains(p))  bossBar.removePlayer(p);
    }

    // ── Main tick ─────────────────────────────────────────────────────────────
    private void mainTick() {
        Player target = getNearestPlayer(300);
        if (target == null) return;

        // Phase 3: slicer only
        if (phase == 3) {
            if (ticksUntilSlicer > 0) ticksUntilSlicer--;
            if (ticksUntilSlicer <= 0) {
                ticksUntilSlicer = (7 + random.nextInt(4)) * 20;
                performSlicer(target);
            }
            return;
        }

        // Phase 1 & 2: normal rotation
        if (ticksUntilWindBurst > 0) ticksUntilWindBurst--;
        if (ticksUntilSlicer    > 0) ticksUntilSlicer--;

        if (ticksUntilSlicer <= 0) {
            ticksUntilSlicer = (int)(((15 + random.nextInt(6)) * 20) * (phase >= 2 ? 0.5 : 1.0));
            performSlicer(target);
            return;
        }
        if (ticksUntilWindBurst <= 0) {
            ticksUntilWindBurst = (int)(((3 + random.nextInt(7)) * 20) * (phase >= 2 ? 0.5 : 1.0));
            performWindBurst(target);
            return;
        }

        int chargeGap = phase >= 2 ? 20 : 40;
        idleTicks++;
        if (idleTicks >= chargeGap) {
            idleTicks = 0;
            startTalonCharge(target);
        }
    }

    // ── Talon Charge — ascend CHARGE_HEIGHT above player, then dive ───────────
    private void startTalonCharge(final Player target) {
        state = State.ASCENDING_FOR_CHARGE;
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 1.5f, 2f);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }

                Location cur  = phantom.getLocation();
                Location dest = target.getLocation().clone().add(0, CHARGE_HEIGHT, 0);

                if (cur.distance(dest) < 3.0) {
                    cancel();
                    startTalonDive(target);
                    return;
                }

                double dx = clamp((dest.getX() - cur.getX()) * 0.15, ASCEND_SPEED * 1.5);
                double dy = clamp((dest.getY() - cur.getY()) * 0.15, ASCEND_SPEED * 2.0);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.15, ASCEND_SPEED * 1.5);
                movePhantom(cur, dx, dy, dz, target.getLocation());

                if (random.nextInt(3) == 0) {
                    phantom.getWorld().spawnParticle(Particle.CLOUD,
                        cur, 2, 0.3, 0.3, 0.3, 0.02);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Talon Dive — accelerating, 15 display HP × seconds ───────────────────
    private void startTalonDive(final Player target) {
        state = State.DIVING;
        broadcastNearby(phantom.getLocation(), 100, "\u00a7c\u00a7l\u25bc DIVING! \u25bc");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 2f, 0.5f);

        new BukkitRunnable() {
            int    ticksFall = 0;
            double speed     = DIVE_BASE;

            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }

                ticksFall++;
                speed = Math.min(DIVE_MAX, speed + DIVE_ACCEL);

                Location cur    = phantom.getLocation();
                Location tLoc   = target.getLocation();
                Vector toTarget = tLoc.toVector().subtract(cur.toVector());

                if (toTarget.length() < 2.5) {
                    double displayDmg = 15.0 * (ticksFall / 20.0);
                    target.damage(displayDmg / 5.0, phantom);
                    target.sendMessage("\u00a74\u00a7lEagle's talons struck you for \u00a7c"
                        + String.format("%.0f", displayDmg) + " HP\u00a74\u00a7l!");
                    phantom.getWorld().playSound(phantom.getLocation(),
                        Sound.ENTITY_PHANTOM_BITE, 2f, 0.8f);
                    phantom.getWorld().spawnParticle(Particle.CRIT,
                        tLoc.clone().add(0, 1, 0), 30, 0.6, 0.6, 0.6, 0.15);
                    cancel();
                    smoothAscend();
                    return;
                }

                Location next = cur.clone().add(toTarget.normalize().multiply(speed));
                next.setDirection(toTarget);
                phantom.teleport(next);

                phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    cur, (int)(speed * 3), 0.3, 0.3, 0.3, 0);
                if (speed > 1.0) {
                    phantom.getWorld().spawnParticle(Particle.CLOUD,
                        cur, 2, 0.2, 0.2, 0.2, 0.05);
                }

                if (ticksFall > 400) { cancel(); smoothAscend(); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Wind Burst ────────────────────────────────────────────────────────────
    private void performWindBurst(final Player target) {
        state = State.WIND_BURST;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }

                Location cur  = phantom.getLocation();
                Location dest = target.getLocation().clone().add(0, 15, 0);

                if (cur.distance(dest) < 4.0) {
                    cancel();
                    fireFeatherSwarm(target);
                    return;
                }

                double dx = clamp((dest.getX() - cur.getX()) * 0.2, PATROL_SPEED * 2);
                double dy = clamp((dest.getY() - cur.getY()) * 0.2, ASCEND_SPEED);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.2, PATROL_SPEED * 2);
                movePhantom(cur, dx, dy, dz, target.getLocation());
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void fireFeatherSwarm(final Player target) {
        int featherCount = 10 + random.nextInt(21);

        broadcastNearby(phantom.getLocation(), 150, "\u00a75\u00a7l\u2604 WIND BURST! \u2604");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 2f, 2f);

        Location from = phantom.getLocation().clone().add(0, 1, 0);

        for (int i = 0; i < featherCount; i++) {
            Vector baseDir = target.getEyeLocation().toVector()
                    .subtract(from.toVector()).normalize();
            Vector spread = new Vector(
                (random.nextDouble() - 0.5) * 0.4,
                (random.nextDouble() - 0.5) * 0.4,
                (random.nextDouble() - 0.5) * 0.4
            );
            double spd = 0.7 + random.nextDouble() * 0.6;
            Vector vel = baseDir.add(spread).normalize().multiply(spd);

            Item feather = phantom.getWorld().dropItem(from, new ItemStack(Material.FEATHER));
            feather.setVelocity(vel);
            feather.setPickupDelay(Integer.MAX_VALUE);
            feather.setGlowing(true);
            feather.setMetadata(META_EAGLE_FEATHER,
                new FixedMetadataValue(plugin, true));
            trackFeather(feather);
        }

        phantom.getWorld().spawnParticle(Particle.CLOUD, from, 20, 1.0, 1.0, 1.0, 0.1);

        new BukkitRunnable() {
            @Override public void run() { state = State.IDLE; }
        }.runTaskLater(plugin, 40L);
    }

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
                for (Entity e : feather.getNearbyEntities(1.0, 1.0, 1.0)) {
                    if (e instanceof Player p && !p.isDead()) {
                        p.damage(1.0);
                        feather.getWorld().spawnParticle(Particle.CRIT,
                            feather.getLocation(), 4, 0.1, 0.1, 0.1, 0.05);
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
        broadcastNearby(phantom.getLocation(), 100, "\u00a7c\u00a7l\u2620 SLICER! \u2620");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_BITE, 2f, 0.6f);
        phantom.getWorld().strikeLightningEffect(phantom.getLocation());

        int slashCount = phase == 3 ? 5 : phase == 2 ? 4 : 3;
        boolean[] fronts = new boolean[slashCount];
        for (int i = 0; i < slashCount; i++) fronts[i] = (i % 2 == 0);
        executeSlashChain(target, fronts, 0);
    }

    private void executeSlashChain(Player target, boolean[] fronts, int index) {
        if (index >= fronts.length) {
            new BukkitRunnable() {
                @Override public void run() {
                    if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                    smoothAscend();
                }
            }.runTaskLater(plugin, 5L);
            return;
        }
        flyToAndSlash(target, fronts[index], () ->
            new BukkitRunnable() {
                @Override public void run() {
                    if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                    executeSlashChain(target, fronts, index + 1);
                }
            }.runTaskLater(plugin, phase == 3 ? 3L : 5L)
        );
    }

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

                if (cur.distance(dest) < 1.5 || timeout > 60) {
                    cancel();
                    doSlash(target);
                    plugin.getServer().getScheduler().runTaskLater(plugin, after, 3L);
                    return;
                }

                double dx = clamp((dest.getX() - cur.getX()) * 0.35, 1.0);
                double dy = clamp((dest.getY() - cur.getY()) * 0.35, 1.0);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.35, 1.0);
                movePhantom(cur, dx, dy, dz, tLoc);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void doSlash(Player target) {
        if (!alive || phantom.isDead()) return;
        if (phantom.getLocation().distance(target.getLocation()) < 5.0) {
            double displayDmg = phase == 3 ? 45.0 : 30.0;
            target.damage(displayDmg / 5.0, phantom);
            target.sendMessage("\u00a7c\u00a7lSliced for \u00a74"
                + (int) displayDmg + " HP\u00a7c\u00a7l!");
        }
        phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
            phantom.getLocation().clone().add(0, 1, 0), 20, 1.2, 0.5, 1.2, 0.1);
        phantom.getWorld().spawnParticle(Particle.CRIT,
            phantom.getLocation().clone().add(0, 1, 0), 8, 0.5, 0.3, 0.5, 0.1);
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 1.0f);
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
                double dy = Math.min(ASCEND_SPEED, targetY - loc.getY());
                phantom.teleport(loc.clone().add(0, dy, 0));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ── Movement helper ───────────────────────────────────────────────────────
    private void movePhantom(Location from, double dx, double dy, double dz,
                              Location facingTarget) {
        Location next = from.clone().add(dx, dy, dz);
        if (facingTarget != null) {
            Vector dir = facingTarget.toVector().subtract(next.toVector());
            if (dir.length() > 0) next.setDirection(dir);
        }
        phantom.teleport(next);
    }

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
                Sound.ENTITY_PHANTOM_DEATH, 2f, 1.2f);
            phantom.getWorld().playSound(phantom.getLocation(),
                Sound.ENTITY_WITHER_DEATH, 1f, 1.5f);
            phantom.getWorld().spawnParticle(Particle.EXPLOSION_LARGE,
                phantom.getLocation(), 8, 1.5, 1.5, 1.5, 0);
            phantom.getWorld().strikeLightningEffect(phantom.getLocation());
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
