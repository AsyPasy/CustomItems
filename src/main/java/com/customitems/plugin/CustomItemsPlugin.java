package com.customitems.plugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class CustomItemsPlugin extends JavaPlugin {

    private ItemListener      itemListener;
    private EagleBossListener eagleBossListener;

    @Override
    public void onEnable() {
        itemListener      = new ItemListener(this);
        eagleBossListener = new EagleBossListener(this);
        getServer().getPluginManager().registerEvents(itemListener,      this);
        getServer().getPluginManager().registerEvents(eagleBossListener, this);
        getLogger().info("CustomItems enabled!");
    }

    @Override
    public void onDisable() {
        ValorDagger.cleanup();
        EaglesEyeBow.cleanup();
        eagleBossListener.cleanup();
        getLogger().info("CustomItems disabled!");
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
                // Removed permission check — op can always run it
                eagleBossListener.spawnBoss(player.getLocation().add(0, 5, 0));
                player.sendMessage("\u00a76Eagle's Baby Boss spawned!");
                yield true;
            }
            default -> false;
        };
    }

    public EagleBossListener getEagleBossListener() { return eagleBossListener; }
}
