package net.me.xinzhi.knowledge.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.config.Settings;
import net.me.xinzhi.knowledge.core.ItemKey;
import net.me.xinzhi.knowledge.recipe.RecipeScanner;
import net.me.xinzhi.knowledge.recipe.Scan;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识生成器：只跑<b>不消耗输入</b>的配方（空输入，或输入全是模板/催化剂）。
 *
 * <h2>模板区（腐竹 2026-11-07 第 3 条）</h2>
 * 模板 = 这类机器「认配方」用的东西（卡片、模具…），机器本身不扣它。
 * 所以：模板区里放够这条配方认得出的东西 ⇒ 才能跑；放不够 ⇒ 一条都跑不出来
 * （真实机器不放卡片也不会出铜锭，我们不能白送）。输入本来就是空的纯发电机
 * （如陌陌粉尘机）不受此限，照旧一直跑。
 *
 * <h2>效率</h2>
 * 与合成机同一套：机器数 × 基准效率 ÷ 配方 tick，余数累积，小于 1 tick 就一 tick 多次。
 *
 * <h2>性能</h2>
 * 与合成机同样节流（识别每 N tick 看一次、停摆隔几 tick 重算、空位只数一次）。
 */
public final class KnowledgeGenerator extends BaseKnowledgeMachine {

    private static final String KEY_MACHINE = "xzk-machine";

    private final Map<Location, State> states = new ConcurrentHashMap<>();

