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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.NamespacedKey;

import java.util.*;

public class EagleBoss {

    public static final String META_EAGLE_BOSS    = "eagle_boss";
    public static final String META_EAGLE_FEATHER = "eagle_feather";

    private static final double MAX_HP        = 1250.0;
    private static final double CHARGE_HEIGHT = 15.0;

    private static final double ASCEND_SPEED  = 1.2;
    private static final double DIVE_BASE     = 0.5;
    private static final double DIVE_ACCEL    = 0.025;
    private static final double DIVE_MAX      = 2.0;
    private static final double PATROL_SPEED  = 0.4;

    private static final double TALON_DAMAGE  = 40.0 / 5.0;

    private static final double BLEED_VANILLA = 3.0 / 5.0;
    private static final int    BLEED_TICKS   = 3;

    private static final int    SWARM_COOLDOWN   = 45 * 20;
    private static final int    SWARM_FORM_TICKS = 70;
    private static final int    SWARM_HOLD_TICKS = 140;
    private static final double SWARM_RADIUS     = 10.0;
    private static final double TORNADO_HEIGHT   = 20.0;

    private final CustomItemsPlugin plugin;
    private final Phantom phantom;
    private boolean alive = false;
    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final BossBar bossBar;

    private int phase = 1;

    private enum State { IDLE, ASCENDING_FOR_CHARGE, DIVING, WIND_BURST, SLICER, ASCENDING, SWARM }
    private State state = State.IDLE;

    private int ticksUntilWindBurst;
    private int ticksUntilSlicer;
    private int swarmTimer    = SWARM_COOLDOWN;
    private boolean swarmQueued = false;
    private int idleTicks     = 0;

