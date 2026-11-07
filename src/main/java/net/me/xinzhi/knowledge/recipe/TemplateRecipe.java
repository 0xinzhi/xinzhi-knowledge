package net.me.xinzhi.knowledge.recipe;

import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * 「这条配方的输入只是<b>认配方用的模板</b>，机器不会扣它」的配方。
 *
 * <p>为什么需要它：有些机器的配方来源是按<b>物品种类</b>索引的表
 * （{@code EnumMap<Material, ItemStack[]>}，如无尽贪婪的虚拟农场），
 * 表里那个 key 只是「你要种什么」的筛选器，机器 tick 只读它的 Material、从不扣减。
 * 这种「输入即筛选器」的语义，从 {@link MachineRecipe} 的输入数组里看不出来
 * （它和「真扣一份」长得完全一样），所以由抽取端显式打标。
 */
public final class TemplateRecipe extends MachineRecipe {

    private TemplateRecipe(int ticks, ItemStack[] input, ItemStack[] output) {
        super(ticks, input, output);
    }

    public static TemplateRecipe of(int ticks, ItemStack[] input, ItemStack[] output) {
        return new TemplateRecipe(Math.max(1, ticks), input, output);
    }
}