    public KnowledgeGenerator(ItemGroup group, SlimefunItemStack item,
                              RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
        addItemHandler(new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(@Nonnull BlockPlaceEvent e) {
                MachineUtil.setOwner(e.getBlock().getLocation(), e.getPlayer());
            }
        });
    }

    /**
     * 玩家打开界面就刷新一次：把停摆节流的截止时间清掉，让下一 tick 走完整流程重算状态。
     * （腐竹 2026-11-08：「设置玩家打开机器的时候刷新一下菜单」）
     */
    @Override
    protected void onMenuOpen(@javax.annotation.Nonnull me.mrCookieSlime.Slimefun.api.inventory.BlockMenu menu,
                              @javax.annotation.Nonnull org.bukkit.block.Block b) {
        State st = states.get(b.getLocation());
        if (st != null) st.blockedUntil = 0;
    }

    @Override protected String menuTitle() {
        return "§b知识生成器 §8· §7不消耗输入";
    }

    @Override protected int[] inputSlots() {
        return new int[0];
    }

    @Override protected int[] outputSlots() {
        return Layout.GEN_OUTPUT;
    }

    @Override protected int machineSlot() {
        return Layout.GEN_MACHINE;
    }

    @Override protected int[] extraMachineSlots() {
        return Layout.GEN_EXTRA_MACHINE;
    }

    @Override protected int recipeSlot() {
        return -1;
    }

    @Override protected int[] templateSlots() {
        return Layout.GEN_TEMPLATE;
    }

    @Override protected int statusSlot() {
        return Layout.STATUS;
    }

    @Override protected String hintLore() {
        return "§8第 " + (machineSlot() + 1) + " 格放机器读配方 · 第 " + extraHint()
                + " 格叠同款机器 · 第 " + (templateSlots()[0] + 1) + " 格放模板";
    }

    /** 生成器只跑「不消耗输入」的配方：要扣料的那是合成机的活 */
    @Override protected List<Scan> scansFor(SlimefunItem sf) {
        return RecipeScanner.generators(sf);
    }

    /** 把附加机器槽写成「第 12/39/48 格」这种玩家能对上的说法（界面槽位从 1 数起） */
    private String extraHint() {
        StringBuilder sb = new StringBuilder();
        for (int v : extraMachineSlots()) {
            if (sb.length() > 0) sb.append('/');
            sb.append(v + 1);
        }
        return sb.toString();
    }

    private static final class State {
        String machineId;
        String machineName;
        /** 是不是多方块核心（界面要给出不同提示） */
        boolean multiblock;
        List<Scan> scans = List.of();
        final long[] acc = new long[1];
        int ticks;
        int blockedUntil;
        int count = 1;
        boolean mixedMachines;
        void reset() {
            machineId = null;
            machineName = null;
            multiblock = false;
            scans = List.of();
            acc[0] = 0;
            count = 1;
            mixedMachines = false;
        }
    }

    @Override
    protected void tick(Block b, BlockMenu menu) {
        Location loc = b.getLocation();
        State st = states.computeIfAbsent(loc, k -> new State());
        st.ticks++;

        Settings cfg = Settings.get();
        int idleSkip = cfg == null ? 2 : cfg.idleSkipTicks();

        // ---- 识别（停摆也照样每 N tick 看一眼，界面不会卡在旧文字上）----
        int identify = cfg == null ? 4 : cfg.identifyEveryTicks();
        if (st.machineId == null || st.ticks % Math.max(1, identify) == 0) {
            ItemStack tool = menu.getItemInSlot(machineSlot());
            SlimefunItem sf = MachineUtil.machineIn(tool);
            if (sf == null) {
                if (st.machineId != null) {
                    st.reset();
                    MachineUtil.unset(loc, KEY_MACHINE);
                }
                st.blockedUntil = st.ticks + idleSkip;
                boolean blank = tool == null || tool.getType().isAir();
                render(menu, blank ? "§7识别槽空着 · 把要扫描的机器放进去"
                        : "§c识别槽里不是粘液物品");
                return;
            }
            boolean[] mixed = new boolean[1];
            st.count = countMachines(menu, sf, mixed);
            st.mixedMachines = mixed[0];
            if (!sf.getId().equals(st.machineId)) {
                st.machineId = sf.getId();
                st.machineName = nameOf(sf);
                st.multiblock = RecipeScanner.skippedAsMultiblock(sf);
                st.scans = scansFor(sf);
                st.acc[0] = 0;
                MachineUtil.set(loc, KEY_MACHINE, sf.getId());
            }
        }
        if (st.blockedUntil > st.ticks) return;
        if (st.scans.isEmpty()) {
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, st.multiblock
                    ? "§c多方块核心 §8· §7它得在结构建成的地方用，知识机器不模拟结构"
                    : "§c" + (st.machineName == null ? "这台机器" : st.machineName) + " §c没有不消耗输入的配方");
            return;
        }

        long room = InvOps.roomFor(menu, outputSlots());
        if (room <= 0) {
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, "§c产物槽满了");
            return;
        }
        Map<ItemKey, Integer> templates = templateCounts(menu);
        Scan chosen = pick(st.scans, templates, room);
        if (chosen == null) {
            st.blockedUntil = st.ticks + idleSkip;
            boolean needsTemplate = false;
            for (Scan s : st.scans) if (!s.need.isEmpty()) { needsTemplate = true; break; }
            if (needsTemplate && templates.isEmpty()) {
                render(menu, "§7把模板放进左边模板区 §8(这个机器的配方要靠模板认)");
            } else if (needsTemplate) {
                render(menu, "§7模板对不上这台机器的配方 §8(或数量不够)");
            } else {
                render(menu, "§7没有能跑的配方");
            }
            return;
        }

        double eff = cfg == null ? 1.0 : cfg.baseEfficiency();
        int cap = cfg == null ? 256 : cfg.maxCraftsPerTick();

        int bySpace = chosen.uncertainOutput()
                ? (int) Math.min(Integer.MAX_VALUE, room)   // 每次只出 1 件，空位够就有多少次
                : Math.max(0, CraftMath.bySpace(menu, outputSlots(), chosen.give));
        if (bySpace <= 0) {
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, "§c产物槽满了");
            return;
        }

        // 电按 tick 扣（腐竹 2026-11-07）：缓冲只要装得下「1 tick 的电」，不再要求攒够整次配方
        long draw = CraftMath.energyPerTick(chosen, st.count, eff);
        if (draw > 0) {
            if (draw > getCapacity()) {
                st.blockedUntil = st.ticks + Math.max(1, idleSkip);
                render(menu, "§c本机存电上限 §f" + getCapacity() + " J§c，这台机器每 tick 要 §f"
                        + draw + " J§8（调 machine.energy-capacity）");
                return;
            }
            long haveCharge = Math.max(0L, charge(loc));
            if (haveCharge < draw) {
                st.blockedUntil = st.ticks + idleSkip;
                render(menu, "§c没电了 §7· 每 tick 需 §f" + draw + " J§7，本机存电 §f" + haveCharge + " J");
                return;
            }
            consumeCharge(loc, draw);
        }

        int crafts = CraftMath.perTick(st.acc, st.count, eff, chosen.ticks, cap);
        if (crafts <= 0) {
            if (st.ticks % 10 == 0) {
                render(menu, "§7蓄力中 §8(" + st.count + "台 / " + chosen.ticks + "tick)");
            }
            return;
        }

        if (!produce(menu, chosen, crafts)) {
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, "§c产物槽满了");
            return;
        }
        if (st.ticks % 10 == 0) {
            render(menu, "§a运行中 §7· §f" + crafts + " §7次 §8(" + st.count + "台, " + chosen.ticks
                    + "tick, 每tick " + draw + " J" + (chosen.energyKnown ? "" : " §c能耗未知") + "§8)"
                    + (st.mixedMachines ? " §c· 附加槽里不是同款机器" : ""));
        }
    }

    /** 模板区里每种物品有多少（数量要算，配方可能要求不止 1 个） */
    private Map<ItemKey, Integer> templateCounts(BlockMenu menu) {
        Map<ItemKey, Integer> counts = new java.util.HashMap<>();
        for (int s : templateSlots()) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType() == Material.AIR) continue;
            ItemKey k = ItemKey.of(it);
            if (k != null) counts.merge(k, it.getAmount(), Integer::sum);
        }
        return counts;
    }

    /**
     * 挑一条能跑的配方。判据 = <b>这条配方「认得出来」需要的每样东西，模板区里都够</b>。
     *
     * <p>⚠ 这就是「不消耗输入的配方」的真实语义（腐竹 2026-11-07 第 3 条：
     * 「识别到配方为不消耗输入配方就可以将模板放到左边」）：
     * 卡片/模板是<b>认配方</b>用的，机器不扣它，但<b>不放就什么都跑不出来</b>。
     * 以前模板区空着就跑全部配方 = 免费出铜锭，已改。
     *
     * <p>输入本来就是空的纯发电机（如陌陌粉尘机）{@code need} 为空 ⇒ 无条件可跑。
     */
    private Scan pick(List<Scan> scans, Map<ItemKey, Integer> templates, long room) {
        for (Scan s : scans) {
            if (!fits(s, room)) continue;
            if (!templateSatisfied(s, templates)) continue;
            return s;
        }
        return null;
    }

    private static boolean templateSatisfied(Scan s, Map<ItemKey, Integer> templates) {
        for (Map.Entry<ItemKey, Integer> e : s.need.entrySet()) {
            int have = templates.getOrDefault(e.getKey(), 0);
            if (have < Math.max(1, e.getValue())) return false;
        }
        return true;
    }

    /**
     * 只要输出区还有空位就能跑一次。
     *
     * <p>腐竹 2026-11-08：「如果产物槽一次产生就会塞满也进行一次。只有所有输出槽都有物品时
     * 才不进行生产。」——原来要求「剩余空间装得下完整一份」，产物一大就永远不跑，已改。
     */
    private static boolean fits(Scan s, long room) {
        return room > 0;
    }

    /**
     * 出产物：随机产物每次配方各抽一次（没中就这次不出），其它产物按数量给。
     * <p>抽法与「AIR 也算随机产物」的判定都在 {@link InvOps#produceMixed} 里，
     * 合成机走同一条路 —— 两台机器不许有两种概率口径。
     */
    private boolean produce(BlockMenu menu, Scan s, int times) {
        return s.weighted()
                ? (s.grouped()
                    ? InvOps.produceGrouped(menu, outputSlots(), s.groups, s.groupWeights, times)
                    : InvOps.produceWeighted(menu, outputSlots(), s.outputs, s.weights, times))
                : s.chanced()
                ? InvOps.produceChanced(menu, outputSlots(), s.outputs, s.chances, s.chanceChooseOne, times)
                : InvOps.produceMixed(menu, outputSlots(), s.outputs, times);
    }

    private long charge(Location loc) {
        try {
            return getChargeLong(loc);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void consumeCharge(Location loc, long amount) {
        try {
            removeCharge(loc, amount);
        } catch (Throwable ignored) {
        }
    }
}