package net.me.xinzhi.knowledge.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每台机器的 tick 耗时统计（只累加两个 long，几乎零成本）。
 *
 * <p>用途：腐竹嫌机器「占性能」时，光凭感觉说不清 —— 让 {@code /xzk info} 直接报
 * 「平均每次多少毫秒、调用了多少次」。改完节流参数前后对着看，而不是互相猜。
 */
public final class Perf {

    private Perf() { }

    /** name → {总纳秒, 调用次数} */
    private static final Map<String, long[]> STATS = new ConcurrentHashMap<>();

    public static void add(String name, long nanos) {
        long[] v = STATS.computeIfAbsent(name, k -> new long[2]);
        v[0] += nanos;
        v[1]++;
    }

    public static void reset() {
        STATS.clear();
    }

    public static List<String> report() {
        List<String> out = new ArrayList<>();
        if (STATS.isEmpty()) {
            out.add("§7还没有 tick 记录。");
            return out;
        }
        List<Map.Entry<String, long[]>> list = new ArrayList<>(STATS.entrySet());
        list.sort(Comparator.comparingLong(e -> -e.getValue()[0]));
        for (Map.Entry<String, long[]> e : list) {
            long[] v = e.getValue();
            if (v[1] <= 0) continue;
            double avgMs = v[0] / 1_000_000.0 / v[1];
            out.add(String.format("§7%s §8· §f%.4f §7ms/次 §8(共 §f%d §8次)",
                    e.getKey(), avgMs, v[1]));
        }
        return out;
    }
}
