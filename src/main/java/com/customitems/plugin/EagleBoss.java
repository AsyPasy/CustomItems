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

    // 1250 display HP = 250 vanilla HP (well within Spigot's 1024 cap)
    private static final double VIRTUAL_HP_MAX  = 1250.0;
    private static final double VANILLA_HP_CAP  = 250.0;

    // Movement speeds (blocks per tick)
    private static final double ASCEND_SPEED  = 1.2;
    private static final double DIVE_BASE     = 0.5;   // starting dive speed
    private static final double DIVE_ACCEL    = 0.025; // +0.025 blocks/tick per tick (~0.5/s)
    private static final double DIVE_MAX      = 2.0;   // cap at 2 blocks/tick (40 blocks/s)
    private static final double PATROL_SPEED  = 0.4;

    private final CustomItemsPlugin plugin;
    private final Phantom phantom;
    private boolean alive = false;
    private double virtualHP = VIRTUAL_HP_MAX;
    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final BossBar bossBar;

    // Phase: 1 = normal, 2 = below 50%, 3 = below 25%
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
        if (maxHp != null) maxHp.setBaseValue(VANILLA_HP_CAP);
        p.setHealth(VANILLA_HP_CAP);

        p.addPotionEffect(new PotionEffect(
            PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 255, false, false));

        alive = true;
        return p;
    }

    // ── Ability cooldown reset (phase-aware) ──────────────────────────────────
    private void resetAbilityCooldowns() {
        // Phase 1: normal timings
        // Phase 2: ~25% faster
        // Phase 3: ~50% faster
        double mult = phase == 3 ? 0.5 : phase == 2 ? 0.75 : 1.0;
        ticksUntilWindBurst = (int)(((10 + random.nextInt(21)) * 20) * mult);
        ticksUntilSlicer    = (int)(((15 + random.nextInt(6))  * 20) * mult);
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
                phantom.setFireTicks(0);
                phantom.setVisualFire(false);
                if (phantom.getHealth() < VANILLA_HP_CAP) phantom.setHealth(VANILLA_HP_CAP);

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
        double pct = virtualHP / VIRTUAL_HP_MAX;
        int newPhase = pct > 0.5 ? 1 : pct > 0.25 ? 2 : 3;
        if (newPhase > phase) {
            phase = newPhase;
            onPhaseChange();
        }
    }

    private void onPhaseChange() {
        Location loc = phantom.getLocation();
        String msg = phase == 2
            ? "\u00a7c\u00a7lEagle's Baby Boss enrages at half health!"
            : "\u00a74\u00a7l\u2762 Eagle's Baby Boss is in a FRENZY! \u2762";
        broadcastNearby(loc, 150, msg);
        loc.getWorld().playSound(loc, Sound.ENTITY_PHANTOM_HURT, 2f, 0.5f);
        loc.getWorld().strikeLightningEffect(loc);

        // Immediately reset cooldowns with phase-based reduction
        resetAbilityCooldowns();
        // Immediately trigger wind burst on phase change
        Player target = getNearestPlayer(300);
        if (target != null && state == State.IDLE) {
            ticksUntilWindBurst = 0;
        }
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────
    private void updateBossBar() {
        double progress = Math.max(0.0, Math.min(1.0, virtualHP / VIRTUAL_HP_MAX));
        bossBar.setProgress(progress);
        bossBar.setTitle(String.format(
            "\u00a76\u00a7lEagle's Baby Boss \u00a7e%.0f\u00a77/\u00a7e%.0f HP",
            virtualHP, VIRTUAL_HP_MAX));

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

    // ── Virtual damage ────────────────────────────────────────────────────────
    public boolean applyVirtualDamage(double vanillaDamage) {
        if (!alive) return false;
        // vanilla damage → display HP (×5 per RPG system)
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

        // Slicer takes priority
        if (ticksUntilSlicer <= 0) {
            ticksUntilSlicer = (int)(((15 + random.nextInt(6)) * 20)
                * (phase == 3 ? 0.5 : phase == 2 ? 0.75 : 1.0));
            performSlicer(target);
            return;
        }
        if (ticksUntilWindBurst <= 0) {
            ticksUntilWindBurst = (int)(((10 + random.nextInt(21)) * 20)
                * (phase == 3 ? 0.5 : phase == 2 ? 0.75 : 1.0));
            performWindBurst(target);
            return;
        }

        // Idle gap before next charge — shorter in higher phases
        int chargeGap = phase == 3 ? 15 : phase == 2 ? 25 : 40;
        idleTicks++;
        if (idleTicks >= chargeGap) {
            idleTicks = 0;
            startTalonCharge(target);
        }
    }

    // ── Talon Charge — Phase 1: Ascend above player ───────────────────────────
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
                double dist   = cur.distance(dest);

                if (dist < 3.0) {
                    cancel();
                    startTalonDive(target);
                    return;
                }

                double dx = clamp((dest.getX() - cur.getX()) * 0.15, ASCEND_SPEED * 1.5);
                double dy = clamp((dest.getY() - cur.getY()) * 0.15, ASCEND_SPEED * 2.0);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.15, ASCEND_SPEED * 1.5);
                movePhantom(cur, dx, dy, dz, target.getLocation());

                // Ascend particles
                if (random.nextInt(3) == 0) {
                    phantom.getWorld().spawnParticle(Particle.CLOUD,
                        cur, 2, 0.3, 0.3, 0.3, 0.02);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Talon Charge — Phase 2: Accelerating dive ────────────────────────────
    // Starts at DIVE_BASE blocks/tick, accelerates by DIVE_ACCEL each tick
    // Damage = 15 display HP × seconds in dive
    private void startTalonDive(final Player target) {
        state = State.DIVING;
        broadcastNearby(phantom.getLocation(), 100,
            "\u00a7c\u00a7l\u25bc DIVING! \u25bc");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 2f, 0.5f);

        new BukkitRunnable() {
            int     ticksFall = 0;
            double  speed     = DIVE_BASE;

            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }

                ticksFall++;

                // Accelerate each tick, capped at DIVE_MAX
                speed = Math.min(DIVE_MAX, speed + DIVE_ACCEL);

                Location cur  = phantom.getLocation();
                Location tLoc = target.getLocation();
                Vector toTarget = tLoc.toVector().subtract(cur.toVector());
                double dist = toTarget.length();

                if (dist < 2.5) {
                    double seconds    = ticksFall / 20.0;
                    double displayDmg = 15.0 * seconds;
                    double vanillaDmg = displayDmg / 5.0;
                    target.damage(vanillaDmg, phantom);
                    target.sendMessage("\u00a74\u00a7lEagle's talons struck you for \u00a7c"
                        + String.format("%.0f", displayDmg) + " HP\u00a74\u00a7l!");
                    phantom.getWorld().playSound(phantom.getLocation(),
                        Sound.ENTITY_PHANTOM_BITE, 2f, 0.8f);
                    phantom.getWorld().spawnParticle(Particle.CRIT,
                        tLoc.clone().add(0, 1, 0), 30, 0.6, 0.6, 0.6, 0.15);
                    phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                        tLoc.clone().add(0, 1, 0), 5, 0.5, 0.3, 0.5, 0);
                    cancel();
                    smoothAscend();
                    return;
                }

                // Move toward player at current speed
                Vector step = toTarget.normalize().multiply(speed);
                Location next = cur.clone().add(step);
                next.setDirection(toTarget);
                phantom.teleport(next);

                // Trail particles — more intense at higher speed
                int particleCount = (int)(speed * 3);
                phantom.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    cur, particleCount, 0.3, 0.3, 0.3, 0);
                if (speed > 1.0) {
                    phantom.getWorld().spawnParticle(Particle.CLOUD,
                        cur, 2, 0.2, 0.2, 0.2, 0.05);
                }

                // Timeout 20s
                if (ticksFall > 400) { cancel(); smoothAscend(); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Wind Burst — massive feather swarm ───────────────────────────────────
    // 5× more feathers: 50–150 in phase 1, up to 250 in phase 3
    private void performWindBurst(final Player target) {
        state = State.WIND_BURST;

        // Fly to position above player first
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
        // Base 50–150, phase 2 = ×1.5, phase 3 = ×2
        int baseCount = 50 + random.nextInt(101);
        int featherCount = (int)(baseCount * (phase == 3 ? 2.0 : phase == 2 ? 1.5 : 1.0));

        broadcastNearby(phantom.getLocation(), 150,
            "\u00a75\u00a7l\u2604 WIND BURST! \u2604 \u00a77(" + featherCount + " feathers)");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 2f, 2f);
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1.5f);

        Location from = phantom.getLocation().clone().add(0, 1, 0);

        // Fire all feathers at once in a spreading swarm
        for (int i = 0; i < featherCount; i++) {
            Vector base = target.getEyeLocation().toVector()
                    .subtract(from.toVector()).normalize();

            // Wider spread for larger bursts
            double spreadAmt = 0.5 + (featherCount / 300.0);
            Vector spread = new Vector(
                (random.nextDouble() - 0.5) * spreadAmt,
                (random.nextDouble() - 0.5) * spreadAmt,
                (random.nextDouble() - 0.5) * spreadAmt
            );
            // Vary speed slightly per feather for organic feel
            double speed = 0.7 + random.nextDouble() * 0.6;
            Vector vel = base.clone().add(spread).normalize().multiply(speed);

            Item feather = phantom.getWorld().dropItem(from, new ItemStack(Material.FEATHER));
            feather.setVelocity(vel);
            feather.setPickupDelay(Integer.MAX_VALUE);
            feather.setGlowing(true);
            feather.setMetadata(META_EAGLE_FEATHER,
                new FixedMetadataValue(plugin, true));

            trackFeather(feather);
        }

        // Visual burst effect
        phantom.getWorld().spawnParticle(Particle.CLOUD,
            from, 40, 1.5, 1.5, 1.5, 0.1);
        phantom.getWorld().spawnParticle(Particle.CRIT,
            from, 20, 1.0, 1.0, 1.0, 0.2);

        new BukkitRunnable() {
            @Override public void run() { state = State.IDLE; }
        }.runTaskLater(plugin, 40L);
    }

    // Track feather proximity — 1 vanilla HP (5 display HP) on hit
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
                        p.damage(1.0); // 5 display HP
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

    // ── Slicer — front → back → front, 30 HP each, 15–20s cooldown ───────────
    private void performSlicer(final Player target) {
        state = State.SLICER;
        broadcastNearby(phantom.getLocation(), 100,
            "\u00a7c\u00a7l\u2620 SLICER! \u2620");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_BITE, 2f, 0.6f);
        phantom.getWorld().strikeLightningEffect(phantom.getLocation());

        // In phase 3 do 5 slashes instead of 3
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
            }.runTaskLater(plugin, 5L)
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
            // Phase 3 deals 45 display HP per slash instead of 30
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
                phantom.getWorld().spawnParticle(Particle.CLOUD,
                    loc, 1, 0.2, 0.2, 0.2, 0.01);
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
