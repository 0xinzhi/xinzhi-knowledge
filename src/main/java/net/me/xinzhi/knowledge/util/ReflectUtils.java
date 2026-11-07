package net.me.xinzhi.knowledge.util;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;

/**
 * 反射小工具。
 *
 * <p><b>来源</b>：这两个方法原来在 5 个文件里各有一份**逐字符相同**的复制
 * （{@code items/RecipeReaderItem}、{@code machines/SmartMachine}、
 * {@code machines/GlobalSmartMachine}、{@code machines/WarehouseSmartMachine}、
 * {@code machines/network/NetworkWorkNode}），合并到这里，共减少约 200 行重复。
 *
 * <p><b>为什么合并是安全的</b>：两份实现都不读任何实例字段、不调任何实例方法，
 * 输出完全由入参决定 —— 是纯函数，合并后行为不可能变。
 *
 * <p>本类**不持有任何静态可变状态**，请保持这一点。
 *
 * @since 0.0.0
 */
public final class ReflectUtils {

    private ReflectUtils() {
    }

    /**
     * {@code clazz} 自身、任意祖先类、以及这些类的一级/二级接口里，
     * 是否存在简单名等于 {@code simpleName} 的接口。
     *
     * <p>注意只查两层接口深度（原实现即如此），别再往下递归。
     */
    public static boolean implementsInterface(Class<?> clazz, String simpleName) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getSimpleName().equals(simpleName)) return true;
                for (Class<?> si : i.getInterfaces()) {
                    if (si.getSimpleName().equals(simpleName)) return true;
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /**
     * 这个物品是不是「随机 / 概率产物」。
     *
     * <p>先按类名认（{@code ProbItemStack} 一族），再按接口认
     * （{@code RandOutItem} / {@code MultiItemStack}）。这类产物的本体常常是
     * {@code AIR}，所以**判随机必须排在判空气之前**。
     */
    public static boolean isProbabilistic(ItemStack item) {
        if (item == null) return false;
        String cn = item.getClass().getName();
        if (cn.contains("ProbItemStack") || cn.contains("RandomItemStack")
                || cn.contains("RandomizedItemStack")   // MomoTech: cn.qy.MomoTech.Items.RandomizedItemStack
                || cn.contains("EqProRandomStack") || cn.contains("RandAmountStack")
                || cn.contains("EquivalItemStack") || cn.contains("AllMatchItem")) return true;
        // 兜底：类名同时带 Random / ItemStack（如 RandomStackItem、RandomizedItemStackV2），
        // 少认一个的代价是「概率产物被当成 AIR 静默丢掉」或「复制一份 AIR 进产物槽」
        if (cn.contains("Random") && cn.contains("ItemStack")) return true;
        if (implementsInterface(item.getClass(), "RandOutItem")) return true;
        if (implementsInterface(item.getClass(), "MultiItemStack")) return true;
        return false;
    }

    /**
     * 这个类（含全部祖先类）的全限定名里，是否 {@code endsWith} 或 {@code contains}
     * 了 {@code name}。
     *
     * <p>原来是 {@code SmartMachine} / {@code GlobalSmartMachine} /
     * {@code WarehouseSmartMachine} / {@code NetworkWorkNode} 各一份
     * （参数名不同、字节码等价），2026-10-07 晚上一并收进来。
     */
    public static boolean isExtendedFrom(Class<?> clazz, String name) {
        if (clazz == null || name == null) return false;
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            String n = c.getName();
            if (n.endsWith(name) || n.contains(name)) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    /**
     * 沿继承链找 {@code clazz}（含）第一个声明了 {@code name} 的字段，并 {@code setAccessible(true)}。
     * 找不到返回 {@code null}。
     */
    public static Field getFieldRecursive(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    /** {@link #getFieldRecursive} 的取值版；读不到一律返回 {@code null}。 */
    public static Object getFieldValueRecursive(Object obj, String name) {
        if (obj == null) return null;
        Field f = getFieldRecursive(obj.getClass(), name);
        if (f != null) { try { return f.get(obj); } catch (IllegalAccessException ignored) {} }
        return null;
    }

    /**
     * 调一个<b>无参</b>方法（{@code getMethod}，所以继承来的 public 方法也认），
     * 任何异常都吞掉返回 {@code null}。
     *
     * <p><b>来源</b>：{@code NetworkWorkNode} 里那份逐字符相同，
     * 2026-10-09 五期搬到这里 —— 随机产物那一套现在有<b>两个</b>调用方
     * （仓库合成 + 知识之书），不能再各留一份。
     */
    public static Object invokeNoArg(Object obj, String method) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按<b>产物自己的 API</b> 抽一次随机产物 —— 「机器怎么出，我就怎么出」。
     *
     * <p>随机产物（见 {@link #isProbabilistic}）里，LogiTech 那一族都实现
     * {@code RandOutItem}，接口上只有 {@code ItemStack getInstance()}；
     * <b>机器自己也是调它</b>（LogiTech {@code CraftUtils} L93-96 /
     * {@code MenuUtils} L98-99）。所以这里优先 {@code getInstance()}，
     * 它给什么就是什么 —— <b>给 AIR 就表示"这次没中"，本方法照样把 AIR 交出去</b>。
     *
     * <p>⚠ <b>绝不在这里"从候选池里兜底抽一个"</b>：那会把"概率没中"变成"必出"，
     * 概率就废了（正是 §93.20 记的那个白送洞）。
     *
     * @return {@code null} = 这不是随机产物（或它没有自己的抽法），
     *         调用方走自己的老路；非 {@code null} = <b>已经抽完了</b>，别再抽第二次
     */
    public static ItemStack rollOwn(ItemStack item) {
        if (item == null || !isProbabilistic(item)) return null;
        Object rolled = invokeNoArg(item, "getInstance");
        if (rolled instanceof ItemStack is) return is.clone();
        return null;
    }
}
