package net.me.xinzhi.knowledge.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetProvider;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNet;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.config.Settings;
import net.me.xinzhi.knowledge.core.ItemKey;
import net.me.xinzhi.knowledge.recipe.RecipeScanner;
import net.me.xinzhi.knowledge.recipe.Scan;
import net.me.xinzhi.knowledge.util.GuiItems;
import net.me.xinzhi.knowledge.util.InputProbe;
import net.me.xinzhi.knowledge.util.PowerProbe;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识发电机：把一堆发电机收进一台，照它们的发电量往外送电。
 *
 * <h2>界面（腐竹 2026-11-08）</h2>
 * 「界面设置为中列用来显示，左边是燃料，右边放发电机」：
 * 左边 4 列（24 格）放燃料，右边 4 列（24 格）放发电机（同款叠台数），
 * 中列只做显示 —— 第 5 格状态、第 14 格发电信息。
 *
 * <h2>发电口径</h2>
 * 每 tick 发出 {@code 发电量 × 台数 × 效率} J，按千分之一累积（效率可以是小数）。
 * 发电量读不到 = <b>不发电</b>（腐竹 2026-11-08：「读不到就不给」），绝不拿配置瞎猜。
 *
 * <h2>燃料</h2>
 * 被识别机器如果有「要扣料」的配方（煤炭发电机这类），就按同样的效率节奏扣燃料；
 * 纯发电机（没有耗料配方，如太阳能）不需要燃料，直接发。
 */
public final class PowerGenerator extends BaseKnowledgeMachine implements EnergyNetProvider {

    private static final String KEY_MACHINE = "xzk-power";

    private final Map<Location, State> states = new ConcurrentHashMap<>();

    public PowerGenerator(ItemGroup group, SlimefunItemStack item, RecipeType recipeType,
                          ItemStack[] recipe) {
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

    @Override protected String menuTitle() { return "§b知识发电机"; }

    @Override protected int[] inputSlots() { return Layout.GEN2_FUEL; }

    @Override protected int[] outputSlots() { return new int[0]; }

    @Override protected int machineSlot() { return Layout.GEN2_MACHINE[0]; }

    @Override protected int[] extraMachineSlots() {
        return Arrays.copyOfRange(Layout.GEN2_MACHINE, 1, Layout.GEN2_MACHINE.length);
    }

    @Override protected int statusSlot() { return Layout.GEN2_STATUS; }

    @Override protected String hintLore() {
        return "§8右边 4 列放发电机（同款叠台数）· 左边 4 列放燃料";
    }

    @Override protected List<Scan> scansFor(SlimefunItem sf) {
        return RecipeScanner.craftable(sf);
    }

    /** 往外送电，所以是 GENERATOR（电力网络会来抽电） */
    @Override public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.GENERATOR;
    }

    private static final class State {
        /** 本状态属于哪个方块（画界面时要读虚拟电量） */
        Location loc;
        String machineId;
        String machineName;
        List<Scan> scans = List.of();
        int ticks;
        int blockedUntil;
        int count = 1;
        boolean mixedMachines;
        boolean powerKnown;
        boolean fuelUnknown;
        long powerPerTick;
        /** 燃料型发电机：这一轮烧的是燃料表里的配方 */
        boolean fuelCycle;
        /** 本轮烧到第几 tick（ticks <= burnEnd 表示还在烧） */
        int burnEnd;
        /** 本轮同时烧着几台（燃料不够台数时少于台数） */
        int burnCount;
        /** 这种机器没有配方也没有燃料表，只能每 tick 问它自己要发电量（如 MomoTech 那台恒 1 J） */
        EnergyNetProvider live;
        /** 发电量的小数累积（千分之一 J） */
        long accMilliJ;
        /** 上一次画在状态卡上的文字（变了就立刻重画 = 不用等 10 tick） */
        String lastStatus;
        /** 上一次显示的每 tick 发电量（停摆时刷发电信息用） */
        long lastPerTick;
        /** 接在电网里时要交给电网的电（J/tick）；没接电网 = 0，自己存着 */
        long netOutput;
        /** 燃料配方的蓄力累积 */
        final long[] fuelAcc = new long[1];
        /** 虚拟电量（内存缓存，每 20 tick 落一次 PDC —— 每 tick 读写 BlockStorage 太贵） */
        long virt;
        boolean virtLoaded;
        boolean virtDirty;
        /** 上一次把虚拟电量写回 PDC 的 tick */
        int virtFlushed;
        /** 这个方块有没有接电网（缓存 20 tick，不用每 tick 去问 EnergyNet） */
        boolean onNet;
        boolean onNetKnown;
        /** 过载结束时间（毫秒）；0 = 没在过载 */
        long ovlUntil;

