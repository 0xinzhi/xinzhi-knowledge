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
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识合成机：识别槽里放「要扫描配方的机器」，用扫出来的配方把输入做成产物。
 *
 * <h2>效率（腐竹 2026-11-07）</h2>
 * 每 tick 次数 = 机器数 × 基准效率 ÷ 配方 tick。小于 1 就几 tick 攒一次，大于 1 就一 tick 多次。
 *
 * <h2>配方选择</h2>
 * 眼睛槽点开选一条 → 固定跑这条；取消 → 自动按输入匹配。
 *
 * <h2>性能口径（腐竹 2026-11-07：识别可以慢，合成速率不能慢）</h2>
 * <ol>
 *   <li><b>识别节流</b>：识别槽每 {@code machine.identify-every-ticks} 个 tick 才看一眼 ——
 *       换机器后最多等这么久才认新配方，已经认好的配方照常每 tick 跑。</li>
 *   <li><b>停摆跳过</b>：判定「跑不动」（缺料/满仓/没电/没配方）后，隔
 *       {@code machine.idle-skip-ticks} 个 tick 再重算，空转机器几乎不耗 CPU。</li>
 *   <li><b>只数一次</b>：自动匹配和扣料共用一个库存统计，不再每 tick 数两遍整包输入。</li>
 * </ol>
 */
public final class RecipeMachine extends BaseKnowledgeMachine {

    private static final String KEY_MACHINE = "xzk-machine";
    private static final String KEY_RECIPE = "xzk-recipe";

    private final boolean wideOutput;
    private final Map<Location, State> states = new ConcurrentHashMap<>();

    public RecipeMachine(ItemGroup group, SlimefunItemStack item, RecipeType recipeType,
                         ItemStack[] recipe, boolean wideOutput) {
        super(group, item, recipeType, recipe);
        this.wideOutput = wideOutput;
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
        return wideOutput ? "§b知识合成机 §8· §7输出优先" : "§b知识合成机 §8· §7输入优先";
    }

    @Override protected int[] inputSlots() {
        return wideOutput ? Layout.CRAFT_A_INPUT : Layout.CRAFT_B_INPUT;
    }

    @Override protected int[] outputSlots() {
        return wideOutput ? Layout.CRAFT_A_OUTPUT : Layout.CRAFT_B_OUTPUT;
    }

    @Override protected int machineSlot() {
        return wideOutput ? Layout.CRAFT_A_MACHINE : Layout.CRAFT_B_MACHINE;
    }

    @Override protected int[] extraMachineSlots() {
        return wideOutput ? Layout.CRAFT_A_EXTRA_MACHINE : Layout.CRAFT_B_EXTRA_MACHINE;
    }

    @Override protected int recipeSlot() {
        return wideOutput ? Layout.CRAFT_A_RECIPE : Layout.CRAFT_B_RECIPE;
    }

    @Override protected int statusSlot() {
        return wideOutput ? Layout.CRAFT_A_STATUS : Layout.CRAFT_B_STATUS;
    }

    /** 合成机只跑「要扣料」的配方：不耗输入的那些属于生成器 */
    @Override protected String hintLore() {
        return "§8第 " + (machineSlot() + 1) + " 格放机器读配方 · 第 " + extraHint() + " 格叠同款机器";
    }

    @Override protected List<Scan> scansFor(SlimefunItem sf) {
        return RecipeScanner.craftable(sf);
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
        /** 识别出来的机器中文名（状态卡用） */
        String machineName;
        /** 是不是多方块核心（界面要给出不同提示） */
        boolean multiblock;
        List<Scan> scans = List.of();
        final long[] acc = new long[1];
        /** 本机被 tick 过的次数（节流用，不用墙钟 —— 服停一会儿不该打乱节奏） */
        int ticks;
        /** 在本机第 ticks 次 tick 之前都不再重算（停摆跳过） */
        int blockedUntil;
        /** 台数（识别槽 + 附加机器槽里同款机器的堆叠数）：只在识别那一刻读一次 */
        int count = 1;
        /** 锁定配方下标的缓存（每 tick 读一次方块数据太贵，5 tick 读一次） */
        int lockedCache = -1;
        int lockedAt = 0;
        /** 附加槽里混进了别的机器（不算台数，界面上要提一句） */
        boolean mixedMachines;
        void reset() {
            machineId = null;
            machineName = null;
            multiblock = false;
            scans = List.of();
            acc[0] = 0;
            count = 1;
            mixedMachines = false;
            lockedCache = -1;
            lockedAt = 0;
        }
    }

