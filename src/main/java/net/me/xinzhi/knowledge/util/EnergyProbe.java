package net.me.xinzhi.knowledge.util;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import net.me.xinzhi.knowledge.config.Settings;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 读第三方机器「每 <b>tick</b> 耗多少电」。
 *
 * <h2>⚠ 语义：每 tick，不是每次配方（2026-11-07 从 Slimefun 源码确认）</h2>
 * {@code AContainer#takeCharge(Location)} 在<b>每次 tick</b> 扣一次
 * {@code getEnergyConsumption()}，一次配方要跑 {@code ticks} 个 tick
 * ⇒ <b>一次配方耗电 = 每 tick 耗电 × 配方 tick 数</b>。
 * 原来本附属把 {@code getEnergyConsumption()} 当「J/次」用，等于少扣了 {@code ticks} 倍。
 *
 * <h2>为什么要多路探测</h2>
 * {@code getEnergyConsumption()} 只有 {@code AContainer} 有；
 * 参与电网靠 {@code EnergyNetComponent}（接口上<b>没有</b>耗电值）。
 * 别的附属各有各的存法（字段 {@code energyConsumedPerTick}、方法 {@code getEnergyCost}…），
 * 所以按「方法 → 字段」逐个试，全试不到才认「未知」。
 *
 * <p>⚠ <b>未知 ≠ 免费</b>：未知时按配置 {@code machine.energy-per-tick-when-unknown} 兜底，
 * 并且 {@link Info#known} 为 {@code false}，界面会写「能耗未知」——不再静默当成不耗电。
 *
 * <p>本类只缓存 {@link Info}（按机器 id），不持有任何机器实例。
 */
public final class EnergyProbe {

    private static final String[] METHODS = {
            "getEnergyConsumption", "getEnergyCost", "getPowerConsumption",
            "getEnergyPerTick", "getEnergyUsage", "getEnergyPerOperation" };

    private static final String[] FIELDS = {
            "energyConsumedPerTick", "energyConsumption", "energyCost",
            "powerConsumption", "energyPerTick", "energyUsage" };

    private static final ConcurrentHashMap<String, Info> CACHE = new ConcurrentHashMap<>();

    private EnergyProbe() {
    }

    /** 探测结果。 */
    public static final class Info {
        /** 每 tick 耗电（未知时是配置兜底值） */
        public final int perTick;
        /** 是不是真的读到了（false = 按配置兜底） */
        public final boolean known;
        /** 这台机器参不参与电网（{@code EnergyNetComponent}） */
        public final boolean networked;
        /** 一次配方耗电 = perTick × 配方 tick（由 {@link #perCraft(int)} 算） */
        public final int rawPerTick;

        Info(int perTick, boolean known, boolean networked, int rawPerTick) {
            this.perTick = perTick;
            this.known = known;
            this.networked = networked;
            this.rawPerTick = rawPerTick;
        }

        /** 一次配方要多少电。 */
        public int perCraft(int ticks) {
            return Math.max(0, perTick) * Math.max(1, ticks);
        }
    }

    public static Info of(SlimefunItem sf) {
        if (sf == null) return new Info(0, true, false, 0);
        String id = sf.getId();
        if (id != null) {
            Info cached = CACHE.get(id);
            if (cached != null) return cached;
        }
        Info info = probe(sf);
        if (id != null) CACHE.put(id, info);
        return info;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static Info probe(SlimefunItem sf) {
        boolean networked = sf instanceof EnergyNetComponent;

        // 0. 明确「这台机器不参与充电」→ 不耗电（AContainer#isChargeable）
        Object chargeable = ReflectUtils.invokeNoArg(sf, "isChargeable");
        if (chargeable instanceof Boolean b && !b) {
            return new Info(0, true, networked, 0);
        }

        Integer positive = null;
        boolean sawZero = false;
        for (String name : METHODS) {
            Object v = ReflectUtils.invokeNoArg(sf, name);
            Integer i = asInt(v);
            if (i == null) continue;
            if (i > 0) { positive = i; break; }
            if (i == 0) sawZero = true;
        }
        if (positive == null) {
            for (String name : FIELDS) {
                Object v = ReflectUtils.getFieldValueRecursive(sf, name);
                Integer i = asInt(v);
                if (i == null) continue;
                if (i > 0) { positive = i; break; }
                if (i == 0) sawZero = true;
            }
        }
        if (positive != null) return new Info(positive, true, networked, positive);
        if (sawZero) return new Info(0, true, networked, 0);

        // 「每批耗电」型：RSC 的 CustomMaterialGenerator 用 per（一批要多少焦耳）+ tickRate（几刻一批）
        if (positive == null && !sawZero) {
            Integer per = asInt(ReflectUtils.getFieldValueRecursive(sf, "per"));
            Integer rate = asInt(ReflectUtils.getFieldValueRecursive(sf, "tickRate"));
            if (per != null && per > 0 && rate != null && rate > 0) {
                return new Info((int) Math.ceil(per / (double) rate), true, networked, per);
            }
        }

        // 读不到：按配置兜底，标成「未知」
        Settings s = Settings.get();
        int fallback = s == null ? 0 : s.energyPerTickWhenUnknown();
        return new Info(Math.max(0, fallback), false, networked, 0);
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof Boolean b) return b ? null : 0;
        return null;
    }
}