        void reset() {
            machineId = null;
            machineName = null;
            scans = List.of();
            count = 1;
            mixedMachines = false;
            powerKnown = false;
            fuelUnknown = false;
            powerPerTick = 0;
            fuelCycle = false;
            burnEnd = 0;
            burnCount = 0;
            live = null;
            accMilliJ = 0;
            fuelAcc[0] = 0;
            lastStatus = null;
            lastPerTick = 0;
            netOutput = 0;
            virtDirty = false;
            ovlUntil = 0;
        }
    }

    @Override
    protected void tick(Block b, BlockMenu menu) {
        Location loc = b.getLocation();
        State st = states.computeIfAbsent(loc, k -> new State());
        st.loc = loc;
        st.ticks++;
        st.netOutput = 0;
        // 虚拟电量攒够 20 tick 落一次盘
        if (st.virtDirty && st.ticks - st.virtFlushed >= 20) flushVirtual(st, loc);

        // ---- 过载（腐竹 2026-11-08：学等价附属 EMC 发电机）----
        // 过载 = 把本机攒的全部电量（缓冲 + 虚拟）一次性灌进 energy-charge，允许超过容量上限；
        // 60 秒后强制归零 —— 「归零不可撤销」是这套玩法的代价，界面按钮上写明。
        if (st.ovlUntil <= 0 && st.ticks % 20 == 0) {
            st.ovlUntil = MachineUtil.longInfo(loc, KEY_OVERLOAD_UNTIL, 0L);
        }
        if (st.ovlUntil > 0 && System.currentTimeMillis() >= st.ovlUntil) {
            try {
                setCharge(loc, 0L);
            } catch (Throwable ignored) {
            }
            storeVirtual(st, loc, 0L);
            flushVirtual(st, loc);
            st.ovlUntil = 0;
            MachineUtil.set(loc, KEY_OVERLOAD_UNTIL, "0");
            status(menu, st, "§c过载结束，电量已归零");
            return;
        }

        Settings cfg = Settings.get();
        int idleSkip = cfg == null ? 2 : cfg.idleSkipTicks();
        int identify = cfg == null ? 4 : cfg.identifyEveryTicks();

        // ---- 跟随模式：上方真的摆着一台发电机就没必要再算配方/燃料 ----
        // 腐竹 2026-11-08：学逻辑的量子超频装置 —— 发电和燃料都由上方那台真机器负责，
        // 我们只读它的 getGeneratedOutput 并按机器格里同款物品的堆叠数放大。
        Block aboveBlock = b.getRelative(BlockFace.UP);
        SlimefunItem aboveSf = BlockStorage.check(aboveBlock);
        if (aboveSf != null && aboveSf != this && aboveSf instanceof EnergyNetProvider provider) {
            if (follow(b, aboveBlock, aboveSf, provider, menu, st, cfg)) return;
        }

        // ---- 识别：右边第一个非空的机器格 ----
        if (st.machineId == null || st.ticks % Math.max(1, identify) == 0) {
            SlimefunItem sf = null;
            for (int slot : machineSlots()) {
                SlimefunItem s = MachineUtil.machineIn(menu.getItemInSlot(slot));
                if (s != null) {
                    sf = s;
                    break;
                }
            }
            if (sf == null) {
                if (st.machineId != null) {
                    st.reset();
                    MachineUtil.unset(loc, KEY_MACHINE);
                }
                st.blockedUntil = st.ticks + idleSkip;
                status(menu, st, "§7右边空着 · 把发电机放进去");
                return;
            }
            boolean[] mixed = new boolean[1];
            st.count = countMachines(menu, sf, mixed);
            st.mixedMachines = mixed[0];
            if (!sf.getId().equals(st.machineId)) {
                st.machineId = sf.getId();
                st.machineName = nameOf(sf);
                // 燃料型发电机（煤炭发电机这类）没有 MachineRecipe，配方就是它的燃料表
                List<Scan> fuels = RecipeScanner.fuels(sf);
                st.scans = fuels.isEmpty() ? scansFor(sf) : fuels;
                PowerProbe.Info p = PowerProbe.of(sf);
                st.powerPerTick = p.perTick;
                st.powerKnown = p.known;
                st.fuelCycle = !fuels.isEmpty();
                st.burnEnd = 0;
                st.burnCount = 0;
                if (!fuels.isEmpty() && fuels.get(0).energyPerTick > 0) {
                    // 燃料表里已经带了「每 tick 发多少」，比反射探测更可信
                    st.powerPerTick = fuels.get(0).energyPerTick;
                    st.powerKnown = true;
                }
                // 没有燃料表、也没有耗料配方、又没有输入槽 ⇒ 无消耗发电机：
                // 只能每 tick 直接问它 getGeneratedOutput（恒 1 J 那种、太阳能那种都走这里）
                // 有输入槽的也能当「无燃料发电机」：只要读得到发电量就按读到的发
                // （RSC 的脚本发电机 CustomEnergyGenerator 就是这样，燃料在脚本里，我们扣不了）
                st.live = (!st.fuelCycle && st.scans.isEmpty()
                        && sf instanceof EnergyNetProvider ep
                        && (st.powerKnown || !InputProbe.hasInputSlots(sf))) ? ep : null;
                // 有输入槽却扫不出耗料配方 ⇒ 燃料在它自己的 tick 里扣，我们扣不了
                st.fuelUnknown = st.scans.isEmpty() && InputProbe.hasInputSlots(sf) && st.live == null;
                st.accMilliJ = 0;
                st.fuelAcc[0] = 0;
                MachineUtil.set(loc, KEY_MACHINE, sf.getId());
            }
        }
        if (st.blockedUntil > st.ticks) {
            idleRefresh(menu, st);
            return;
        }

        long powerValue;
        if (st.live != null) {
            powerValue = livePower(loc, st.live);
            if (powerValue <= 0 && st.powerKnown) powerValue = st.powerPerTick;
            if (powerValue <= 0) {
                st.blockedUntil = st.ticks + idleSkip;
                status(menu, st, "§7" + (st.machineName == null ? "这台机器" : st.machineName) + " §7现在没发电");
                return;
            }
        } else {
            if (!st.powerKnown || st.powerPerTick <= 0) {
                st.blockedUntil = st.ticks + idleSkip;
                status(menu, st, "§c读不到 " + (st.machineName == null ? "这台机器" : st.machineName) + " §c的发电量 · 不发电");
                return;
            }
            powerValue = st.powerPerTick;
        }

        long cap = getCapacity();
        long charge = charge(loc);
        long virt = virtualOf(st);
        // 虚拟电量：缓冲没满就立刻补回去（用的时候不卡）
        if (virt > 0 && charge < cap) {
            long take = Math.min(cap - charge, virt);
            addCharge(loc, take);
            charge += take;
            virt -= take;
            storeVirtual(st, loc, virt);
        }
        // 接在电网里 ⇒ 电直接交给电网，本机不需要缓冲（也就不做「电满」停机）
        boolean onNet = onNet(st, loc);
        long room = onNet ? Long.MAX_VALUE : Math.max(0L, cap - charge);
        // 电满了也不停：缓冲装不下的部分进虚拟电量（腐竹 2026-11-08：
        // 「虚拟电量也因为电满了就停止发电了」——虚拟电量本来就是给溢出准备的）

        double eff = cfg == null ? 1.0 : cfg.baseEfficiency();
        int capCrafts = cfg == null ? 256 : cfg.maxCraftsPerTick();

        // ---- 燃料 ----
        if (st.fuelUnknown) {
            st.blockedUntil = st.ticks + idleSkip;
            status(menu, st, "§c" + (st.machineName == null ? "这台机器" : st.machineName)
                    + " §c的燃料在它自己界面里扣，这里扣不了 · 不发电");
            return;
        }
        if (st.fuelCycle) {
            // 燃料型发电机（煤炭发电机这类）：一轮烧 N 个燃料，烧完这一轮才继续发。
            // ⚠ 先扣燃料再发电 —— 反过来的话「拿走燃料」能在这一轮剩余时间白拿电。
            if (st.ticks > st.burnEnd) {
                Map<ItemKey, Integer> have = InvOps.count(menu, inputSlots());
                Scan fuel = autoMatch(have, st.scans);
                if (fuel == null) {
                    st.blockedUntil = st.ticks + idleSkip;
                    status(menu, st, "§7缺燃料 §8(左边放这台机器要烧的东西)");
                    return;
                }
                int affordable = (int) Math.min(st.count, CraftMath.byInput(have, fuel.need));
                if (affordable <= 0) {
                    st.blockedUntil = st.ticks + idleSkip;
                    status(menu, st, "§7缺燃料 §8(左边放这台机器要烧的东西)");
                    return;
                }
                InvOps.consume(menu, inputSlots(), fuel.cost, affordable);
                st.burnCount = affordable;
                // 效率越高烧得越快：一轮 = 燃料 tick 数 ÷ 效率
                st.burnEnd = st.ticks + Math.max(1, (int) Math.round(fuel.ticks / Math.max(0.01, eff)));
            }
        } else if (!st.scans.isEmpty()) {
            Map<ItemKey, Integer> have = InvOps.count(menu, inputSlots());
            Scan fuel = autoMatch(have, st.scans);
            if (fuel == null) {
                st.blockedUntil = st.ticks + idleSkip;
                status(menu, st, "§7缺燃料 §8(左边放这台机器要烧的东西)");
                return;
            }
            int crafts = CraftMath.perTick(st.fuelAcc, st.count, eff, fuel.ticks, capCrafts);
            if (crafts > 0) InvOps.consume(menu, inputSlots(), fuel.cost, crafts);
        }

        // ---- 发电 ----
        int running = st.fuelCycle ? st.burnCount : st.count;
        st.accMilliJ += Math.round(powerValue * running * eff * 1000.0);
        long give = st.accMilliJ / 1000L;
        if (give <= 0) {
            status(menu, st, "§7蓄电中 §8(" + st.count + "台)");
            if (st.ticks % 5 == 0) info(st, menu, st.lastPerTick, st.count, charge, cap);
            return;
        }
        st.accMilliJ -= give * 1000L;
        if (onNet) {
            // 接在电网里：把这一 tick 的电交给电网去分（自己不留）
            st.netOutput = give;
            // 缓冲已经满了还接着电网：电网会不会取走我们看不到，此时也把这一 tick 存进
            // 虚拟电量（腐竹 2026-11-08：「一直在发电但虚拟电量显示 0」）。
            // 关掉 power.virtual-when-on-net 就回到「电网优先、不另存」。
            if ((cfg == null || cfg.virtualWhenOnNet()) && charge >= cap) {
                storeVirtual(st, loc, safeAdd(virt, give));
            }
        } else {
            long fit = Math.min(give, room);
            if (fit > 0) addCharge(loc, fit);
            long left = give - fit;
            // 缓冲装不下的部分进虚拟电量，不浪费
            if (left > 0) storeVirtual(st, loc, safeAdd(virt, left));
        }

        if (st.ticks % 5 == 0) {
            long perTick = Math.round(powerValue * running * eff);
            status(menu, st, (onNet ? "§a发电中 §7· §f输出 +" : "§a发电中 §7· §f+") + perTick + " J/tick §8(" + st.count + "台"
                    + (st.scans.isEmpty() ? "，不用燃料" : "，烧燃料") + ")"
                    + (st.fuelCycle && st.burnCount < st.count ? " §e· 燃料只够 " + st.burnCount + " 台" : "")
                    + (st.mixedMachines ? " §c· 右边混了别的机器" : ""));
            info(st, menu, perTick, st.count, onNet ? charge(loc) : Math.min(cap, charge + give), cap);
        }
    }

