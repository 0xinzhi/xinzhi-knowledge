package net.me.xinzhi.knowledge.recipe;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import net.me.xinzhi.knowledge.util.ReflectUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⭐ <b>「展示配方」闸门</b>：读出来的配方里，只要<b>输入</b>是「改名 / 带 lore 的原版物品」，
 * 就说明这条配方是写给人看的占位图，<b>整条丢掉</b>，不让任何机器拿它合成。
 *
 * <h2>判据（三条必须同时成立）</h2>
 * <ol>
 *   <li>{@link SlimefunItem#getByItem(ItemStack)} 认不出它 —— 粘液物品一律放行
 *       （它们的名字和 lore 是本体的一部分，RSC 的自定义物品也走 {@code CustomItem extends SlimefunItem}）；</li>
 *   <li>有自定义名称 <b>或</b>有 lore —— 纯原版物品（只有材质和数量）一律放行；</li>
 *   <li>{@link ItemMeta#getPersistentDataContainer()} 是空的、且**没有** {@code CustomModelData}
 *       —— <b>带插件标识的物品是"真物品"，不是占位图</b>。
 *       这一条是<b>故意加的保险</b>：本附属的「物品设计机 / 附属编辑器」产出的自制物品、
 *       从别的附属导入的物品（{@code xinzhi:orig_def}）、以及资源包类附属（CustomModelData）
 *       的物品都带插件标识，少了这一条它们会被当成展示占位物，连自己的机器一起废掉。</li>
 * </ol>
 *
 * <h2>为什么是「整条丢」而不是「去掉那一项」</h2>
 * 去掉那一项 = 交出「少扣一种料」的配方，正是最危险的方向（白送）。
 * 展示配方的输入本来就只是图标，剩下的部分没有任何可信度，只能整条不要。
 *
 * <h2>开关与日志</h2>
 * <ul>
 *   <li>{@code machines.display-recipe-guard}（代码里默认 <b>开</b>）—— 一键关掉，不必重编译；</li>
 *   <li>{@code debug.display-recipe}（默认关）—— 每台被丢掉配方的机器打一行日志，
 *       用来对账「有没有误伤自己的机器」。提取结果是<b>按机器缓存</b>的，所以这一行天然不会刷屏。</li>
 * </ul>
 *
 * <p>本类**不持有任何静态可变状态**（两个开关由配置写入，属于配置镜像，不是运行状态）。
 */
public final class DisplayRecipeGuard {

    private DisplayRecipeGuard() {
    }

    private static volatile boolean enabled = true;
    private static volatile boolean log = false;

    /** 已经打过日志的机器 id（同一次启动只报一次）。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setLog(boolean v) {
        log = v;
    }

    /**
     * 逐条筛：把展示配方整条滤掉。
     *
     * @param in      提取出来的配方（可为 null / 空）
     * @param machine 只用于日志，可为 null
     * @return 过滤后的新列表；开关关闭或没有展示配方时<b>原样返回同一个列表</b>
     */
    public static List<MachineRecipe> filter(List<MachineRecipe> in, SlimefunItem machine) {
        if (in == null || in.isEmpty() || !enabled) return in;

        List<MachineRecipe> out = new ArrayList<>(in.size());
        List<ItemStack> dropped = log ? new ArrayList<>() : null;
        for (MachineRecipe r : in) {
            if (r == null) continue;
            ItemStack bad = decorationOf(r);
            if (bad == null) {
                out.add(r);
            } else if (dropped != null) {
                dropped.add(bad);
            }
        }
        if (dropped != null && !dropped.isEmpty()) noteDropped(machine, dropped);
        return out;
    }

    /** 这条配方的输入里第一件「展示用占位物」；没有返回 {@code null}。 */
    private static ItemStack decorationOf(MachineRecipe r) {
        ItemStack[] ins = r.getInput();
        if (ins == null) return null;
        for (ItemStack is : ins) {
            if (isDecoration(is)) return is;
        }
        return null;
    }

    /**
     * 是不是「展示用的原版占位物」——判据见类注释。
     *
     * <p>⚠ 顺序不能反：<b>先认粘液物品</b>（它们必然有名字和 lore），
     * 再判名字 / lore，最后才看插件标识（PDC / CustomModelData）。
     */
    public static boolean isDecoration(ItemStack is) {
        if (is == null) return false;
        try {
            if (ReflectUtils.isProbabilistic(is)) return false;   // 概率产物本体可能是 AIR，先挡掉
            if (is.getType().isAir()) return false;
            if (SlimefunItem.getByItem(is) != null) return false;
        } catch (Throwable ignored) {
            return false;                                          // 认不出就放行：宁可少拦，不可误伤
        }
        try {
            ItemMeta meta = is.getItemMeta();
            if (meta == null) return false;
            if (!meta.hasDisplayName() && !meta.hasLore()) return false;
            // ③ 带插件标识的一律放行：PDC 里任何数据、或自定义模型（资源包物品）。
            if (!meta.getPersistentDataContainer().isEmpty()) return false;
            return !meta.hasCustomModelData();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 诊断日志：同一台机器只在本次启动打一行。 */
    private static void noteDropped(SlimefunItem machine, List<ItemStack> dropped) {
        String id = machine == null ? null : machine.getId();
        if (id == null || !LOGGED.add(id)) return;
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ItemStack is : dropped) {
            if (shown++ > 0) sb.append("、");
            sb.append(describe(is));
            if (shown >= 3) break;
        }
        if (shown < dropped.size()) sb.append(" 等 ").append(dropped.size()).append(" 种");
        // 调试输出已按腐竹要求移除（2026-11-08：结束前清掉控制台调试）。需要时用 /xzk dump 看。
    }

    /** 日志用的一行描述（去掉颜色代码，免得日志里一堆 §）。 */
    private static String describe(ItemStack is) {
        try {
            ItemMeta meta = is.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                return ChatColor.stripColor(meta.getDisplayName()) + "(" + is.getType().name() + ")";
            }
        } catch (Throwable ignored) {
        }
        return is.getType().name();
    }
}
