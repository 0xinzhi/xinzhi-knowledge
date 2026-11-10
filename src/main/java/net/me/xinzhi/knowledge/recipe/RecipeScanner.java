package net.me.xinzhi.knowledge.recipe;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.items.electric.AbstractEnergyProvider;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineFuel;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.Material;
import net.me.xinzhi.knowledge.util.EnergyProbe;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把「识别槽里那台机器」的配方读出来 —— 扫描这一层只看 {@link RecipeExtractor}，
 * 不自己拼「输入,产物」配对（那是三个 bug 的共同来源，2026-11-07 重写）。
 *
 * <h2>三个视角</h2>
 * <ul>
 *   <li>{@link #of}：全部（诊断用）；</li>
 *   <li>{@link #craftable}：<b>要扣料</b>的配方 —— 知识合成机用；</li>
 *   <li>{@link #generators}：<b>不消耗输入</b>的配方 —— 知识生成器用。</li>
 * </ul>
 * 分成两个视角是刻意的：把生成器的配方塞进合成机 = 白送产物；
 * 把合成机的配方塞进生成器 = 模板区认错东西。
 *
 * <p>耗电口径见 {@link EnergyProbe}：<b>每 tick</b> 扣一次，一次配方 = 每 tick × ticks。
 *
 * <p>⚠ 缓存按机器 id 存的是**扫描结果**（配方列表），不存任何方块实例。
 */
public final class RecipeScanner {

    private static final Map<String, List<Scan>> CACHE = new ConcurrentHashMap<>();
    private static final List<Scan> EMPTY = Collections.emptyList();

    private RecipeScanner() {
    }

    /** 是不是我们已经包装过的配方（概率/抽组/模板）——这些不能再套一层，会丢原有信息 */
    private static boolean isWrapped(MachineRecipe mr) {
        return mr instanceof TemplateRecipe || mr instanceof ChanceRecipe
                || mr instanceof WeightedRecipe || mr instanceof GroupedRecipe
                || mr instanceof CatalystRecipe;
    }

    /** 这台机器扫出来的全部配方（含生成器配方）。 */
    public static List<Scan> of(SlimefunItem sf) {
        if (sf == null) return EMPTY;
        String id = sf.getId();
        if (id == null) return build(sf);
        List<Scan> cached = CACHE.get(id);
        if (cached != null) return cached;
        List<Scan> built = build(sf);
        CACHE.put(id, built);
        return built;
    }

    /**
     * 燃料型发电机的「燃料表」（煤炭发电机这类：烧一件燃料 = 跑 {@code ticks} tick）。
     *
     * <h2>为什么要单独一条</h2>
     * 这类机器**没有** {@code MachineRecipe} —— 配方就是 {@link MachineFuel#getInput()} 那几件燃料，
     * 于是 {@link #craftable}/{@link #generators} 都是 0 条，知识发电机只能判「燃料在它自己界面里扣」。
     * 直接把燃料表翻译成「输入 = 那件燃料、耗时 = 它烧的 tick 数、每 tick 发电 = getEnergyProduction()」，
     * 知识发电机就能自己烧、自己发。
     *
     * @return 空表 = 这台机器不是燃料型（太阳能/跟随型），交回原逻辑
     */
    public static List<Scan> fuels(SlimefunItem sf) {
        if (!(sf instanceof AbstractEnergyProvider provider)) return EMPTY;
        List<Scan> out = new ArrayList<>();
        try {
            int perTick = provider.getEnergyProduction();
            for (MachineFuel fuel : provider.getFuelTypes()) {
                if (fuel == null) continue;
                ItemStack in = fuel.getInput();
                if (in == null || in.getType() == Material.AIR) continue;
                in = in.clone();
                int amount = Math.max(1, in.getAmount());
                in.setAmount(amount);
                ItemStack produced = fuel.getOutput();
                ItemStack[] outs = produced == null || produced.getType() == Material.AIR
                        ? new ItemStack[0] : new ItemStack[]{produced};
                MachineRecipe raw = new MachineRecipe(Math.max(1, fuel.getTicks()),
                        new ItemStack[]{in}, outs);
                out.add(new Scan(raw, new int[]{amount}, perTick, perTick > 0));
            }
        } catch (Throwable ignored) {
            // 读不到就当没有燃料表，别让一台怪机器把 tick 拖崩
        }
        return out;
    }

    /** 要扣料的配方（知识合成机）。 */
    /**
     * 是不是「多方块机器的核心」。
     *
     * <p>判据只看形状：类上有一处方法叫 {@code getMultiBlockType}，或者实现了名字里带
     * {@code MultiBlockCore} 的接口 —— 不点任何附属的类名/机器 id。
     *
     * <p>为什么这类机器要跳过：核心方块单独摆着是跑不了的，真正的结构在它周围（别处），
     * 知识机器是另一块方块，**物理上不可能替它验证结构**；硬读配方等于让玩家拿一块核心
     * 就白嫖整条多方块产线。
     */
    public static boolean isMultiblock(SlimefunItem sf) {
        if (sf == null) return false;
        // 粘液本体那批「工作台式多方块」（盔甲锻造台、冶炼炉…）不算：它们的配方本来就该能远程合成
        if (sf instanceof io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine) return false;
        for (Class<?> c = sf.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            // 0) 类名兜底：第三方「多方块核心」基本都会把 MultiBlock 写进类名
            //    （RSC 的 CustomSuperMultiBlockMachine、LogiTech 的 MultiBlockProcessor…）
            if (c.getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("multiblock")) return true;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                String name = m.getName();
                // 1) LogiTech 风格：getMultiBlockType()
                if (name.equals("getMultiBlockType")) return true;
                // 2) RSC 超级多方块风格：getDefinition() 返回的类型名里带 MultiBlock
                if (name.equals("getDefinition") && m.getReturnType() != null
                        && m.getReturnType().getName().toLowerCase(java.util.Locale.ROOT).contains("multiblock")) {
                    return true;
                }
            }
            // 3) 接口名兜底（MultiBlockCore / MultiBlockPart / SuperMultiBlock…）
            for (Class<?> itf : c.getInterfaces()) {
                if (implementsNamed(itf, "multiblock")) return true;
            }
        }
        return false;
    }

    private static boolean implementsNamed(Class<?> itf, String needle) {
        for (Class<?> k = itf; k != null; k = k.getSuperclass()) {
            if (k.getName().toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
            for (Class<?> up : k.getInterfaces()) {
                if (implementsNamed(up, needle)) return true;
            }
        }
        return false;
    }

    public static List<Scan> craftable(SlimefunItem sf) {
        List<Scan> all = of(sf);
        if (all.isEmpty()) return EMPTY;
        List<Scan> out = new ArrayList<>();
        for (Scan s : all) if (!s.cost.isEmpty() && s.producesSomething()) out.add(s);
        return out.isEmpty() ? EMPTY : out;
    }

    /** 不消耗输入的配方（知识生成器）。 */
    public static List<Scan> generators(SlimefunItem sf) {
        List<Scan> all = of(sf);
        if (all.isEmpty()) return EMPTY;
        List<Scan> out = new ArrayList<>();
        for (Scan s : all) if (s.noConsume) out.add(s);
        return out.isEmpty() ? EMPTY : out;
    }

    /** 被扫描机器的耗电探测结果（每 tick）。 */
    public static EnergyProbe.Info energyOf(SlimefunItem sf) {
        return EnergyProbe.of(sf);
    }

    public static void clearCache() {
        CACHE.clear();
        EnergyProbe.clearCache();
    }

    /** 配置开着、且这台是多方块核心 ⇒ 一条配方都不给（原因见 {@link #isMultiblock}） */
    public static boolean skippedAsMultiblock(SlimefunItem sf) {
        if (!isMultiblock(sf)) return false;
        try {
            net.me.xinzhi.knowledge.config.Settings cfg = net.me.xinzhi.knowledge.config.Settings.get();
            return cfg == null || cfg.skipMultiblock();
        } catch (Throwable t) {
            return true;
        }
    }

    private static List<Scan> build(SlimefunItem sf) {
        if (isMultiblock(sf)) {
            try {
                net.me.xinzhi.knowledge.config.Settings cfg = net.me.xinzhi.knowledge.config.Settings.get();
                if (cfg == null || cfg.skipMultiblock()) return List.of();
            } catch (Throwable ignored) {
            }
        }
        List<MachineRecipe> raw = RecipeExtractor.extractMachineRecipes(sf);
        if (raw == null || raw.isEmpty()) return EMPTY;
        EnergyProbe.Info energy = EnergyProbe.of(sf);
        boolean neverConsumes = ConsumeRules.machineNeverConsumes(sf);
        boolean off = false;
        try {
            off = !net.me.xinzhi.knowledge.config.Settings.get().useConsumeRules();
        } catch (Throwable ignored) {
        }

        net.me.xinzhi.knowledge.config.Settings cfg = null;
        try { cfg = net.me.xinzhi.knowledge.config.Settings.get(); } catch (Throwable ignored) { }
        int ticksOverride = cfg == null ? -1 : cfg.overrideTicks(sf.getId());
        int amountOverride = cfg == null ? -1 : cfg.overrideOutputAmount(sf.getId());
        // mode: generator ⇒ 输入只用来认配方（卡片/模具），机器不扣。
        // 为什么要有它：像 Supreme TechGenerator 这种「卡片当输入但永不消耗」的机器，
        // 配方对象里没有任何"不消耗"标记（RSC 有 noConsumeAll，它没有），静态判不出来。
        boolean forceGenerator = cfg != null && "generator".equals(cfg.overrideMode(sf.getId()));

        // 模板生成器（处理器槽那一族，如逻辑工艺的种植机/矿机）：配方里的输入只是
        // 「你要跑哪一种」的开关，机器从不扣它。机器本身没有任何"不消耗"标记，
        // 静态判据在 TemplateProbe（看类里有没有 PROCESSOR/TEMPLATE 槽）。
        boolean templateMachine = TemplateProbe.isTemplateMachine(sf);

        List<Scan> out = new ArrayList<>(raw.size());
        for (MachineRecipe mr : raw) {
            if (mr == null) continue;
            mr = applyOverride(mr, ticksOverride, amountOverride);
            if (templateMachine && !isWrapped(mr)) {
                // 只要不是我们自己包装过的那几种（概率/抽组/已打标），就打上模板标。
                // 不能写成 mr.getClass() == MachineRecipe.class：逻辑工艺用的是
                // StackMachineRecipe（MachineRecipe 的子类），那样判会漏掉整族机器。
                mr = TemplateRecipe.of(mr.getTicks(), mr.getInput(), mr.getOutput());
            }
            int[] amounts = null;
            if (neverConsumes || off || forceGenerator) {
                ItemStack[] ins = mr.getInput();
                amounts = new int[ins == null ? 0 : ins.length];
            } else if (mr instanceof AmountsCarrier ac && ac.consumeAmounts() != null) {
                // 配方自己带了扣料计划（模板机 = 模板位 0）：照它来，别让默认口径把模板扣掉
                amounts = ac.consumeAmounts().clone();
            } else if (mr instanceof TemplateRecipe) {
                ItemStack[] ins = mr.getInput();
                amounts = new int[ins == null ? 0 : ins.length];
            } else {
                try {
                    amounts = ConsumeRules.consumeAmounts(mr);
                } catch (Throwable ignored) {
                }
            }
            ItemStack[] ins = mr.getInput();
            if (amounts != null && ins != null && amounts.length != ins.length) amounts = null;
            try {
                out.add(new Scan(mr, amounts, energy.perTick, energy.known));
            } catch (Throwable ignored) {
            }
        }
        return out.isEmpty() ? EMPTY : out;
    }

    /**
     * 配置里的逐机器覆盖：配方耗时 / 一次产出数量。
     *
     * <p>为什么要它：有的机器配方对象里根本没有"多久一次""一次给几个"
     * （Supreme 的 {@code AbstractItemRecipe} 只有输入/输出两个数组，
     * 时间写在机器 tick 逻辑里、数量写在 {\@code maxAmountTechGenerator} 配置里），
     * 强猜等于给服务器埋一个加速外挂。宁可让服主显式写一行。
     */
    private static MachineRecipe applyOverride(MachineRecipe mr, int ticks, int amount) {
        if (ticks <= 0 && amount <= 0) return mr;
        if (mr instanceof WeightedRecipe) return mr;      // 加权配方别动它的权重数组
        ItemStack[] in = mr.getInput();
        ItemStack[] out = mr.getOutput();
        if (amount > 0 && out != null) {
            ItemStack[] scaled = new ItemStack[out.length];
            for (int i = 0; i < out.length; i++) {
                ItemStack is = out[i];
                if (is == null) continue;
                ItemStack c = is.clone();
                c.setAmount(amount);
                scaled[i] = c;
            }
            out = scaled;
        }
        int t = ticks > 0 ? ticks : mr.getTicks();
        return new MachineRecipe(t, in, out);
    }

    private static java.util.List<String> fields(Object o, java.util.Set<String> skip) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getName();
                if (!skip.add(c.getSimpleName() + "." + n)) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe) v = "<配方对象>";
                    out.add("§8  · " + n + " §7= §f" + v);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /**
     * 诊断用（{@code /xzk dump}）：逐条列出原始配方类名、产物数、概率表、chooseOne。
     * 用来回答「到底是哪一层把概率吃掉了」。
     */
    public static List<String> dump(SlimefunItem sf) {
        List<String> lines = new ArrayList<>();
        List<Scan> all = of(sf);
        lines.add("§b" + sf.getId() + " §8· §7" + all.size() + " 条");
        // 第一条配方的原始对象字段（看清 chooseOne 这类开关到底存不存在、叫什么）
        try {
            java.util.List<me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe> raw =
                    RecipeExtractor.extractMachineRecipes(sf);
            if (raw.isEmpty()) raw = RecipeExtractor.extractGeneratorRecipes(sf);
            if (!raw.isEmpty()) {
                lines.add("§8字段 · " + raw.get(0).getClass().getSimpleName());
                lines.addAll(fields(raw.get(0), new java.util.HashSet<>()));
            }
        } catch (Throwable t) {
            lines.add("§c字段探测失败: " + t);
        }
        int n = 0;
        for (Scan s : all) {
            if (n++ >= 12) { lines.add("§8… 还有 " + (all.size() - 12) + " 条"); break; }
            lines.add("§8#" + n + " §7" + s.rawClass
                    + " §8| 产物 §f" + (s.outputs == null ? 0 : s.outputs.length)
                    + " §8| chances " + (s.chances == null ? "§cnull" : "§f" + java.util.Arrays.toString(s.chances))
                    + " §8| chooseOne §f" + s.chanceChooseOne
                    + " §8| groups " + (s.groups == null ? "null" : String.valueOf(s.groups.size()))
                    + " §8| weights " + (s.weights == null ? "null" : String.valueOf(s.weights.length)));
        }
        return lines;
    }

    /**
     * 诊断用：把一台机器扫出来的东西摊成几行文字（{@code /xzk scan}）。
     * 腐竹在游戏里遇到「有的机器识别不对」时，直接把这几行发给我就能定位。
     */
    public static List<String> describe(SlimefunItem sf) {
        List<String> lines = new ArrayList<>();
        if (sf == null) {
            lines.add("§c手里没有粘液物品");
            return lines;
        }
        EnergyProbe.Info e = energyOf(sf);
        List<Scan> all = of(sf);
        lines.add("§b" + sf.getId() + " §7(" + sf.getClass().getSimpleName() + ")");
        lines.add("§7配方 §f" + all.size()
                + " §8| §7可合成 §f" + craftable(sf).size()
                + " §8| §7不耗输入 §f" + generators(sf).size());
        lines.add("§7耗电 §f" + e.perTick + " §7J/tick"
                + (e.known ? "" : " §c(读不到，按配置兜底)")
                + (e.networked ? " §8| 参与电网" : " §8| 不参与电网"));
        int n = 0;
        for (Scan s : all) {
            if (n++ >= 6) {
                lines.add("§8… 还有 " + (all.size() - 6) + " 条");
                break;
            }
            lines.add("§8· §7" + String.join(" §8/ §7", s.lore()));
        }
        return lines;
    }
}