    /**
     * 跟随模式：上方是真实发电机时，发电与燃料都交给它，我们只读并放大。
     *
     * <p>判据照逻辑工艺的量子超频装置：上方方块必须真是那台机器（{@link BlockStorage#check}），
     * 机器格里放同款机器物品，堆叠数 = 放大倍数；上方那台的 {@code getGeneratedOutput}
     * 本身就会扣它自己的燃料（芯片卡这类），所以燃料不需要我们另算。
     *
     * @return true = 这一 tick 已由跟随模式处理完（含停摆）
     */
    private boolean follow(Block b, Block aboveBlock, SlimefunItem aboveSf,
                           EnergyNetProvider provider, BlockMenu menu, State st, Settings cfg) {
        Location loc = b.getLocation();
        Location aboveLoc = aboveBlock.getLocation();

        boolean[] mixed = new boolean[1];
        int count = countMachines(menu, aboveSf, mixed);
        if (count <= 0) {
            st.blockedUntil = st.ticks + 2;
            status(menu, st, "§7上方是 §f" + nameOf(aboveSf) + "§7 · 把同款机器放进机器格再发电");
            return true;
        }

        SlimefunBlockData data;
        try {
            data = Slimefun.getDatabaseManager().getBlockDataController().getBlockData(aboveLoc);
        } catch (Throwable t) {
            data = null;
        }
        if (data == null) {
            st.blockedUntil = st.ticks + 2;
            status(menu, st, "§7上方那台机器还没加载好 · 稍等");
            return true;
        }

        long out;
        try {
            out = provider.getGeneratedOutputLong(aboveLoc, data);
        } catch (Throwable t) {
            st.blockedUntil = st.ticks + 2;
            status(menu, st, "§c读 " + nameOf(aboveSf) + " §c的发电量时出错 · 不发电");
            return true;
        }
        if (out <= 0) {
            st.blockedUntil = st.ticks + 2;
            status(menu, st, "§7" + nameOf(aboveSf) + " §7现在没发电 §8(缺燃料/没运转)");
            return true;
        }

        long cap = getCapacity();
        long charge = charge(loc);
        long room = Math.max(0L, cap - charge);
        if (room <= 0) {
            st.blockedUntil = st.ticks + 2;
            status(menu, st, "§c电满了 §7· " + charge + " J");
            return true;
        }

        double eff = cfg == null ? 1.0 : cfg.baseEfficiency();
        // 放大倍率（腐竹 2026-11-08：跟随太强势，按 10% 算）；小数用千分之一累积
        double mult = cfg == null ? 0.1 : cfg.followMultiplier();
        st.accMilliJ += Math.round((double) out * count * eff * mult * 1000.0);
        long gen = st.accMilliJ / 1000L;
        if (gen <= 0) {
            status(menu, st, "§7跟随蓄电中 §8(" + count + "台)");
            return true;
        }
        st.accMilliJ -= gen * 1000L;
        if (onNetwork(loc)) {
            st.netOutput = gen;
        } else {
            addCharge(loc, Math.min(gen, room));
        }

        if (st.ticks % 5 == 0) {
            status(menu, st, "§a跟随发电 §7· §f+" + gen + " J/tick §8(上方 " + nameOf(aboveSf)
                    + " ×" + count + (mixed[0] ? " §c· 机器格混了别的机器" : "") + ")");
            info(st, menu, gen, count, charge(loc), cap);
        }
        return true;
    }

