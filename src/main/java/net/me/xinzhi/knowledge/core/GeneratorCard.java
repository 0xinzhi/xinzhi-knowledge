package net.me.xinzhi.knowledge.core;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import net.me.xinzhi.knowledge.recipe.Scan;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成器卡：一颗通用道具 + PDC 记「产物 / 间隔 / 数量」。
 *
 * <p>为什么不做成几千个物品：粘液物品种类几千个，一个产物注册一个物品既拖启动又拖指南。
 * 和主附属的「编码种子」是同一套做法。
 *
 * <p>卡片放进知识生成器的识别槽，知识生成器就把它当成一条「不消耗输入的配方」无限产
 * —— 见 {@link #scanOf(ItemStack, int)}。
 */
public final class GeneratorCard {

    /** 一张卡最快也只能 10 tick 产一次（腐竹 2026-11-14 定的下限） */
    public static final int MIN_INTERVAL = 10;

    /** 单次融合把间隔拉长的倍数（腐竹 2026-11-14：「合成次数越多，延长1.2倍」） */
    public static final double FUSE_GROWTH = 1.2;

    /** 融合一次之后的新间隔：向上取整，最低 {@link #MIN_INTERVAL} */
    public static int grow(int interval) {
        return Math.max(MIN_INTERVAL, (int) Math.ceil(Math.max(MIN_INTERVAL, interval) * FUSE_GROWTH));
    }

    private static NamespacedKey kProduct;
    private static NamespacedKey kInterval;
    private static NamespacedKey kCount;
    /**
     * 随机产物卡：候选表（{@code KEY=数量;KEY=数量}）。
     * 没有这个键 = 普通卡（每次产一种东西）；有这个键 = 每次从候选里抽一件
     * （腐竹 2026-11-15：无尽磨粉机那类随机产物也要能融合成生成器）。
     */
    private static NamespacedKey kPool;
    private static String cardItemId;
    private static String baseItemId;
    private static ItemStack cardTemplate;

    private GeneratorCard() { }

    public static void init(JavaPlugin plugin, String cardId, ItemStack cardExample, String baseId) {
        kProduct = new NamespacedKey(plugin, "gen-product");
        kInterval = new NamespacedKey(plugin, "gen-interval");
        kCount = new NamespacedKey(plugin, "gen-count");
        kPool = new NamespacedKey(plugin, "gen-pool");
        cardItemId = cardId;
        baseItemId = baseId;
        cardTemplate = cardExample == null ? null : cardExample.clone();
    }

    // ---------------- 认物品 ----------------

    private static String idOf(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        try {
            SlimefunItem sf = SlimefunItem.getByItem(it);
            return sf == null ? null : sf.getId();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isBase(ItemStack it) {
        return baseItemId != null && baseItemId.equals(idOf(it));
    }

    public static boolean isCard(ItemStack it) {
        if (cardItemId == null || !cardItemId.equals(idOf(it))) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(kProduct, PersistentDataType.STRING);
    }

    // ---------------- 读字段 ----------------

    public static ItemKey product(ItemStack it) {
        if (!isCard(it)) return null;
        return ItemKey.fromStored(it.getItemMeta().getPersistentDataContainer()
                .get(kProduct, PersistentDataType.STRING));
    }

    public static int interval(ItemStack it) {
        if (!isCard(it)) return MIN_INTERVAL;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(kInterval, PersistentDataType.INTEGER);
        return v == null || v < MIN_INTERVAL ? MIN_INTERVAL : v;
    }

    public static int count(ItemStack it) {
        if (!isCard(it)) return 1;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(kCount, PersistentDataType.INTEGER);
        return v == null || v < 1 ? 1 : v;
    }

    /** 这张卡是不是「随机产物卡」（每次从候选里抽一件） */
    public static boolean isPool(ItemStack it) {
        if (!isCard(it)) return false;
        return it.getItemMeta().getPersistentDataContainer().has(kPool, PersistentDataType.STRING);
    }

    /** 随机卡的候选（带各自数量）；普通卡返回空表 */
    public static List<ItemStack> poolItems(ItemStack it) {
        List<ItemStack> out = new ArrayList<>();
        String raw = poolRaw(it);
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            ItemKey k = ItemKey.fromStored(part.substring(0, eq));
            if (k == null) continue;
            int amt;
            try { amt = Math.max(1, Integer.parseInt(part.substring(eq + 1))); }
            catch (NumberFormatException e) { continue; }
            ItemStack t = k.toTemplate();
            if (t == null) continue;
            t.setAmount(amt);
            out.add(t);
        }
        return out;
    }

    private static String poolRaw(ItemStack it) {
        if (!isCard(it)) return null;
        return it.getItemMeta().getPersistentDataContainer().get(kPool, PersistentDataType.STRING);
    }

    /**
     * 这张卡能充当哪些物品。
     * <p>普通卡 = 它那一种；随机卡 = 全部候选 —— 融合时一张随机卡就能顶它候选里的任意一种原料。
     */
    public static List<ItemKey> products(ItemStack it) {
        List<ItemKey> out = new ArrayList<>();
        if (!isCard(it)) return out;
        if (isPool(it)) {
            for (ItemStack c : poolItems(it)) {
                ItemKey k = ItemKey.of(c);
                if (k != null) out.add(k);
            }
            if (!out.isEmpty()) return out;
        }
        ItemKey k = product(it);
        if (k != null) out.add(k);
        return out;
    }

    /** 产物显示名（不含 §b 前缀） */
    public static String productName(ItemStack it) {
        if (isPool(it)) return "随机产物(" + poolItems(it).size() + "种)";
        ItemKey k = product(it);
        return k == null ? "未知" : k.getDisplayName();
    }

    /** 识别用的签名：同一张卡（同产物同间隔）只重算一次 */
    public static String signature(ItemStack it) {
        if (isPool(it)) {
            return "POOL|" + poolRaw(it) + "|" + interval(it);
        }
        ItemKey k = product(it);
        return "CARD|" + (k == null ? "?" : k.toString()) + "|" + interval(it);
    }

    /** 两张卡是不是同一种（产物 + 间隔 + 单次数量），用于堆叠判断 */
    public static boolean same(ItemStack a, ItemStack b) {
        return isCard(a) && isCard(b)
                && signature(a).equals(signature(b))
                && count(a) == count(b);
    }

    public static ItemStack template() {
        return cardTemplate == null ? null : cardTemplate.clone();
    }

    // ---------------- 造卡 ----------------

    public static ItemStack create(ItemKey product, int interval, int count) {
        if (product == null || cardTemplate == null) return null;
        ItemStack it = cardTemplate.clone();
        it.setAmount(1);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        int iv = Math.max(MIN_INTERVAL, interval);
        int c = Math.max(1, count);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(kProduct, PersistentDataType.STRING, product.toString());
        pdc.set(kInterval, PersistentDataType.INTEGER, iv);
        pdc.set(kCount, PersistentDataType.INTEGER, c);
        String name = product.getDisplayName();
        meta.setDisplayName("§b" + name + "生成器");
        List<String> lore = new ArrayList<>();
        lore.add("§8放进知识生成器的识别槽：不耗输入，一直产这件东西");
        lore.add("§7每 §f" + iv + "§7 tick 产 §f" + c + "§7 个 §f" + name);
        lore.add("§8按配方融合一次，间隔 ×1.2（最低 " + MIN_INTERVAL + " tick）；合并/拆分不变");
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /**
     * 造一张「随机产物卡」（腐竹 2026-11-15：无尽磨粉机这类随机产物要能融合成生成器）。
     *
     * <p>每 {@code interval} tick <b>抽 {@code rolls} 次</b>，每次从候选里等概率挑一件、
     * 按那件自己的数量出 —— 和原机器「跑一次随机出一种」完全同分布，不会因为拆卡变成每种都给。
     */
    public static ItemStack createPool(List<ItemStack> candidates, int interval, int rolls) {
        if (candidates == null || candidates.isEmpty() || cardTemplate == null) return null;
        StringBuilder raw = new StringBuilder();
        List<String> names = new ArrayList<>();
        ItemKey first = null;
        int n = 0;
        for (ItemStack c : candidates) {
            if (c == null) continue;
            ItemKey k = ItemKey.of(c);
            if (k == null) continue;
            if (first == null) first = k;
            if (raw.length() > 0) raw.append(';');
            raw.append(k).append('=').append(Math.max(1, c.getAmount()));
            names.add(k.getDisplayName() + "§7×§f" + Math.max(1, c.getAmount()));
            if (++n >= 64) break;
        }
        if (first == null) return null;

        ItemStack it = cardTemplate.clone();
        it.setAmount(1);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        int iv = Math.max(MIN_INTERVAL, interval);
        int r = Math.max(1, rolls);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // kProduct 留第一个候选：isCard() 靠它认卡，显示/兜底也有个落点
        pdc.set(kProduct, PersistentDataType.STRING, first.toString());
        pdc.set(kInterval, PersistentDataType.INTEGER, iv);
        pdc.set(kCount, PersistentDataType.INTEGER, r);
        pdc.set(kPool, PersistentDataType.STRING, raw.toString());
        meta.setDisplayName("§b随机产物生成器 §8(" + names.size() + " 种)");
        List<String> lore = new ArrayList<>();
        lore.add("§8放进知识生成器的识别槽 / 编码生成器：不耗输入，一直产");
        lore.add("§7每 §f" + iv + "§7 tick 抽 §f" + r + "§7 次，每次随机出一种 §8(与原机器同分布)");
        for (int i = 0; i < names.size() && i < 6; i++) lore.add("§8· §f" + names.get(i));
        if (names.size() > 6) lore.add("§8…还有 " + (names.size() - 6) + " 种");
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /**
     * 把一张卡变成知识生成器能跑的那条「不消耗输入的配方」。
     *
     * @param energyPerTick 每 tick 耗电（J），0 = 不吃电
     */
    public static Scan scanOf(ItemStack card, int energyPerTick) {
        if (isPool(card)) {
            List<ItemStack> pool = poolItems(card);
            if (pool.isEmpty()) return null;
            return new Scan(pool, Math.max(MIN_INTERVAL, interval(card)), Math.max(0, energyPerTick));
        }
        ItemKey k = product(card);
        if (k == null) return null;
        ItemStack out = k.toTemplate();
        if (out == null) return null;
        out.setAmount(1);
        // ⚠ MachineRecipe(int, ...) 的第一个参数是「秒」，内部 ticks = 秒 × 2（javap 实证：
        // iload_1 / iconst_2 / imul / putfield ticks）。卡面写的是 tick，所以先构造再覆写 ticks，
        // 否则 10 tick 的卡在知识生成器里会变成 20 tick。
        MachineRecipe mr = new MachineRecipe(1, new ItemStack[0], new ItemStack[]{out});
        mr.setTicks(Math.max(GeneratorCard.MIN_INTERVAL, interval(card)));
        return new Scan(mr, new int[0], Math.max(0, energyPerTick), true);
    }

    /**
     * 机器槽里所有同类卡「每个间隔合计产多少个」。
     * <p>腐竹 2026-11-14：「相同的生成器但是不同时间可以叠加，不会干扰时间只会增加产出数量」
     * —— 所以这里只把数量加起来，间隔由第一张卡决定（不同间隔的卡由融合机先合并）。
     */
    public static int totalPerInterval(ItemStack[] cards) {
        int total = 0;
        for (ItemStack it : cards) {
            if (!isCard(it)) continue;
            total += Math.max(1, it.getAmount()) * count(it);
        }
        return Math.max(1, total);
    }
}
