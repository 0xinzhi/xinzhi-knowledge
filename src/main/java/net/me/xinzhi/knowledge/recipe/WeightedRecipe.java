package net.me.xinzhi.knowledge.recipe;

import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * 「每次只出<b>一件</b>，出哪件按权重抽」的配方 —— 矿机（quarry）就是这种。
 *
 * <h2>为什么不能用普通 {@link MachineRecipe} 表达</h2>
 * 普通配方的产物是「全给」：一件事做出来，{@link MachineRecipe#getOutput()} 里每样都出。
 * 矿机不一样：一次只从候选表里抽 <b>一个</b>，概率按 {@code chance} 加权。
 * 把候选表当成普通产物，就会变成「一次出十七种矿各一个」——腐竹 2026-11-07 报的
 * 「概率获得变成 100% 获得」就是这个。
 *
 * <p>所以这里只多带一个 {@link #weights()}：与 {@link #getOutput()} 等长，
 * 表示「抽到这一项的相对权重（0 = 抽不到）」。
 *
 * <p>⚠ 权重是<b>相对值</b>，不要求加起来正好 100 —— 上游的 chance 加起来不是 100 也照算。
 */
public final class WeightedRecipe extends MachineRecipe {

    private final int[] weights;

    private WeightedRecipe(int ticks, ItemStack[] inputs, ItemStack[] outputs, int[] weights) {
        super(ticks, inputs, outputs);
        this.weights = weights;
    }

    /** 造一条加权单抽配方；{@code weights} 不足时补齐为 1，超出的丢掉。 */
    public static WeightedRecipe of(int ticks, ItemStack[] inputs, ItemStack[] outputs, int[] weights) {
        int n = outputs == null ? 0 : outputs.length;
        int[] w = new int[n];
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int v = weights != null && i < weights.length ? Math.max(0, weights[i]) : 1;
            w[i] = v;
            sum += v;
        }
        // 一个都抽不到 ⇒ 退化成等概率，免得整条配方变成死配方
        if (sum <= 0) for (int i = 0; i < n; i++) w[i] = 1;
        return new WeightedRecipe(ticks, inputs, outputs, w);
    }

    /** 与 {@link #getOutput()} 等长的权重表。 */
    public int[] weights() {
        return weights;
    }
}
