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
 * 那个槽是「你要跑哪一种」的开关，机器不会扣它。
 *
 * <p>但 <b>PROCESSOR_SLOT 不是模板机的充分条件</b>（腐竹 2026-11-15：「LOGITECH_SEQ_CONSTRUCTOR
 * 这两个都被识别为了不消耗，其实是消耗的」）：逻辑工艺的序列机
 * {@code AbstractSequenceProcessor} 也有 {@code PROCESSOR_SLOT}，可它只是<b>进度显示</b>槽，
 * 真正扣料走的是 {@code processor}（{@code MachineProcessor<SequenceCraftingOperation>}）里
 * 那串 {@code ItemConsumer}。判据因此改成：
 * <ul>
 *   <li>有 {@code TEMPLATE} 类型字段 ⇒ 模板机（这条不变）；</li>
 *   <li>只有 {@code PROCESSOR} 字段时，再看类层级里有没有<b>运算器对象</b>
 *       （字段类型名含 {@code Processor} / {@code Operation}）：
 *       有 ⇒ 这台机器会把料吃进工序里 ⇒ <b>不是</b>模板机；
 *       没有 ⇒ 和 {@code AbstractTransformer} 一样只是拿槽位做开关 ⇒ 模板/生成器。</li>
 * </ul>
 *
 * <p>判据仍然只看<b>形状</b>，不点任何附属的类名或机器 id。
 */
public final class TemplateProbe {

    private TemplateProbe() { }

    public static boolean isTemplateMachine(SlimefunItem sf) {
        if (sf == null) return false;
        boolean processorSlot = false;
        boolean operationProcessor = false;
        for (Class<?> c = sf.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                Class<?> type = f.getType();
                String tn = type.getSimpleName();
                if (tn.contains("Processor") || tn.contains("Operation")) operationProcessor = true;

                if (Modifier.isStatic(f.getModifiers())) continue;
                if (type != int.class && type != int[].class) continue;
                String n = f.getName().toUpperCase(Locale.ROOT);
                if (n.contains("TEMPLATE")) return true;
                if (n.contains("PROCESSOR")) processorSlot = true;
            }
        }
        // 有「处理器槽」但没有「工序对象」：那槽只是放开关/进度条的，配方输入不扣
        return processorSlot && !operationProcessor;
    }
}