    // ── Normal spawn constructor ──────────────────────────────────────────────
    public EagleBoss(CustomItemsPlugin plugin, Location spawnLoc) {
        this.plugin = plugin;
        bossBar = Bukkit.createBossBar(
            "\u00a76\u00a7lEagle's Baby", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        this.phantom = spawnPhantom(spawnLoc);
        resetAttackCooldowns();
        startTasks();
        broadcastNearby(spawnLoc, 150,
            "\u00a74\u00a7l\u26a0 \u00a7e\u00a7lEagle's Baby \u00a74\u00a7lhas descended! \u26a0");
        spawnLoc.getWorld().playSound(spawnLoc, Sound.ENTITY_PHANTOM_AMBIENT, 2f, 2f);
        spawnLoc.getWorld().playSound(spawnLoc, Sound.ENTITY_WITHER_SPAWN, 1f, 1.8f);
    }

    // ── Reattach constructor (server restart) ─────────────────────────────────
    public EagleBoss(CustomItemsPlugin plugin, Phantom existing) {
        this.plugin = plugin;
        bossBar = Bukkit.createBossBar(
            "\u00a76\u00a7lEagle's Baby", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        this.phantom = existing;

        existing.setMetadata(META_EAGLE_BOSS, new FixedMetadataValue(plugin, true));
        existing.setAI(false);
        existing.setGravity(false);
        existing.setRemoveWhenFarAway(false);
        existing.setCustomName("\u00a76\u00a7lEagle's Baby");
        existing.setCustomNameVisible(true);

        if (!existing.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) {
            existing.addPotionEffect(new PotionEffect(
                PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 255, false, false));
        }

        double pct = existing.getHealth() / MAX_HP;
        phase = pct > 0.5 ? 1 : pct > 0.05 ? 2 : 3;

        alive = true;
        resetAttackCooldowns();
        startTasks();

        Bukkit.broadcastMessage(
            "\u00a74\u00a7l[!] \u00a7e\u00a7lEagle's Baby \u00a74\u00a7lhas been reloaded from world data!");
    }

    // ── Spawn ─────────────────────────────────────────────────────────────────
    private Phantom spawnPhantom(Location loc) {
        Phantom p = (Phantom) loc.getWorld().spawnEntity(loc, EntityType.PHANTOM);
        p.setMetadata(META_EAGLE_BOSS, new FixedMetadataValue(plugin, true));
        p.getPersistentDataContainer().set(
            new NamespacedKey(plugin, "eagle_boss"),
            PersistentDataType.BYTE, (byte) 1);
        p.setCustomName("\u00a76\u00a7lEagle's Baby");
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

    // ── Cooldown reset (never touches swarm timer) ────────────────────────────
    private void resetAttackCooldowns() {
        double mult = (phase >= 2) ? 0.25 : 0.5;
        ticksUntilWindBurst = (int)(((3  + random.nextInt(8))  * 20) * mult);
        ticksUntilSlicer    = (int)(((15 + random.nextInt(6))  * 20) * mult);
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────
    private void startTasks() {
        // Main AI task
        tasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom == null || phantom.isDead()) { cancel(); return; }
                phantom.setFireTicks(0);
                phantom.setVisualFire(false);
                checkPhaseTransition();
                updateBossBar();
                updateBossBarPlayers();
                if (state == State.IDLE) mainTick();
            }
        }.runTaskTimer(plugin, 1L, 1L));

        // Completely independent swarm timer — never affected by anything else
        tasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom == null || phantom.isDead()) { cancel(); return; }
                if (swarmTimer > 0) { swarmTimer--; return; }
                swarmTimer = SWARM_COOLDOWN;
                if (state == State.IDLE) {
                    performSwarm();
                } else {
                    swarmQueued = true;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L));
    }

    // ── Phase transitions ─────────────────────────────────────────────────────
    private void checkPhaseTransition() {
        double pct = phantom.getHealth() / MAX_HP;
        int newPhase = pct > 0.5 ? 1 : pct > 0.05 ? 2 : 3;
        if (newPhase > phase) {
            phase = newPhase;
            onPhaseChange();
        }
    }

    private void onPhaseChange() {
        Location loc = phantom.getLocation();
        String msg = phase == 2
            ? "\u00a7c\u00a7lEagle's Baby enrages at half health! \u00a77(4\u00d7 speed)"
            : "\u00a74\u00a7l\u2762 FRENZY at 5% HP! Slicer spam begins! \u2762";
        broadcastNearby(loc, 150, msg);
        loc.getWorld().playSound(loc, Sound.ENTITY_PHANTOM_HURT, 2f, 0.5f);
        loc.getWorld().strikeLightningEffect(loc);
        resetAttackCooldowns();
        if (phase == 3) ticksUntilSlicer = 0;
    }

    // ── Boss bar ──────────────────────────────────────────────────────────────
    private void updateBossBar() {
        double hp       = phantom.getHealth();
        double progress = Math.max(0.0, Math.min(1.0, hp / MAX_HP));
        bossBar.setProgress(progress);
        bossBar.setTitle(String.format(
            "\u00a76\u00a7lEagle's Baby \u00a7e%.0f\u00a77/\u00a7e%.0f HP", hp, MAX_HP));
        if (progress > 0.5)       bossBar.setColor(BarColor.YELLOW);
        else if (progress > 0.05) bossBar.setColor(BarColor.RED);
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
        if (swarmQueued) { swarmQueued = false; performSwarm(); return; }

        Player target = getNearestPlayer(300);
        if (target == null) return;

        // Phase 3: slicer spam only
        if (phase == 3) {
            if (ticksUntilSlicer > 0) ticksUntilSlicer--;
            if (ticksUntilSlicer <= 0) {
                ticksUntilSlicer = (int)(((5 + random.nextInt(4)) * 20) * 0.25);
                performSlicer(target);
            }
            return;
        }

        // Phase 1 & 2
        if (ticksUntilWindBurst > 0) ticksUntilWindBurst--;
        if (ticksUntilSlicer    > 0) ticksUntilSlicer--;

        if (ticksUntilSlicer <= 0) {
            double mult = phase >= 2 ? 0.25 : 0.5;
            ticksUntilSlicer = (int)(((15 + random.nextInt(6)) * 20) * mult);
            performSlicer(target);
            return;
        }
        if (ticksUntilWindBurst <= 0) {
            double mult = phase >= 2 ? 0.25 : 0.5;
            ticksUntilWindBurst = (int)(((3 + random.nextInt(8)) * 20) * mult);
            performWindBurst(target);
            return;
        }

        int chargeGap = phase >= 2 ? 10 : 20;
        idleTicks++;
        if (idleTicks >= chargeGap) {
            idleTicks = 0;
            startTalonCharge(target);
        }
    }

    // ── Talon Charge ──────────────────────────────────────────────────────────
    private void startTalonCharge(final Player target) {
        state = State.ASCENDING_FOR_CHARGE;
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 1.5f, 2f);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead())  { state = State.IDLE; cancel(); return; }
                if (!target.isOnline())          { state = State.IDLE; cancel(); return; }

                Location cur  = phantom.getLocation();
                Location dest = target.getLocation().clone().add(0, CHARGE_HEIGHT, 0);
                if (cur.distance(dest) < 3.0) { cancel(); startTalonDive(target); return; }

                double dx = clamp((dest.getX() - cur.getX()) * 0.15, ASCEND_SPEED * 1.5);
                double dy = clamp((dest.getY() - cur.getY()) * 0.15, ASCEND_SPEED * 2.0);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.15, ASCEND_SPEED * 1.5);
                movePhantom(cur, dx, dy, dz, target.getLocation());
                if (random.nextInt(3) == 0)
                    phantom.getWorld().spawnParticle(Particle.CLOUD,
                        cur, 2, 0.3, 0.3, 0.3, 0.02);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Talon Dive — flat 40 display HP ───────────────────────────────────────
    private void startTalonDive(final Player target) {
        state = State.DIVING;
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 2f, 0.5f);

        new BukkitRunnable() {
            int     ticksFall = 0;
            double  speed     = DIVE_BASE;
            boolean hit       = false;

            @Override
            public void run() {
                if (!alive || phantom.isDead())  { state = State.IDLE; cancel(); return; }
                if (!target.isOnline())          { state = State.IDLE; cancel(); return; }

                ticksFall++;
                speed = Math.min(DIVE_MAX, speed + DIVE_ACCEL);
                Location cur    = phantom.getLocation();
                Location tLoc   = target.getLocation();
                Vector toTarget = tLoc.toVector().subtract(cur.toVector());

                if (toTarget.length() < 2.5 && !hit) {
                    hit = true;
                    target.damage(TALON_DAMAGE, phantom);
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
                if (speed > 1.0)
                    phantom.getWorld().spawnParticle(Particle.CLOUD,
                        cur, 2, 0.2, 0.2, 0.2, 0.05);
                if (ticksFall > 400) { cancel(); smoothAscend(); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ── Wind Burst — 10 rounds of 3 feathers each ────────────────────────────
    private void performWindBurst(final Player target) {
        state = State.WIND_BURST;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!alive || phantom.isDead())  { state = State.IDLE; cancel(); return; }
                if (!target.isOnline())          { state = State.IDLE; cancel(); return; }

                Location cur  = phantom.getLocation();
                Location dest = target.getLocation().clone().add(0, 15, 0);
                if (cur.distance(dest) < 4.0) { cancel(); startFeatherRounds(target); return; }

                double dx = clamp((dest.getX() - cur.getX()) * 0.2, PATROL_SPEED * 2);
                double dy = clamp((dest.getY() - cur.getY()) * 0.2, ASCEND_SPEED);
                double dz = clamp((dest.getZ() - cur.getZ()) * 0.2, PATROL_SPEED * 2);
                movePhantom(cur, dx, dy, dz, target.getLocation());
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void startFeatherRounds(final Player target) {
        broadcastNearby(phantom.getLocation(), 150, "\u00a75\u00a7l\u2604 WIND BURST! \u2604");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_FLAP, 2f, 2f);
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1.5f);

        new BukkitRunnable() {
            int round = 0;
            @Override
            public void run() {
                if (!alive || phantom.isDead())  { state = State.IDLE; cancel(); return; }
                if (!target.isOnline())          { state = State.IDLE; cancel(); return; }
                if (round >= 10)                 { state = State.IDLE; cancel(); return; }

                Location from = phantom.getLocation().clone().add(0, 1, 0);
                for (int i = 0; i < 3; i++) {
                    Vector baseDir = target.getEyeLocation().toVector()
                            .subtract(from.toVector()).normalize();
                    Vector spread = new Vector(
                        (random.nextDouble() - 0.5) * 0.3,
                        (random.nextDouble() - 0.5) * 0.15,
                        (random.nextDouble() - 0.5) * 0.3
                    );
                    Vector vel = baseDir.add(spread).normalize()
                            .multiply(0.8 + random.nextDouble() * 0.4);

                    Item feather = phantom.getWorld().dropItem(
                        from, new ItemStack(Material.FEATHER));
                    feather.setVelocity(vel);
                    feather.setPickupDelay(Integer.MAX_VALUE);
                    feather.setGlowing(true);
                    feather.setMetadata(META_EAGLE_FEATHER,
                        new FixedMetadataValue(plugin, true));
                    trackFeather(feather);
                }
                phantom.getWorld().spawnParticle(Particle.CLOUD,
                    from, 5, 0.3, 0.3, 0.3, 0.03);
                round++;
            }
        }.runTaskTimer(plugin, 0L, 6L);
    }

    // Feather hit — 5 display HP + bleeding
    private void trackFeather(final Item feather) {
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks++;
                if (feather.isDead() || !feather.isValid() || ticks > 60) {
                    feather.remove(); cancel(); return;
                }
                for (Entity e : feather.getNearbyEntities(1.0, 1.0, 1.0)) {
                    if (e instanceof Player p && !p.isDead()) {
                        p.damage(1.0, phantom);
                        feather.getWorld().spawnParticle(Particle.CRIT,
                            feather.getLocation(), 5, 0.1, 0.1, 0.1, 0.05);
                        feather.getWorld().spawnParticle(Particle.BLOCK_CRACK,
                            feather.getLocation().clone().add(0, 0.5, 0),
                            8, 0.2, 0.2, 0.2, 0.05,
                            Material.REDSTONE_WIRE.createBlockData());
                        applyBleeding(p);
                        feather.remove(); cancel(); return;
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // Bleeding — 3 display HP per second for 3 seconds
    private void applyBleeding(final Player player) {
        new BukkitRunnable() {
            int seconds = 0;
            @Override
            public void run() {
                if (seconds >= BLEED_TICKS || !player.isOnline() || player.isDead()) {
                    cancel(); return;
                }
                player.damage(BLEED_VANILLA);
                player.getWorld().spawnParticle(Particle.CRIT,
                    player.getLocation().clone().add(0, 1, 0), 5, 0.3, 0.4, 0.3, 0.02);
                player.getWorld().playSound(player.getLocation(),
                    Sound.ENTITY_PLAYER_HURT, 0.5f, 0.8f);
                seconds++;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ── Eagle's Swarm ─────────────────────────────────────────────────────────
    private void performSwarm() {
        state = State.SWARM;
        broadcastNearby(phantom.getLocation(), 150,
            "\u00a7b\u00a7l\u2604 EAGLE'S SWARM! \u2604 \u00a77Get away from the tornado!");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_ENDER_DRAGON_FLAP, 2f, 0.6f);

        final Location center  = phantom.getLocation().clone();
        final double   groundY = getGroundY(center);

        // Phase 1: spin 3.5s forming tornado
        new BukkitRunnable() {
            int    tick  = 0;
            double angle = 0;
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { state = State.IDLE; cancel(); return; }
                if (tick >= SWARM_FORM_TICKS)   { cancel(); holdTornado(center, groundY); return; }

                double progress = (double) tick / SWARM_FORM_TICKS;
                double radius   = 4.0 - (3.0 * progress);
                angle += 0.3;

                phantom.teleport(new Location(center.getWorld(),
                    center.getX() + Math.cos(angle) * radius,
                    center.getY() + 1,
                    center.getZ() + Math.sin(angle) * radius));

                spawnTornadoParticles(center, groundY, progress);

                if (tick % 10 == 0)
                    center.getWorld().playSound(center,
                        Sound.ENTITY_PHANTOM_FLAP, 1.5f, 0.5f + (float) progress);
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // Phase 2: hold tornado 7s, pull entities
    private void holdTornado(final Location center, final double groundY) {
        broadcastNearby(center, 150, "\u00a7b\u00a7l\u2605 TORNADO ACTIVE! \u2605");
        center.getWorld().playSound(center,
            Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 1.2f);

        new BukkitRunnable() {
            int tick = 0;
            @Override
            public void run() {
                if (!alive || phantom.isDead()) { endTornado(center); cancel(); return; }
                if (tick >= SWARM_HOLD_TICKS)   { cancel(); endTornado(center); return; }

                phantom.teleport(center.clone().add(0, 2, 0));
                spawnTornadoParticles(center, groundY, 1.0);

                for (Entity e : center.getWorld().getNearbyEntities(
                        center, SWARM_RADIUS, SWARM_RADIUS + 10, SWARM_RADIUS)) {
                    if (e.equals(phantom)) continue;
                    if (e instanceof LivingEntity) {
                        Vector pull = center.toVector()
                                .subtract(e.getLocation().toVector())
                                .normalize().multiply(0.7);
                        pull.setY(0.15);
                        e.setVelocity(pull);
                    }
                }

                if (tick % 15 == 0)
                    center.getWorld().playSound(center,
                        Sound.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 1.5f);
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // Phase 3: tornado ends, apply debuffs
    private void endTornado(final Location center) {
        broadcastNearby(center, 150,
            "\u00a77\u00a7lThe tornado dissipates... \u00a7c\u00a7lYou feel weakened!");
        center.getWorld().playSound(center, Sound.ENTITY_PHANTOM_AMBIENT, 2f, 1.5f);
        center.getWorld().spawnParticle(Particle.CLOUD,
            center.clone().add(0, 10, 0), 100, 5, 5, 5, 0.2);

        for (Entity e : center.getWorld().getNearbyEntities(
                center, SWARM_RADIUS, SWARM_RADIUS, SWARM_RADIUS)) {
            if (!(e instanceof Player p)) continue;
            p.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOW, 20 * 20, 1, false, true, true));
            p.addPotionEffect(new PotionEffect(
                PotionEffectType.WEAKNESS, 20 * 20, 1, false, true, true));
            p.sendMessage(
                "\u00a7c\u00a7lThe tornado left you \u00a7cslowed "
                + "\u00a7c\u00a7land \u00a7cweak \u00a7c\u00a7lfor 20 seconds!");
        }
        state = State.IDLE;
    }

    // Tornado particles — from ground up 20 blocks, narrow at bottom wide at top
    private void spawnTornadoParticles(Location center, double groundY, double intensity) {
        int    layers        = 24;
        int    pointsPerRing = 18;
        double timeOffset    = (System.currentTimeMillis() % 10000) * 0.004;

        for (int layer = 0; layer < layers; layer++) {
            double heightFraction = (double) layer / layers;
            double y = groundY + heightFraction * TORNADO_HEIGHT;
            double r = intensity * (1.0 + heightFraction * 7.0);

            for (int i = 0; i < pointsPerRing; i++) {
                double a = (Math.PI * 2.0 / pointsPerRing) * i
                         + (heightFraction * 6.0) + timeOffset;
                Location ring = new Location(center.getWorld(),
                    center.getX() + Math.cos(a) * r,
                    y,
                    center.getZ() + Math.sin(a) * r);

                center.getWorld().spawnParticle(Particle.CLOUD,
                    ring, 1, 0.04, 0.08, 0.04, 0.04);
                center.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                    ring, 1, 0, 0, 0, 0);
                if (intensity > 0.3 && layer % 2 == 0)
                    center.getWorld().spawnParticle(Particle.SMOKE_NORMAL,
                        ring, 1, 0.08, 0.08, 0.08, 0.02);
                if (intensity > 0.6 && layer % 3 == 0)
                    center.getWorld().spawnParticle(Particle.CRIT,
                        ring, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
    }

    // Find highest solid block below location
    private double getGroundY(Location loc) {
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        for (int y = (int) loc.getY(); y > loc.getWorld().getMinHeight(); y--) {
            if (loc.getWorld().getBlockAt(x, y, z).getType().isSolid()) return y + 1.0;
        }
        return loc.getWorld().getMinHeight();
    }

    // ── Slicer ────────────────────────────────────────────────────────────────
    private void performSlicer(final Player target) {
        state = State.SLICER;
        if (!target.isOnline()) { state = State.IDLE; return; }

        broadcastNearby(phantom.getLocation(), 50, "\u00a7c\u00a7l\u2620 SLICER! \u2620");
        phantom.getWorld().playSound(phantom.getLocation(),
            Sound.ENTITY_PHANTOM_BITE, 2f, 0.6f);
        phantom.getWorld().strikeLightningEffect(phantom.getLocation());

        int slashCount = phase == 3 ? 5 : phase == 2 ? 4 : 3;
        boolean[] fronts = new boolean[slashCount];
        for (int i = 0; i < slashCount; i++) fronts[i] = (i % 2 == 0);
        executeSlashChain(target, fronts, 0);
    }

    private void executeSlashChain(Player target, boolean[] fronts, int index) {
        if (!target.isOnline()) { state = State.IDLE; return; }

        if (index >= fronts.length) {
            if (phase == 3) {
                new BukkitRunnable() {
                    @Override public void run() {
                        if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                        if (!target.isOnline())         { state = State.IDLE; return; }
                        performSlicer(target);
                    }
                }.runTaskLater(plugin, 8L);
            } else {
                new BukkitRunnable() {
                    @Override public void run() {
                        if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                        smoothAscend();
                    }
                }.runTaskLater(plugin, 5L);
            }
            return;
        }

        flyToAndSlash(target, fronts[index], () ->
            new BukkitRunnable() {
                @Override public void run() {
                    if (!alive || phantom.isDead()) { state = State.IDLE; return; }
                    if (!target.isOnline())         { state = State.IDLE; return; }
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
                if (!target.isOnline())         { state = State.IDLE; cancel(); return; }
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
        if (!alive || phantom.isDead() || !target.isOnline()) return;
        if (phantom.getLocation().distance(target.getLocation()) < 5.0) {
            double displayDmg = phase == 3 ? 40.0 : 30.0;
            target.damage(displayDmg / 5.0, phantom);
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