    /**
     * 画状态卡：文字一变就立刻重画，没变也每 10 tick 刷一次。
     * <p>腐竹 2026-11-08：「菜单的刷新不是很及时，如果没在发电就不刷新了」——
     * 原来只在「跑起来」的分支里每 10 tick 画一次，停摆时状态卡就冻住了。
     */
    private void status(BlockMenu menu, State st, String text) {
        boolean changed = !text.equals(st.lastStatus);
        st.lastStatus = text;
        if (changed || st.ticks % 5 == 0) render(menu, text);
    }

    /** 停摆时的刷新：状态卡照旧重画 + 发电信息跟着存电走 */
    private void idleRefresh(BlockMenu menu, State st) {
        if (st.ticks % 5 != 0) return;
        if (st.lastStatus != null) render(menu, st.lastStatus);
        info(st, menu, st.lastPerTick, st.count, charge(menu.getBlock().getLocation()), getCapacity());
    }

    private void info(State st, BlockMenu menu, long perTick, int count, long charge, long cap) {
        st.lastPerTick = perTick;
        menu.replaceExistingItem(Layout.GEN2_INFO, GuiItems.of(Material.REDSTONE, "§b发电信息",
                "§7每 tick 发出 §f" + perTick + " J",
                "§7台数 §f" + count,
                "§7本机存电 §f" + charge + " §7/ §f" + cap + " J",
                "§7虚拟电量 §f" + virtualOf(st) + " J",
                st.ovlUntil > System.currentTimeMillis()
                        ? "§c过载中 §8· 剩余 §f" + Math.max(0L, (st.ovlUntil - System.currentTimeMillis()) / 1000) + " 秒"
                        : "§8过载：把全部电量一次送出去"), true);
        long ovlLeft = st.ovlUntil > System.currentTimeMillis()
                ? Math.max(0L, (st.ovlUntil - System.currentTimeMillis()) / 1000) : 0L;
        menu.replaceExistingItem(Layout.GEN2_OVERLOAD, overloadIcon(ovlLeft), true);
    }

