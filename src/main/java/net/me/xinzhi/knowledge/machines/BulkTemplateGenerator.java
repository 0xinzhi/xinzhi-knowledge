package net.me.xinzhi.knowledge.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.config.Settings;
import net.me.xinzhi.knowledge.core.ItemKey;
import net.me.xinzhi.knowledge.core.MachineWhitelist;
import net.me.xinzhi.knowledge.recipe.RecipeScanner;
import net.me.xinzhi.knowledge.recipe.Scan;
import net.me.xinzhi.knowledge.tank.TankNet;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大模板生成器：给「模板物品不可堆叠」的机器用。
 *
 * <p>普通知识生成器的模板区只有 1 格（第 49 格）。模板物品如果是不可堆叠的
 * （工具、机器本体这类 maxStackSize = 1），一格永远只能算 1 个，
 * 配方要求 2 个以上就永远匹配不上。
 *
 * <p>腐竹 2026-11-16 的点子：<b>拿储罐当模板仓</b>——
 * 把模板物品放进紧挨着这台机器的<b>输入储罐</b>（54 格），机器去罐里数，
 * 一格放一份不可堆叠物品，最多能数到 54 份，还不用占界面格子。
 *
 * <p>它只跑「不消耗输入」的配方（和知识生成器同一条口径），产物优先塞输出储罐。
 */
public class BulkTemplateGenerator extends BaseKnowledgeMachine {

    private static final String KEY_MACHINE = "xzk-machine";

    private final Map<Location, State> states = new ConcurrentHashMap<>();

    public BulkTemplateGenerator(ItemGroup group, SlimefunItemStack item,
                                 RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
    }

    @Override
    protected void onMenuOpen(@javax.annotation.Nonnull BlockMenu menu,
                              @javax.annotation.Nonnull Block b) {
        State st = states.get(b.getLocation());
        if (st != null) st.blockedUntil = 0;
    }

