package net.me.xinzhi.knowledge.recipe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * 从配方对象上抠「每条产物的概率」，只认形状不点名附属。
 *
 * <p>认这些：无参 {@code getChances()} / {@code getChance()}，或同名字段；
 * 返回值可以是 {@code int[]}、{@code List<Integer>}、以及 intlist 那种
 * 「有 {@code toIntArray()} / {@code getItems()} 的东西」。
 */
public final class Chances {

    private Chances() { }

    /**
     * @param recipe 原始配方对象
     * @param outputs 产物数量（用来补齐长度、全 100 时返回 null）
     * @return 每个产物的百分比；抠不到 / 全是 100 返回 null
     */
    public static int[] of(Object recipe, int outputs) {
        if (recipe == null) return null;
        Object raw = null;
        for (String name : new String[]{"getChances", "getChance"}) {
            try {
                Method m = recipe.getClass().getMethod(name);
                raw = m.invoke(recipe);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (raw == null) {
            for (String name : new String[]{"chances", "chance"}) {
                try {
                    Field f = findField(recipe.getClass(), name);
                    if (f == null) continue;
                    f.setAccessible(true);
                    raw = f.get(recipe);
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        int[] vals = toInts(raw);
        if (vals == null || vals.length == 0) return null;
        int len = Math.max(outputs, vals.length);
        int[] out = new int[len];
        for (int i = 0; i < len; i++) out[i] = i < vals.length ? clamp(vals[i]) : 100;
        return ChanceRecipe.interesting(out) ? out : null;
    }

    /**
     * 是不是「一次产出 1 种」：RSC 的 {@code isChooseOneIfHas()} / {@code isChooseOne()}。
     * 腐竹 2026-11-08 的截图：一次产出 1 种，每种 11.1%。
     */
    public static boolean chooseOne(Object recipe) {
        if (recipe == null) return false;
        for (String name : new String[]{"isChooseOneIfHas", "isChooseOne"}) {
            try {
                Object v = recipe.getClass().getMethod(name).invoke(recipe);
                if (v instanceof Boolean b && b) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    /** 各种「整数列表」都摊平成 int[] */
    private static int[] toInts(Object raw) {
        if (raw == null) return null;
        if (raw instanceof int[] a) return a;
        if (raw instanceof Collection<?> c) {
            int[] r = new int[c.size()];
            int i = 0;
            for (Object o : c) {
                if (o instanceof Number n) r[i++] = n.intValue();
                else return null;
            }
            return r;
        }
        // it.unimi.dsi.fastutil.ints.IntList / RSC 自己的包装
        for (String name : new String[]{"toIntArray", "toArray"}) {
            try {
                Method m = raw.getClass().getMethod(name);
                Object v = m.invoke(raw);
                if (v instanceof int[] a) return a;
            } catch (Throwable ignored) {
            }
        }
        try {
            Method m = raw.getClass().getMethod("getInt", int.class);
            Object size = raw.getClass().getMethod("size").invoke(raw);
            if (size instanceof Number n) {
                int[] r = new int[n.intValue()];
                for (int i = 0; i < r.length; i++) {
                    Object v = m.invoke(raw, i);
                    if (!(v instanceof Number num)) return null;
                    r[i] = num.intValue();
                }
                return r;
            }
        } catch (Throwable ignored) {
        }
        if (raw instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Number) {
            int[] r = new int[l.size()];
            for (int i = 0; i < r.length; i++) r[i] = ((Number) l.get(i)).intValue();
            return r;
        }
        return null;
    }
}
