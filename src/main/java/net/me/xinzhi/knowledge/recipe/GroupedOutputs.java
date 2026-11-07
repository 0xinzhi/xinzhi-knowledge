package net.me.xinzhi.knowledge.recipe;

import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 从「会随机抽一组的配方」上抠出候选组与权重，只认形状不点名附属。
 *
 * <p>识别形状：对象有无参 {@code getRandomOutputs()}，返回数组/集合；
 * 每个元素有 {@code getOutputItem()}（ItemStack 或 ItemStack[]）与 {@code getWeight()}。
 */
public final class GroupedOutputs {

    private GroupedOutputs() { }

    /** 抠不到返回 null */
    public static GroupedRecipe read(Object recipe, MachineRecipe base) {
        if (recipe == null || base == null) return null;
        Object raw = call(recipe, "getRandomOutputs");
        if (raw == null) return null;
        List<Object> items = elements(raw);
        if (items.isEmpty()) return null;
        List<ItemStack[]> groups = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (Object e : items) {
            if (e == null) continue;
            ItemStack[] g = toStacks(call(e, "getOutputItem"));
            if (g == null || g.length == 0) g = toStacks(call(e, "getOutput"));
            if (g == null || g.length == 0) continue;
            Object w = call(e, "getWeight");
            int weight = w instanceof Number n ? n.intValue() : 1;
            groups.add(g);
            weights.add(Math.max(1, weight));
        }
        if (groups.isEmpty()) return null;
        int[] ws = new int[weights.size()];
        for (int i = 0; i < ws.length; i++) ws[i] = weights.get(i);
        return GroupedRecipe.of(base, groups, ws);
    }

    private static Object call(Object o, String name) {
        try {
            Method m = o.getClass().getMethod(name);
            return m.invoke(o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Object> elements(Object raw) {
        List<Object> out = new ArrayList<>();
        if (raw == null) return out;
        if (raw.getClass().isArray()) {
            int n = Array.getLength(raw);
            for (int i = 0; i < n; i++) out.add(Array.get(raw, i));
        } else if (raw instanceof Iterable<?> it) {
            for (Object o : it) out.add(o);
        }
        return out;
    }

    private static ItemStack[] toStacks(Object v) {
        if (v == null) return null;
        if (v instanceof ItemStack is) return new ItemStack[]{is};
        if (v instanceof ItemStack[] arr) return arr;
        if (v instanceof Iterable<?> it) {
            List<ItemStack> l = new ArrayList<>();
            for (Object o : it) if (o instanceof ItemStack is) l.add(is);
            return l.isEmpty() ? null : l.toArray(new ItemStack[0]);
        }
        return null;
    }
}
