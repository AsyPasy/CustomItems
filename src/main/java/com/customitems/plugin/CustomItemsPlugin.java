package com.customitems.plugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class CustomItemsPlugin extends JavaPlugin {

    private ItemListener itemListener;

    @Override
    public void onEnable() {
        itemListener = new ItemListener(this);
        getServer().getPluginManager().registerEvents(itemListener, this);
        getLogger().info("CustomItems enabled!");
    }

    @Override
    public void onDisable() {
        ValorDagger.cleanup();
        EaglesEyeBow.cleanup();
        getLogger().info("CustomItems disabled!");
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command command,
                             String label,
                             String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        return switch (command.getName().toLowerCase()) {
            case "valuedagger" -> {
                player.getInventory().addItem(ValorDagger.createValorDagger());
                player.sendMessage("\u00a7aYou received the \u00a76Valor Dagger\u00a7a!");
                yield true;
            }
            case "eagleseyebow" -> {
                player.getInventory().addItem(EaglesEyeBow.createEaglesEyeBow());
                player.sendMessage("\u00a7aYou received the \u00a79Eagle\u2019s Eye\u00a7a!");
                yield true;
            }
            default -> false;
        };
    }
}
