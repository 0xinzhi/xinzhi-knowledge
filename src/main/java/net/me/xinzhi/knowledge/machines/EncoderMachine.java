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
import net.me.xinzhi.knowledge.price.BaseItems;
import net.me.xinzhi.knowledge.recipe.Scan;
import net.me.xinzhi.knowledge.tank.TankNet;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编码机（腐竹 2026-11-14）：5×5 网格，正中间放<b>基础生成器</b>，外圈 16 格各放 64 个
 * <b>同一种</b>基础物品（16 × 64 = 1024），编出一张「这件物品的生成器卡」。
 *
 * <h2>什么叫「基础物品」</h2>
 * 等价附属基础定价表里 {@code 0 < EMC < encoder.base-item-max-emc(5000)} 的物品。
 * 表来自 jar 内快照 + 服务器上的 {@code plugins/XinZhiExchange/prices.yml}，见
 * {@link BaseItems}。超过门槛的物品放进去也不认（界面会说清原因）。
 *
 * <h2>为什么是「一张卡」而不是一种新物品</h2>
 * 粘液物品种类几千个，一种物品注册一个生成器既拖启动又拖指南；卡用 PDC 记产物、
 * 间隔、单次数量 —— 和主附属的「编码种子」同一套做法。
 *
 * <h2>时间</h2>
 * 编码本身不吃生成器的间隔（那是产出的属性），只按 {@code encoder.craft-ticks} 走一遍。
 */
public final class EncoderMachine extends BaseKnowledgeMachine {

    /** 外圈 16 格 × 每格 64 */
    private static final int NEED = 16 * 64;

    private final Map<Location, Long> progress = new ConcurrentHashMap<>();

    public EncoderMachine(ItemGroup group, SlimefunItemStack item,
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
        return "§b编码机 §8· §7基础物品 → 生成器";
    }

    /** 输入 = 5x5 除正中间那格之外的全部 24 格 */
    @Override protected int[] inputSlots() { return Layout.ENC_INPUT; }

    /** 正中间那格也要空着可放（基类只认 input/output/模板/机器/配方，中间格单独放开） */
    @Override protected boolean isFree(int slot) {
        return slot == Layout.ENC_CENTER || super.isFree(slot);
    }
    @Override protected int[] outputSlots() { return new int[]{Layout.ENC_OUTPUT}; }
    /** 编码机没有「识别机器」的概念 */
    @Override protected int machineSlot() { return -1; }
    @Override protected int statusSlot() { return Layout.ENC_STATUS; }

    @Override protected String hintLore() {
        return "§8正中间放基础生成器 · 5x5 其余格合计放够 1024 个同种基础物品 · 点第 47 格可花 "
                + "§f" + fmt5() + " J §8直接编完";
    }

    private static String fmt5() {
        Settings s = Settings.get();
        return String.valueOf(s == null ? 50000L : s.encoderFastCost());
    }

    /** 加速按钮（腐竹 2026-11-14）：花 encoder.fast-cost(默认 5 万 J) 把本次编码直接推满 */
    @Override
    protected void initMenu(@Nonnull me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset preset) {
        super.initMenu(preset);
        preset.addItem(Layout.ENC_FAST, net.me.xinzhi.knowledge.util.GuiItems.of(
                org.bukkit.Material.CLOCK, "§e快速编码 §8· 花 " + fmt5() + " J",
                "§7把本机存电 " + "§f" + fmt5() + " J" + "§7 直接换成本次编码完成",
                "§8只在编码进行中可用",
                "§8电量不足时会告诉你还差多少"), (pl, slot, item, action) -> {
            Block b = openedBlock(pl);
            if (b == null) return false;
            Location loc = b.getLocation();
            if (!progress.containsKey(loc)) {
                pl.sendMessage("§e[编码机] 现在没有正在进行的编码");
                return false;
            }
            Settings s = Settings.get();
            long cost = s == null ? 50000L : s.encoderFastCost();
            long have = Math.max(0L, charge(loc));
            if (have < cost) {
                pl.sendMessage("§c[编码机] 电量不足：需要 §f" + cost + " J§c，本机只有 §f" + have + " J");
                return false;
            }
            consumeCharge(loc, cost);
            progress.put(loc, (long) (s == null ? 200 : s.encoderCraftTicks()));
            pl.sendMessage("§a[编码机] 已花 §f" + cost + " J§a 加速，下一个 tick 出成品");
            return false;
        });
    }

