package net.me.xinzhi.knowledge.recipe;

import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import net.me.xinzhi.knowledge.core.ItemKey;
import net.me.xinzhi.knowledge.util.ReflectUtils;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条「扫出来的」配方：{@link #need} 管<b>认得出来</b>，{@link #cost} 管<b>真扣</b>。
 *
 * <h2>为什么不共用一个表</h2>
 * 模板机的输入只用来认配方，认出来不扣（{@link #amounts} 里为 0 的那些）。
 * 用同一张表既当匹配又当扣料，会把模板也扣掉 —— 那是把玩家的东西吃了。
 *
 * <p>{@code ticks} 取 {@link MachineRecipe#getTicks()}；读不到按 1 tick。
 *
 * <p>{@code energyPerCraft = 每 tick 耗电 × ticks}（见
 * {@link net.me.xinzhi.knowledge.util.EnergyProbe} 的语义说明）。
 */
public final class Scan {

    public final ItemStack[] inputs;
    /** 每条输入这次要扣多少；0 = 催化剂/模板，只认不扣 */
    public final int[] amounts;
    public final ItemStack[] outputs;
    public final int ticks;
    /** 每 tick 耗电（J） */
    public final int energyPerTick;
    /** 耗电是不是真读到的（false = 按配置兜底，界面写「能耗未知」） */
    public final boolean energyKnown;
    /** 一次配方耗电（J） */
    public final int energyPerCraft;
    /** 匹配用：每种物品至少要放多少（催化剂按 1 算） */
    public final Map<ItemKey, Integer> need = new LinkedHashMap<>();
    /** 扣料用：只含真要扣的（催化剂不在这里） */
    public final Map<ItemKey, Integer> cost = new LinkedHashMap<>();
    public final Map<ItemKey, Integer> give = new LinkedHashMap<>();
    /** 产物里有「随机产物」（本体常是 AIR，抠不出物品 key，但确实会出东西） */
    public final boolean probabilisticOutput;
    /** 全部输入都是催化剂（= 不消耗输入的配方，生成器专用） */
    public final boolean noConsume;
    /** 展示用：主产物 */
    public final ItemStack icon;

    /**
     * 「每次只出<b>一件</b>、出哪件按权重抽」的权重表（与 {@link #outputs} 等长）；
     * {@code null} = 普通配方（产物全给）。矿机走这条，见 {@link WeightedRecipe}。
     */
    public final int[] weights;

    /**
     * 每条产物<b>各自</b>的概率（0-100，与 {@link #outputs} 等长）；{@code null} = 全给。
     * 见 {@link ChanceRecipe}。矿机的 {@link #weights} 是「抽一件」，这是「各掷一次」。
     */
    public final int[] chances;

    /** {@link #chances} 是不是「一次只出 1 种」（掷中的候选里随机挑一件） */
    public final boolean chanceChooseOne;

    /** 原始配方对象的类名（/xzk dump 诊断用：看清是哪一层把概率吃掉了） */
    public final String rawClass;

    /** 按权重抽一组的候选组（每组整份给出）；{@code null} = 不是这种配方 */
    public final java.util.List<ItemStack[]> groups;
    /** 与 {@link #groups} 等长的权重 */
    public final int[] groupWeights;

    public Scan(MachineRecipe raw, int[] amounts, int energyPerTick, boolean energyKnown) {
        this.rawClass = raw.getClass().getSimpleName();
        this.inputs = raw.getInput() == null ? new ItemStack[0] : raw.getInput();
        this.outputs = raw.getOutput() == null ? new ItemStack[0] : raw.getOutput();
        this.ticks = Math.max(1, readTicks(raw));
        this.energyPerTick = Math.max(0, energyPerTick);
        this.energyKnown = energyKnown;
        this.energyPerCraft = this.energyPerTick * this.ticks;
        this.amounts = amounts == null ? new int[inputs.length] : amounts;

        for (int i = 0; i < inputs.length; i++) {
            ItemStack is = inputs[i];
            if (is == null || is.getType() == Material.AIR) continue;
            ItemKey k = ItemKey.of(is);
            if (k == null) continue;
            int amt = i < this.amounts.length ? this.amounts[i] : is.getAmount();
            need.merge(k, amt > 0 ? amt : 1, Integer::sum);
            if (amt > 0) cost.merge(k, amt, Integer::sum);
        }
        int[] ch = raw instanceof ChanceRecipe cr ? cr.chances() : null;
        this.chances = ch;
        this.chanceChooseOne = raw instanceof ChanceRecipe cr2 && cr2.chooseOne();
        this.groups = raw instanceof GroupedRecipe gr ? gr.groups() : null;
        this.groupWeights = raw instanceof GroupedRecipe gr2 ? gr2.groupWeights() : null;
        boolean prob = false;
        if (ch != null) {
            for (int c : ch) if (c < 100) { prob = true; break; }
        }
        for (ItemStack is : outputs) {
            if (is == null) continue;
            // ⚠ 判随机必须排在判空气之前：MomoTech 的 RandomizedItemStack 本体就是 AIR
            if (ReflectUtils.isProbabilistic(is)) { prob = true; continue; }
            if (is.getType() == Material.AIR) continue;
            ItemKey k = ItemKey.of(is);
            if (k == null) continue;
            give.merge(k, is.getAmount(), Integer::sum);
        }
        this.probabilisticOutput = prob;
        this.noConsume = cost.isEmpty() && producesSomething();
        this.icon = outputs.length > 0 ? outputs[0] : null;
        this.weights = raw instanceof WeightedRecipe wr ? wr.weights() : null;
    }

    private static int readTicks(MachineRecipe raw) {
        try {
            int t = raw.getTicks();
            return t > 0 ? t : 1;
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 是不是「每次按权重抽一件」（矿机）。这种配方的产物表是候选表，不是「全给」。 */
    public boolean weighted() {
        return weights != null && weights.length > 0;
    }

    /** 是不是「每件产物各掷一次概率」。{@link #weighted()} 的另一种，语义不同。 */
    public boolean chanced() {
        return chances != null && chances.length > 0;
    }

    /**
     * 产物数量算不准（按权重抽 / 按概率各掷 / 随机物品）：
     * 占位只按「还有没有空位」判，不按产物数量算。
     */
    public boolean uncertainOutput() {
        return grouped() || weighted() || chanced() || probabilisticOutput;
    }

    /** 是不是「按权重抽一组」（乱序技艺那类） */
    public boolean grouped() {
        return groups != null && !groups.isEmpty();
    }

    /** 这条配方到底会不会出东西（普通产物看得见 key，随机产物看不见） */
    public boolean producesSomething() {
        return !give.isEmpty() || probabilisticOutput;
    }

    /** 产物里有没有这个物品（生成器按模板筛配方用） */
    public boolean gives(ItemKey key) {
        return give.containsKey(key);
    }

    /** 认得出来这个物品（输入/模板里有它） */
    public boolean needs(ItemKey key) {
        return need.containsKey(key);
    }

    public List<String> lore() {
        List<String> out = new ArrayList<>();
        out.add("§7输入 §f" + joinInputs());
        out.add("§7产出 §f" + join(give));
        out.add("§7耗时 §f" + ticks + " §7tick §8· §7耗电 §f" + energyPerCraft + " §7J"
                + (energyKnown ? "" : "§c(未知)"));
        if (grouped()) out.add("§d随机抽一组 §f(" + groups.size() + " 组，按权重)");
        if (weighted()) out.add("§d每次按概率出 §f1 §d件");
        if (chanced()) out.add("§d每条产物各按自身概率出" + (chanceChooseOne ? " §8(一次只出 1 种)" : ""));
        if (noConsume) out.add("§a不消耗输入");
        return out;
    }

    /** 输入一行：认得出来要放多少；催化剂标「不扣」——诊断时一眼看出扣料判定对不对 */
    private String joinInputs() {
        if (need.isEmpty()) return "无";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<ItemKey, Integer> e : need.entrySet()) {
            Integer c = cost.get(e.getKey());
            if (c == null || c <= 0) {
                parts.add(label(e.getKey()) + "§7×§f" + e.getValue() + " §8(不扣)");
            } else {
                parts.add(label(e.getKey()) + "§7×§f" + c + (c < e.getValue() ? " §8(放" + e.getValue() + ")" : ""));
            }
        }
        return String.join("§7, ", parts);
    }

    private static String join(Map<ItemKey, Integer> map) {
        if (map.isEmpty()) return "无";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<ItemKey, Integer> e : map.entrySet()) {
            parts.add(label(e.getKey()) + "§7×§f" + e.getValue());
        }
        return String.join("§7, ", parts);
    }

    /** 主产物的显示名（配方选择界面当标题用，免得只看到 #12 不知道是啥） */
    public String outputName() {
        if (!give.isEmpty()) return label(give.keySet().iterator().next());
        if (icon != null) {
            try {
                if (icon.hasItemMeta() && icon.getItemMeta().hasDisplayName()) {
                    return "§f" + ChatColor.stripColor(icon.getItemMeta().getDisplayName());
                }
            } catch (Throwable ignored) {
            }
            ItemKey k = ItemKey.of(icon);
            if (k != null) return label(k);
        }
        return "§7随机产物";
    }

    /**
     * 物品显示名：粘液物品 → 它自己的物品名，原版 → 中文名（{@link ItemKey#getDisplayName()}）。
     * <p>腐竹 2026-11-08：「把 id 转换为中文名称，主附属是通过语言文件来转换的，有更好的办法吗？」
     * 更好的办法就是直接问物品本身 —— 粘液物品自带名字，原版名走 jar 内 zh_cn.json，
     * 都不需要服主维护映射表；覆盖不到才退回 {@code SF:ID}，好让读不懂的名字还能去查。
     */
    private static String label(ItemKey k) {
        String name;
        try {
            name = k.getDisplayName();
        } catch (Throwable t) {
            name = null;
        }
        if (name == null || name.isBlank()) name = k.toString();
        String plain = ChatColor.stripColor(name);
        if (plain == null || plain.isBlank()) plain = k.toString();
        return "§f" + plain;
    }
}