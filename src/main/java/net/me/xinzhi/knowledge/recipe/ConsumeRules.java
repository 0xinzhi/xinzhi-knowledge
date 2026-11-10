package net.me.xinzhi.knowledge.recipe;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import net.me.xinzhi.knowledge.util.ReflectUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「这条配方的<b>哪个输入是催化剂</b>」的通用判定 —— 纯判定，<b>不扣任何东西</b>。
 *
 * <h2>什么叫催化剂 / 无消耗</h2>
 * 有的机器是<b>模板机</b>：输入槽里的东西只用来<b>认</b>是哪条配方，
 * 认出来之后<b>不会被扣掉</b>，产物照出。本项目的 yml 机器早就是这个语义
 * （{@code config/machine/RecipeDef}：匹配用 {@code getMatchInputs()}，
 * 扣料用 {@code getConsumeInputs()}）——本类把它推广到<b>第三方附属</b>。
 *
 * <p>⚠ <b>匹配仍然用「全部输入」</b>。催化剂是「照扣不误」的反面，不是「不用放」：
 * 模板不放，机器认不出配方，本类也绝不认为满足条件。少判一样就是白送。
 *
 * <h2>怎么认（按可靠性排序，全是反射，不写死任何附属的类名）</h2>
 * <ol>
 *   <li><b>「每条输入该扣多少」形状</b>（2026-10-09 补，RSC 专用）：配方有
 *       {@code getInputs()}（返回集合），元素上同时有 {@code getStack()}({@code ItemStack})
 *       与 {@code getConsumeAmount()}({@code int}) —— 直接拿"该扣多少"当扣料计划，
 *       比布尔标记更精确（支持<b>部分</b>不消耗）</li>
 *   <li><b>逐下标方法</b> {@code boolean isNoConsumeInput(int)} —— 最精确</li>
 *   <li><b>集合 getter</b> {@code getNoConsumeInputs()} →
 *       {@code Set}/{@code Collection}/{@code int[]}</li>
 *   <li><b>整体布尔</b> {@code boolean noConsume()} / {@code isNoConsume()}（record 风格）</li>
 *   <li><b>同名字段</b> {@code noConsumeInputs} —— 兜底</li>
 * </ol>
 *
 * <p>⭐ <b>为什么这套名字不是猜的</b>（2026-10-09 反编译本服 12 个附属得出）：
 * <pre>
 *   逻辑工艺 LogiTech  StackMachineRecipe extends MachineRecipe
 *                      private Set&lt;Integer&gt; noConsumeInputs
 *                      public  Set&lt;Integer&gt; getNoConsumeInputs()
 *                      public  boolean       isNoConsumeInput(int)
 *                      —— CraftUtils 里就是 Set.contains(下标) → 对应 getInput()[下标]
 *   RSC 自定义机器      CustomMachineRecipe.getInputs() → List&lt;InputWrapper&gt;
 *                      InputWrapper.getStack() / getConsumeAmount() / getAmount()
 *                      —— getConsumeAmount() = 总量 − noConsume 的量（支持部分不消耗）
 * </pre>
 *
 * <h2>⚠⚠ 一条踩过的坑：{@code getNoConsume()} 不是"下标"</h2>
 * 本类原来还会认 {@code getNoConsume()}（集合）与字段 {@code noConsume}，那是<b>错的</b>：
 * <pre>
 *   RSC  CustomLinkedMachineRecipe.getNoConsume() → IntSet，装的是「<b>槽位号</b>」
 *        （来自 yml 的 slot: 与 linkedOutput 的 key）
 *        而它的 getInput() 是 linkedInput.values().toArray()（顺序与槽位号无关）
 *        ⇒ 拿槽位号去当「getInput() 的下标」会<b>错位</b>，
 *          把不相干的输入当成催化剂跳过 = 白送材料
 *   RSC  CustomMachineRecipe.getNoConsume() → IntList，是 fuckLogitech() 造的
 *        「0..n-1」序列（纯为 LogiTech 反射兼容），<b>既不是下标也不是槽位</b>
 * </pre>
 * ⚠ 也正因如此，{@code doProbe} 里的探测顺序<b>不能改</b>：⓪（{@code getConsumeAmount}
 * 形状）必须排在 SET_SOURCE 前面，否则会先命中上面那个"假集合"。
 *
 * <p>⚠ <b>RSC 自己其实会用"槽位级 noConsume"</b>（2026-10-09 对着源码复核，本文件
 * 之前那句"RSC 自己扣料也不用它"是<b>写错了</b>）：
 * {@code CustomLinkedMachineRecipe.matches} L203 会 skip 掉这些槽，
 * {@code CustomMachineRecipe.matches} L276 也会 skip
 * {@code wrapper.getNoConsume().getLinkedNoConsume()} 里的槽。
 * 但那套是<b>按槽位</b>的语义，跟"{@code getInput()} 的下标"是两码事。
 * ⚠ 我们<b>故意不做</b>槽位那套（腐竹 2026-10-09 定：槽位只是<b>摆放</b>问题）——
 * 书一律按种类 / 总量匹配、按种类从输入槽扣料。曾按槽位做过一版，已撤（见 K §93.20(a)）。
 * 普通配方里"槽位级 noConsume"的差别因此只剩<b>从哪个槽扣</b>（总量一样），
 * 已知的 RSC 用法里没有实例，也不实现。
 *
 * <p>所以现在<b>只认名字里带 inputs 的那两个</b>（{@code getNoConsumeInputs} /
 * {@code noConsumeInputs}）—— 名字里有 inputs 才敢当"下标"用。
 *
 * <h2>整机名单（第二道，兜底）</h2>
 * 有些机器把「不消耗」<b>硬编码在 tick 逻辑里</b>，配方对象上一点痕迹都没有。
 * 这种只能靠机器 id 名单：{@link #machineNeverConsumes}。名单<b>不写死在本类</b>——
 * 由 {@code config.yml} 的 {@code machines.knowledge-book.no-consume-machines} 提供，
 * 本类只负责判定。
 *
 * <p>⚠ 本类<b>不持有任何静态可变状态</b>（探测结果缓存的是 {@link Class}，不是机器实例）。
 */
public final class ConsumeRules {

    private ConsumeRules() {
    }

    /** 机器 id → 整机不消耗（由 config 灌进来；存的是归一化后的 id） */
    private static final Set<String> MACHINE_NO_CONSUME = ConcurrentHashMap.newKeySet();

    /** {@code Class} → 这台机器的配方对象该用哪套探测法（只缓存策略，不缓存实例） */
    private static final ConcurrentHashMap<Class<?>, Probe> PROBES = new ConcurrentHashMap<>();

    /** {@code Class} → 「整条配方一点料都不扣」的方法（没有就记 {@code null}） */
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> WHOLE_NO_CONSUME = new ConcurrentHashMap<>();

    /** 探测不出东西的哨兵，免得对同一个类反复走完整套反射 */
    private static final Probe NONE = new Probe(ProbeKind.NONE, null);

    // ============================================================
    // 名单
    // ============================================================

    /**
     * 归一化机器 id：{@code SF:XXX} / {@code xxx} → {@code XXX}（大写）。
     * <p>口径与 {@code warehouse/recipe/RecipeBlacklist} 一致（那边也有一个同名归一）。
     */
    public static String norm(String id) {
        if (id == null) return null;
        String s = id.trim();
        if (s.isEmpty()) return null;
        int c = s.indexOf(':');
        if (c >= 0) s = s.substring(c + 1);
        return s.toUpperCase(Locale.ROOT);
    }

    /** 整机不消耗名单：<b>只增不减</b>，启动时灌一次。 */
    public static void setMachineNoConsume(Collection<String> ids) {
        MACHINE_NO_CONSUME.clear();
        if (ids == null) return;
        for (String id : ids) {
            String n = norm(id);
            if (n != null) MACHINE_NO_CONSUME.add(n);
        }
    }

    /** 当前名单快照（日志 / 调试用） */
    public static List<String> machineNoConsumeList() {
        List<String> out = new ArrayList<>(MACHINE_NO_CONSUME);
        out.sort(String::compareTo);
        return out;
    }

    /** 这台机器是不是「整机不消耗输入」。 */
    public static boolean machineNeverConsumes(SlimefunItem sf) {
        if (sf == null) return false;
        return MACHINE_NO_CONSUME.contains(norm(sf.getId()));
    }

    // ============================================================
    // 逐条配方
    // ============================================================

    /**
     * 这条配方里，<b>哪些输入是催化剂</b>。返回数组长度 = {@code r.getInput().length}。
     *
     * <p>全是 {@code false} 时返回的也是等长数组（调用方直接下标访问，不用判空）。
     */
    public static boolean[] noConsumeFlags(MachineRecipe r) {
        if (r == null) return new boolean[0];
        ItemStack[] inputs = r.getInput();
        int n = inputs == null ? 0 : inputs.length;
        boolean[] out = new boolean[n];
        if (n == 0) return out;

        // ⭐ 配方级开关优先：RSC 的 noConsume: true 落在 isNoConsumeAll() 上，
        //   每条输入的 getConsumeAmount() 仍然是原样（2026-11-07 从字节码确认）。
        if (wholeNoConsume(r)) {
            for (int i = 0; i < n; i++) out[i] = true;
            return out;
        }

        Probe p = probeOf(r.getClass());
        switch (p.kind) {
            case CONSUME_AMOUNTS -> { /* 这条机制不用逐下标布尔 —— 它给的是"该扣多少"，见 consumePlan */ }
            case INDEX_METHOD -> {
                for (int i = 0; i < n; i++) out[i] = callIndex(p.method, r, i);
            }
            case SET_SOURCE -> {
                Object set = callGetter(p.method, r);
                for (int i = 0; i < n; i++) out[i] = setContains(set, i);
            }
            case WHOLE_BOOLEAN -> {
                boolean all = callBool(p.method, r);
                if (all) for (int i = 0; i < n; i++) out[i] = true;
            }
            case NONE -> { /* 全 false，数组已经就位 */ }
        }
        return out;
    }

    /** 第 {@code index} 个输入是不是催化剂。 */
    public static boolean isCatalyst(MachineRecipe r, int index) {
        boolean[] f = noConsumeFlags(r);
        return index >= 0 && index < f.length && f[index];
    }

    /** 这条配方里有没有任何催化剂（没有的话调用方可以走原路，省一次数组构造）。 */
    public static boolean hasCatalyst(MachineRecipe r) {
        for (boolean b : noConsumeFlags(r)) if (b) return true;
        return false;
    }

    /**
     * 「<b>真要扣掉</b>」的那部分输入 —— 匹配仍然用 {@code r.getInput()}，
     * 只有扣料这一步换成它。
     *
     * <p>每条该扣多少<b>只有一个来源</b>：{@link #consumeAmounts}。
     * <b>一条"少扣"都没有</b>时<b>原样返回</b> {@code r.getInput()}（不复制），
     * 于是绝大多数机器一个字节的行为都不变。
     */
    public static ItemStack[] consumeSubset(MachineRecipe r) {
        if (r == null) return new ItemStack[0];
        ItemStack[] all = r.getInput();
        if (all == null || all.length == 0) return new ItemStack[0];

        int[] want = consumeAmounts(r);
        boolean anyCut = false;
        for (int i = 0; i < all.length; i++) {
            ItemStack is = all[i];
            if (is == null || is.getType() == Material.AIR) continue;
            if (i >= want.length || want[i] != is.getAmount()) { anyCut = true; break; }
        }
        if (!anyCut) return all;        // 没人被少扣 → 原样返回（快路径）

        List<ItemStack> list = new ArrayList<>(all.length);
        for (int i = 0; i < all.length; i++) {
            ItemStack is = all[i];
            if (is == null || is.getType() == Material.AIR) continue;
            int amount = i < want.length ? want[i] : is.getAmount();
            if (amount <= 0) continue;
            if (amount == is.getAmount()) {
                list.add(is);                                   // 没变就交原对象（不复制）
            } else {
                ItemStack less = is.clone();
                less.setAmount(amount);
                list.add(less);
            }
        }
        return list.toArray(new ItemStack[0]);
    }

    /**
     * <b>每条输入这次实际该扣多少</b>（下标 → 数量，{@code 0} = 一点不扣）。
     *
     * <p>⭐ <b>全项目只有这一处算这个数</b>：扣料（{@link #consumeSubset}）和界面标注
     * （{@code utils.RecipeGui} 的 {@code [催化剂]} / {@code [只扣 N]}）都从它取 ——
     * 所以绝不会"界面说一套、手上做一套"。
     *
     * <p>两条机制：
     * <ol>
     *   <li>{@link #noConsumeFlags}：整项是催化剂 ⇒ 0；</li>
     *   <li>{@link #consumePlan}（RSC 形状）：这条输入"该扣多少" ⇒ 只扣那么多。</li>
     * </ol>
     *
     * <p>⚠ 计划是<b>就地消耗</b>的（{@link #takeFromPlan}）：同一种物品在输入数组里
     * 出现多次时，它们<b>共享</b>同一份计划量，不会被每一份都扣一遍。
     */
    public static int[] consumeAmounts(MachineRecipe r) {
        ItemStack[] all = r == null ? null : r.getInput();
        if (all == null || all.length == 0) return new int[0];

        int[] out = new int[all.length];
        if (wholeNoConsume(r)) return out;      // 整条配方不扣料 ⇒ 全 0（数组本来就全 0）

        boolean[] flags = noConsumeFlags(r);
        // ⚠ 每次都要重新拿一份：takeFromPlan 会把计划扣减掉
        List<ItemStack> plan = consumePlan(r);

        for (int i = 0; i < all.length; i++) {
            ItemStack is = all[i];
            if (is == null || is.getType() == Material.AIR) continue;
            if (i < flags.length && flags[i]) continue;         // ① 整项催化剂

            int full = is.getAmount();
            Integer cap = planCap(plan, is);                    // ② 这条类型该扣多少（就地取走）
            out[i] = cap == null ? full : Math.max(0, Math.min(full, cap));
        }
        return out;
    }

    /**
     * 「这条配方该扣多少」的<b>唯一推荐入口</b>：包装类拿源配方直接算好再带走。
     *
     * <p>为什么包装类必须自己算（2026-11-10 卡片序列机）：知识附属的包装
     * （{@link ChanceRecipe} / {@link GroupedRecipe}）会把配方换成自己的类，
     * 之后 {@code build} 再调 {@link #consumeAmounts} 时反射看到的是<b>包装类</b>，
     * 源配方上那些 noConsume 标记一律探不到 ⇒ 不消耗配方被当成要扣料，
     * 生成器视图里 61 条配方一条都不剩。
     *
     * <p>返回 {@code null} = 读不出（调用方继续走自己的默认口径）。
     */
    public static int[] amountsFor(MachineRecipe src) {
        if (src == null) return null;
        if (src instanceof AmountsCarrier ac) {
            int[] a = ac.consumeAmounts();
            if (a != null) return a.clone();
        }
        try {
            return consumeAmounts(src);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * RSC 形状的<b>扣料计划</b>：每项的 {@code amount} = 「这种输入本次真正该扣多少」，
     * {@code 0} = 纯催化剂。
     *
     * <p>认不出（不是那种形状 / 元素读不动 / 列表为空）返回 {@code null} =
     * <b>按配方原样扣</b> —— 宁可多扣（玩家看得见），也不要因为读错而少扣（白送）。
     */
    public static List<ItemStack> consumePlan(MachineRecipe r) {
        if (r == null) return null;
        Probe p = probeOf(r.getClass());
        if (p.kind != ProbeKind.CONSUME_AMOUNTS) return null;
        if (p.method() == null || p.stackMethod() == null || p.consumeMethod() == null) return null;

        try {
            Object raw = p.method().invoke(r);
            if (!(raw instanceof Collection<?> col) || col.isEmpty()) return null;

            List<ItemStack> plan = new ArrayList<>();
            for (Object o : col) {
                if (o == null) continue;
                Object stack = p.stackMethod().invoke(o);
                if (!(stack instanceof ItemStack is) || is.getType() == Material.AIR) continue;
                Object consume = p.consumeMethod().invoke(o);
                if (!(consume instanceof Number num)) return null;   // 读不动就整个放弃
                int n = num.intValue();
                if (n < 0) return null;
                mergePlan(plan, is, n);
            }
            return plan.isEmpty() ? null : plan;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 同类型合并（同一种物品出现在多个 wrapper 里时，可扣量相加）。 */
    private static void mergePlan(List<ItemStack> plan, ItemStack proto, int amount) {
        for (ItemStack q : plan) {
            if (q.isSimilar(proto)) {
                q.setAmount(q.getAmount() + amount);
                return;
            }
        }
        ItemStack one = proto.clone();
        one.setAmount(amount);
        plan.add(one);
    }

    /**
     * 计划里这种物品还剩多少可扣 —— <b>取多少就扣掉多少</b>（就地改计划里的数量）。
     *
     * <p>⚠ 必须"就地扣减"：同一种物品可能在输入数组里出现多次
     * （RSC 的 `getInputs()` 被 `flatMap` 摊平过），
     * 让每一份都独立取一次上限 = 把计划量用超 = 少扣 = 白送。
     *
     * @return 这条输入<b>这次</b>最多能扣多少；{@code null} = 计划里没有它（按原样扣）
     */
    private static Integer planCap(List<ItemStack> plan, ItemStack is) {
        if (plan == null || is == null) return null;
        boolean present = false;   // 计划里有这种物品（哪怕该扣 0）
        int left = 0;
        for (ItemStack p : plan) {
            if (p == null || !p.isSimilar(is)) continue;
            present = true;
            left += Math.max(0, p.getAmount());
        }
        if (!present) return null;
        // ⚠ 计划里明确写了「该扣 0」（RSC 的 noConsume 卡片）要返回 0，
        //   不能当成「计划里没有、按原样扣」——否则纯催化剂配方会被整份扣掉。
        if (left <= 0) return 0;

        int remain = left;
        for (ItemStack p : plan) {
            if (remain <= 0) break;
            if (p == null || !p.isSimilar(is)) continue;
            int take = Math.min(remain, Math.max(0, p.getAmount()));
            if (take <= 0) continue;
            p.setAmount(p.getAmount() - take);
            remain -= take;
        }
        return left;
    }

    // ============================================================
    // 反射探测
    // ============================================================

    private enum ProbeKind { CONSUME_AMOUNTS, INDEX_METHOD, SET_SOURCE, WHOLE_BOOLEAN, NONE }

    /**
     * 探测结果。{@code method} 的含义随 {@code kind} 变；
     * {@code stackMethod / amountMethod / consumeMethod} 只在
     * {@link ProbeKind#CONSUME_AMOUNTS} 时有值（元素类上的三个方法）。
     */
    private record Probe(ProbeKind kind, Method method,
                         Method stackMethod, Method amountMethod, Method consumeMethod) {
        Probe(ProbeKind kind, Method method) {
            this(kind, method, null, null, null);
        }
    }

    /**
     * 这条配方是不是「整条一点料都不扣」。
     *
     * <p>认两种：方法 {@code isNoConsumeAll()} / {@code isNoConsume()} 返回 true，
     * 或同名字段为 true。RSC 的 yml 写 {@code noConsume: true} 就是这条路
     * （构造参数 {@code noConsumeAll}，**不动** InputWrapper 的 consumeAmount）——
     * 只认 {@code getConsumeAmount()} 会把整份卡片算进成本，机器就"识别不出模板机"。
     */
    public static boolean wholeNoConsume(MachineRecipe r) {
        if (r == null) return false;
        Class<?> c = r.getClass();
        Optional<Method> m = WHOLE_NO_CONSUME.computeIfAbsent(c, k -> {
            for (String name : new String[]{ "isNoConsumeAll", "noConsumeAll" }) {
                Method found = findMethod(k, name);
                if (returns(found, boolean.class, Boolean.class)) return Optional.of(found);
            }
            return Optional.empty();
        });
        if (m.isPresent()) return callBool(m.get(), r);

        Object f = ReflectUtils.getFieldValueRecursive(r, "noConsumeAll");
        return f instanceof Boolean b && b;
    }

    private static Probe probeOf(Class<?> c) {
        if (c == null) return NONE;
        Probe cached = PROBES.get(c);
        if (cached != null) return cached;
        Probe p = doProbe(c);
        PROBES.put(c, p);
        return p;
    }

    private static Probe doProbe(Class<?> c) {
        // ⓪ RSC 形状：「配方有 getInputs()，元素上有 getStack() + getConsumeAmount()」
        Probe plan = probeConsumeAmounts(c);
        if (plan != null) return plan;

        // ⓪.5 旧版 RSC（objects.machine.CustomMachineRecipe）：
        //     getNoConsume() 返回「不扣料的输入下标」（IntList，按 getInput() 的下标）。
        //     ⚠ 新版把同名类改成了 wrapper 形状（走 ⓪），那一版的 getNoConsume 是伪值
        //     （0..n 连续表），所以**只有在没有 getInputs() 时才认**这个 getter。
        if (findMethod(c, "getInputs") == null) {
            Method nc = findMethod(c, "getNoConsume");
            if (nc != null) {
                Class<?> rt = nc.getReturnType();
                if (Collection.class.isAssignableFrom(rt) || int[].class == rt) {
                    return new Probe(ProbeKind.SET_SOURCE, nc);
                }
            }
        }

        // ① 逐下标方法（最精确）
        Method m = findMethod(c, "isNoConsumeInput", int.class);
        if (returns(m, boolean.class, Boolean.class)) {
            return new Probe(ProbeKind.INDEX_METHOD, m);
        }

        // ② 集合 getter（⚠ 只认名字里带 inputs 的 —— getNoConsume 装的是槽号，见类注释）
        for (String name : new String[]{ "getNoConsumeInputs" }) {
            Method g = findMethod(c, name);
            if (g == null) continue;
            Class<?> rt = g.getReturnType();
            if (Set.class.isAssignableFrom(rt)
                    || Collection.class.isAssignableFrom(rt)
                    || int[].class == rt
                    || Integer[].class == rt) {
                return new Probe(ProbeKind.SET_SOURCE, g);
            }
        }

        // ③ 整体布尔（record 风格：noConsume() / isNoConsume()）
        for (String name : new String[]{ "noConsume", "isNoConsume" }) {
            Method b = findMethod(c, name);
            if (returns(b, boolean.class, Boolean.class)) {
                return new Probe(ProbeKind.WHOLE_BOOLEAN, b);
            }
        }

        // ④ 同名字段兜底（没有 getter 的附属；同样只认带 inputs 的）
        for (String name : new String[]{ "noConsumeInputs" }) {
            Field f = ReflectUtils.getFieldRecursive(c, name);
            if (f == null) continue;
            Class<?> t = f.getType();
            if (Set.class.isAssignableFrom(t) || Collection.class.isAssignableFrom(t)) {
                return new Probe(ProbeKind.SET_SOURCE, null);
            }
            if (t == int[].class || t == Integer[].class) {
                return new Probe(ProbeKind.SET_SOURCE, null);
            }
            if (t == boolean.class || t == Boolean.class) {
                return new Probe(ProbeKind.WHOLE_BOOLEAN, null);
            }
        }
        return NONE;
    }

    /**
     * ⓪ <b>RSC 形状</b>：配方有 {@code getInputs()}（返回集合），
     * 元素上同时有 {@code getStack()}({@code ItemStack}) 与 {@code getConsumeAmount()}({@code int})。
     *
     * <p>元素类是从 {@code getInputs()} 的<b>泛型签名</b>上读的
     * （{@code List<InputWrapper>} → {@code InputWrapper}）—— 读不到就<b>放弃</b>，
     * 绝不靠"遍历实例猜类名"。
     *
     * <p>⚠ 这是唯一一种"能表达<b>部分</b>不消耗"的形状：RSC 的
     * {@code getConsumeAmount() = 总量 − noConsume 的量}，所以它比布尔标记更精确。
     */
    private static Probe probeConsumeAmounts(Class<?> c) {
        Method getter = findMethod(c, "getInputs");
        if (getter == null || !Collection.class.isAssignableFrom(getter.getReturnType())) return null;

        Class<?> elem = elementClassOf(getter);
        if (elem == null || elem == Object.class) return null;

        Method stack = findMethod(elem, "getStack");
        Method consume = findMethod(elem, "getConsumeAmount");
        if (stack == null || !ItemStack.class.isAssignableFrom(stack.getReturnType())) return null;
        if (!returns(consume, int.class, Integer.class)) return null;

        // getAmount()（总量）只是留档参考，缺了也能跑，所以不强制
        return new Probe(ProbeKind.CONSUME_AMOUNTS, getter, stack,
                findMethod(elem, "getAmount"), consume);
    }

    /** 从 {@code List<X>} 这种泛型返回类型上取 {@code X}；取不到返回 {@code null}。 */
    private static Class<?> elementClassOf(Method m) {
        try {
            Type t = m.getGenericReturnType();
            if (t instanceof ParameterizedType p) {
                Type[] args = p.getActualTypeArguments();
                if (args.length == 1 && args[0] instanceof Class<?> k) return k;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 沿着继承链找无参 / 指定参的方法；找到就 {@code setAccessible}（附属常有 protected）。 */
    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                k = k.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean returns(Method m, Class<?> a, Class<?> b) {
        if (m == null) return false;
        Class<?> rt = m.getReturnType();
        return rt == a || rt == b;
    }

    private static boolean callIndex(Method m, Object target, int index) {
        if (m == null) return false;
        try {
            Object v = m.invoke(target, index);
            return v instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean callBool(Method m, Object target) {
        if (m == null) return false;
        try {
            Object v = m.invoke(target);
            return v instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object callGetter(Method m, Object target) {
        if (m != null) {
            try {
                return m.invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }
        // 没有 getter → 直接读字段
        Object v = ReflectUtils.getFieldValueRecursive(target, "noConsumeInputs");
        return v != null ? v : ReflectUtils.getFieldValueRecursive(target, "noConsume");
    }

    /**
     * 集合里有没有下标 {@code index}。
     *
     * <p>⚠ fastutil 的 {@code IntSet} <b>extends {@code Set<Integer>}</b>，
     * 所以这里按 {@link Set} 走 {@code contains(Integer)} 是对的
     * （不要去找 {@code contains(int)} 那个重载，反射会选错）。
     */
    private static boolean setContains(Object set, int index) {
        if (set == null) return false;
        try {
            if (set instanceof int[] arr) {
                for (int v : arr) if (v == index) return true;
                return false;
            }
            if (set instanceof Integer[] arr) {
                for (Integer v : arr) if (v != null && v == index) return true;
                return false;
            }
            if (set instanceof Set<?> s) return s.contains(index);
            if (set instanceof Collection<?> col) {
                for (Object o : col) if (o instanceof Integer i && i == index) return true;
                return false;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}