    @Override
    protected void tick(Block b, BlockMenu menu) {
        Location loc = b.getLocation();
        State st = states.computeIfAbsent(loc, k -> new State());
        st.ticks++;

        Settings cfg = Settings.get();
        int idleSkip = cfg == null ? 2 : cfg.idleSkipTicks();

        // ---- 识别（节流：每 identifyEveryTicks 个 tick 看一次；停摆也照样看，界面才不会卡在旧文字上）----
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
                // 换了机器，之前锁的配方下标对新机器没有意义，必须作废（腐竹 2026-11-07 报的坑）
                MachineUtil.unset(loc, KEY_RECIPE);
                MachineUtil.set(loc, KEY_MACHINE, sf.getId());
            }
        }
        if (st.blockedUntil > st.ticks) return;
        if (st.scans.isEmpty()) {
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, "§c" + (st.machineName == null ? "这台机器" : st.machineName) + " §c没有要扣料的配方");
            return;
        }

        double eff = cfg == null ? 1.0 : cfg.baseEfficiency();
        int cap = cfg == null ? 256 : cfg.maxCraftsPerTick();

        // 锁定配方下标：每 tick 读一次方块数据太贵（腐竹 2026-11-08 问「合成机占用为什么高」），
        // 改成 5 tick 读一次；换机器/点取消时 lockedAt 会归零，立刻重读。
        int locked;
        if (st.ticks - st.lockedAt >= 5 || st.lockedAt == 0) {
            locked = MachineUtil.intInfo(loc, KEY_RECIPE, -1);
            st.lockedCache = locked;
            st.lockedAt = st.ticks;
        } else {
            locked = st.lockedCache;
        }
        Scan chosen;
        Map<ItemKey, Integer> have = null;
        if (locked >= 0 && locked < st.scans.size()) {
            chosen = st.scans.get(locked);
        } else {
            have = InvOps.count(menu, inputSlots());
            chosen = autoMatch(have, st.scans);
        }
        if (chosen == null) {
            st.acc[0] = 0;
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, locked >= 0 ? "§7锁定配方缺料，等材料" : "§7输入槽里没有能做的配方");
            return;
        }

        if (have == null) have = InvOps.count(menu, inputSlots());
        int byIn = CraftMath.byInput(have, chosen.need);
        int bySpace = chosen.uncertainOutput()
                ? (int) Math.min(Integer.MAX_VALUE, InvOps.roomFor(menu, outputSlots()))   // 每次只出 1 件
                : Math.max(0, CraftMath.bySpace(menu, outputSlots(), chosen.give));
        if (byIn <= 0 || bySpace <= 0) {
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, byIn <= 0 ? "§7缺料，等输入" : "§c产物槽满了");
            return;
        }

        // 电按 tick 扣，不是攒够一次才扣（腐竹 2026-11-07）：
        // 这台机器本 tick 要推进 count*eff 台机器的工作量，就付 count*eff 份「每 tick 耗电」。
        // 一次配方累计下来正好 = energyPerCraft，但缓冲只需要装得下「1 tick 的电」。
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

        // ⚠ 必须被「这 tick 的料够跑几次」「产物放得下几次」削顶，否则多台机器会
        // 按机器数放大产物却没跟上扣料（腐竹 2026-11-08：机器多了材料消耗没增加）
        int crafts = Math.min(CraftMath.perTick(st.acc, st.count, eff, chosen.ticks, cap),
                Math.min(byIn, bySpace));
        if (crafts <= 0) {
            // 蓄力中：每 tick 都要累加，只是别每 tick 都重画界面
            if (st.ticks % 10 == 0) {
                render(menu, "§7蓄力中 §8(" + st.count + "台 / " + chosen.ticks + "tick)");
            }
            return;
        }

        InvOps.consume(menu, inputSlots(), chosen.cost, crafts);
        boolean placed = true;
        if (chosen.grouped()) {
            placed = InvOps.produceGrouped(menu, outputSlots(), chosen.groups, chosen.groupWeights, crafts);
        } else if (chosen.chanced()) {
            placed = InvOps.produceChanced(menu, outputSlots(), chosen.outputs, chosen.chances,
                    chosen.chanceChooseOne, crafts);
        } else if (chosen.weighted()) {
            placed = InvOps.produceWeighted(menu, outputSlots(), chosen.outputs, chosen.weights, crafts);
        } else {
            placed = InvOps.produceMixed(menu, outputSlots(), chosen.outputs, crafts);
        }
        // 腐竹 2026-11-08：「一次产生就会塞满也进行一次」——所以这里按"尽力塞"处理，
        // 真塞不进去（整份都放不下）就当场停，别继续扣料
        if (!placed) {
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

    private Scan autoMatch(Map<ItemKey, Integer> have, List<Scan> scans) {
        for (Scan s : scans) {
            if (!s.producesSomething()) continue;
            if (CraftMath.byInput(have, s.need) > 0) return s;
        }
        return null;
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

    @Override
    protected int[] storageSlotsForDrop() {
        int[] base = super.storageSlotsForDrop();
        int[] all = new int[base.length + (recipeSlot() >= 0 ? 1 : 0)];
        System.arraycopy(base, 0, all, 0, base.length);
        return all;
    }
}