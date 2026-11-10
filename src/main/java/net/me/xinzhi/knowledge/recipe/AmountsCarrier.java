package net.me.xinzhi.knowledge.recipe;

/**
 * 「这条配方自己带了扣料计划」的标记。
 *
 * <p>为什么需要它：{@link me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe}
 * 只存「输入物品」，不存「这次该扣几个」。默认口径是「按物品自身的堆叠数全扣」，
 * 但有些配方里有<b>只认不扣</b>的位置（模板物品、模具、催化剂），
 * 靠 {@code MachineRecipe} 的输入数组看不出来 —— 只能由抽取端显式带上计划。
 *
 * <p>0 = 只认不扣（{@link Scan#need} 记 1，{@link Scan#cost} 不记）。
 */
public interface AmountsCarrier {

    /**
     * 与 {@code getInput()} <b>等长</b>的扣料计划：{@code amounts[i]} = 第 i 个输入这次扣几个。
     *
     * @return {@code null} = 没指定，交回 {@link ConsumeRules#consumeAmounts}
     */
    int[] consumeAmounts();
}
