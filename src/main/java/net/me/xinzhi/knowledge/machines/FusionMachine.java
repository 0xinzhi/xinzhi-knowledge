package net.me.xinzhi.knowledge.machines;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.config.Settings;
import net.me.xinzhi.knowledge.core.GeneratorCard;
import net.me.xinzhi.knowledge.core.ItemKey;
import net.me.xinzhi.knowledge.core.MachineWhitelist;
import net.me.xinzhi.knowledge.recipe.RecipeLock;
import net.me.xinzhi.knowledge.recipe.RecipeScanner;
import net.me.xinzhi.knowledge.recipe.Scan;
import net.me.xinzhi.knowledge.tank.TankNet;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 融合机（腐竹 2026-11-14）。
 *
 * <h2>四种模式（看第 25 格 + 左边放了什么）</h2>
 * <ol>
 *   <li><b>按配方融合</b>：识别槽里放一台机器，左边 6 格放它配方里各原料的<b>生成器卡</b>，
 *       就按那台机器的配方合成「产物生成器卡」—— 消耗几张原料卡 = 配方的原料数量，
 *       产出的卡「每个间隔产几个」= 配方产出数量（原话：「产出的生成器数量按原配方产出数量算」）。
 *       这一路才会把间隔 ×1.2（原话：「合成次数越多，延长 1.2 倍」）。</li>
 *   <li><b>多合 1</b>（识别槽空着、左边是同一种产物的卡）：合并成<b>一张</b>卡 ——
 *       3A → A×3。间隔取其中最大的（原话：「如果时间不同按最高的算」「不会干扰时间只会增加产出数量」），
 *       产量相加，<b>不再</b> ×1.2（要能原样拆回去，拉长就不可逆了）。
 *       不同产物<b>绝不</b>合并（腐竹 2026-11-15：「杂合到一块」是 bug）。</li>
 *   <li><b>1 分多</b>（识别槽空着、左边只有一张一次产多个的卡）：拆回 N 张一次产 1 个的卡 ——
 *       A×3 → 3A，是「多合 1」的逆运算。随机产物卡不能拆（腐竹：「100% 出的可以拆，随机不能拆」）。</li>
 *   <li><b>机器转卡</b>（识别槽放的是生成器）：按它的产出速率归一成生成器卡，机器被吃掉。</li>
 * </ol>
 *
 * <h2>时间</h2>
 * 融合一次的时间 = 参与融合的卡里最长的那个间隔（原话：「如果时间不同按最高的算」）。
 * 按配方融合的产出卡间隔 = 最大值 ×1.2；合并/拆分的产出卡间隔 = 最大值，不再增长。
 */
public final class FusionMachine extends BaseKnowledgeMachine {

    private final Map<Location, State> states = new ConcurrentHashMap<>();

