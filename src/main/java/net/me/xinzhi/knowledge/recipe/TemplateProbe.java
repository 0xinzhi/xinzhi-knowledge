package net.me.xinzhi.knowledge.recipe;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * 「模板生成器」的静态判据：这类机器<b>有一个放处理器/模板的槽</b>，配方里的输入只是认配方用的。
 *
 * <p>为什么需要它（腐竹 2026-11-08）：「逻辑附属的生成器，有关模板的生成器也被识别成配方机器了」。
 * 逻辑工艺的 {@code MMGenerator} / {@code SMGenerator}（种植机、矿机那一族）都继承
 * {@code AbstractTransformer}，里面有 {@code protected int PROCESSOR_SLOT} ——
 * 处理器槽里那件物品是「你要跑哪一种」的开关，机器不会扣它。
 *
 * <p>判据只看<b>形状</b>（类层级里有没有 int/int[] 字段，名字里带 PROCESSOR 或 TEMPLATE），
 * 不点任何附属的类名或机器 id —— 换个附属只要用同一套叫法就一样认得出。
 */
public final class TemplateProbe {

    private TemplateProbe() { }

    public static boolean isTemplateMachine(SlimefunItem sf) {
        if (sf == null) return false;
        for (Class<?> c = sf.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> type = f.getType();
                if (type != int.class && type != int[].class) continue;
                String n = f.getName().toUpperCase(Locale.ROOT);
                if (n.contains("PROCESSOR") || n.contains("TEMPLATE")) return true;
            }
        }
        return false;
    }
}