    @Override protected String menuTitle() {
        return "§b大模板生成器 §8· §7模板放储罐";
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

    @Override protected int[] templateSlots() {
        // 模板不在机器里，在紧挨着的输入储罐里（见类注释）
        return new int[0];
    }

    @Override protected int statusSlot() {
        return Layout.STATUS;
    }

    @Override protected String hintLore() {
        return "§8第 " + (machineSlot() + 1) + " 格放机器 · 模板放进紧挨着的输入储罐（54 格，一格一份）";
    }

    @Override protected List<Scan> scansFor(SlimefunItem sf) {
        return RecipeScanner.generators(sf);
    }

    private static final class State {
        String machineId;
        String machineName;
        boolean multiblock;
        List<Scan> scans = List.of();
        final long[] acc = new long[1];
        int ticks;
        int blockedUntil;
        int count = 1;
        boolean mixedMachines;
        /** 名单机器（生物模拟室）：配方扫不到，产出由芯片掉落表决定 */
        boolean chamber;
        int chamberTicks;
        String chipSig;
        int limit;
        /** 识别到的机器本体（自输出机器要拿它去查 A=A 配方） */
        SlimefunItem sf;
        /** 自输出机器（A=A）：储罐里放什么就产什么 */
        boolean selfOut;

        void reset() {
            machineId = null;
            machineName = null;
            multiblock = false;
            scans = List.of();
            acc[0] = 0;
            count = 1;
            mixedMachines = false;
            chamber = false;
            chamberTicks = 0;
            chipSig = null;
            limit = 0;
            sf = null;
            selfOut = false;
        }
    }

    @Override
    protected void tick(Block b, BlockMenu menu) {
        // 产物先塞输出储罐（腐竹 2026-11-15：「先放入储罐，储罐满了再放入机器」）
        TankNet.sweep(menu, outputSlots(), TankNet.around(b, true));
        Location loc = b.getLocation();
        State st = states.computeIfAbsent(loc, k -> new State());
        st.ticks++;

        Settings cfg = Settings.get();
        int idleSkip = cfg == null ? 2 : cfg.idleSkipTicks();
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
                render(menu, blank ? "§7识别槽空着 · 把要扫模板的机器放进去"
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
                st.sf = sf;
                st.chamber = MachineWhitelist.isSimChamber(sf);
                st.selfOut = MachineWhitelist.isSelfOutput(sf);
                st.chamberTicks = st.chamber ? MachineWhitelist.chamberTicks(sf) : 0;
                st.chipSig = null;
                st.limit = 0;
                st.scans = st.chamber ? List.of() : scansFor(sf);
                st.acc[0] = 0;
                MachineUtil.set(loc, KEY_MACHINE, sf.getId());
            }
            if (st.chamber) {
                MachineWhitelist.Chip chip = MachineWhitelist.findChip(menu, new int[0], TankNet.around(b, false));
                if (chip == null) {
                    st.scans = List.of();
                    st.limit = 0;
                    st.chipSig = null;
                    st.blockedUntil = st.ticks + idleSkip;
                    render(menu, "§c" + (st.machineName == null ? "这台机器" : st.machineName)
                            + " §c要靠生物芯片决定产出 §7· 把芯片放进紧挨着的输入储罐");
                    return;
                }
                if (!chip.signature().equals(st.chipSig)) {
                    st.chipSig = chip.signature();
                    Scan cardScan = MachineWhitelist.chipScan(chip, st.chamberTicks, cardEnergy(cfg));
                    st.scans = cardScan == null ? List.of() : List.of(cardScan);
                    st.acc[0] = 0;
                }
                st.limit = chip.count;
            }
            // ---- 自输出机器（A=A）：储罐里放什么就产什么（动力科技蜂箱这类） ----
            if (st.selfOut) {
                Map<ItemKey, Integer> tpl = TankNet.contents(TankNet.around(b, false));
                List<Scan> built = new java.util.ArrayList<>();
                StringBuilder sig = new StringBuilder();
                int total = 0;
                for (Map.Entry<ItemKey, Integer> e : tpl.entrySet()) {
                    ItemStack rep = e.getKey().toTemplate();
                    if (rep == null) continue;
                    Scan sc = MachineWhitelist.selfOutputScan(rep, st.sf);
                    if (sc == null) continue;
                    built.add(sc);
                    total += e.getValue();
                    sig.append(e.getKey()).append('x').append(e.getValue()).append(';');
                }
                if (built.isEmpty()) {
                    st.scans = List.of();
                    st.chipSig = null;
                    st.limit = 0;
                    st.blockedUntil = st.ticks + idleSkip;
                    render(menu, "§c" + (st.machineName == null ? "这台机器" : st.machineName)
                            + " §c按放进去的物品出东西 §7· 把要产的东西放进紧挨着的输入储罐");
                    return;
                }
                if (!sig.toString().equals(st.chipSig)) {
                    st.chipSig = sig.toString();
                    st.scans = built;
                    st.acc[0] = 0;
                }
                st.limit = total;
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
        Map<ItemKey, Integer> templates = TankNet.contents(TankNet.around(b, false));
        Scan chosen = pick(st.scans, templates);
        if (chosen == null) {
            st.blockedUntil = st.ticks + idleSkip;
            boolean needs = false;
            for (Scan s : st.scans) if (!s.need.isEmpty()) { needs = true; break; }
            if (needs && templates.isEmpty()) {
                render(menu, "§7把模板放进紧挨着的输入储罐 §8(这台机器的配方要靠模板认)");
            } else if (needs) {
                render(menu, "§7储罐里的模板对不上 §8(或数量不够，一格一份也行)");
            } else {
                render(menu, "§7没有能跑的配方");
            }
            return;
        }

        double eff = cfg == null ? 1.0 : cfg.baseEfficiency();
        int cap = cfg == null ? 256 : cfg.maxCraftsPerTick();

        // 腐竹 2026-11-17：「模板数决定使用的机器数量」—— 一份模板带一台机器，模板不够就只有那么多台在跑
        int runCount = st.count;
        if (!chosen.need.isEmpty()) {
            int copies = Integer.MAX_VALUE;
            for (java.util.Map.Entry<ItemKey, Integer> en : chosen.need.entrySet()) {
                int have = templates.getOrDefault(en.getKey(), 0) / Math.max(1, en.getValue());
                copies = Math.min(copies, have);
            }
            if (copies != Integer.MAX_VALUE) runCount = Math.min(runCount, copies);
            if (runCount < 1) runCount = 1;
        }
        if (st.limit > 0) runCount = Math.max(1, Math.min(runCount, st.limit));
        int ticks = chosen.ticks;

        int bySpace = chosen.uncertainOutput()
                ? (int) Math.min(Integer.MAX_VALUE, room)
                : Math.max(0, CraftMath.bySpace(menu, outputSlots(), chosen.give));
        if (bySpace <= 0) {
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, "§c产物槽满了");
            return;
        }

        long draw = CraftMath.energyPerTick(chosen, runCount, eff);
        if (draw > 0) {
            if (draw > getCapacity()) {
                st.blockedUntil = st.ticks + Math.max(1, idleSkip);
                render(menu, "§c本机存电上限 §f" + getCapacity() + " J§c，这台机器每 tick 要 §f"
                        + draw + " J§8（调 machine.energy-capacity）");
                return;
            }
            long haveCharge = charge(loc);
            if (haveCharge < draw) {
                st.blockedUntil = st.ticks + idleSkip;
                render(menu, "§c没电了 §7· 每 tick 需 §f" + draw + " J§7，本机存电 §f" + haveCharge + " J");
                return;
            }
            consumeCharge(loc, draw);
        }

        int crafts = CraftMath.perTick(st.acc, runCount, eff, ticks, cap);
        if (crafts <= 0) {
            if (st.ticks % 10 == 0) {
                render(menu, "§7蓄力中 §8(" + runCount + "台 / " + ticks + "tick)");
            }
            return;
        }

        if (!produce(menu, chosen, crafts)) {
            st.blockedUntil = st.ticks + idleSkip;
            render(menu, "§c产物槽满了");
            return;
        }
        if (st.ticks % 10 == 0) {
            render(menu, "§a运行中 §7· §f" + crafts + " §7次 §8(" + runCount + "台, " + ticks
                    + "tick, 每tick " + draw + " J" + (chosen.energyKnown ? "" : " §c能耗未知") + "§8)"
                    + (st.mixedMachines ? " §c· 附加槽里不是同款机器" : ""));
        }
    }

    /** 挑一条能跑的配方：模板区（储罐）认得出来它要的每样东西，且产物有空位 */
    private Scan pick(List<Scan> scans, Map<ItemKey, Integer> templates) {
        for (Scan s : scans) {
            boolean ok = true;
            for (Map.Entry<ItemKey, Integer> e : s.need.entrySet()) {
                if (templates.getOrDefault(e.getKey(), 0) < Math.max(1, e.getValue())) {
                    ok = false;
                    break;
                }
            }
            if (ok) return s;
        }
        return null;
    }

    private boolean produce(BlockMenu menu, Scan s, int times) {
        return s.weighted()
                ? (s.grouped()
                    ? InvOps.produceGrouped(menu, outputSlots(), s.groups, s.groupWeights, times)
                    : InvOps.produceWeighted(menu, outputSlots(), s.outputs, s.weights, times))
                : s.chanced()
                ? InvOps.produceChanced(menu, outputSlots(), s.outputs, s.chances, s.chanceChooseOne, times)
                : InvOps.produceMixed(menu, outputSlots(), s.outputs, times);
    }

    private static int cardEnergy(Settings cfg) {
        return cfg == null ? 20 : cfg.cardEnergyPerTick();
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