    public FusionMachine(ItemGroup group, SlimefunItemStack item,
                         RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
        addItemHandler(new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(@Nonnull BlockPlaceEvent e) {
                MachineUtil.setOwner(e.getBlock().getLocation(), e.getPlayer());
            }
        });
    }

    @Override protected String menuTitle() {
        return "§b融合机 §8· §7放机器按配方融合 · 不放机器 = 合并";
    }

    private static final String KEY_MACHINE = "xzk-machine";

    @Override protected int[] inputSlots() { return Layout.FUSE_IN; }
    @Override protected int[] outputSlots() { return Layout.FUSE_OUT; }
    @Override protected int machineSlot() { return Layout.FUSE_MACHINE; }
    @Override protected int recipeSlot() { return Layout.FUSE_RECIPE; }
    @Override protected int statusSlot() { return Layout.FUSE_STATUS; }

    @Override protected String hintLore() {
        return "§8第 25 格放机器（不是卡）· 左 6 列放生成器卡 · 第 34 格选配方 · 右 2 列出产物"
                + " · 机器空着 = 多合 1 / 1 分多 · 旁边摆储罐就先用储罐、先塞储罐";
    }

    @Override protected int infoSlot() { return Layout.FUSE_INFO; }

    @Override protected String[] infoLines() {
        return new String[]{
                "§7四种模式，看第 25 格 + 左边放了什么：",
                "§f① 放了机器 §8= 按那台机器的配方融合",
                "§8   左 6 列放配方里各原料的生成器卡",
                "§8   产物 = 配方产物各自的生成器卡（数量按配方），间隔 ×1.2",
                "§f② 机器空着、同一种产物的卡 §8= 多合 1",
                "§8   3A → A×3：并成一张，产量相加，间隔取最大（不再 ×1.2）",
                "§8   不同产物的卡不会被合到一起",
                "§f③ 机器空着、只有一张一次产多个的卡 §8= 1 分多",
                "§8   A×3 → 3A：拆回 N 张一次产 1 个的卡（多合 1 的逆运算）",
                "§8   随机产物卡不能拆（100% 出的才能拆）",
                "§f④ 放的是生成器 §8= 把机器换成生成器卡",
                "§8   按它的产出速率归一，机器被吃掉",
                "§8   例：1tick 产 5 个 ⇒ 50 张 10tick 产 1 的卡",
                "§8   例：50tick 产 2 个 ⇒ 1 张 25tick 产 1 的卡",
                "§b随机产物 §8（如无尽磨粉机）按配方融合完成时掷一次：",
                "§8   60% 出「随机产物卡」（每次按原概率抽一件）",
                "§8   40% 出候选里某一种的单卡（按那一种的数量产）",
                "§7第 34 格选这台机器要用的配方 §8· 同种产物叠成一叠，多种才分格",
                "§b输入储罐 §8(同一层、紧挨着)：里面的卡先用，不够才用左 6 列的",
                "§b输出储罐 §8(同一层、紧挨着)：产物先塞进去，塞满了才落回右 2 列"
        };
    }

    /** 融合要扣原料，所以只认「要扣料」的配方 */
    @Override protected List<Scan> scansFor(SlimefunItem sf) {
        return RecipeScanner.craftable(sf);
    }

    /**
     * 没有可融合配方时把原因写清楚（腐竹 2026-11-14：「一直都是写扫不出配方啊」）。
     *
     * <p>最常见的一种是「这台机器只有不消耗输入的配方」（纯生成器，例如不耗料的产线机），
     * 那种配方本来就不该进融合机 —— 直接放知识生成器就行。要说到这一步，
     * 否则玩家只能自己去猜。
     */
    @Override protected String noRecipeMessage(SlimefunItem sf) {
        if (sf == null) return "§c识别槽里没有机器。";
        if (!RecipeScanner.generators(sf).isEmpty()) {
            return "§c" + nameOf(sf) + " §c只有不消耗输入的配方 §8· 那种不用扣料，放「知识生成器」里直接产；"
                    + "融合机只认要扣料的配方";
        }
        return "§c" + nameOf(sf) + " §c扫不出配方 §8(/xzk dump " + sf.getId() + " 可以看它到底有没有配方)";
    }

    private static final class Card {
        /** 卡池里的唯一编号（同一张卡在储罐和机器槽里可能撞槽号，扣料时靠它区分） */
        final int uid;
        /** 卡所在的储罐（null = 机器自己的左 6 列） */
        final BlockMenu tank;
        final int slot;
        /** 卡本体（储罐里的卡要比对相似度才知道扣哪一格） */
        final ItemStack stack;
        /** 这张卡能充当的产物：普通卡 1 种；随机产物卡 = 全部候选 */
        final List<ItemKey> products;
        /** 随机产物卡：候选本体（带各自数量），合并时要整体带过去 */
        final List<ItemStack> poolChoices;
        final boolean pool;
        final int interval;
        /** 普通卡 = 每间隔产几个；随机卡 = 每间隔抽几次 */
        final int count;
        final int amount;

        Card(int uid, BlockMenu tank, int slot, ItemStack stack,
             List<ItemKey> products, List<ItemStack> poolChoices, boolean pool,
             int interval, int count, int amount) {
            this.uid = uid;
            this.tank = tank;
            this.slot = slot;
            this.stack = stack;
            this.products = products;
            this.poolChoices = poolChoices;
            this.pool = pool;
            this.interval = interval;
            this.count = count;
            this.amount = amount;
        }

        boolean covers(ItemKey k) {
            return products.contains(k);
        }

        /** 合并的分组键：普通卡按产物；随机卡按「整张候选表」（候选不同就不该混） */
        String groupKey() {
            return pool ? "P|" + products : "I|" + products.get(0);
        }
    }

    /**
     * 「随机产物」那一路的产出（腐竹 2026-11-15）：<b>60% 出随机产物卡</b>（每次抽一件），
     * 40% 出候选里某一种的<b>单卡</b>（按那一种自己的数量产）。
     *
     * <p>掷点在<b>融合完成那一下</b>，不在算计划时 —— 计划每 identifyEveryTicks 重算一次，
     * 在计划里掷等于一直重掷，预览抖、结果还偏。
     */
    private static final class RandomOut {
        final List<ItemStack> choices;
        final int interval;
        final int rolls;

        RandomOut(List<ItemStack> choices, int interval, int rolls) {
            this.choices = choices;
            this.interval = interval;
            this.rolls = Math.max(1, rolls);
        }

        /** 只用于「产物格放不放得下」的占位（随机卡与单卡都只占 1 格，形状一样） */
        ItemStack placeholder() {
            ItemStack c = GeneratorCard.createPool(choices, interval, rolls);
            return c == null ? new ItemStack(Material.PAPER) : c;
        }

        ItemStack roll() {
            java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
            if (rnd.nextInt(100) < 60) {
                ItemStack c = GeneratorCard.createPool(choices, interval, rolls);
                if (c != null) return c;
            } else {
                ItemStack pick = choices.get(rnd.nextInt(choices.size()));
                ItemKey k = ItemKey.of(pick);
                ItemStack c = k == null ? null : GeneratorCard.create(k, interval, pick.getAmount() * rolls);
                if (c != null) return c;
            }
            // 兜底：造单卡失败也至少要给一张随机卡，别把产物吞掉
            ItemStack c = GeneratorCard.createPool(choices, interval, rolls);
            return c == null ? new ItemStack(Material.PAPER) : c;
        }
    }

    private static final class Plan {
        int duration = GeneratorCard.MIN_INTERVAL;
        /** 参与融合的卡（来自储罐或机器槽，扣料时按各自的 tank 走） */
        List<Card> src = List.of();
        int[] take = new int[0];
        /** 完成时从识别槽里吃掉几个机器（「放生成器换卡」那一路：原机器是要消耗的） */
        int takeMachine;
        /** 名单识别用的模板物品（生物芯片这类，不是生成器卡）：完成时一并吃掉 */
        ItemStack chip;
        BlockMenu chipTank;
        int chipSlot = -1;
        /** 自输出机器（蜂箱这类 A=A）的模板物品：每种吃 1 个 */
        List<TplSrc> tpls = List.of();
        List<ItemStack> out = List.of();
        List<RandomOut> randoms = List.of();
        String label = "";
        /**
         * 分批产出用的待发队列：第一次完成时把真产物（含随机那一路掷出来的结果）定下来，
         * 之后每完成一轮只往空格里放放得下的部分，源卡/源机器等最后一批才扣
         * （腐竹 2026-11-15：「如果一次性种类太多，那就按批分」）。
         */
        java.util.ArrayDeque<ItemStack> queue;

        /** 占位产物（判空间用）：同种卡叠成一叠，多种才各占一格 */
        List<ItemStack> pending() {
            return stackUp(rawPending());
        }

        /** 真产物：随机那几路在这一刻掷 */
        List<ItemStack> roll() {
            return stackUp(rawRoll());
        }

        private List<ItemStack> rawPending() {
            if (randoms.isEmpty()) return out;
            List<ItemStack> l = new ArrayList<>(out);
            for (RandomOut r : randoms) l.add(r.placeholder());
            return l;
        }

        private List<ItemStack> rawRoll() {
            if (randoms.isEmpty()) return out;
            List<ItemStack> l = new ArrayList<>(out);
            for (RandomOut r : randoms) l.add(r.roll());
            return l;
        }
    }

    /**
     * 同种物品叠成一叠（腐竹 2026-11-15：「如果产物是单种多个就产成一叠。多种才要分开」）。
     *
     * <p>换算成卡以后一种产物常常是几十张（1tick 产 5 个 ⇒ 50 张卡），一张一格根本放不下；
     * 合成一叠既省格子，也符合玩家看物品栏的习惯。超过单叠上限就再开一格。
     */
    private static List<ItemStack> stackUp(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            int left = Math.max(1, it.getAmount());
            for (ItemStack o : out) {
                if (left <= 0) break;
                if (!o.isSimilar(it)) continue;
                int max = Math.max(1, o.getMaxStackSize());
                int room = max - o.getAmount();
                if (room <= 0) continue;
                int move = Math.min(room, left);
                o.setAmount(o.getAmount() + move);
                left -= move;
            }
            while (left > 0) {
                ItemStack one = it.clone();
                int max = Math.max(1, it.getMaxStackSize());
                int put = Math.min(max, left);
                one.setAmount(put);
                out.add(one);
                left -= put;
            }
        }
        return out;
    }

    /** 计划签名：只用来判断「是不是同一份计划」（进度该不该重头算） */
    private static String planSignature(Plan p) {
        if (p == null) return "";
        return p.label + '|' + p.duration + '|' + p.src.size() + '|' + p.take.length
                + '|' + p.out.size() + '|' + p.randoms.size() + '|' + p.takeMachine;
    }

    private static final class State {
        String machineId;
        String machineName;
        List<Scan> scans = List.of();
        long acc;
        int ticks;
        int blockedUntil;
        Plan plan;
        /** 上一轮用的计划签名：变了就重头计时 */
        String planSig = "";
        /** 识别槽里这台机器只有「不消耗输入」的配方 ⇒ 走「按速率换成生成器卡」 */
        boolean converting;
        /**
         * 名单识别的机器（生物模拟室这类：配方不在 MachineRecipe 里，模板物品决定产出）
         * ⇒ 不走扫描器，按 {@link MachineWhitelist} 的内置规则转卡
         */
        boolean whitelist;
        /** 自输出机器（A=A：动力科技蜂箱这类）：放进去的是什么就产什么 */
        boolean selfOut;
        /** 这一次计划用的是锁定的配方（状态卡上标一下，玩家才看得出锁定生效了） */
        boolean locked;
        /** 计划为空时的原因（锁定的配方缺料 / 锁定的那条已经不在了），null = 没锁 */
        String lockNote;
        void reset() {
            machineId = null;
            machineName = null;
            scans = List.of();
            acc = 0;
            plan = null;
            planSig = "";
            converting = false;
            whitelist = false;
            selfOut = false;
            locked = false;
            lockNote = null;
        }
    }

    @Override
    protected void tick(Block b, BlockMenu menu) {
        Location loc = b.getLocation();
        State st = states.computeIfAbsent(loc, k -> new State());
        st.ticks++;

        Settings cfg = Settings.get();
        int idleSkip = cfg == null ? 2 : cfg.idleSkipTicks();
        int identify = cfg == null ? 4 : cfg.identifyEveryTicks();

        ItemStack tool = machineSlot() < 0 ? null : menu.getItemInSlot(machineSlot());
        // 生成器卡本身也是粘液物品：machineIn 会把它当机器返回，机器槽里放卡要先排除掉
        SlimefunItem sf = GeneratorCard.isCard(tool) ? null : MachineUtil.machineIn(tool);
        if (sf == null) {
            if (st.machineId != null) {
                MachineUtil.unset(loc, KEY_MACHINE);
                RecipeLock.clear(loc);
            }
            st.reset();
        } else if (!sf.getId().equals(st.machineId)) {
            // 「方块数据里原本登记的是另一台机器」才算真的换了机器。
            // st 只是内存态：服务器重启 / 区块重载后它是 null，那时候清锁等于把玩家选的配方白扔
            // （腐竹 2026-11-15：「无论怎么锁定都是使用第一条」——这是其中一条丢失路径）。
            String stored = MachineUtil.info(loc, KEY_MACHINE);
            st.machineId = sf.getId();
            st.machineName = nameOf(sf);
            List<Scan> craft = scansFor(sf);
            st.whitelist = false;
            st.selfOut = MachineWhitelist.isSelfOutput(sf);
            if (st.selfOut) {
                // 自输出机器（腐竹 2026-11-18：动力科技蜂箱 A=A）：配方是「A×N → A」，
                // 不能当普通配方机去配原料卡，改成「放进去什么就产出什么卡」。
                st.converting = false;
                st.scans = List.of();
            } else if (craft.isEmpty()) {
                if (MachineWhitelist.isSimChamber(sf)) {
                    // 名单识别（腐竹 2026-11-17 第 4 条）：生物模拟室的配方不在 MachineRecipe 里，
                    // 扫描器永远扫空 —— 按内置规则「模拟室 + 生物芯片 ⇒ 随机掉落生成器卡」。
                    st.whitelist = true;
                    st.converting = false;
                    st.scans = List.of();
                } else {
                    // 只会「不消耗输入」的机器 = 生成器：把它按产出速率换成生成器卡
                    List<Scan> gens = RecipeScanner.generators(sf);
                    st.converting = !gens.isEmpty();
                    st.scans = gens;
                }
            } else {
                st.converting = false;
                st.scans = craft;
            }
            st.acc = 0;
            st.plan = null;
            if (stored != null && !stored.equals(sf.getId())) RecipeLock.clear(loc);
            MachineUtil.set(loc, KEY_MACHINE, sf.getId());
        }

        List<BlockMenu> inTanks = TankNet.around(b, false);
        List<BlockMenu> outTanks = TankNet.around(b, true);
        List<Card> pool = readPool(menu, inTanks);
        if (pool.isEmpty() && !st.converting && !st.whitelist && !st.selfOut) {
            st.acc = 0;
            st.plan = null;
            render(menu, sf == null
                    ? "§7第 25 格放机器 · 左 6 列放生成器卡 §8(机器空着 = 合并同类卡 / 拆分)"
                    : "§7把 §f" + st.machineName + " §7配方里各原料的生成器卡放进左 6 列");
            return;
        }
        if (sf == null && GeneratorCard.isCard(tool)) {
            st.acc = 0;
            st.plan = null;
            render(menu, "§c第 25 格放的是生成器卡 §7· 那一格要放机器物品，卡放左 6 列");
            return;
        }
        if (sf == null && misplacedMachine(menu)) {
            st.acc = 0;
            st.plan = null;
            render(menu, "§c识别槽（第 25 格）空着 §7· 你把机器放进左边卡槽了，换到第 25 格");
            return;
        }

        // 分批产出进行中时别重算计划（重算会把还没发完的队列丢掉；源卡这时也还在）
        boolean batching = st.plan != null && st.plan.queue != null && !st.plan.queue.isEmpty();
        if (st.plan == null || (!batching && st.ticks % Math.max(1, identify) == 0)) {
            st.lockNote = null;
            if (st.whitelist) {
                st.locked = false;
                st.plan = planWhitelist(sf, menu, inTanks);
            } else if (st.selfOut) {
                st.locked = false;
                st.plan = planSelfOut(sf, menu, inTanks);
            } else if (sf == null) {
                st.locked = false;
                st.plan = planMerge(pool);
            } else {
                Scan locked = RecipeLock.resolve(loc, st.scans);
                if (locked != null) {
                    st.locked = true;
                    st.plan = planOne(locked, pool);
                    if (st.plan == null) {
                        if (st.converting) {
                            st.lockNote = "§c这条配方转不出生成器卡 §8· 点第 " + (Layout.FUSE_RECIPE + 1) + " 格换一条";
                            render(menu, st.lockNote);
                            st.acc = 0;
                            return;
                        }
                        st.lockNote = "§c锁定的那条配方缺料 §8· 补料，或点第 " + (Layout.FUSE_RECIPE + 1)
                                + " 格换一条（" + locked.outputName() + "§c）";
                    }
                } else if (RecipeLock.requested(loc)) {
                    // 选的这条已经不在这台机器的列表里（换过机器 / 重载过 / 配方被过滤）：
                    // 必须明说，绝不静默换一条跑 —— 那正是「怎么锁都跑第一条」的由来
                    st.locked = false;
                    st.plan = null;
                    st.lockNote = "§c锁定的配方已不在这台机器的列表里 §7(换过机器或重载过) §8· 点第 "
                            + (Layout.FUSE_RECIPE + 1) + " 格重新选一条";
                } else {
                    st.locked = false;
                    st.plan = planAuto(st.scans, pool);
                }
            }
        }
        if (st.plan == null) {
            st.acc = 0;
            if (st.lockNote != null) {
                render(menu, st.lockNote);
                return;
            }
            if (st.whitelist) {
                ChipSource chip = findChip(menu, inTanks);
                String nm = st.machineName == null ? "模拟室" : st.machineName;
                render(menu, chip == null
                        ? "§7把生物芯片放进左 6 列或输入储罐 §8(" + nm + " 靠芯片决定产出哪种生物的掉落物)"
                        : "§c这张芯片没有掉落表 §8· 空芯片要先用生物芯片注入器注入，再放进融合机");
            } else if (st.selfOut) {
                render(menu, "§7把要产的东西放进左 6 列或输入储罐 §8(" + st.machineName
                        + " 是 A→A 机器，放什么就产什么卡)");
            } else if (st.converting) {
                render(menu, "§c" + st.machineName + " §c这台生成器扫不到可转的产物 §8(换一台，或用 /xzk scan "
                        + st.machineId + " 看)");
            } else if (sf == null) {
                render(menu, noMachineHint(pool));
            } else if (st.scans.isEmpty()) {
                boolean onlyGen = !RecipeScanner.generators(sf).isEmpty();
                render(menu, "§c" + st.machineName + (onlyGen
                        ? " §c只有不消耗输入的配方 §8(那种放知识生成器，不需要融合)"
                        : " §c没有可融合的配方 §8(它没有要扣料的合成配方)"));
            } else {
                // 把「最接近的那条配方缺什么」直接写出来，别只丢一句「对不上」（腐竹连报两轮识别不了配方）
                render(menu, "§c配方对不上 §8· " + missingHint(st.scans, pool));
            }
            return;
        }

        // 计划换了（换卡 / 换配方 / 换机器）就重头计时，别让新计划沿用旧进度
        String sig = planSignature(st.plan);
        if (!sig.equals(st.planSig)) {
            st.planSig = sig;
            st.acc = 0;
        }

        // 产物区一次性腾够地方再开工：以前是「满了就停、清一点再接着等一轮」，
        // 分批那一轮还要重新等满一个间隔，看着就像卡在某个百分比不动
        int free = emptyOutputSlots(menu);
        if (st.plan.queue == null) {
            // 有输出储罐时，储罐的空格也算「放得下」——产物先往储罐塞（腐竹：先放入储罐，满了才放机器）
            int needSlots = st.plan.pending().size();
            if (!outTanks.isEmpty()) needSlots = Math.max(0, needSlots - TankNet.emptySlots(outTanks));
            if (free < needSlots) {
                st.acc = 0;
                render(menu, "§c产物区要 §f" + needSlots + " §c个空格（右 2 列），现在只有 " + free + " 个");
                return;
            }
        }
        if (free == 0 && outTanks.isEmpty()) {
            st.acc = 0;
            render(menu, "§c产物格满了");
            return;
        }

        st.acc++;
        if (st.acc < st.plan.duration) {
            render(menu, "§7融合中 §f" + (st.acc * 100 / st.plan.duration) + "% §8(" + st.plan.label
                    + (st.locked ? " §a· 锁定" : "") + "§8)");
            return;
        }

        // 产物定下来一次（随机那一路就在这一刻掷）——之后分批发，不能每批重掷。
        // 输出储罐优先接：塞得下的不进队列，队列里只留机器产物格要放的
        if (st.plan.queue == null) {
            java.util.ArrayDeque<ItemStack> q = new java.util.ArrayDeque<>();
            for (ItemStack it : st.plan.roll()) {
                ItemStack rest = outTanks.isEmpty() ? it : TankNet.deposit(outTanks, it);
                if (rest != null && !rest.getType().isAir() && rest.getAmount() > 0) q.add(rest);
            }
            st.plan.queue = q;
        }

        int left = st.plan.queue.size();
        boolean lastBatch = left <= free;
        // 最后一批发完才扣源卡/源机器：中途扣了，产物留在内存里，重启就白丢
        if (lastBatch && !consume(menu, st.plan)) {
            st.acc = 0;
            st.plan = null;
            render(menu, "§c卡片数量变了，重新算一次");
            return;
        }
        // 产物只进空格：已有东西的格子不叠加（腐竹 2026-11-15：会先 +1，能刷出货）
        int room = free;
        while (room > 0 && !st.plan.queue.isEmpty()) {
            if (!placeIntoEmpty(menu, st.plan.queue.peekFirst())) break;
            st.plan.queue.pollFirst();
            room--;
        }
        if (!st.plan.queue.isEmpty()) {
            // 下一批只要腾出格子就接着发，不用再等一个完整间隔
            st.acc = st.plan.duration;
            render(menu, "§7分批产出中 §8· 还剩 " + st.plan.queue.size() + " 件 §7(清一下右 2 列就接着出)");
            return;
        }
        render(menu, "§a融合完成 §7· " + st.plan.label);
        st.acc = 0;
        st.plan = null;
        st.planSig = "";
    }

    // ---------------- 卡池 ----------------

    /**
     * 卡池：<b>先输入储罐、再机器左 6 列</b>（腐竹 2026-11-15：「先用储罐的」）。
     *
     * <p>顺序就是优先级 —— 按配方融合时从头往下扣，储罐里的卡会先被吃掉，
     * 机器槽里的卡只有在储罐不够时才补上。
     */
    private List<Card> readPool(BlockMenu menu, List<BlockMenu> inTanks) {
        List<Card> list = new ArrayList<>();
        int uid = 0;
        for (BlockMenu tank : inTanks) {
            for (int s = 0; s < TankNet.SLOTS; s++) {
                ItemStack it = tank.getItemInSlot(s);
                if (!GeneratorCard.isCard(it)) continue;
                Card c = cardOf(uid++, tank, s, it);
                if (c != null) list.add(c);
            }
        }
        for (int s : Layout.FUSE_IN) {
            ItemStack it = menu.getItemInSlot(s);
            if (!GeneratorCard.isCard(it)) continue;
            Card c = cardOf(uid++, null, s, it);
            if (c != null) list.add(c);
        }
        return list;
    }

    private static Card cardOf(int uid, BlockMenu tank, int slot, ItemStack it) {
        List<ItemKey> products = GeneratorCard.products(it);
        if (products.isEmpty()) return null;      // 坏卡：不参与融合，也别把整机卡死
        boolean pool = GeneratorCard.isPool(it);
        return new Card(uid, tank, slot, it, products,
                pool ? GeneratorCard.poolItems(it) : List.of(), pool,
                GeneratorCard.interval(it), GeneratorCard.count(it), it.getAmount());
    }

    /** 「机器空着」那一侧计划为空时，说清到底缺什么（合并 / 拆分各有一条路） */
    private String noMachineHint(List<Card> pool) {
        if (pool.isEmpty()) return "§7第 25 格放机器 · 左 6 列放生成器卡 §8(机器空着 = 合并同类卡 / 拆分)";
        Card c = pool.get(0);
        if (pool.size() == 1 && c.amount == 1) {
            if (c.pool) return "§7随机产物卡不能拆 §8· 只有 100% 出某一种的卡才能拆";
            if (c.count <= 1) return "§7只有一张一次产 1 个的卡，没得拆也没得合 §8(多放几张同类卡才会合并，"
                    + "或把机器放进第 25 格按配方融合)";
        }
        return "§7合并卡住了 §8(检查左 6 列里有没有坏卡 / 别的机器物品)";
    }

    // ---------------- 名单识别（生物模拟室这类） ----------------

    /** 找到的那张模板（生物芯片）：可能来自输入储罐，也可能来自机器左 6 列 */
    private static final class ChipSource {
        final BlockMenu tank;
        final int slot;
        final ItemStack stack;
        final List<ItemStack> products;

        ChipSource(BlockMenu tank, int slot, ItemStack stack, List<ItemStack> products) {
            this.tank = tank;
            this.slot = slot;
            this.stack = stack;
            this.products = products;
        }
    }

    /** 先输入储罐、再机器左 6 列（和卡池同一套优先级） */
    private ChipSource findChip(BlockMenu menu, List<BlockMenu> inTanks) {
        for (BlockMenu tank : inTanks) {
            for (int s = 0; s < TankNet.SLOTS; s++) {
                ItemStack it = tank.getItemInSlot(s);
                List<ItemStack> p = MachineWhitelist.chipProducts(it);
                if (!p.isEmpty()) return new ChipSource(tank, s, it, p);
            }
        }
        for (int s : Layout.FUSE_IN) {
            ItemStack it = menu.getItemInSlot(s);
            List<ItemStack> p = MachineWhitelist.chipProducts(it);
            if (!p.isEmpty()) return new ChipSource(null, s, it, p);
        }
        return null;
    }

    /**
     * 名单识别：模拟室 + 生物芯片 ⇒ 一张「随机掉落生成器卡」。
     *
     * <p>腐竹 2026-11-17：「芯片+模拟室变成生成器卡的话产出数量写死1个」
     * —— 所以每张卡一次只抽 1 件（rolls = 1），不按机器速率放大；
     * 间隔按机器真实速率（无尽 30 tick / 逻辑 2 tick，卡本身最低 10 tick）。
     * 完成时机器和芯片一起被吃掉。
     */
    private Plan planWhitelist(SlimefunItem sf, BlockMenu menu, List<BlockMenu> inTanks) {
        ChipSource chip = findChip(menu, inTanks);
        if (chip == null) return null;
        int interval = Math.max(GeneratorCard.MIN_INTERVAL, MachineWhitelist.chamberTicks(sf));
        ItemStack card = GeneratorCard.createPool(chip.products, interval, 1);
        if (card == null) return null;
        Plan p = new Plan();
        p.duration = interval;
        p.takeMachine = 1;
        p.out = List.of(card);
        p.chip = chip.stack;
        p.chipTank = chip.tank;
        p.chipSlot = chip.slot;
        p.label = "把 " + nameOf(sf) + " + " + chip.products.size() + " 种掉落换成生成器卡";
        return p;
    }

    // ---------------- 自输出机器（A=A：动力科技蜂箱这类） ----------------

    /** 一张模板物品的来源（输入储罐或机器左 6 列） */
    private static final class TplSrc {
        final BlockMenu tank;
        final int slot;
        final ItemStack stack;

        TplSrc(BlockMenu tank, int slot, ItemStack stack) {
            this.tank = tank;
            this.slot = slot;
            this.stack = stack;
        }
    }

    /**
     * 自输出机器：模板放什么就产出什么卡。
     *
     * <p>腐竹 2026-11-18：动力科技蜂箱的配方是「A×N → A」（A 本身当模板），
     * 当普通配方机配原料卡永远配不出来 —— 所以这里按模板物品去查它自己的 A=A 配方，
     * 每种模板给一张卡，间隔读机器真实配方耗时（不写死），机器与模板各吃 1 份。
     */
    private Plan planSelfOut(SlimefunItem sf, BlockMenu menu, List<BlockMenu> inTanks) {
        List<TplSrc> tpls = new ArrayList<>();
        for (BlockMenu tank : inTanks) {
            for (int s = 0; s < TankNet.SLOTS; s++) {
                ItemStack it = tank.getItemInSlot(s);
                if (isTemplate(it)) tpls.add(new TplSrc(tank, s, it));
            }
        }
        for (int s : Layout.FUSE_IN) {
            ItemStack it = menu.getItemInSlot(s);
            if (isTemplate(it)) tpls.add(new TplSrc(null, s, it));
        }
        if (tpls.isEmpty()) return null;
        List<ItemStack> outs = new ArrayList<>();
        List<TplSrc> used = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int duration = GeneratorCard.MIN_INTERVAL;
        for (TplSrc t : tpls) {
            if (!seen.add(t.stack.getType().name() + '|' + t.stack.getItemMeta())) continue;
            Scan sc = MachineWhitelist.selfOutputScan(t.stack, sf);
            if (sc == null) continue;
            ItemStack give = null;
            if (sc.give != null && !sc.give.isEmpty()) give = sc.give.keySet().iterator().next().toTemplate();
            else if (sc.randomChoices != null && !sc.randomChoices.isEmpty()) give = sc.randomChoices.get(0);
            if (give == null || give.getType().isAir()) continue;
            // 自输出机器（蜂箱这类）一次只产 1 个：腐竹 2026-11-18「产出数量写死1个」
            ItemStack card = GeneratorCard.create(ItemKey.of(give), Math.max(GeneratorCard.MIN_INTERVAL, sc.ticks), 1);
            if (card == null) continue;
            outs.add(card);
            used.add(t);
            duration = Math.max(duration, Math.max(GeneratorCard.MIN_INTERVAL, sc.ticks));
        }
        if (outs.isEmpty()) return null;
        Plan p = new Plan();
        p.duration = duration;
        p.takeMachine = 1;
        p.tpls = used;
        p.out = outs;
        p.label = "把 " + nameOf(sf) + " + " + outs.size() + " 种模板换成生成器卡";
        return p;
    }

    /** 能当自输出机器模板的物品：不是生成器卡、不是机器 */
    private static boolean isTemplate(ItemStack it) {
        return it != null && !it.getType().isAir()
                && !GeneratorCard.isCard(it) && !GeneratorCard.isBase(it)
                && MachineUtil.machineIn(it) == null;
    }

    // ---------------- 大杂烩：合并 / 拆分 ----------------

    private Plan planMerge(List<Card> pool) {
        if (pool.isEmpty()) return null;
        if (pool.size() == 1 && pool.get(0).amount == 1) {
            // 只有一张卡：一次产多个才拆得开，否则什么也做不了
            Card only = pool.get(0);
            return !only.pool && only.count > 1 ? planSplit(only) : null;
        }
        Map<String, Card> first = new LinkedHashMap<>();       // 分组键 → 代表卡
        Map<String, long[]> groups = new LinkedHashMap<>();    // 分组键 → {最大间隔, 合计产量}
        for (Card c : pool) {
            String key = c.groupKey();
            first.putIfAbsent(key, c);
            long[] v = groups.computeIfAbsent(key, k -> new long[2]);
            v[0] = Math.max(v[0], c.interval);
            v[1] += (long) c.count * c.amount;
        }
        Plan p = new Plan();
        List<ItemStack> outs = new ArrayList<>();
        List<RandomOut> randoms = new ArrayList<>();
        for (Map.Entry<String, long[]> e : groups.entrySet()) {
            Card rep = first.get(e.getKey());
            // 合并/拆分是互逆的，所以这里不涨间隔（只有「按配方融合」那一路才 ×1.2）
            int interval = Math.max(GeneratorCard.MIN_INTERVAL, (int) Math.min(Integer.MAX_VALUE, e.getValue()[0]));
            int total = (int) Math.min(Integer.MAX_VALUE, e.getValue()[1]);
            if (rep.pool) {
                // 合并出来的随机产物同样走 60/40（掷点在完成时）
                randoms.add(new RandomOut(rep.poolChoices, interval, total));
                continue;
            }
            ItemStack card = GeneratorCard.create(rep.products.get(0), interval, total);
            if (card == null) return null;
            outs.add(card);
        }
        // 合成耗时 = 参与融合的卡里最慢的那个（间隔不因融合而变慢的那一半由产出卡承担）
        for (Card c : pool) p.duration = Math.max(p.duration, c.interval);
        p.out = outs;
        p.randoms = randoms;
        p.src = pool;
        p.take = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) p.take[i] = pool.get(i).amount;
        p.label = groups.size() == 1 ? "合并同类卡" : "合并 " + groups.size() + " 种";
        return p;
    }

    /**
     * 1 分多：一张「一次产 N 个」的卡拆回 N 张「一次产 1 个」的卡 —— 多合 1 的逆运算
     * （腐竹 2026-11-15：「多合1，和1分多，就是从3A转到AX3，和Ax3转3A」）。
     *
     * <p>只拆 100% 出某一种的卡；随机产物卡不能拆（原话：「是100%出的可以拆，随机不能拆」）。
     * 间隔原样保留，不 ×1.2，这样「拆开再合回去」能回到原样。
     */
    private Plan planSplit(Card c) {
        if (c == null || c.pool || c.count <= 1) return null;
        ItemStack unit = GeneratorCard.create(c.products.get(0), c.interval, 1);
        if (unit == null) return null;
        List<ItemStack> outs = new ArrayList<>(c.count);
        for (int i = 0; i < c.count; i++) outs.add(unit.clone());
        Plan p = new Plan();
        p.duration = Math.max(GeneratorCard.MIN_INTERVAL, c.interval);
        p.src = List.of(c);
        p.take = new int[]{1};
        p.out = outs;
        p.label = "拆成 " + c.count + " 张";
        return p;
    }

    // ---------------- 按配方融合 ----------------

    private Plan planAuto(List<Scan> scans, List<Card> pool) {
        if (scans == null || scans.isEmpty()) return null;
        for (Scan s : scans) {
            Plan p = planOne(s, pool);
            if (p != null) return p;
        }
        return null;
    }

    /** 只按这一条配方扣卡；卡不够 / 产出建不出来返回 null */
    private Plan planOne(Scan s, List<Card> pool) {
        if (s == null || !s.producesSomething()) return null;
        if (s.cost == null || s.cost.isEmpty()) return planConvert(s);
        Map<Integer, Integer> takeByUid = new LinkedHashMap<>();
        Map<Integer, Card> byUid = new LinkedHashMap<>();
        int duration = GeneratorCard.MIN_INTERVAL;
        for (Map.Entry<ItemKey, Integer> e : s.cost.entrySet()) {
            int need = Math.max(1, e.getValue());
            int have = 0;
            for (Card c : pool) {
                if (!c.covers(e.getKey())) continue;
                int use = Math.min(c.amount - takeByUid.getOrDefault(c.uid, 0), need - have);
                if (use <= 0) continue;
                byUid.put(c.uid, c);
                takeByUid.merge(c.uid, use, Integer::sum);
                have += use;
                duration = Math.max(duration, c.interval);
                if (have >= need) break;
            }
            if (have < need) return null;
        }

        List<ItemStack> outs = new ArrayList<>();
        int outInterval = GeneratorCard.grow(duration);
        for (Map.Entry<ItemKey, Integer> e : s.give.entrySet()) {
            if (e.getValue() <= 0) continue;
            ItemStack card = GeneratorCard.create(e.getKey(), outInterval, e.getValue());
            if (card == null) return null;
            outs.add(card);
        }
        if (outs.isEmpty() && s.randomChoices.isEmpty()) return null;
        // 随机产物（无尽磨粉机那类）：不拆成「每种候选一张卡」（那等于把随机出一种放大成每种都给），
        // 而是融合完成时掷一次：60% 随机产物卡（每次抽一件），40% 候选里某一种的单卡。
        List<RandomOut> randoms = s.randomChoices.isEmpty()
                ? List.of()
                : List.of(new RandomOut(s.randomChoices, outInterval, 1));
        if (outs.size() + randoms.size() > Layout.FUSE_OUT.length) return null;

        Plan p = new Plan();
        p.randoms = randoms;
        p.duration = duration;
        List<Card> src = new ArrayList<>(takeByUid.size());
        p.take = new int[takeByUid.size()];
        int i = 0;
        for (Map.Entry<Integer, Integer> e : takeByUid.entrySet()) {
            src.add(byUid.get(e.getKey()));
            p.take[i] = e.getValue();
            i++;
        }
        p.src = src;
        p.out = outs;
        p.label = "按 " + e2name(s) + " 融合";
        return p;
    }

    /**
     * 「放生成器 ⇒ 换成生成器卡」（腐竹 2026-11-15 第 4 条，m01746 定速率）。
     *
     * <p>速率归一：每出一件要 {@code ticks / count} tick（记作 p）。
     * <ul>
     *   <li>p &lt; 10：换成 {@code round(10 / p)} 张「10tick 产 1 个」的卡 ——
     *       1tick 产 5 个 ⇒ 50 张 10tick 产 1 的卡；</li>
     *   <li>p ≥ 10：换成 1 张、间隔 {@code ceil(p)} —— 50tick 产 2 个 ⇒ 1 张 25tick 产 1 的卡。</li>
     * </ul>
     *
     * <p>产物是随机的走随机卡规则（60/40 在完成时掷）；多种产物就每种各自给卡。
     * 融合完成时把识别槽里那台机器吃掉 —— 原话「原机器是要消耗的」。
     */
    private Plan planConvert(Scan s) {
        if (s == null || !s.producesSomething()) return null;
        List<ItemStack> outs = new ArrayList<>();
        for (Map.Entry<ItemKey, Integer> e : s.give.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            int amount = Math.max(1, e.getValue());
            double per = (double) s.ticks / amount;
            int interval;
            int cards;
            if (per < GeneratorCard.MIN_INTERVAL) {
                interval = GeneratorCard.MIN_INTERVAL;
                cards = Math.max(1, (int) Math.round(GeneratorCard.MIN_INTERVAL / per));
            } else {
                interval = (int) Math.ceil(per);
                cards = 1;
            }
            for (int i = 0; i < cards; i++) {
                ItemStack c = GeneratorCard.create(e.getKey(), interval, 1);
                if (c == null) return null;
                outs.add(c);
            }
        }
        List<RandomOut> randoms = new ArrayList<>();
        if (!s.randomChoices.isEmpty()) {
            int interval = Math.max(GeneratorCard.MIN_INTERVAL, s.ticks);
            int rolls = s.ticks < GeneratorCard.MIN_INTERVAL
                    ? Math.max(1, (int) Math.round((double) GeneratorCard.MIN_INTERVAL / s.ticks))
                    : 1;
            randoms.add(new RandomOut(s.randomChoices, interval, rolls));
        }
        if (outs.isEmpty() && randoms.isEmpty()) return null;

        Plan p = new Plan();
        p.duration = Math.max(GeneratorCard.MIN_INTERVAL, s.ticks);
        p.takeMachine = 1;
        p.out = outs;
        p.randoms = randoms;
        p.label = "把机器换成 " + e2name(s) + " 生成器卡";
        return p;
    }

    /**
     * 找出「最接近」的那条配方，说明它要什么、还缺什么 —— 状态卡只能显示一行，
     * 所以只报缺得最少的那条，最多 2 项。
     */
    private String missingHint(List<Scan> scans, List<Card> pool) {
        Map<ItemKey, Integer> have = new LinkedHashMap<>();
        for (Card c : pool) {
            for (ItemKey k : c.products) have.merge(k, c.amount, Integer::sum);
        }
        StringBuilder best = null;
        int bestMissing = Integer.MAX_VALUE;
        String bestOut = "";
        for (Scan s : scans) {
            if (s.cost == null || s.cost.isEmpty() || !s.producesSomething()) continue;
            List<String> miss = new ArrayList<>();
            for (Map.Entry<ItemKey, Integer> e : s.cost.entrySet()) {
                int need = Math.max(1, e.getValue());
                int got = Math.min(need, have.getOrDefault(e.getKey(), 0));
                if (got < need) miss.add(e.getKey().getDisplayName() + "×" + (need - got));
            }
            if (miss.isEmpty()) continue;            // 理论上会被 planRecipe 接住，兜底而已
            if (miss.size() < bestMissing) {
                bestMissing = miss.size();
                best = new StringBuilder();
                int n = 0;
                for (String m : miss) {
                    if (n > 0) best.append(" §7+ §c");
                    best.append(m);
                    if (++n >= 2) break;
                }
                bestOut = s.outputName() == null ? "" : s.outputName();
            }
        }
        if (best == null) return "§7" + scans.size() + " 条配方全对不上";
        return "§c缺 " + best + (bestOut.isEmpty() ? "" : " §8(为 " + bestOut + ")");
    }

    /** 左下卡槽里塞了机器物品（不是卡）—— 腐竹 2026-11-14 报「配方识别不了」最常见的原因 */
    private boolean misplacedMachine(BlockMenu menu) {
        for (int s : Layout.FUSE_IN) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType().isAir()) continue;
            // 生成器卡自己也是粘液物品：不排掉的话「卡池里有卡」永远会被当成「机器放错位置」，
            // 大杂烩模式直接卡死在提示里（腐竹 2026-11-14 第 3 条：「融合机还是识别不到配方」）
            if (GeneratorCard.isCard(it)) continue;
            if (MachineUtil.machineIn(it) != null) return true;
        }
        return false;
    }

    private static String e2name(Scan s) {
        return s.outputName() == null ? "配方" : s.outputName();
    }

    // ---------------- 扣卡 / 空位 ----------------

    /** 扣料：储罐里的卡从储罐扣，机器槽里的卡从机器槽扣；先校验齐了再一起扣 */
    private boolean consume(BlockMenu menu, Plan p) {
        for (int i = 0; i < p.src.size(); i++) {
            Card c = p.src.get(i);
            int need = p.take[i];
            if (c.tank != null) {
                if (TankNet.count(c.tank, c.stack) < need) return false;
            } else {
                ItemStack it = menu.getItemInSlot(c.slot);
                if (!GeneratorCard.isCard(it) || it.getAmount() < need) return false;
            }
        }
        if (p.takeMachine > 0) {
            ItemStack tool = menu.getItemInSlot(machineSlot());
            if (tool == null || tool.getType().isAir() || tool.getAmount() < p.takeMachine) return false;
        }
        if (p.chip != null) {
            if (p.chipTank != null) {
                if (TankNet.count(p.chipTank, p.chip) < 1) return false;
            } else {
                ItemStack it = menu.getItemInSlot(p.chipSlot);
                if (!MachineWhitelist.isChamberChip(it) || it.getAmount() < 1) return false;
            }
        }
        for (TplSrc t : p.tpls) {
            if (t.tank != null) {
                if (TankNet.count(t.tank, t.stack) < 1) return false;
            } else {
                ItemStack it = menu.getItemInSlot(t.slot);
                if (it == null || it.getType().isAir() || !it.isSimilar(t.stack)) return false;
            }
        }
        for (int i = 0; i < p.src.size(); i++) {
            Card c = p.src.get(i);
            if (c.tank != null) {
                TankNet.withdraw(c.tank, c.stack, p.take[i]);
            } else {
                EncoderMachine.takeAt(menu, c.slot, p.take[i]);
            }
        }
        if (p.takeMachine > 0) {
            // 原机器是要消耗的（腐竹 2026-11-15 第 4 条）
            EncoderMachine.takeAt(menu, machineSlot(), p.takeMachine);
        }
        if (p.chip != null) {
            if (p.chipTank != null) TankNet.withdraw(p.chipTank, p.chip, 1);
            else EncoderMachine.takeAt(menu, p.chipSlot, 1);
        }
        for (TplSrc t : p.tpls) {
            if (t.tank != null) TankNet.withdraw(t.tank, t.stack, 1);
            else EncoderMachine.takeAt(menu, t.slot, 1);
        }
        return true;
    }

    /**
     * 产物只放空格：每个产物各占一格，已有物品的格子不参与（腐竹 2026-11-15：
     * 以前允许并进已有堆叠，「第一格有东西就会被先加上 1」，能刷出货）。
     * 同时产出两种产物就占两格。
     */
    private boolean hasRoom(BlockMenu menu, List<ItemStack> outs) {
        if (outs.isEmpty()) return false;
        return emptyOutputSlots(menu) >= outs.size();
    }

    private static int emptyOutputSlots(BlockMenu menu) {
        int n = 0;
        for (int s : Layout.FUSE_OUT) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType().isAir()) n++;
        }
        return n;
    }

    /** 找一个空格放进去；没有空格返回 false */
    private static boolean placeIntoEmpty(BlockMenu menu, ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        for (int s : Layout.FUSE_OUT) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType().isAir()) {
                menu.replaceExistingItem(s, item);
                return true;
            }
        }
        return false;
    }
}
