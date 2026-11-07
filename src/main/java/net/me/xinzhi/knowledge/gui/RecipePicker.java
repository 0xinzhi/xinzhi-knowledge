package net.me.xinzhi.knowledge.gui;

import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.machines.MachineUtil;
import net.me.xinzhi.knowledge.recipe.Scan;
import net.me.xinzhi.knowledge.util.GuiItems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
 * 配方选择界面：一页 45 条，第 46/54 格翻页，第 50 格「取消选择」。
 *
 * <p>选中后写进方块数据 {@code xzk-recipe}（下标），机器固定跑这一条；
 * 取消 = 删掉这个键，机器回到自动识别。
 */
public final class RecipePicker {

    private RecipePicker() { }

    public static ItemStack icon(Scan cur) {
        if (cur == null) {
            return GuiItems.of(Material.ENDER_EYE, "§e配方选择 §7· §f自动识别",
                    "§7点这里从被识别机器的配方里挑一条",
                    "§7选定后固定跑这条，§8再点一次可取消");
        }
        return GuiItems.of(Material.ENDER_EYE, "§a配方选择 §7· §f已锁定",
                "§7点这里重新选 / 取消选择",
                "§8点「取消选择」回到自动识别");
    }

    public static void open(Player p, Location loc, String machineId, List<Scan> list, int current, BlockMenu back) {
        Holder holder = new Holder(loc, machineId, list, current, back);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8配方选择 §7· " + list.size() + " 条");
        holder.inv = inv;
        holder.fill(0);
        p.openInventory(inv);
    }

    public static final class Holder implements InventoryHolder {

        private final Location loc;
        private final String machineId;
        private final List<Scan> list;
        private final int current;
        /** 选完/取消后要跳回去的机器界面 */
        private final BlockMenu back;
        private Inventory inv;
        private int page;

        Holder(Location loc, String machineId, List<Scan> list, int current, BlockMenu back) {
            this.loc = loc;
            this.machineId = machineId;
            this.list = list;
            this.current = current;
            this.back = back;
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
                    meta.setDisplayName((idx == current ? "§a✔ " : "§7") + "#" + (idx + 1) + " " + s.outputName());
                    List<String> lore = s.lore();
                    lore.add(idx == current ? "§a当前锁定" : "§e点击锁定这条配方");
                    meta.setLore(lore);
                    it.setItemMeta(meta);
                }
                inv.setItem(i, it);
            }
            inv.setItem(45, GuiItems.of(Material.ARROW, "§7上一页"));
            inv.setItem(53, GuiItems.of(Material.ARROW, "§7下一页"));
            inv.setItem(49, GuiItems.of(Material.BARRIER, "§c取消选择",
                    "§7回到自动识别模式"));
            inv.setItem(48, GuiItems.of(Material.BOOK, "§b第 §f" + (page + 1) + "§b 页",
                    "§7共 §f" + Math.max(1, (list.size() + 44) / 45) + " §7页"));
            for (int i = 45; i < 54; i++) if (inv.getItem(i) == null) {
                inv.setItem(i, GuiItems.of(Material.GRAY_STAINED_GLASS_PANE, " "));
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
            if (!(e.getWhoClicked() instanceof Player p)) return;
            int slot = e.getRawSlot();
            if (slot < 0) return;
            if (slot == 45) { h.prev(); return; }
            if (slot == 53) { h.next(); return; }
            if (slot == 49) {
                MachineUtil.unset(h.loc, "xzk-recipe");
                p.sendMessage("§7已取消锁定，机器回到自动识别。");
                backToMachine(p, h);
                return;
            }
            int idx = h.page * 45 + slot;
            if (slot >= 45 || idx >= h.list.size()) return;
            MachineUtil.set(h.loc, "xzk-recipe", String.valueOf(idx));
            p.sendMessage("§a已锁定配方 #" + (idx + 1) + " §7（" + h.machineId + "）");
            backToMachine(p, h);
        }

        /**
         * 选完配方直接跳回机器界面，而不是把界面关掉留玩家站在原地看着空气。
         * <p>必须下一 tick 再开：Bukkit 在点击事件里 closeInventory 之后立刻 open，
         * 客户端会把新界面当成被关掉的那个一起收掉（表现为「闪一下就没了」）。
         */
        private static void backToMachine(Player p, Holder h) {
            p.closeInventory();
            BlockMenu back = h.back;
            if (back == null) return;
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("XinZhiKnowledge");
            if (plugin == null) { back.open(p); return; }
            Bukkit.getScheduler().runTask(plugin, () -> back.open(p));
        }
    }
}