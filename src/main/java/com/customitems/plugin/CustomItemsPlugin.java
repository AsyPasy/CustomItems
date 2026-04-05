package com.customitems.plugin;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Comparator;
import java.util.List;

public class CustomItemsPlugin extends JavaPlugin {

    private ItemListener      itemListener;
    private EagleBossListener eagleBossListener;

    @Override
    public void onEnable() {
        itemListener      = new ItemListener(this);
        eagleBossListener = new EagleBossListener(this);
        getServer().getPluginManager().registerEvents(itemListener,      this);
        getServer().getPluginManager().registerEvents(eagleBossListener, this);

        // Scan all worlds for surviving eagle boss phantoms from before restart
        reattachSurvivingBosses();

        getLogger().info("CustomItems enabled!");
    }

    @Override
    public void onDisable() {
        ValorDagger.cleanup();
        EaglesEyeBow.cleanup();
        eagleBossListener.cleanup();
        getLogger().info("CustomItems disabled!");
    }

    // Scan every loaded chunk in every world for phantoms marked with PDC
    private void reattachSurvivingBosses() {
        NamespacedKey key = new NamespacedKey(this, "eagle_boss");
        int count = 0;

        for (World world : getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getType() != EntityType.PHANTOM) continue;
                Phantom phantom = (Phantom) entity;

                // Check PDC — this survives restarts unlike metadata
                if (!phantom.getPersistentDataContainer()
                        .has(key, PersistentDataType.BYTE)) continue;

                // Re-attach full boss behavior to this phantom
                EagleBoss boss = new EagleBoss(this, phantom);
                eagleBossListener.registerBoss(boss);
                count++;
                getLogger().info("Reattached Eagle's Baby boss: " + phantom.getUniqueId());
            }
        }

        if (count > 0) {
            getLogger().info("Reattached " + count + " Eagle's Baby boss(es) from world data.");
        }
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        return switch (command.getName().toLowerCase()) {
            case "valuedagger" -> {
                player.getInventory().addItem(ValorDagger.createValorDagger());
                player.sendMessage("\u00a7aYou received the \u00a7fValor Dagger\u00a7a!");
                yield true;
            }
            case "eagleseyebow" -> {
                player.getInventory().addItem(EaglesEyeBow.createEaglesEyeBow());
                player.sendMessage("\u00a7aYou received the \u00a76Eagle\u2019s Eye Bow\u00a7a!");
                yield true;
            }
            case "eagleboss" -> {
                eagleBossListener.spawnBoss(player.getLocation().add(0, 5, 0));
                player.sendMessage("\u00a76Eagle's Baby spawned!");
                yield true;
            }
            case "locatenest" -> {
    List<Location> nests = EagleNest.getNestLocations()
        .stream()
        .filter(l -> l.getWorld().equals(player.getWorld()))
        .sorted(Comparator.comparingDouble(
            l -> l.distanceSquared(player.getLocation())))
        .limit(10)
        .toList();

    if (nests.isEmpty()) {
        player.sendMessage("\u00a7cNo eagle nests discovered yet.");
        yield true;
    }
    player.sendMessage("\u00a76\u00a7l=== 10 Nearest Eagle Nests ===");
    int i = 1;
    for (Location l : nests) {
        double dist = player.getLocation().distance(l);
        player.sendMessage(String.format(
            "\u00a7e#%d \u00a7f\u00bb \u00a7aX: %d  Y: %d  Z: %d  \u00a77(%.0fm)",
            i++, l.getBlockX(), l.getBlockY(), l.getBlockZ(), dist));
    }
    yield true;
}
            default -> false;
        };
    }

    public EagleBossListener getEagleBossListener() { return eagleBossListener; }
}
