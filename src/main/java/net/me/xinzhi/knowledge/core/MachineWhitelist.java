package net.me.xinzhi.knowledge.core;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.recipe.RecipeScanner;
import net.me.xinzhi.knowledge.recipe.Scan;
import net.me.xinzhi.knowledge.tank.TankNet;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 名单识别：有些机器「配方」根本不在 MachineRecipe 里，扫描器永远扫不到，
 * 只能按机器特征 + 模板物品手写一套规则（腐竹 2026-11-17 第 4 条）。
 *
 * <p>第一个名单成员是<b>生物模拟室</b>：它吃「生物芯片」，芯片决定产哪种生物的掉落物。
 * 无尽附属的模拟室 30 tick 出一件，逻辑工艺的「无尽生物模拟室」2 tick 出一件。
 * 融合机把「模拟室 + 芯片」换成生成器卡时，每张卡<b>一次只产 1 个</b>（腐竹 2026-11-17）。
 */
public final class MachineWhitelist {

    /** 无尽附属生物模拟室 */
    private static final String INFINITY_CHAMBER = "MOB_SIMULATION_CHAMBER";
    /** 逻辑工艺的无尽生物模拟室（id 前缀 LOGITECH_，2 tick 出一件） */
    private static final String LOGITECH_MARK = "MOBSIMULATION";

    private MachineWhitelist() { }

    private static String upperId(SlimefunItem sf) {
        if (sf == null || sf.getId() == null) return "";
        return sf.getId().toUpperCase(Locale.ROOT);
    }

    /** 这台机器是不是「名单识别」里的模拟室（无尽 / 逻辑各一台） */
    public static boolean isSimChamber(SlimefunItem sf) {
        String id = upperId(sf);
        return id.contains(INFINITY_CHAMBER) || id.contains(LOGITECH_MARK);
    }

    /** 模拟室多久产出一件：逻辑的 2 tick，无尽头 30 tick */
    public static int chamberTicks(SlimefunItem sf) {
        return upperId(sf).contains("LOGITECH") ? 2 : 30;
    }

    /**
     * 生物芯片能产出哪些东西。
     *
     * <p>芯片本体是 {@code io.github.mooy1.infinityexpansion.items.mobdata.MobDataCard}，
     * 掉落表在它的 {@code drops}（RandomizedSet，带权重）里 —— 这里只取「有哪几种」，
     * 权重怎么分交给随机卡按同分布抽。
     *
     * @return 候选列表（每种一件，按芯片自己的数量）；不是芯片 / 空芯片返回空表
     */
    public static List<ItemStack> chipProducts(ItemStack chip) {
        if (chip == null || chip.getType().isAir()) return List.of();
        SlimefunItem sf;
        try {
            sf = SlimefunItem.getByItem(chip);
        } catch (Throwable t) {
            return List.of();
        }
        if (sf == null) return List.of();
        if (!sf.getClass().getName().endsWith("MobDataCard")) return List.of();
        Object drops;
        try {
            Field f = sf.getClass().getDeclaredField("drops");
            f.setAccessible(true);
            drops = f.get(sf);
        } catch (Throwable t) {
            return List.of();
        }
        if (!(drops instanceof Iterable<?>)) return List.of();
        Map<String, ItemStack> uniq = new LinkedHashMap<>();
        try {
            for (Object o : (Iterable<?>) drops) {
                if (!(o instanceof ItemStack)) continue;
                ItemStack it = (ItemStack) o;
                if (it.getType().isAir()) continue;
                ItemKey k = ItemKey.of(it);
                if (k == null) continue;
                uniq.putIfAbsent(k.toString(), it.clone());
            }
        } catch (Throwable t) {
            return List.of();
        }
        return new ArrayList<>(uniq.values());
    }

    /** 这个物品能不能当「模拟室」的模板用（= 有掉落表的生物芯片） */
    public static boolean isChamberChip(ItemStack it) {
        return !chipProducts(it).isEmpty();
    }

    /** 找到的芯片 + 它一共有几张 */
    public static final class Chip {
        public final ItemStack item;
        public final int count;
        public final List<ItemStack> products;

        private Chip(ItemStack item, int count, List<ItemStack> products) {
            this.item = item;
            this.count = count;
            this.products = products;
        }

