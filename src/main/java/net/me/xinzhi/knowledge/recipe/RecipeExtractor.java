package net.me.xinzhi.knowledge.recipe;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import net.me.xinzhi.knowledge.util.RecipeKeys;
import net.me.xinzhi.knowledge.util.ReflectUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⭐ <b>「从任意一台第三方机器里反射抠出它的配方」的唯一实现。</b>
 *
 * <p><b>来源</b>：照搬主附属 {@code net.me.xinzhi.xinzhitech.machines.RecipeExtractor}
 * （2026-11-07），只是去掉了主附属自己 yml 机器那支（{@code ExactRecipeSource}）。
 * 为什么不直接依赖主附属：本附属要能<b>单独装</b>（腐竹要求独立成附属）。
 *
 * <p>读取顺序：{@code AContainer#getMachineRecipes} → 字段
 * {@code recipes/machineRecipes/recipeList/RECIPE_LIST} → {@code templates[].recipes}
 * → {@code getRecipes()} → 配方载体字段（{@link #extractFromCarriers}，RSC 的 ticker 走这条）
 * → 生成器那族（{@link #extractGeneratorRecipes}）
 * → 出口过 {@link DisplayRecipeGuard}。
 *
 * <p>⚠ <b>展示配方（getDisplayRecipes）一律不采信</b> —— 腐竹 2026-11-07 原话：
 * 「无论如何，配方都不能使用展示配方。展示配方经常是用于展示的，配方很可能是不完整的。」
 * 抠不出精确配方的机器就让它显示「扫不出配方」，不许猜一个错的去合成。
 *
 * <p>⚠ <b>为什么要照抄而不是自己写一套</b>：这套顺序是主附属在十几个附属上
 * 一条条踩出来的（RECIPE_LIST 大小写、无尽计数配方、展示列表多输入归并…）。
 * 自己另写一套 = 把那些坑重踩一遍。
 *
 * <p>本类<b>不持有任何静态可变状态</b>。
 */
public final class RecipeExtractor {

    private RecipeExtractor() {
    }

    /** 把这台机器里能抠出来的配方全部抠出来并去重。 */
    public static List<MachineRecipe> extractMachineRecipes(SlimefunItem sf) {
        List<MachineRecipe> result = new ArrayList<>();
        if (sf == null) return result;
        Set<String> seen = new HashSet<>();

        if (sf instanceof AContainer container) {
            try {
                List<MachineRecipe> api = container.getMachineRecipes();
                // ⚠ 这里也必须过 convertRecipe：RSC 的 AdvancedCustomMachine 走 registerRecipe
                // 把 CustomMachineRecipe 直接放进 AContainer 的表里，而它的概率/chooseOne
                // 只在原对象上 —— 不过一遍就变成「9 种全给」（腐竹 2026-11-08 现场）。
                if (api != null) for (MachineRecipe r : api) {
                    MachineRecipe c = convertRecipe(r);
                    if (c != null && seen.add(RecipeKeys.of(c))) result.add(c);
                }
            } catch (Exception ignored) {
            }
        }

        // ⚠ RECIPE_LIST（大写）不能省：无尽奇点构造机把配方放在 private static final
        //   List<...> RECIPE_LIST 里，字段查找大小写敏感。
        String[] names = { "recipes", "machineRecipes", "recipeList", "RECIPE_LIST" };
        for (String name : names) {
            Object v = ReflectUtils.getFieldValueRecursive(sf, name);
            if (v instanceof List<?> list) {
                for (Object obj : list) {
                    MachineRecipe r = convertRecipe(obj);
                    if (r != null && seen.add(RecipeKeys.of(r))) result.add(r);
                }
            }
        }

        Object tpl = ReflectUtils.getFieldValueRecursive(sf, "templates");
        if (tpl instanceof List<?> list) {
            for (Object t : list) {
                Object rl = ReflectUtils.getFieldValueRecursive(t, "recipes");
                if (rl instanceof List<?> rll) {
                    for (Object obj : rll) {
                        MachineRecipe r = convertRecipe(obj);
                        if (r != null && seen.add(RecipeKeys.of(r))) result.add(r);
                    }
                }
            }
        }

        try {
            Method m = sf.getClass().getMethod("getRecipes");
            Object r = m.invoke(sf);
            if (r instanceof List<?> list) {
                for (Object obj : list) {
                    MachineRecipe mr = convertRecipe(obj);
                    if (mr != null && seen.add(RecipeKeys.of(mr))) result.add(mr);
                }
            }
        } catch (Exception ignored) {
        }

        extractFromCarriers(sf, result, seen);
        extractFromStaticLists(sf, result, seen);

        for (MachineRecipe r : extractGeneratorRecipes(sf)) {
            if (seen.add(RecipeKeys.of(r))) result.add(r);
        }

        // 最后一道闸门：输入是「改名 / 带 lore 的原版物品」的配方 = 写给人看的占位图，整条丢。
        return DisplayRecipeGuard.filter(result, sf);
    }


    /**
     * <b>配方寄存在另一个对象里</b>的通用探测（不点名任何附属）。
     *
     * <p>典型：RykenSlimeCustomizer 的 {@code AdvancedCustomMachine} 自身
     * {@code getMachineRecipes()} 是空的，配方在 {@code private MachineTicker ticker} 里，
     * 而 ticker 实现了 {@code RecipesHolder#getRecipes()}。2026-11-07 腐竹给的
     * {@code KAPIAN_XULIEJI}（卡片序列机）就属于这种。
     *
     * <p>判据只用形状：<b>机器上某个（非静态）字段 / 无参 getter 返回的对象，其类上有
     * {@code getRecipes()} 且返回集合</b> → 取那份配方。不写死类名、不写死字段名。
     */
    private static void extractFromCarriers(SlimefunItem sf, List<MachineRecipe> out, Set<String> seen) {
        for (Class<?> c = sf.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!hasRecipeGetter(f.getType())) continue;
                Object holder;
                try {
                    f.setAccessible(true);
                    holder = f.get(sf);
                } catch (Throwable t) {
                    continue;
                }
                collectFromHolder(holder, out, seen);
            }
        }
    }

    /**
     * <b>配方放在 static 列表里</b>的机器（腐竹 2026-11-07 给的 {@code SUPREME_TECH_GENERATOR}）。
     *
     * <p>Supreme 的 {@code TechGenerator} 把配方塞在
     * {@code public static final List<AbstractItemRecipe> receitasParaProduzir}，
     * 机器实例上那个 {@code machineRecipes} 是空的 ⇒ 按实例字段/API 找永远是 0 条。
     *
     * <p>判据只用形状：<b>类（含父类）上的 static 字段是集合、其泛型元素类型有
     * {@code getInput()/getOutput()} 返回数组</b> → 逐条解码。不点类名、不点字段名。
     * ⚠ 解码不出来就整条跳过，绝不用"猜"补齐。
     */
    private static void extractFromStaticLists(SlimefunItem sf, List<MachineRecipe> out, Set<String> seen) {
        for (Class<?> c = sf.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!java.util.Collection.class.isAssignableFrom(f.getType())) continue;
                if (!elementLooksLikeArrayRecipe(f)) continue;
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(null);
                } catch (Throwable t) {
                    continue;
                }
                if (!(v instanceof java.util.Collection<?> col)) continue;
                for (Object obj : col) {
                    MachineRecipe mr = convertRecipe(obj);
                    if (mr != null && seen.add(RecipeKeys.of(mr))) out.add(mr);
                }
            }
        }
    }

    /** 集合字段的<b>泛型元素类型</b>是不是「getInput()/getOutput() 回数组」那种配方对象。 */
    private static boolean elementLooksLikeArrayRecipe(java.lang.reflect.Field f) {
        try {
            java.lang.reflect.Type t = f.getGenericType();
            if (!(t instanceof java.lang.reflect.ParameterizedType pt)) return false;
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args.length != 1 || !(args[0] instanceof Class<?> elem)) return false;
            return hasArrayAccessor(elem, "getInput") && hasArrayAccessor(elem, "getOutput");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasArrayAccessor(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                if (m.getParameterCount() == 0 && ItemStack[].class.isAssignableFrom(m.getReturnType())) return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    /** 这个类上有没有 {@code getRecipes()}（无参、返回集合）——只看签名，不实例化。 */
    private static boolean hasRecipeGetter(Class<?> type) {
        if (type == null) return false;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("getRecipes") || m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (java.util.Collection.class.isAssignableFrom(rt) || rt.isArray()) return true;
            }
        }
        return false;
    }

    private static void collectFromHolder(Object holder, List<MachineRecipe> out, Set<String> seen) {
        if (holder == null) return;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Method m;
            try {
                m = c.getDeclaredMethod("getRecipes");
            } catch (NoSuchMethodException e) {
                continue;
            }
            try {
                m.setAccessible(true);
                Object v = m.invoke(holder);
                if (v instanceof java.util.Collection<?> col) {
                    for (Object obj : col) {
                        MachineRecipe mr = convertRecipe(obj);
                        if (mr != null && seen.add(RecipeKeys.of(mr))) out.add(mr);
                    }
                }
            } catch (Throwable ignored) {
            }
            return;
        }
    }

    /** 按类名分支，把第三方机器当成「发电机」抠配方。 */
    public static List<MachineRecipe> extractGeneratorRecipes(SlimefunItem sf) {
        List<MachineRecipe> result = new ArrayList<>();
        try {
            if (ReflectUtils.isExtendedFrom(sf.getClass(), "GrowingMachine")) {
                extractGrowing(sf, result);
            } else if (ReflectUtils.isExtendedFrom(sf.getClass(), ".MaterialGenerator")) {
                extractMaterial(sf, result);
            } else if (ReflectUtils.isExtendedFrom(sf.getClass(), ".AbstractQuarry")
                    || ReflectUtils.isExtendedFrom(sf.getClass(), ".Quarry")) {
                extractQuarry(sf, result);
            } else if (ReflectUtils.isExtendedFrom(sf.getClass(), ".CustomMaterialGenerator")) {
                extractRSC(sf, result);
            } else {
                extractGeneric(sf, result);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    /**
     * 「长得像配方」的对象 → {@link MachineRecipe}；认不出返回 {@code null}。
     *
     * <p>带概率的（RSC 的 chances / chooseOneIfHas）在这里包成 {@link ChanceRecipe}，
     * 否则下游会把「候选表」当成「全给」—— 腐竹 2026-11-08 的「一次给了 9 种」。
     */
    public static MachineRecipe convertRecipe(Object obj) {
        MachineRecipe mr = RecipeShapes.decode(obj);
        if (mr == null) return null;
        // 会随机抽一组的（乱序技艺 RandomMachineRecipe 等）：必须走组权重，不能把候选当全给
        GroupedRecipe grouped = GroupedOutputs.read(obj, mr);
        if (grouped != null) return grouped;
        ItemStack[] out = mr.getOutput();
        int len = out == null ? 0 : out.length;
        int[] ch = Chances.of(obj, len);
        boolean chooseOne = Chances.chooseOne(obj);
        // 「一次只出其中一种」但没写概率（RSC yml 的 chooseOne: true）= 每种等概率
        if (ch == null && chooseOne && len > 0) {
            ch = new int[len];
            java.util.Arrays.fill(ch, 100);
        }
        return ch == null ? mr : ChanceRecipe.of(mr, ch, chooseOne);
    }

    private static void extractGrowing(SlimefunItem sf, List<MachineRecipe> out) {
        int ticks = 1;
        Object t = ReflectUtils.getFieldValueRecursive(sf, "ticksPerOutput");
        if (t instanceof Number n) ticks = Math.max(1, n.intValue());
        Object r = ReflectUtils.getFieldValueRecursive(sf, "recipes");
        if (r instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object k = e.getKey(), v = e.getValue();
                ItemStack[] in;
                if (k instanceof Material m) in = new ItemStack[]{ new ItemStack(m) };
                else if (k instanceof ItemStack i) in = new ItemStack[]{ i };
                else in = new ItemStack[0];
                ItemStack[] ou;
                if (v instanceof ItemStack[] arr) ou = arr;
                else if (v instanceof ItemStack i) ou = new ItemStack[]{ i };
                else continue;
                // key 是「种什么」的筛选器（Material / 单件物品），不是要扣的料
                out.add(TemplateRecipe.of(ticks, in, ou));
            }
        }
    }

    private static void extractMaterial(SlimefunItem sf, List<MachineRecipe> out) {
        int amt = 1;
        Object s = ReflectUtils.getFieldValueRecursive(sf, "speed");
        if (s instanceof Number n) amt = Math.max(1, n.intValue());
        Object m = ReflectUtils.getFieldValueRecursive(sf, "material");
        if (m instanceof Material mat) {
            out.add(new MachineRecipe(1, new ItemStack[0], new ItemStack[]{ new ItemStack(mat, amt) }));
        } else if (m instanceof ItemStack is) {
            ItemStack c = is.clone();
            c.setAmount(amt);
            out.add(new MachineRecipe(1, new ItemStack[0], new ItemStack[]{ c }));
        }
    }

    private static void extractQuarry(SlimefunItem sf, List<MachineRecipe> out) {
        int ticks = 100;
        Object iv = ReflectUtils.getFieldValueRecursive(sf, "INTERVAL");
        if (iv instanceof Number n) ticks = Math.max(1, n.intValue());
        else {
            Object ds = ReflectUtils.getFieldValueRecursive(sf, "delaySpeed");
            if (ds instanceof Number n) ticks = Math.max(1, n.intValue());
        }
        int amt = 1;
        Object sp = ReflectUtils.getFieldValueRecursive(sf, "speed");
        if (sp instanceof Number n) amt = Math.max(1, n.intValue());
        Object w = ReflectUtils.getFieldValueRecursive(sf, "output");
        if (w != null) {
            Object items = ReflectUtils.getFieldValueRecursive(w, "outputItems");
            if (items instanceof List<?> list) {
                List<ItemStack> tmp = new ArrayList<>();
                List<Integer> chances = new ArrayList<>();
                for (Object o : list) {
                    Object is = ReflectUtils.getFieldValueRecursive(o, "itemStack");
                    if (!(is instanceof ItemStack s)) continue;
                    ItemStack one = s.clone();
                    one.setAmount(1);                 // 矿机每次只出 1 件
                    tmp.add(one);
                    Object ch = ReflectUtils.getFieldValueRecursive(o, "chance");
                    chances.add(ch instanceof Number n ? Math.max(0, n.intValue()) : 1);
                }
                if (!tmp.isEmpty()) {
                    // ⚠ 矿机是「按 chance 抽一个」，不是「全都出」——
                    //   当成普通产物会变成一次出十几种矿（腐竹 2026-11-07 报的 100% 获得）。
                    int[] weights = new int[chances.size()];
                    for (int i = 0; i < weights.length; i++) weights[i] = chances.get(i);
                    out.add(WeightedRecipe.of(ticks, new ItemStack[0], tmp.toArray(new ItemStack[0]), weights));
                    return;
                }
            }
        }
        Object outs = ReflectUtils.getFieldValueRecursive(sf, "outputs");
        if (outs instanceof Material[] ms) {
            List<ItemStack> tmp = new ArrayList<>();
            for (Material m : ms) tmp.add(new ItemStack(m, amt));
            if (!tmp.isEmpty()) out.add(new MachineRecipe(ticks, new ItemStack[0], tmp.toArray(new ItemStack[0])));
        }
    }

    private static void extractRSC(SlimefunItem sf, List<MachineRecipe> out) {
        int tick = 1;
        Object t = ReflectUtils.getFieldValueRecursive(sf, "tickRate");
        if (t instanceof Number n) tick = Math.max(1, n.intValue());
        Object output = ReflectUtils.getFieldValueRecursive(sf, "output");
        if (output == null) output = ReflectUtils.getFieldValueRecursive(sf, "generation");
        if (output == null) return;
        List<Integer> chances = null;
        Object co = ReflectUtils.getFieldValueRecursive(sf, "chances");
        if (co instanceof List<?> cl) {
            chances = new ArrayList<>();
            for (Object o : cl) chances.add(o instanceof Number n ? n.intValue() : 100);
        }
        List<ItemStack> tmp = new ArrayList<>();
        if (output instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                if (o instanceof ItemStack is) {
                    ItemStack c = is.clone();
                    if (chances != null && i < chances.size()) {
                        ItemMeta meta = c.getItemMeta();
                        if (meta != null) {
                            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                            lore.add("§7概率: §e" + chances.get(i) + "%");
                            meta.setLore(lore);
                            c.setItemMeta(meta);
                        }
                    }
                    tmp.add(c);
                }
            }
        } else if (output instanceof ItemStack is) {
            tmp.add(is.clone());
        }
        if (!tmp.isEmpty()) out.add(new MachineRecipe(tick, new ItemStack[0], tmp.toArray(new ItemStack[0])));
    }

    /**
     * 通用发电机：读 {@code output/outputs/generation/generations}。
     *
     * <p>⚠ 一旦发现 {@code input}/{@code inputs} 字段就<b>不动</b> —— 有输入就不是发电机，
     * 硬抠会把配方机的输入抹掉，变成白送。陌陌的
     * {@code AbstractEasyGeneratorGUI} 只有 {@code output} 字段、没有 input ⇒ 正好命中这里。
     */
    private static void extractGeneric(SlimefunItem sf, List<MachineRecipe> out) {
        if (ReflectUtils.getFieldValueRecursive(sf, "input") != null) return;
        if (ReflectUtils.getFieldValueRecursive(sf, "inputs") != null) return;
        Object o = ReflectUtils.getFieldValueRecursive(sf, "output");
        if (o == null) o = ReflectUtils.getFieldValueRecursive(sf, "outputs");
        if (o == null) o = ReflectUtils.getFieldValueRecursive(sf, "generation");
        if (o == null) o = ReflectUtils.getFieldValueRecursive(sf, "generations");
        if (o == null) return;
        ItemStack[] outputs = null;
        if (o instanceof ItemStack i) outputs = new ItemStack[]{ i };
        else if (o instanceof ItemStack[] a) outputs = a;
        else if (o instanceof Material m) outputs = new ItemStack[]{ new ItemStack(m) };
        else if (o instanceof Material[] ms) {
            outputs = new ItemStack[ms.length];
            for (int i = 0; i < ms.length; i++) outputs[i] = new ItemStack(ms[i]);
        } else if (o instanceof List<?> list) {
            List<ItemStack> tmp = new ArrayList<>();
            for (Object x : list) {
                if (x instanceof ItemStack i) tmp.add(i);
                else if (x instanceof Material m) tmp.add(new ItemStack(m));
            }
            outputs = tmp.toArray(new ItemStack[0]);
        }
        if (outputs == null || outputs.length == 0) return;
        int ticks = RecipeShapes.ticksOf(sf);
        out.add(new MachineRecipe(ticks, new ItemStack[0], outputs));
    }
}