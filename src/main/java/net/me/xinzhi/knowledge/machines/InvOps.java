package net.me.xinzhi.knowledge.machines;

import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.core.ItemKey;
import net.me.xinzhi.knowledge.util.ReflectUtils;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** 槽位级的物品统计 / 扣料 / 产物放入（只碰指定槽位，绝不整包清空）。 */
final class InvOps {

    private InvOps() { }

    static Map<ItemKey, Integer> count(BlockMenu menu, int[] slots) {
        Map<ItemKey, Integer> map = new HashMap<>();
        for (int s : slots) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType() == Material.AIR) continue;
            ItemKey k = ItemKey.of(it);
            if (k == null) continue;
            map.merge(k, it.getAmount(), Integer::sum);
        }
        return map;
    }

    static boolean covers(Map<ItemKey, Integer> have, Map<ItemKey, Integer> need, long times) {
        for (Map.Entry<ItemKey, Integer> e : need.entrySet()) {
            long want = (long) e.getValue() * times;
            Integer got = have.get(e.getKey());
            if (got == null || got < want) return false;
        }
        return true;
    }

    /** 真扣料：按需从各槽扣，扣完的槽删掉。 */
    static void consume(BlockMenu menu, int[] slots, Map<ItemKey, Integer> need, long times) {
        Map<ItemKey, Long> left = new HashMap<>();
        for (Map.Entry<ItemKey, Integer> e : need.entrySet()) left.put(e.getKey(), (long) e.getValue() * times);
        for (int s : slots) {
            if (left.isEmpty()) break;
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType() == Material.AIR) continue;
            ItemKey k = ItemKey.of(it);
            if (k == null) continue;
            Long want = left.get(k);
            if (want == null || want <= 0) continue;
            int take = (int) Math.min(want, it.getAmount());
            int rest = it.getAmount() - take;
            if (rest <= 0) {
                menu.replaceExistingItem(s, null);
            } else {
                ItemStack copy = it.clone();
                copy.setAmount(rest);
                menu.replaceExistingItem(s, copy);
            }
            if (want - take <= 0) left.remove(k);
            else left.put(k, want - take);
        }
    }

    /** 产物放得下吗（每种产物分别累计空隙）。 */
    static boolean canFit(BlockMenu menu, int[] slots, Map<ItemKey, Integer> give, long times) {
        long room = 0;
        for (int s : slots) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType() == Material.AIR) room += 64;
            else room += Math.max(0, 64 - it.getAmount());
        }
        long needRoom = 0;
        for (Integer v : give.values()) needRoom += (long) v * times;
        return room >= needRoom;
    }

    /** 产物入槽：先叠到同类堆，再找空位。放不下就返回 false（调用方已提前检查）。 */
    static boolean produce(BlockMenu menu, int[] slots, ItemStack[] outputs, long times) {
        boolean ok = true;
        for (ItemStack proto : outputs) {
            if (proto == null || proto.getType() == Material.AIR) continue;
            long remaining = (long) proto.getAmount() * times;
            ItemKey key = ItemKey.of(proto);
            // 先叠同类
            for (int s : slots) {
                if (remaining <= 0) break;
                ItemStack it = menu.getItemInSlot(s);
                if (it == null || it.getType() == Material.AIR) continue;
                if (!it.isSimilar(proto)) continue;
                int space = 64 - it.getAmount();
                if (space <= 0) continue;
                int add = (int) Math.min(space, remaining);
                ItemStack copy = it.clone();
                copy.setAmount(it.getAmount() + add);
                menu.replaceExistingItem(s, copy);
                remaining -= add;
            }
            // 再找空位
            for (int s : slots) {
                if (remaining <= 0) break;
                ItemStack it = menu.getItemInSlot(s);
                if (it != null && it.getType() != Material.AIR) continue;
                int add = (int) Math.min(64, remaining);
                ItemStack copy = proto.clone();
                copy.setAmount(add);
                menu.replaceExistingItem(s, copy);
                remaining -= add;
            }
            if (remaining > 0) ok = false;
        }
        return ok;
    }

    /**
     * 出产物，带随机产物处理：
     * <ul>
     *   <li>普通产物：数量 × times，交给 {@link #produce}；</li>
     *   <li>随机产物（本体常是 AIR）：<b>每次配方各抽一次</b>，抽中几次就出几份 ——
     *       抽一次再乘 times 会把「10% 概率」放大成「10% 概率一次拿 N 份」。</li>
     * </ul>
     * 随机产物自己的抽法是 {@code getInstance()}（见 {@link ReflectUtils#rollOwn}），
     * 给 AIR 就当这次没中，绝不从候选池兜底。
     */
    static boolean produceMixed(BlockMenu menu, int[] slots, ItemStack[] outputs, int times) {
        if (outputs == null || outputs.length == 0) return true;
        List<ItemStack> flat = new ArrayList<>();
        for (ItemStack proto : outputs) {
            if (proto == null) continue;
            if (ReflectUtils.isProbabilistic(proto)) {
                for (int i = 0; i < times; i++) {
                    ItemStack got = ReflectUtils.rollOwn(proto);
                    if (got == null || got.getType() == Material.AIR || got.getAmount() <= 0) continue;
                    ItemStack copy = got.clone();
                    copy.setAmount(Math.max(1, got.getAmount()));
                    flat.add(copy);
                }
            } else if (proto.getType() != Material.AIR) {
                ItemStack copy = proto.clone();
                copy.setAmount((int) Math.min(Integer.MAX_VALUE, (long) proto.getAmount() * times));
                flat.add(copy);
            }
        }
        if (flat.isEmpty()) return true;
        return produce(menu, slots, flat.toArray(new ItemStack[0]), 1);
    }

    /**
     * 「按权重抽一组」的产物（乱序技艺 RandomMachineRecipe）：每次配方抽一组，把整组给出去。
     */
    static boolean produceGrouped(BlockMenu menu, int[] slots, List<ItemStack[]> groups,
                                  int[] weights, int times) {
        if (groups == null || groups.isEmpty() || times <= 0) return true;
        int total = 0;
        for (int w : weights) total += Math.max(1, w);
        if (total <= 0) return true;
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        List<ItemStack> flat = new ArrayList<>();
        for (int t = 0; t < times; t++) {
            int pick = rnd.nextInt(total);
            int idx = 0;
            for (int i = 0; i < weights.length; i++) {
                pick -= Math.max(1, weights[i]);
                if (pick < 0) { idx = i; break; }
            }
            for (ItemStack is : groups.get(idx)) {
                if (is == null || is.getType() == Material.AIR) continue;
                flat.add(is.clone());
            }
        }
        if (flat.isEmpty()) return true;
        return produce(menu, slots, flat.toArray(new ItemStack[0]), 1);
    }

    /**
     * 「每件产物各掷一次概率」的产物（RSC 的 chance）。
     *
     * <p>⚠ 与 {@link #produceWeighted} 的区别：那边 N 次抽 N 件（互斥），
     * 这边是每件产物各掷一次、可以同时出好几件 —— 腐竹 2026-11-08 给的截图里
     * 「有 11.1% 的概率产出 三重压缩圆石」就是这种。
     */
    static boolean produceChanced(BlockMenu menu, int[] slots, ItemStack[] outputs,
                                  int[] chances, boolean chooseOne, int times) {
        if (outputs == null || outputs.length == 0 || times <= 0) return true;
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        int[] counts = new int[outputs.length];
        int[] passed = new int[outputs.length];
        // 每次配方各掷一遍（times 次）；不是掷一次再乘 times
        for (int t = 0; t < times; t++) {
            int n = 0;
            for (int i = 0; i < outputs.length; i++) {
                ItemStack proto = outputs[i];
                if (proto == null || proto.getType() == Material.AIR) continue;
                int chance = chances != null && i < chances.length
                        ? Math.max(0, Math.min(100, chances[i])) : 100;
                if (chance >= 100 || (chance >= 1 && rnd.nextInt(100) < chance)) passed[n++] = i;
            }
            if (n == 0) continue;
            // 「一次产出 1 种」（RSC chooseOneIfHas）：在掷中的候选里随机挑一件
            if (chooseOne) counts[passed[rnd.nextInt(n)]]++;
            else for (int j = 0; j < n; j++) counts[passed[j]]++;
        }
        List<ItemStack> flat = new ArrayList<>();
        for (int i = 0; i < outputs.length; i++) {
            if (counts[i] <= 0) continue;
            ItemStack copy = outputs[i].clone();
            copy.setAmount((int) Math.min(Integer.MAX_VALUE, (long) outputs[i].getAmount() * counts[i]));
            flat.add(copy);
        }
        if (flat.isEmpty()) return true;
        return produce(menu, slots, flat.toArray(new ItemStack[0]), 1);
    }

    /**
     * 「每次只出一件、按权重抽」的产物（矿机）。
     *
     * <p>⚠ 与 {@link #produceMixed} 是两种语义，不能混：
     * 这里是 <b>N 次配方 = 抽 N 次，每次恰好 1 件</b>；那边是「每样产物各抽一次」。
     * 把矿机的候选表当成「各抽一次」= 一次出十几瓶矿（腐竹 2026-11-07 报的现象）。
     *
     * @param outputs  候选产物（与 weights 等长）
     * @param weights  相对权重（0 = 抽不到）
     * @param times    这次跑了几次配方 = 抽几次
     */
    static boolean produceWeighted(BlockMenu menu, int[] slots,
                                   ItemStack[] outputs, int[] weights, int times) {
        if (outputs == null || outputs.length == 0 || times <= 0) return true;
        int total = 0;
        for (int i = 0; i < outputs.length; i++) {
            total += weights != null && i < weights.length ? Math.max(0, weights[i]) : 1;
        }
        if (total <= 0) total = outputs.length;

        List<ItemStack> flat = new ArrayList<>(Math.min(times, 256));
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int t = 0; t < times; t++) {
            int roll = rnd.nextInt(total);
            int acc = 0;
            ItemStack pick = null;
            for (int i = 0; i < outputs.length; i++) {
                int w = weights != null && i < weights.length ? Math.max(0, weights[i]) : 1;
                acc += w;
                if (roll < acc) {
                    pick = outputs[i];
                    break;
                }
            }
            if (pick == null || pick.getType() == Material.AIR) continue;
            ItemStack copy = pick.clone();
            copy.setAmount(Math.max(1, pick.getAmount()));
            flat.add(copy);
        }
        if (flat.isEmpty()) return true;
        return produce(menu, slots, flat.toArray(new ItemStack[0]), 1);
    }

    static long roomFor(BlockMenu menu, int[] slots) {
        long room = 0;
        for (int s : slots) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType() == Material.AIR) room += 64;
            else room += Math.max(0, 64 - it.getAmount());
        }
        return room;
    }
}