    /** 玩家当前打开的界面属于哪个方块 */
    private static Block openedBlock(org.bukkit.entity.Player pl) {
        try {
            if (pl.getOpenInventory().getTopInventory().getHolder()
                    instanceof me.mrCookieSlime.Slimefun.api.inventory.BlockMenu bm) {
                return bm.getBlock();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override protected List<Scan> scansFor(SlimefunItem sf) {
        return List.of();
    }

    @Override
    protected void tick(Block b, BlockMenu menu) {
        // 储罐（腐竹 2026-11-15）：先把输入罐里的料喂进网格，产物先塞输出罐
        TankNet.refill(menu, Layout.ENC_INPUT, TankNet.around(b, false));
        TankNet.sweep(menu, new int[]{Layout.ENC_OUTPUT}, TankNet.around(b, true));
        Location loc = b.getLocation();
        Settings cfg = Settings.get();
        int needTicks = cfg == null ? 200 : cfg.encoderCraftTicks();

        ItemStack center = menu.getItemInSlot(Layout.ENC_CENTER);
        if (!GeneratorCard.isBase(center)) {
            progress.remove(loc);
            boolean blank = center == null || center.getType().isAir();
            render(menu, blank ? "§7正中间放一个基础生成器"
                    : "§c正中间只能放基础生成器");
            return;
        }

        // 5x5 除了正中间：必须都是同一种物品
        ItemKey key = null;
        int total = 0;
        for (int s : Layout.ENC_INPUT) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType().isAir()) {
                continue;
            }
            ItemKey k = ItemKey.of(it);
            if (k == null) {
                progress.remove(loc);
                render(menu, "§c网格里有一格认不出是什么物品");
                return;
            }
            if (key == null) key = k;
            else if (!key.equals(k)) {
                progress.remove(loc);
                render(menu, "§c网格里只能放同一种物品");
                return;
            }
            total += it.getAmount();
        }
        if (key == null) {
            progress.remove(loc);
            render(menu, "§75x5 网格放 1024 个同种基础物品 §8(现在全空)");
            return;
        }
        if (total < NEED) {
            progress.remove(loc);
            render(menu, "§7还差 §f" + Math.max(0, NEED - total) + " §7个 §f" + key.getDisplayName()
                    + " §8(共要 " + NEED + " 个，现在 " + total + " 个)");
            return;
        }

        if (!BaseItems.isBase(key)) {
            progress.remove(loc);
            double emc = BaseItems.emc(key);
            if (emc < 0) {
                render(menu, "§c《" + key.getDisplayName() + "》不在等价附属的基础定价表里，不能编码");
            } else {
                render(menu, "§c《" + key.getDisplayName() + "》EMC §f" + fmt(emc)
                        + " §c≥ 门槛 " + fmt(BaseItems.threshold()) + "，不算基础物品");
            }
            return;
        }

        ItemStack card = GeneratorCard.create(key, GeneratorCard.MIN_INTERVAL, 1);
        if (card == null) {
            render(menu, "§c生成器卡没注册好（插件没加载对）");
            return;
        }
        if (!canFit(menu, card)) {
            progress.remove(loc);
            render(menu, "§c成品格满了");
            return;
        }

        int draw = cfg == null ? 0 : cfg.encoderEnergyPerTick();
        if (draw > 0) {
            long have = Math.max(0L, charge(loc));
            if (have < draw) {
                if (!holding(loc)) progress.remove(loc);
                render(menu, "§c没电了 §7· 每 tick 需 §f" + draw + " J§7，本机存电 §f" + have + " J");
                return;
            }
            consumeCharge(loc, draw);
        }

        long done = progress.merge(loc, 1L, Long::sum);
        if (done < needTicks) {
            render(menu, "§7编码中 §f" + (done * 100 / needTicks) + "% §8(" + key.getDisplayName() + ")");
            return;
        }
        progress.remove(loc);

        // 扣料：从外圈凑够 NEED 个
        int left = NEED;
        for (int s : Layout.ENC_INPUT) {
            if (left <= 0) break;
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType().isAir()) continue;
            int take = Math.min(left, it.getAmount());
            takeAt(menu, s, take);
            left -= take;
        }
        takeAt(menu, Layout.ENC_CENTER, 1);
        menu.pushItem(card, Layout.ENC_OUTPUT);
        render(menu, "§a已编出 §f" + key.getDisplayName() + "生成器");
    }

    /** 从某一格扣东西：扣空就清格，否则写回并让界面即时刷新 */
    static void takeAt(BlockMenu menu, int slot, int amount) {
        ItemStack it = menu.getItemInSlot(slot);
        if (it == null || it.getType().isAir() || amount <= 0) return;
        int left = it.getAmount() - amount;
        if (left <= 0) {
            menu.replaceExistingItem(slot, null);
        } else {
            it.setAmount(left);
            menu.replaceExistingItem(slot, it);
        }
    }

    private boolean holding(Location loc) {
        return progress.containsKey(loc);
    }

    /** 成品格能不能再塞一张同样的卡 */
    private static boolean canFit(BlockMenu menu, ItemStack card) {
        ItemStack cur = menu.getItemInSlot(Layout.ENC_OUTPUT);
        if (cur == null || cur.getType().isAir()) return true;
        return GeneratorCard.same(cur, card)
                && cur.getAmount() + card.getAmount() <= cur.getMaxStackSize();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
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
