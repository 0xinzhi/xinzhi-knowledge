package net.me.xinzhi.knowledge.util;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 机器配方的**指纹字符串**：用于「同一台机器扫出来的配方去重」等场景。
 *
 * <p><b>来源</b>：{@code GlobalSmartMachine.getRecipeKey + describeForKey}、
 * {@code WarehouseSmartMachine.recipeKey + describeForKey}、
 * {@code SmartMachine.getRecipeKey + describeForKey} 三份实现连体相同，2026-10-07 晚上合并到这里。
 *
 * <p>⚠⚠ **网络工作节点与附属扫描器里同名的方法没有并进来** —— 它们的指纹**故意不同**：
 * <ul>
 *   <li>{@code NetworkWorkNode.recipeKey/describeForKey}：随机产物用
 *       {@code "PROB:<类名>#<位置>"}，**不带 {@code identityHashCode}**
 *       （跨次提取要稳定，否则"写完配方校验失败 → 回退字节副本 → 输出为空"）；</li>
 *   <li>{@code AddonScanner.recipeKey/desc}：分隔符与格式另一套。
 * </ul>
 * 合并前先确认「是不是同一语义」—— 这两处**不是**。
 *
 * <p>本类**不持有任何静态可变状态**，请保持这一点。
 */
public final class RecipeKeys {

    private RecipeKeys() {
    }

    /** 配方指纹（与旧实现的字符串构造逐字一致）。 */
    public static String of(MachineRecipe r) {
        StringBuilder sb = new StringBuilder("I:");
        for (ItemStack i : r.getInput()) sb.append(describe(i)).append(";");
        sb.append("O:");
        for (ItemStack i : r.getOutput()) sb.append(describe(i)).append(";");
        sb.append("T:").append(r.getTicks());
        return sb.toString();
    }

    /** 单个物品的指纹：概率产物 → 类名@identityHashCode；空气/空 → 空串；SF / VANILLA + 数量。 */
    public static String describe(ItemStack is) {
        if (is == null) return "";
        if (ReflectUtils.isProbabilistic(is)) return "PROB:" + is.getClass().getSimpleName() + "@" + System.identityHashCode(is);
        if (is.getType() == Material.AIR) return "";
        SlimefunItem sf = SlimefunItem.getByItem(is);
        if (sf != null) return "SF:" + sf.getId() + ":" + is.getAmount();
        return "VANILLA:" + is.getType().name() + ":" + is.getAmount();
    }
}
