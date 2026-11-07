package net.me.xinzhi.knowledge.gui;

import net.me.xinzhi.knowledge.recipe.Scan;
import net.me.xinzhi.knowledge.util.GuiItems;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * 配方查看界面：<b>只读</b>，一页 45 条，第 46/54 格翻页，第 50 格显页码。
 *
 * <p>配方读取器道具点开的就是这个 —— 它不写任何方块数据，也不锁配方，
 * 和机器界面里的「配方选择」是两回事（那个会写 xzk-recipe）。
 */
public final class RecipeViewer {

    private RecipeViewer() { }

    public static void open(Player p, String machineName, List<Scan> list) {
        Holder h = new Holder(machineName, list);
        Inventory inv = Bukkit.createInventory(h, 54,
                "§8配方 " + shortName(machineName) + " §7· " + list.size() + " 条");
        h.inv = inv;
        h.fill(0);
        p.openInventory(inv);
    }

    private static String shortName(String n) {
        String plain = n == null ? "?" : ChatColor.stripColor(n);
        if (plain == null || plain.isBlank()) plain = "?";
        return plain.length() > 16 ? plain.substring(0, 16) : plain;
    }

    public static final class Holder implements InventoryHolder {

        private final String machineName;
        private final List<Scan> list;
        private Inventory inv;
        private int page;

        Holder(String machineName, List<Scan> list) {
            this.machineName = machineName;
            this.list = list;
        }

        @Override public @Nonnull Inventory getInventory() { return inv; }

        void fill(int page) {
            this.page = page;
            inv.clear();
            int start = page * 45;
            for (int i = 0; i < 45; i++) {
                int idx = start + i;
                if (idx >= list.size()) break;
                Scan s = list.get(idx);
                ItemStack it = s.icon == null ? new ItemStack(Material.PAPER) : s.icon.clone();
                it.setAmount(Math.min(64, Math.max(1, it.getAmount())));
                var meta = it.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§7#" + (idx + 1) + " " + s.outputName());
                    List<String> lore = s.lore();
                    lore.add(s.producesSomething() ? "§8只读展示 · 机器里照样能跑这条" : "§8只读展示");
                    meta.setLore(lore);
                    it.setItemMeta(meta);
                }
                inv.setItem(i, it);
            }
            inv.setItem(45, GuiItems.of(Material.ARROW, "§7上一页"));
            inv.setItem(53, GuiItems.of(Material.ARROW, "§7下一页"));
            inv.setItem(49, GuiItems.of(Material.BOOK, "§b第 §f" + (page + 1) + "§b 页",
                    "§7共 §f" + Math.max(1, (list.size() + 44) / 45) + " §7页"));
            for (int i = 45; i < 54; i++) {
                if (inv.getItem(i) == null) inv.setItem(i, GuiItems.of(Material.GRAY_STAINED_GLASS_PANE, " "));
            }
        }

        void next() { if ((page + 1) * 45 < list.size()) fill(page + 1); }
        void prev() { if (page > 0) fill(page - 1); }
    }

    public static final class Click implements Listener {

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof Holder h)) return;
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot == 45) h.prev();
            else if (slot == 53) h.next();
        }
    }
}
