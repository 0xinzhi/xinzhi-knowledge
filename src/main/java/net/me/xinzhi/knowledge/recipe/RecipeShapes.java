package net.me.xinzhi.knowledge.recipe;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import net.me.xinzhi.knowledge.util.ReflectUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 「配方对象长什么样」的通用解码层 —— 纯函数，<b>不持有任何静态可变状态</b>。
 *
 * <h2>为什么要有这一层</h2>
 * 第三方机器的"配方对象"形状各异，原来的 {@code RecipeExtractor.convertRecipe}
 * 只认一种形状：<b>同时有 {@code time}、{@code input}、{@code output} 三个字段</b>。
 * 形状对不上就返回 {@code null}，配方被静默丢掉 —— 无尽附属就是这样"识别不到"的。
 * 把"认形状"独立到本类之后，<b>以后再加一种形状只动这一个文件</b>。
 *
 * <h2>已识别的形状</h2>
 * <ol>
 *   <li><b>三字段</b> {@code time + input + output} —— Slimefun 原生与多数附属（原实现，行为不变）</li>
 *   <li><b>字符串键</b> {@code strings[] + amounts[] + output} —— 无尽 {@code MachineBlockRecipe}：
 *       输入被归一化成「Slimefun id 或 {@code Material} 名」的字符串，用 {@link #itemByKey} 反解</li>
 *   <li><b>两方法</b> {@code recipe()/input()/inputs()} + {@code output()} —— 无尽 {@code CraftingBlockRecipe}</li>
 *   <li><b>计数配方</b> {@code input}（1 个模板）+ {@code output} + {@code amount} —— 无尽
 *       {@code SingularityConstructor$Recipe}：真正的数量在 {@code amount} 里
 *       （铁 2000 / 铜 3000），展示列表只会给你 64 个一叠的模板</li>
 * </ol>
 *
 * <p>⚠ <b>形状 2 的解码必须完整</b>：只要有一个键反解不出来，整条配方作废（返回 {@code null}）。
 * 交出一条"少了料"的配方，等于让调用方少扣料多出货。
 */
public final class RecipeShapes {

    private RecipeShapes() {
    }

    /**
     * 机器"多少 tick 出一份"的常见字段名。
     *
     * <p>原来这份数组写在 {@code machines/RecipeExtractor.extractGeneric} 里，
     * 2026-10-09 收到这里，两处共用一份。
     */
    private static final String[] TICK_FIELDS = {
            "ticksPerOutput", "tickRate", "INTERVAL", "interval", "speed", "delaySpeed", "delay" };

    /**
     * 把一个"长得像配方"的对象解码成 {@link MachineRecipe}；认不出返回 {@code null}。
     *
     * <p>已经是 {@link MachineRecipe} 的原样返回。
     */
    public static MachineRecipe decode(Object obj) {
        if (obj == null) return null;
        if (obj instanceof MachineRecipe mr) return mr;
        MachineRecipe r = fromTimeInputOutput(obj);
        if (r != null) return r;
        r = fromArrayAccessors(obj);
        if (r != null) return r;
        r = fromStringAmounts(obj);
        if (r != null) return r;
        r = fromCounting(obj);
        if (r != null) return r;
        return fromAccessorMethods(obj);
    }

    // ============================================================
    // 形状 1：time + input + output
    // ============================================================

    private static MachineRecipe fromTimeInputOutput(Object obj) {
        try {
            Class<?> c = obj.getClass();
            Field tf = ReflectUtils.getFieldRecursive(c, "time");
            Field inf = ReflectUtils.getFieldRecursive(c, "input");
            Field of = ReflectUtils.getFieldRecursive(c, "output");
            if (tf == null || inf == null || of == null) return null;
            return new MachineRecipe(tf.getInt(obj),
                    (ItemStack[]) inf.get(obj), (ItemStack[]) of.get(obj));
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // 形状 1.5：getInput()/getInputNotNull() + getOutput()/getOutputNotNull() 直接回数组
    // ============================================================

    /**
     * <b>「输入/输出就是数组」形状</b>：{@code getInput()}(或 {@code getInputNotNull()})
     * 与 {@code getOutput()}(或 {@code getOutputNotNull()}) 都直接返回 {@code ItemStack[]}。
     *
     * <p>典型：Supreme 的 {@code AbstractItemRecipe}（字段 {@code ItemStack[] input/output}，
     * lombok 生成 getter）。这类对象<b>没有</b> {@code time} 字段，
     * 形状 1 会直接漏掉，机器就"扫不出配方"。
     *
     * <p>⚠ 必须要求<b>两个</b>数组都拿得到：只拿到输出、把输入当空 =
     * 一条不要料的配方 = 白送，宁可整条不认。
     */
    private static MachineRecipe fromArrayAccessors(Object obj) {
        try {
            ItemStack[] in = asItems(invoke(obj, "getInput"));
            if (in.length == 0) in = asItems(invoke(obj, "getInputNotNull"));
            ItemStack[] out = asItems(invoke(obj, "getOutput"));
            if (out.length == 0) out = asItems(invoke(obj, "getOutputNotNull"));
            if (in.length == 0 && out.length == 0) return null;
            if (out.length == 0) return null;              // 没有产物 = 不是配方
            if (in.length == 0) {
                // 输入抓不到不代表"无输入"：可能是方法名不同（getRecipe…），交给别的形状。
                // 只有「明确有空输入访问器」时才接受 0 输入。
                if (invoke(obj, "getInput") == null && invoke(obj, "getInputNotNull") == null) return null;
            }
            return new MachineRecipe(1, in, out);
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================================================
    // 形状 2：strings[] + amounts[] + output（无尽 MachineBlockRecipe）
    // ============================================================

    private static MachineRecipe fromStringAmounts(Object obj) {
        try {
            Object s = ReflectUtils.getFieldValueRecursive(obj, "strings");
            Object a = ReflectUtils.getFieldValueRecursive(obj, "amounts");
            if (!(s instanceof String[] strings) || !(a instanceof int[] amounts)) return null;
            if (strings.length == 0 || strings.length != amounts.length) return null;
            ItemStack out = asItem(ReflectUtils.getFieldValueRecursive(obj, "output"));
            if (blank(out)) return null;
            ItemStack[] ins = new ItemStack[strings.length];
            for (int i = 0; i < strings.length; i++) {
                ItemStack in = itemByKey(strings[i]);
                // ⚠ 反解不全 → 整条作废，绝不交出"少扣料"的配方
                if (in == null) return null;
                in.setAmount(Math.max(1, amounts[i]));
                ins[i] = in;
            }
            return new MachineRecipe(1, ins, new ItemStack[]{ out.clone() });
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把"输入的字符串键"反解成物品：先当 Slimefun id 查，再当原版材质名查。
     *
     * <p>与无尽 {@code StackUtils.getItemIdOrType} 的编码方向一致
     * （它写键时也是「{@code SlimefunItemStack.getItemId()}，拿不到就用 {@code Material.name()}」）。
     */
    public static ItemStack itemByKey(String key) {
        if (key == null || key.isEmpty()) return null;
        // ⚠ Slimefun 注册表在服务器启动早期/离线单测里可能是空的（getById 会 NPE），
        //   所以包一层：读不到就退回"当原版材质名解"，而不是把异常抛给调用方。
        try {
            SlimefunItem sf = SlimefunItem.getById(key);
            if (sf != null && sf.getItem() != null) return sf.getItem().clone();
        } catch (Throwable ignored) {
        }
        Material m = Material.matchMaterial(key);
        if (m == null || m.isAir()) return null;
        return new ItemStack(m);
    }

    // ============================================================
    // 形状 3：计数配方 —— input（1 个模板）+ output + amount/count/total
    // ============================================================

    /**
     * 「要多少个」的常见字段名。
     *
     * <p>⚠ 这份名单<b>故意收得很紧</b>：只收明确表示数量的词。
     * 像 {@code chance} / {@code weight} / {@code time} 这类同样是 int、
     * 但语义完全不同的字段<b>绝不能进来</b> —— 一旦混进来，
     * 一条“1 个换 1 个”的配方会被读成“100 个换 1 个”，然后机器就废了。
     */
    private static final String[] COUNT_FIELDS = {
            "amount", "count", "total", "required", "need", "stackAmount" };

    /**
     * 形状 4：<b>计数配方</b> —— 输入是「1 个模板」，另一个 int 字段说明「一共要多少个」。
     *
     * <p>⭐ <b>为什么必须有这一条</b>（2026-10-09 反编译无尽得出）：它的<b>奇点构造机</b>
     * 配方类长这样（{@code SingularityConstructor$Recipe}，私有嵌套类、无 getter）：
     * <pre>
     *   private final SlimefunItemStack output;
     *   private final ItemStack        input;    // 1 个模板
     *   private final String           id;
     *   private final int              amount;   // ★ 真正的数量：铁 2000、铜 3000
     * </pre>
     * 形状 1/2/3 全都不认它（没有 {@code time}、没有 {@code strings}、没有 getter），
     * 于是这台机器会掉进「展示配方兜底」——而展示列表里给的输入是
     * {@code new CustomItemStack(in, 64)} 这种<b>一整叠的模板</b>，
     * 结果就是「64 个铁 → 1 个铁奇点」，比真配方（2000 个）便宜 31 倍。
     * <b>那是一条能白送的白送配方</b>，必须在读不到的时候也别乱猜。
     *
     * <p>⚠ 只认 {@code count > 1}：等于 1 的交回别的形状处理，
     * 免得把同名的杂项 int 字段当成数量。
     */
    private static MachineRecipe fromCounting(Object obj) {
        try {
            ItemStack in = asItem(ReflectUtils.getFieldValueRecursive(obj, "input"));
            if (blank(in)) return null;
            ItemStack out = asItem(ReflectUtils.getFieldValueRecursive(obj, "output"));
            if (blank(out)) return null;

            int count = 0;
            for (String f : COUNT_FIELDS) {
                Object v = ReflectUtils.getFieldValueRecursive(obj, f);
                if (v instanceof Number n) { count = n.intValue(); break; }
            }
            if (count <= 1) return null;

            ItemStack scaled = in.clone();
            scaled.setAmount(count);
            return new MachineRecipe(1, new ItemStack[]{ scaled }, new ItemStack[]{ out.clone() });
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // 形状 4：recipe()/input()/inputs() + output()
    // ============================================================


    private static MachineRecipe fromAccessorMethods(Object obj) {
        try {
            ItemStack out = asItem(invoke(obj, "output"));
            if (blank(out)) return null;
            Object in = invoke(obj, "inputs");
            if (in == null) in = invoke(obj, "input");
            if (in == null) in = invoke(obj, "recipe");
            ItemStack[] ins = asItems(in);
            if (ins.length == 0) return null;
            return new MachineRecipe(1, ins, new ItemStack[]{ out });
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invoke(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name);
                if (m.getParameterCount() != 0) return null;
                m.setAccessible(true);
                return m.invoke(obj);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    // ============================================================
    // 公共小工具
    // ============================================================

    /** 「多少 tick 出一份」：按 {@link #TICK_FIELDS} 依次找，找不到算 1。 */
    public static int ticksOf(SlimefunItem machine) {
        if (machine == null) return 1;
        for (String f : TICK_FIELDS) {
            Object t = ReflectUtils.getFieldValueRecursive(machine, f);
            if (t instanceof Number n) return Math.max(1, n.intValue());
        }
        return 1;
    }

    /** 任意对象 → 单个物品（认 {@link ItemStack} / {@link Material} / 字符串键）；认不出返回 {@code null}。 */
    public static ItemStack asItem(Object o) {
        if (o instanceof ItemStack is) return blank(is) ? null : is;
        if (o instanceof Material m) return m.isAir() ? null : new ItemStack(m);
        if (o instanceof String s) return itemByKey(s);
        return null;
    }

    /** 任意对象 → 物品数组（认数组 / {@link List} / 单个物品），空槽与空气项被丢掉。 */
    public static ItemStack[] asItems(Object o) {
        if (o == null) return new ItemStack[0];
        if (o instanceof ItemStack[] arr) {
            List<ItemStack> tmp = new ArrayList<>(arr.length);
            for (ItemStack is : arr) if (!blank(is)) tmp.add(is);
            return tmp.toArray(new ItemStack[0]);
        }
        if (o instanceof List<?> list) {
            List<ItemStack> tmp = new ArrayList<>(list.size());
            for (Object x : list) {
                ItemStack is = asItem(x);
                if (is != null) tmp.add(is);
            }
            return tmp.toArray(new ItemStack[0]);
        }
        ItemStack one = asItem(o);
        return one == null ? new ItemStack[0] : new ItemStack[]{ one };
    }

    /**
     * 空槽判定。
     *
     * <p>⚠ 判随机产物<b>必须排在判空气之前</b>（见 {@code ReflectUtils.isProbabilistic}）：
     * 那类产物的本体常常就是 AIR。
     */
    public static boolean blank(ItemStack is) {
        if (is == null) return true;
        if (ReflectUtils.isProbabilistic(is)) return false;
        return is.getType().isAir();
    }
}