    private Scan autoMatch(Map<ItemKey, Integer> have, List<Scan> scans) {
        for (Scan s : scans) {
            if (!s.producesSomething() && s.cost.isEmpty()) continue;
            if (CraftMath.byInput(have, s.need) > 0) return s;
        }
        return null;
    }

    /** 无消耗发电机：每 tick 直接问它要发电量；问不出来就当 0（不发电） */
    private long livePower(Location loc, EnergyNetProvider provider) {
        try {
            SlimefunBlockData data = Slimefun.getDatabaseManager().getBlockDataController().getBlockData(loc);
            if (data == null) return 0L;
            return provider.getGeneratedOutputLong(loc, data);
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ---- 虚拟电量（存 PDC，重启不丢） ----
    private static final String KEY_VIRTUAL = "xzk-venergy";

    /**
     * 虚拟电量：内存缓存，读一次 PDC 之后就不再每 tick 读。
     * <p>腐竹 2026-11-08「发电机的性能占用变高了，0.35ms 左右」——原来每 tick 走两三次
     * BlockStorage 的 get/set（每次都要碰方块数据），现在改成内存 + 每 20 tick 落盘一次。
     */
    private long virtualOf(State st) {
        if (!st.virtLoaded) {
            st.virt = Math.max(0L, MachineUtil.longInfo(st.loc, KEY_VIRTUAL, 0L));
            st.virtLoaded = true;
            st.virtFlushed = st.ticks;
        }
        return st.virt;
    }

    /** 记下新值；每 20 tick（或首次）才真正写一次 PDC，避免每 tick 都写方块数据 */
    private void storeVirtual(State st, Location loc, long v) {
        long nv = Math.max(0L, v);
        if (nv == st.virt) return;
        st.virt = nv;
        st.virtDirty = true;
        if (st.ticks - st.virtFlushed >= 20) flushVirtual(st, loc);
    }

    private void flushVirtual(State st, Location loc) {
        if (!st.virtDirty) return;
        MachineUtil.set(loc, KEY_VIRTUAL, Long.toString(st.virt));
        st.virtDirty = false;
        st.virtFlushed = st.ticks;
    }

    private static long safeAdd(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }

    /**
     * 电网来取电（Slimefun 的 EnergyNet 只把 {@link EnergyNetProvider} 当发电机，
     * 光声明 GENERATOR 类型不够 —— 这是「知识发电机只能自己发电自己用」的根因）。
     *
     * <p>返回值是 long：腐竹 2026-11-08「发电的速度可以快，超过 int 上限也没事」。
     */
    @Override
    public long getGeneratedOutputLong(Location loc, SlimefunBlockData data) {
        State st = states.get(loc);
        return st == null ? 0L : Math.max(0L, st.netOutput);
    }

    /** 这个方块是不是接在电网里（有导线连着）。缓存 20 tick —— 每 tick 问一次 EnergyNet 也不便宜 */
    private boolean onNet(State st, Location loc) {
        if (!st.onNetKnown || st.ticks % 20 == 1) {
            st.onNet = onNetwork(loc);
            st.onNetKnown = true;
        }
        return st.onNet;
    }

    private boolean onNetwork(Location loc) {
        try {
            return EnergyNet.getNetworkFromLocation(loc) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private long charge(Location loc) {
        try {
            return getChargeLong(loc);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 直接写 energy-charge，绕开 setCharge 的容量夹断（过载专用） */
    private void setRawCharge(Location loc, long amount) {
        long v = Math.max(0L, amount);
        try {
            com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils
                    .setData(loc, "energy-charge", Long.toString(v));
            return;
        } catch (Throwable ignored) {
        }
        try {
            setCharge(loc, v);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 过载 ----------------

    private static final String KEY_OVERLOAD_UNTIL = "xzk-ovl-until";
    /** 过载持续时间：60 秒（与等价附属 EMC 发电机同一口径） */
    private static final long OVERLOAD_MS = 60_000L;

    @Override
    protected void initMenu(@Nonnull me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset preset) {
        super.initMenu(preset);
        preset.addItem(Layout.GEN2_OVERLOAD, overloadIcon(0L), (p, slot, item, action) -> {
            Block b = openedBlock(p);
            if (b == null) return false;
            Location loc = b.getLocation();
            java.util.UUID owner = MachineUtil.ownerOrClaim(loc, p);
            if (owner == null || !owner.equals(p.getUniqueId())) {
                p.sendMessage("§c[知识发电机] 只有机主能过载");
                return false;
            }
            long until = MachineUtil.longInfo(loc, KEY_OVERLOAD_UNTIL, 0L);
            if (until > System.currentTimeMillis()) {
                p.sendMessage("§e[知识发电机] 过载进行中，剩余 "
                        + ((until - System.currentTimeMillis()) / 1000) + " 秒");
                return false;
            }
            State st = states.get(loc);
            long total = safeAdd(Math.max(0L, charge(loc)), st == null ? 0L : virtualOf(st));
            // ⚠ 不能用 setCharge：Slimefun 的 default setCharge 内部会
            // NumberUtils.clamp(0, charge, getCapacity()) —— 超过容量的部分直接被夹掉，
            // 表现就是「点了过载，存量一点没变」。这里直接写方块数据绕过夹断。
            setRawCharge(loc, total);
            long now = System.currentTimeMillis();
            if (st != null) {
                st.virt = 0L;
                st.virtLoaded = true;
                st.virtDirty = true;
                flushVirtual(st, loc);
                st.ovlUntil = now + OVERLOAD_MS;
            }
            MachineUtil.set(loc, KEY_OVERLOAD_UNTIL, Long.toString(now + OVERLOAD_MS));
            p.sendMessage("§c[知识发电机] 过载开始：全部电量已灌进本机（" + total
                    + " J），60 秒后强制归零");
            return false;
        });
    }

    private static org.bukkit.inventory.ItemStack overloadIcon(long leftSec) {
        boolean running = leftSec > 0;
        return GuiItems.of(Material.TNT, running ? "§c过载中" : "§c过载输出",
                running ? "§7剩余 §f" + leftSec + " 秒" : "§7把本机全部电量一次性灌进电网",
                "§7缓冲 + 虚拟电量一起送出去",
                "§8可以超过容量上限",
                "§c60 秒后强制归零",
                "§8慎用：归零不可撤销");
    }

    /** 玩家当前打开的机器界面属于哪个方块；不是本插件的界面就 null */
    private static Block openedBlock(org.bukkit.entity.Player p) {
        try {
            org.bukkit.inventory.Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof BlockMenu bm) return bm.getBlock();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
