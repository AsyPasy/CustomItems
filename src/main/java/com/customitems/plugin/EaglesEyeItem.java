package com.customitems.plugin;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class EaglesEyeItem {

    public static final String NAME = "\u00a76Eagle's Eye";

    public static ItemStack create() {
        ItemStack item = new ItemStack(Material.SPIDER_EYE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(NAME);
        meta.setLore(List.of(
            "\u00a77A rare drop from the \u00a76Eagle's Baby Boss\u00a77.",
            "\u00a77Used to craft the \u00a76\u00a7lEagle's Eye Bow\u00a77."
        ));
        item.setItemMeta(meta);
        return item;
    }

    public static boolean is(ItemStack item) {
        if (item == null || item.getType() != Material.SPIDER_EYE) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName()
                && meta.getDisplayName().equals(NAME);
    }
}
