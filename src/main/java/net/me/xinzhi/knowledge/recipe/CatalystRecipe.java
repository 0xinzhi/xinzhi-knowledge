package net.me.xinzhi.knowledge.recipe;

import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * 「配方里有一个位置只认不扣」的配方 —— 目前唯一的来源是 <b>RSC 的模板机</b>
 * （{@code CustomTemplateMachineRecipe#getTemplateStack()}）。
 *
 * <p>RSC 的模板机把模板物品放在自己的 {@code templateSlot} 里，<b>不在</b>输入槽、
 * 也永不消耗；配方的 {@code inputs} 里只有真扣的料。而知识生成器的语义是
 * 「模板放在模板区、配方输入在识别槽」，所以这里把模板<b>插到输入数组第 0 位</b>、
 * 扣料计划写 0：识别（{@link Scan#need}）能看见它，扣料（{@link Scan#cost}）永远不动它。
 *
 * <p>顺序很重要：模板排第 0 位，{@link net.me.xinzhi.knowledge.machines.KnowledgeGenerator}
 * 的模板区比对就是按这个位置找的。
 */
public final class CatalystRecipe extends MachineRecipe implements AmountsCarrier {

    private final int[] amounts;
    private final ItemStack template;
    /** RSC 的 {@code fasterIfMoreTemplates}：模板区放几个，进度每 tick 就走几格 */
    private final boolean fasterByTemplate;

    private CatalystRecipe(int ticks, ItemStack[] input, ItemStack[] output, int[] amounts, ItemStack template,
                           boolean fasterByTemplate) {
        super(ticks, input, output);
        this.amounts = amounts;
        this.template = template;
        this.fasterByTemplate = fasterByTemplate;
    }

    /**
     * 把 {@code template} 插到 {@code src} 的输入最前面，扣料计划第 0 位写 0。
     *
     * @param template 模板物品（原配方自己定义的那件，不是玩家放的）
     */
    public static CatalystRecipe withTemplate(MachineRecipe src, ItemStack template) {
        return withTemplate(src, template, false);
    }

    /**
     * @param fasterByTemplate 模板区里放 N 个模板时，这台机器的 tick 进度一次走 N 格
     *                         （RSC {@code fasterIfMoreTemplates}，魔法卡片复制机等 12 台在用）
     */
    public static CatalystRecipe withTemplate(MachineRecipe src, ItemStack template, boolean fasterByTemplate) {
        ItemStack[] ins = src.getInput() == null ? new ItemStack[0] : src.getInput();
        int[] plan = null;
        try {
            // 模板机的其余输入仍是「真扣」的，跟着源配方的计划走（RSC 的 noConsume/noConsumeAll 在这层）
            if (src instanceof AmountsCarrier ac) plan = ac.consumeAmounts();
            else plan = ConsumeRules.consumeAmounts(src);
        } catch (Throwable ignored) {
        }
        ItemStack[] in = new ItemStack[ins.length + 1];
        int[] am = new int[ins.length + 1];
        ItemStack t = template.clone();
        in[0] = t;
        am[0] = 0;
        System.arraycopy(ins, 0, in, 1, ins.length);
        for (int i = 0; i < ins.length; i++) {
            if (plan != null && i < plan.length) {
                am[i + 1] = Math.max(0, plan[i]);
            } else if (ins[i] != null) {
                am[i + 1] = Math.max(0, ins[i].getAmount());
            }
        }
        ItemStack[] out = src.getOutput() == null ? new ItemStack[0] : src.getOutput();
        return new CatalystRecipe(Math.max(1, src.getTicks()), in, out, am, t, fasterByTemplate);
    }

    @Override
    public int[] consumeAmounts() {
        return amounts;
    }

    /** 模板物品本身（生成器判断「这机器要不要模板」用） */
    public ItemStack template() {
        return template;
    }

    /** 模板放得越多跑得越快（RSC {@code fasterIfMoreTemplates}） */
    public boolean fasterByTemplate() {
        return fasterByTemplate;
    }
}
