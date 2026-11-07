package net.me.xinzhi.knowledge.machines;

import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.core.ItemKey;
import net.me.xinzhi.knowledge.recipe.Scan;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** 三件事的公共算术：输入够几次、产出放得下几次、一 tick 应该跑几次。 */
final class CraftMath {

    private CraftMath() { }

    /**
     * 这台机器本 tick 要付多少电（J）。
     * <p>腐竹 2026-11-07：不攒够一次才扣，而是按 tick 付 ——
     * 本 tick 推进「count × 效率」台机器的工作量，就付这么多份「每 tick 耗电」。
     * 一次配方累计下来正好等于 {@code energyPerCraft}，但缓冲只需要装得下 1 tick 的电。
     */
    static long energyPerTick(Scan s, int count, double eff) {
        if (s.energyPerCraft <= 0 || s.energyPerTick <= 0) return 0;
        double v = s.energyPerTick * Math.max(1, count) * Math.max(0.0, eff);
        return Math.max(1L, (long) Math.ceil(v));
    }

    /** 按输入槽现有量，最多能做几次（至少 1 才能开工） */
    static int byInput(Map<ItemKey, Integer> have, Map<ItemKey, Integer> need) {
        if (need.isEmpty()) return Integer.MAX_VALUE;
        int min = Integer.MAX_VALUE;
        for (Map.Entry<ItemKey, Integer> e : need.entrySet()) {
            Integer got = have.get(e.getKey());
            if (got == null) return 0;
            min = Math.min(min, got / Math.max(1, e.getValue()));
        }
        return Math.max(0, min);
    }

    /**
     * 按输出槽剩余空间，最多能放几次。
     *
     * <p>腐竹 2026-11-08 定的规则：「如果产物槽一次产生就会塞满也进行一次。
     * 只有所有输出槽都有物品时才不进行生产。」——所以**只要还有一格空着就至少跑 1 次**，
     * 不再要求「剩余空间装得下完整一份」；塞不下的部分由 {@link InvOps} 尽力塞，
     * 真塞不下就说明满了，下一 tick 自然停。
     */
    static int bySpace(BlockMenu menu, int[] slots, Map<ItemKey, Integer> give) {
        boolean empty = false;
        for (int s : slots) {
            ItemStack it = menu.getItemInSlot(s);
            if (it == null || it.getType().isAir()) { empty = true; break; }
        }
        if (!empty) return 0;
        if (give.isEmpty()) {
            // 全是随机产物：看不见种类，只能看「还有没有空位」
            return Integer.MAX_VALUE;
        }
        long room = InvOps.roomFor(menu, slots);
        long per = 0;
        for (Integer v : give.values()) per += Math.max(1, v);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, room / Math.max(1, per)));
    }

    /**
     * 一 tick 该完成几次：{@code 机器数 × 基准效率 ÷ 配方 tick}，余数留到下一 tick。
     * <p>4 台 × 2 tick ⇒ 每 tick 2 次；2 台 × 20 tick ⇒ 每 tick 0 次，10 tick 一次。
     *
     * @param acc 累加器（千分之一为单位，避免浮点漂移），调用方自己保存
     * @return 本次该跑的合成次数
     */
    static int perTick(long[] acc, int machineCount, double efficiency, int recipeTicks, int cap) {
        long per = 1000L * Math.max(1, recipeTicks);
        acc[0] += Math.round(machineCount * Math.max(0.0, efficiency) * 1000.0);
        int crafts = (int) Math.min(cap, acc[0] / per);
        if (crafts > 0) acc[0] -= (long) crafts * per;
        // 停机太久别把余数攒成无限大
        if (acc[0] > per) acc[0] = per;
        return crafts;
    }
}