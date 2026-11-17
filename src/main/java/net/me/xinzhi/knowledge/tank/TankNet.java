package net.me.xinzhi.knowledge.tank;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.core.ItemKey;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 输入 / 输出储罐的识别与读写。
 *
 * <p>储罐只认「机器同一层、八邻格」里的自己人（腐竹 2026-11-15：「同层 1 格范围」）。
 * 规则的落点是「输入先用储罐的、产物先塞储罐」，机器那边只负责按这个顺序调用。
 */
public final class TankNet {

    /** 一个储罐的格子数（54 = 双箱一页半，腐竹选的） */
    public static final int SLOTS = 54;

    private static String inputId;
    private static String outputId;

    private TankNet() {
    }

    public static void init(String input, String output) {
        inputId = input;
        outputId = output;
    }

    public static boolean isInputTank(ItemStack it) {
        return hasId(it, inputId);
    }

    public static boolean isOutputTank(ItemStack it) {
        return hasId(it, outputId);
    }

    public static boolean isTank(ItemStack it) {
        return isInputTank(it) || isOutputTank(it);
    }

    private static boolean hasId(ItemStack it, String id) {
        if (it == null || id == null) return false;
        try {
            SlimefunItem sf = SlimefunItem.getByItem(it);
            return sf != null && id.equals(sf.getId());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 机器周围同层的储罐（输入或输出由 {@code output} 决定），顺序固定：先四个正方向，再斜角 */
    public static List<BlockMenu> around(Block b, boolean output) {
        List<BlockMenu> list = new ArrayList<>(8);
        for (int[] d : NEIGHBOURS) {
            Block nb = b.getRelative(d[0], 0, d[1]);
            BlockMenu m = menuOf(nb, output);
            if (m != null) list.add(m);
        }
        return list;
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static BlockMenu menuOf(Block b, boolean output) {
        try {
            SlimefunItem sf = BlockStorage.check(b);
            if (sf == null) return null;
            String id = output ? outputId : inputId;
            if (id == null || !id.equals(sf.getId())) return null;
            return BlockStorage.getInventory(b);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 储罐里有几件这种物品 */
    public static int count(BlockMenu tank, ItemStack proto) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack it = tank.getItemInSlot(i);
            if (isSame(it, proto)) n += it.getAmount();
        }
        return n;
    }

    /** 从储罐里取 {@code want} 件，返回实际取到的数量 */
    public static int withdraw(BlockMenu tank, ItemStack proto, int want) {
        int need = Math.max(0, want);
        for (int i = 0; i < SLOTS && need > 0; i++) {
            ItemStack it = tank.getItemInSlot(i);
            if (!isSame(it, proto)) continue;
            int take = Math.min(need, it.getAmount());
            setSlot(tank, i, it, it.getAmount() - take);
            need -= take;
        }
        return Math.max(0, want) - need;
    }

    /**
     * 把一件东西塞进任意一个储罐：同类先叠，再找空格。
     *
     * @return 塞不下的剩余（null 表示全塞进去了）
     */
    public static ItemStack deposit(List<BlockMenu> tanks, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return null;
        ItemStack rest = item.clone();
        for (BlockMenu tank : tanks) {
            for (int i = 0; i < SLOTS && rest.getAmount() > 0; i++) {
                ItemStack it = tank.getItemInSlot(i);
                if (it == null || it.getType().isAir()) continue;
                if (!isSame(it, rest)) continue;
                int room = Math.max(0, it.getMaxStackSize() - it.getAmount());
                if (room <= 0) continue;
                int move = Math.min(room, rest.getAmount());
                it.setAmount(it.getAmount() + move);
                tank.replaceExistingItem(i, it);
                rest.setAmount(rest.getAmount() - move);
            }
            for (int i = 0; i < SLOTS && rest.getAmount() > 0; i++) {
                ItemStack it = tank.getItemInSlot(i);
                if (it != null && !it.getType().isAir()) continue;
                ItemStack put = rest.clone();
                tank.replaceExistingItem(i, put);
                rest.setAmount(0);
            }
            if (rest.getAmount() <= 0) return null;
        }
        return rest.getAmount() <= 0 ? null : rest;
    }

    /** 还有多少空格（按「一格一件」算，只用于判断够不够放） */
    public static int emptySlots(List<BlockMenu> tanks) {
        int n = 0;
        for (BlockMenu tank : tanks) {
            for (int i = 0; i < SLOTS; i++) {
                ItemStack it = tank.getItemInSlot(i);
                if (it == null || it.getType().isAir()) n++;
            }
        }
        return n;
    }

    /**
     * 把机器产物格里的东西全扫进输出储罐（「先放入储罐」的通用写法）。
     *
     * <p>每 tick 开头扫一次就行：储罐塞得下的都过去，塞不下的留在原格，
     * 于是一条流水线下来「储罐满了才开始堆在机器里」自然成立。
     *
     * @return 挪走的件数
     */
    public static int sweep(BlockMenu menu, int[] slots, List<BlockMenu> tanks) {
        if (tanks == null || tanks.isEmpty() || slots == null) return 0;
        int moved = 0;
        for (int s : slots) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            int before = it.getAmount();
            ItemStack rest = deposit(tanks, it);
            if (rest == null) {
                menu.replaceExistingItem(s, null);
                moved += before;
            } else if (rest.getAmount() < before) {
                rest.setAmount(rest.getAmount());
                menu.replaceExistingItem(s, rest);
                moved += before - rest.getAmount();
            }
        }
        return moved;
    }

    /**
     * 从输入储罐把机器输入格「喂满」：只补格子里已经有的那种物品（机器靠格里的东西认产物，
     * 罐里冻着别的料也不会让机器乱认）。
     *
     * @return 补进去的件数
     */
    public static int refill(BlockMenu menu, int[] slots, List<BlockMenu> tanks) {
        if (tanks == null || tanks.isEmpty() || slots == null) return 0;
        int moved = 0;
        for (int s : slots) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType().isAir()) continue;
            int room = Math.max(0, it.getMaxStackSize() - it.getAmount());
            if (room <= 0) continue;
            for (BlockMenu tank : tanks) {
                int take = withdraw(tank, it, room);
                if (take > 0) {
                    int now = it.getAmount() + take;
                    it.setAmount(now);
                    menu.replaceExistingItem(s, it);
                    room -= take;
                    moved += take;
                }
                if (room <= 0) break;
            }
        }
        return moved;
    }

    /**
     * 把储罐里的东西数成「物品 → 数量」。
     * <p>大模板生成器靠它读模板：不可堆叠的模板一格算 1，堆叠数照常累加。
     */
    public static Map<ItemKey, Integer> contents(List<BlockMenu> tanks) {
        Map<ItemKey, Integer> map = new HashMap<>();
        if (tanks == null || tanks.isEmpty()) return map;
        for (BlockMenu tank : tanks) {
            for (int i = 0; i < SLOTS; i++) {
                ItemStack it = tank.getItemInSlot(i);
                if (it == null || it.getType().isAir()) continue;
                ItemKey k = ItemKey.of(it);
                if (k == null) continue;
                map.merge(k, it.getAmount(), Integer::sum);
            }
        }
        return map;
    }

    private static boolean isSame(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType().isAir() || b.getType().isAir()) return false;
        try {
            return a.isSimilar(b);
        } catch (Throwable t) {
            return a.getType() == b.getType();
        }
    }

    private static void setSlot(BlockMenu tank, int slot, ItemStack it, int amount) {
        if (amount <= 0) {
            tank.replaceExistingItem(slot, null);
            return;
        }
        it.setAmount(amount);
        tank.replaceExistingItem(slot, it);
    }
}
