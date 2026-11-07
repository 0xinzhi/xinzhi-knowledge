package net.me.xinzhi.knowledge.recipe;

import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

/**
 * 每个产物<b>各自独立</b>带一条概率（0-100）的配方。
 *
 * <p>典型：RykenSlimeCustomizer（RSC）的 {@code CustomMachineRecipe#getChances()}——
 * 一次配方同时掷所有产物，每个产物按自己的百分比决定出不出（腐竹 2026-11-08
 * 给的截图：「有 11.1% 的概率产出 三重压缩圆石」）。
 *
 * <p>⚠ 与 {@link WeightedRecipe} 是两种语义，不能混：
 * 那边是「N 次里抽 1 件」（互斥），这边是「每件各掷一次」（可同时出多件）。
 */
public final class ChanceRecipe extends MachineRecipe {

    private final int[] chances;
    /** true = 掷完只在「掷中的那几件」里随机给 1 件（RSC 的 chooseOneIfHas：「一次产出 1 种」） */
    private final boolean chooseOne;

    private ChanceRecipe(MachineRecipe src, int[] chances, boolean chooseOne) {
        super(src.getTicks(), src.getInput(), src.getOutput());
        this.chances = chances;
        this.chooseOne = chooseOne;
    }

    public static ChanceRecipe of(MachineRecipe src, int[] chances, boolean chooseOne) {
        return new ChanceRecipe(src, chances, chooseOne);
    }

    /** 一次只出 1 种（在掷中的候选里随机挑一件） */
    public boolean chooseOne() {
        return chooseOne;
    }

    /** 与 {@link MachineRecipe#getOutput()} 等长，单位百分比；缺省 100 */
    public int[] chances() {
        return chances;
    }

    /** 是不是真带概率（全是 100 就不必包一层） */
    public static boolean interesting(int[] c) {
        if (c == null) return false;
        for (int v : c) if (v < 100) return true;
        return false;
    }
}