        /** 同一种芯片的内容签名（换芯片才重算配方） */
        public String signature() {
            ItemKey k = ItemKey.of(item);
            return (k == null ? "?" : k.toString()) + "|" + products.size();
        }
    }

    /**
     * 在「机器格 + 输入储罐」里找第一张芯片，并数它一共有几张。
     *
     * <p>知识生成器把模板放在模板区（第 49 格），大模板生成器把东西放在输入储罐里，
     * 两条路都走这里：先看机器槽位，再看储罐 54 格。
     */
    public static Chip findChip(BlockMenu menu, int[] slots, List<BlockMenu> tanks) {
        List<ItemStack> all = new ArrayList<>();
        if (menu != null && slots != null) {
            for (int s : slots) {
                ItemStack it = menu.getItemInSlot(s);
                if (it != null && !it.getType().isAir()) all.add(it);
            }
        }
        if (tanks != null) {
            for (BlockMenu t : tanks) {
                for (int s = 0; s < TankNet.SLOTS; s++) {
                    ItemStack it = t.getItemInSlot(s);
                    if (it != null && !it.getType().isAir()) all.add(it);
                }
            }
        }
        ItemStack chip = null;
        String chipKey = null;
        int count = 0;
        for (ItemStack it : all) {
            ItemKey k = ItemKey.of(it);
            if (chip == null) {
                if (k == null || !isChamberChip(it)) continue;
                chip = it.clone();
                chipKey = k.toString();
                count += it.getAmount();
            } else if (k != null && chipKey != null && chipKey.equals(k.toString())) {
                count += it.getAmount();
            }
        }
        if (chip == null) return null;
        return new Chip(chip, Math.max(1, count), chipProducts(chip));
    }

    /**
     * 「A=A」型自输出机器：配方是「一堆 A 换 1 个 A」，实际是<b>拿 A 当模板出 A</b> 的机器。
     *
     * <p>动力科技蜂箱（MaterialHive）和矿物化蜂房（MineralizedApiary）都是这个形状，
     * 腐竹 2026-11-17：「蜂箱……他的配方是 A=A，但实际是 200 tick 产出 1 个 A」。
     * 这类机器的产出由「放进去的那件物品」决定，所以必须按模板逐件建伪配方。
     */
    public static boolean isSelfOutput(SlimefunItem sf) {
        if (sf == null) return false;
        String id = upperId(sf);
        if (id.contains("HIVE") || id.contains("APIARY")) return true;
        String cn = sf.getClass().getSimpleName();
        return cn.contains("Hive") || cn.contains("Apiary");
    }

    /**
     * 自输出机器产这件东西的伪配方：不扣输入，间隔取机器自己那条 A=A 配方的 tick。
     *
     * <p>tick 从机器真实配方里读（不同版本 200/3600 都可能），不写死数字。
     *
     * @return 认不出这个物品返回 null
     */
    public static Scan selfOutputScan(ItemStack template, SlimefunItem sf) {
        if (template == null || template.getType().isAir() || sf == null) return null;
        ItemKey key = ItemKey.of(template);
        if (key == null) return null;
        List<Scan> scans;
        try {
            scans = RecipeScanner.of(sf);
        } catch (Throwable t) {
            return null;
        }
        for (Scan s : scans) {
            if (!s.gives(key)) continue;
            if (s.cost.size() != 1 || !s.cost.containsKey(key)) continue;
            ItemStack one = key.toTemplate();
            if (one == null) continue;
            one.setAmount(1);
            int energy = s.energyPerTick > 0 ? s.energyPerTick : 20;
            return new Scan(List.of(one), Math.max(10, s.ticks), energy);
        }
        return null;
    }

    /**
     * 模拟室的伪配方：没有输入，产物=芯片掉落表的等权候选，每次抽一件。
     * 间隔取 min(10, 机器节拍)，逻辑工艺 2 tick 的机器也给 10 tick（生成器卡最快 10 tick）。
     */
    public static Scan chipScan(Chip chip, int chamberTicks, int energyPerTick) {
        if (chip == null || chip.products.isEmpty()) return null;
        int ticks = Math.max(10, chamberTicks);
        return new Scan(chip.products, ticks, energyPerTick);
    }
}
