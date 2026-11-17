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
import net.me.xinzhi.knowledge.recipe.Scan;
import net.me.xinzhi.knowledge.tank.TankNet;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编码生成器（腐竹 2026-11-14 第 3 条）：「出一个编码生成器专用知识生成器，原识别槽太少了。」
 *
 * <p>就是一台<b>只吃生成器卡</b>的机器：左 4 列 24 格放卡，右 4 列 24 格出产物，
 * 中间一列是功能槽（状态 + 说明，兼作分隔）。
 * 和知识生成器（一个识别槽）比，这里是 24 个卡槽，能同时跑几十种产物。
 *
 * <h2>速度</h2>
 * 每个卡槽各走各的进度：卡面上写的间隔是多少 tick 出一份，这里就多少 tick 出一份；
 * 同一格叠 N 张卡 ⇒ 一份的时间出 N 份。不同间隔的卡互不干扰，产出直接相加
 * （腐竹原话：「相同的生成器但是不同时间可以叠加，不会干扰时间只会增加产出数量」）。
 *
 * <h2>耗电</h2>
 * 腐竹 2026-11-14 第 4 条要求这台机器耗电 0，默认配置 {@code card-machine.energy-per-tick = 0}。
 */
public final class CardGeneratorMachine extends BaseKnowledgeMachine {

    /** 方块 → (卡槽:卡签名 → 已累进 tick 数)：换卡就换 key，进度不会串到别的产物上 */
    private final Map<Location, Map<String, Long>> progress = new ConcurrentHashMap<>();

    public CardGeneratorMachine(ItemGroup group, SlimefunItemStack item,
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
        return "§b编码生成器 §8· §7生成器卡 → 产物";
    }

    @Override protected int[] inputSlots() { return Layout.CARD_IN; }
    @Override protected int[] outputSlots() { return Layout.CARD_OUT; }
    @Override protected int machineSlot() { return -1; }
    @Override protected int statusSlot() { return Layout.CARD_STATUS; }
    @Override protected int infoSlot() { return Layout.CARD_INFO; }

    @Override protected String hintLore() {
        return "§8左 4 列放生成器卡 · 右 4 列出产物 · 中间一列是状态与说明";
    }

    @Override protected String[] infoLines() {
        return new String[]{
                "§7左 4 列 §8(24 格)§7：放生成器卡，不同产物/不同间隔可混放",
                "§7右 4 列 §8(24 格)§7：产物出口，满了就停",
                "§7中列 §8：§7第 5 格状态、第 14 格说明，其余格是分隔",
                "§8同一格叠 N 张卡 = 一份的时间出 N 份"
        };
    }

    @Override protected List<Scan> scansFor(SlimefunItem sf) {
        return List.of();
    }

    @Override
    protected void tick(Block b, BlockMenu menu) {
        Location loc = b.getLocation();
        Settings cfg = Settings.get();
        Map<String, Long> accs = progress.computeIfAbsent(loc, k -> new LinkedHashMap<>());
        // 产物先往输出储罐塞（腐竹 2026-11-15），机器里只留储罐塞不下的
        TankNet.sweep(menu, Layout.CARD_OUT, TankNet.around(b, true));

        // 收卡：每种卡各自一条进度
        List<int[]> lines = new ArrayList<>();          // {slot, interval, 每 tick 累进}
        List<String> keys = new ArrayList<>();
        for (int s : Layout.CARD_IN) {
            ItemStack it = menu.getItemInSlot(s);
            if (!GeneratorCard.isCard(it)) continue;
            lines.add(new int[]{s, GeneratorCard.interval(it), GeneratorCard.count(it) * it.getAmount()});
            keys.add(s + ":" + GeneratorCard.signature(it));
        }
        if (lines.isEmpty()) {
            accs.clear();
            render(menu, "§7把生成器卡放进左 4 列 §8(最多 24 格，不同产物可混放)");
            return;
        }
        accs.keySet().retainAll(keys);

        boolean anyOut = false;
        long produced = 0L;
        for (int i = 0; i < lines.size(); i++) {
            int[] line = lines.get(i);
            String accKey = keys.get(i);
            int slot = line[0];
            int interval = Math.max(GeneratorCard.MIN_INTERVAL, line[1]);
            long perTick = line[2];
            long acc = accs.getOrDefault(accKey, 0L) + perTick;
            long units = acc / interval;
            if (units <= 0) {
                accs.put(accKey, acc);
                continue;
            }
            ItemStack card = menu.getItemInSlot(slot);
            int give = (int) Math.min(64L * Layout.CARD_OUT.length, units);
            ItemStack[] batch;
            if (GeneratorCard.isPool(card)) {
                // 随机产物卡：每次抽一件、按那件自己的数量出（与原机器同分布）
                List<ItemStack> choices = GeneratorCard.poolItems(card);
                if (choices.isEmpty()) continue;
                java.util.concurrent.ThreadLocalRandom rnd =
                        java.util.concurrent.ThreadLocalRandom.current();
                List<ItemStack> flat = new ArrayList<>(give);
                for (int n = 0; n < give; n++) flat.add(choices.get(rnd.nextInt(choices.size())).clone());
                batch = flat.toArray(new ItemStack[0]);
            } else {
                ItemKey product = GeneratorCard.product(card);
                ItemStack proto = product == null ? null : product.toTemplate();
                if (proto == null) continue;
                proto.setAmount(1);
                batch = new ItemStack[]{proto};
            }
            if (!InvOps.produce(menu, Layout.CARD_OUT, batch, GeneratorCard.isPool(card) ? 1 : give)) {
                // 产物格满了：这一格的进度先攒着，等玩家取走就接着出
                accs.put(accKey, Math.min(acc, (long) interval * 64L));
                continue;
            }
            accs.put(accKey, acc - (long) give * interval);
            anyOut = true;
            produced += give;
        }

        // 耗电（默认 0）
        int draw = cfg == null ? 0 : cfg.cardMachineEnergyPerTick();
        if (draw > 0) {
            long have = Math.max(0L, charge(loc));
            if (have < draw) {
                render(menu, "§c没电了 §7· 每 tick 需 §f" + draw + " J§7，本机存电 §f" + have + " J");
                return;
            }
            try { removeCharge(loc, draw); } catch (Throwable ignored) { }
        }

        render(menu, anyOut
                ? "§a产出 §f" + produced + " §a件 §8(" + lines.size() + " 种卡在跑)"
                : "§7生成中 §8(" + lines.size() + " 种卡 · 各自按卡面间隔出)");
    }

    private long charge(Location loc) {
        try {
            return getChargeLong(loc);
        } catch (Throwable t) {
            return 0L;
        }
    }
}
