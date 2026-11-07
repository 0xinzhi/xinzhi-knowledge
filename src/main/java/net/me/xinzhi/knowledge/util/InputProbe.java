package net.me.xinzhi.knowledge.util;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.interfaces.InventoryBlock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 「这台机器自己有没有输入槽」。
 *
 * <p>知识发电机用它判断「燃料读不清」：机器声明了输入槽、却扫不出耗料配方，
 * 说明燃料是它自己在 tick 里从界面拿的（逻辑工艺那批芯片/卡机），
 * 知识机器扣不了 ⇒ 不发电（腐竹 2026-11-08「读不到就不给」）。
 */
public final class InputProbe {

    private InputProbe() { }

    public static boolean hasInputSlots(SlimefunItem sf) {
        if (sf == null) return false;
        if (sf instanceof InventoryBlock ib) {
            try {
                int[] in = ib.getInputSlots();
                if (in != null && in.length > 0) return true;
            } catch (Throwable ignored) {
            }
        }
        try {
            Method m = findMethod(sf.getClass(), "getInputSlots");
            if (m != null) {
                Object v = m.invoke(sf);
                if (v instanceof int[] arr && arr.length > 0) return true;
            }
        } catch (Throwable ignored) {
        }
        for (Class<?> k = sf.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField("inputSlots");
                if (f.getType() != int[].class) continue;
                f.setAccessible(true);
                Object v = f.get(sf);
                if (v instanceof int[] arr && arr.length > 0) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                if (m.getParameterCount() == 0 && m.getReturnType() == int[].class) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
