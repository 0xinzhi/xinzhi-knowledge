package net.me.xinzhi.knowledge.util;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发电量探测（J/tick）。
 *
 * <p>腐竹 2026-11-08 第 2 条：「读不到就不给」—— 探不到就返回 {@code known = false}，
 * 机器不发电也不瞎猜，状态卡直接说「读不到这台机器的发电量」。
 *
 * <p>只认「产出」语义的名字，绝不拿 energyConsumption 之类当发电量：
 * 先找 getEnergyProduction / getGeneratedEnergy 这类显式方法，再找同名字段；
 * 只有类名本身像发电机时才退到 energyPerTick / energy 这类泛称。
 */
public final class PowerProbe {

    public static final class Info {
        public final long perTick;
        public final boolean known;
        public final String source;

        Info(long perTick, boolean known, String source) {
            this.perTick = perTick;
            this.known = known;
            this.source = source;
        }
    }

    private static final Map<String, Info> CACHE = new ConcurrentHashMap<>();

    private static final String[] METHODS = {
            "getEnergyProduction", "getGeneratedEnergy", "getEnergyOutput", "getEnergyGeneration",
            "getPowerOutput", "getPowerGeneration", "getProductionRate", "getGenerationRate"
    };

    private static final String[] FIELDS = {
            "energyProduction", "generatedEnergy", "energyOutput", "energyGeneration",
            "powerOutput", "powerGeneration", "productionRate", "generationRate"
    };

    private static final String[] GENERIC_METHODS = {
            "getEnergyPerTick", "getProduction", "getGeneration",
            // 太阳能（Slimefun SolarGenerator / RSC CustomSolarGenerator）：只暴露 day/night
            "getDayEnergy"
    };

    private static final String[] GENERIC_FIELDS = {
            "energyPerTick", "energy", "production", "generation",
            // RSC 的 CustomEnergyGenerator（脚本发电机）：只暴露 defaultOutput 这个 int
            "defaultOutput"
    };

    private static final String[] GEN_WORDS = {
            "Generator", "Reactor", "Panel", "Turbine", "Dynamo", "Solar", "Windmill", "Generatorr"
    };

    private PowerProbe() { }

    public static Info of(SlimefunItem sf) {
        if (sf == null) return new Info(0L, false, "");
        return CACHE.computeIfAbsent(sf.getId(), k -> probe(sf));
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static Info probe(SlimefunItem sf) {
        Class<?> c = sf.getClass();
        for (String name : METHODS) {
            Method m = method(c, name);
            if (m == null) continue;
            Long v = read(m, sf);
            if (v != null && v > 0) return new Info(v, true, "方法 " + name);
        }
        for (String name : FIELDS) {
            Long v = field(c, sf, name);
            if (v != null && v > 0) return new Info(v, true, "字段 " + name);
        }
        if (looksLikeGenerator(c)) {
            for (String name : GENERIC_METHODS) {
                Method m = method(c, name);
                if (m == null) continue;
                Long v = read(m, sf);
                if (v != null && v > 0) return new Info(v, true, "方法 " + name);
            }
            for (String name : GENERIC_FIELDS) {
                Long v = field(c, sf, name);
                if (v != null && v > 0) return new Info(v, true, "字段 " + name);
            }
        }
        return new Info(0L, false, "");
    }

    private static boolean looksLikeGenerator(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getSimpleName();
            for (String w : GEN_WORDS) if (n.contains(w)) return true;
        }
        return false;
    }

    private static Method method(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                if (m.getParameterCount() != 0) continue;
                Class<?> r = m.getReturnType();
                if (r == int.class || r == long.class || r == Integer.class || r == Long.class) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Long read(Method m, Object target) {
        try {
            Object v = m.invoke(target);
            return v instanceof Number n ? n.longValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long field(Class<?> c, Object target, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                Class<?> t = f.getType();
                if (t != int.class && t != long.class && t != Integer.class && t != Long.class) continue;
                f.setAccessible(true);
                Object v = f.get(target);
                return v instanceof Number n ? n.longValue() : null;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
