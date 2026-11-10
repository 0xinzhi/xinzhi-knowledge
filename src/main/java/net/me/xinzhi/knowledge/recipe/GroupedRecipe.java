package net.me.xinzhi.knowledge.recipe;

import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 「每组一个权重、每次按权重抽<b>一组</b>」的随机配方。
 *
 * <p>典型：乱序技艺（FinalTECH）的 {@code io.taraxacum.libs.slimefun.dto.RandomMachineRecipe}——
 * 它的 {@code getRandomOutputs()} 每组是「一次产出的一整套物品 + 权重」，
 * 它自己的 {@code getOutput()} 每调一次就按权重随机抽一组返回。
 *
 * <p>⚠ 因为源配方 {@code getOutput()} 每次都变，直接读它会把「候选池」当成「全给」——
 * 腐竹 2026-11-08「我用那个机器一次就给了 9 种物品」就是这么来的。
 */
public final class GroupedRecipe extends MachineRecipe implements AmountsCarrier {

    private final List<ItemStack[]> groups;
    private final int[] weights;
    /** 源配方的扣料计划（同 {@link ChanceRecipe}：包装之后反射就看不回源配方了） */
    private final int[] amounts;

    private GroupedRecipe(MachineRecipe src, List<ItemStack[]> groups, int[] weights) {
        super(src.getTicks(), src.getInput(), src.getOutput());
        this.groups = groups;
        this.weights = weights;
        this.amounts = ConsumeRules.amountsFor(src);
    }

    @Override
    public int[] consumeAmounts() {
        return amounts;
    }

    public static GroupedRecipe of(MachineRecipe src, List<ItemStack[]> groups, int[] weights) {
        return new GroupedRecipe(src, groups, weights);
    }

    /** 每次命中一组，把这一组整份给出去 */
    public List<ItemStack[]> groups() {
        return groups;
    }

    /** 与 {@link #groups()} 等长，权重（不是百分比，按总和归一） */
    public int[] groupWeights() {
        return weights;
    }
}
