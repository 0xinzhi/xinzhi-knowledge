package net.me.xinzhi.knowledge.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 全局配置。数值口径（腐竹 2026-11-07）：
 * <ul>
 *   <li>基准效率 1：每 tick 完成「机器数 ÷ 配方 tick」次；</li>
 *   <li>电量缓冲、整机不消耗名单都可改；</li>
 *   <li>整机不消耗名单不写死在本附属里 —— 由服主填机器 id。</li>
 * </ul>
 */
public final class Settings {

    private static Settings instance;

    private final int energyCapacity;
    private final double baseEfficiency;
    private final int maxCraftsPerTick;
    private final int energyPerTickWhenUnknown;
    private final boolean useConsumeRules;
    private final int identifyEveryTicks;
    private final int idleSkipTicks;
    private final boolean skipMultiblock;
    /** 跟随模式（上方真发电机）的放大倍率：腐竹 2026-11-08 从 1.0 降到 0.1，嫌太强势 */
    private final double followMultiplier;
    /** 接着电网时，缓冲满了要不要也把这一 tick 的电存进虚拟电量（腐竹要求「一直发电就要能看到攒」） */
    private final boolean virtualWhenOnNet;

    /** 编码机：编一次要多少 tick（腐竹 2026-11-14 没定，先按 200 tick = 100 秒） */
    private final int encoderCraftTicks;
    /** 编码机：编码时每 tick 扣多少 J */
    private final int encoderEnergyPerTick;
    /** 生成器卡接进知识生成器后，每 tick 扣多少 J */
    private final int cardEnergyPerTick;
    /** 基础物品表门槛：等价附属锚点价 < 这个值才算基础物品（腐竹 2026-11-14：5000） */
    private final double baseItemMaxEmc;
    /**
     * 基础物品表是否并入「生物掉落物兜底价」那一套（腐竹 2026-11-15：「把等价中获取生物掉落物
     * 那一套加入基础物品」）。只补锚点表里没有的物品，概率档位与等价附属一致。
     */
    private final boolean baseItemMobDrops;
    /**
     * 基础物品表是否并入「方块掉落兜底价」（腐竹 2026-11-15：「还有方块掉落」）。
     * RSC 子附属常只写 drop_from / drop_chance，没有配方；口径与等价附属一致。
     */
    private final boolean baseItemBlockDrops;
    /**
     * 地理资源（没有合成配方、由矿机产出）的默认价（腐竹 2026-11-15：「和地理资源」）。
     * 与等价附属 {@code price.geo-resource-default} 同一口径，默认 30；&lt;= 0 = 不并入。
     */
    private final double baseItemGeoPrice;
    /** 编码机加速按钮：一次性扣多少 J 直接把进度推满（腐竹 2026-11-14：5 万 J） */
    private final long encoderFastCost;
    /** 编码生成器（卡 → 产物）每 tick 扣多少 J（腐竹 2026-11-14：耗电 0） */
    private final int cardMachineEnergyPerTick;


    /** 每个机器 id 的「配方 tick 数」覆盖（配置 scan.overrides.<id>.ticks） */
    private final java.util.Map<String, Integer> overrideTicks = new java.util.HashMap<>();

    /** 每个机器 id 的「一次产出多少个」覆盖（配置 scan.overrides.<id>.output-amount） */
    private final java.util.Map<String, Integer> overrideOutput = new java.util.HashMap<>();

    /**
     * 每个机器 id 的「当什么机器用」覆盖（配置 scan.overrides.&lt;id&gt;.mode）。
     * <p>{@code generator} = 输入只用来认配方、机器自己不扣（卡片不消耗的那类）；
     * 不写 = 自动判（按配方里的扣料量）。
     */
    private final java.util.Map<String, String> overrideMode = new java.util.HashMap<>();

    public Settings(FileConfiguration c) {
        this.energyCapacity = Math.max(0, c.getInt("machine.energy-capacity", 2000000000));
        this.baseEfficiency = Math.max(0.0, c.getDouble("machine.base-efficiency", 1.0));
        this.maxCraftsPerTick = Math.max(1, c.getInt("machine.max-crafts-per-tick", 256));
        // 兼容老键：energy-per-craft-when-unknown（原来按「每次配方」记，语义已改）
        this.energyPerTickWhenUnknown = Math.max(0,
                c.getInt("machine.energy-per-tick-when-unknown",
                        c.getInt("machine.energy-per-craft-when-unknown", 0)));
        this.useConsumeRules = c.getBoolean("scan.use-consume-rules",
                c.getBoolean("machines.no-consume-machines", true));
        // 识别节流（腐竹 2026-11-07）：换机器后可以晚几 tick 才认配方，但合成速率不许受影响
        this.identifyEveryTicks = Math.max(1, c.getInt("machine.identify-every-ticks", 4));
        // 已经判定「跑不动」（缺料/满仓/没电）的机器，隔几个 tick 再重算一次
        this.idleSkipTicks = Math.max(0, c.getInt("machine.idle-skip-ticks", 2));
        // 「卡片机」（无尽生物模拟室这类）：配方不在机器上，在卡片自己的产物表里，一次给一件
        // 多方块机器的核心单独拿出来是跑不了的（结构在别处），默认不读它的配方
        this.skipMultiblock = c.getBoolean("scan.skip-multiblock", true);
        this.followMultiplier = Math.max(0.0, c.getDouble("power.follow-multiplier", 0.1));
        this.virtualWhenOnNet = c.getBoolean("power.virtual-when-on-net", true);
        this.encoderCraftTicks = Math.max(1, c.getInt("encoder.craft-ticks", 200));
        this.encoderEnergyPerTick = Math.max(0, c.getInt("encoder.energy-per-tick", 40));
        this.cardEnergyPerTick = Math.max(0, c.getInt("generator.card-energy-per-tick", 20));
        this.baseItemMaxEmc = Math.max(1.0, c.getDouble("encoder.base-item-max-emc", 5000));
        this.baseItemMobDrops = c.getBoolean("encoder.base-item-mob-drops", true);
        this.baseItemBlockDrops = c.getBoolean("encoder.base-item-block-drops", true);
        this.baseItemGeoPrice = Math.max(0.0, c.getDouble("encoder.base-item-geo-price", 30.0));
        this.encoderFastCost = Math.max(0L, c.getLong("encoder.fast-cost", 50000L));
        this.cardMachineEnergyPerTick = Math.max(0, c.getInt("card-machine.energy-per-tick", 0));

        // 个别机器的配方对象里没有时间/数量（比如 Supreme 的静态配方表），只能由服主补
        org.bukkit.configuration.ConfigurationSection sec = c.getConfigurationSection("scan.overrides");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String id = net.me.xinzhi.knowledge.recipe.ConsumeRules.norm(key);
                if (id == null) continue;
                int t = sec.getInt(key + ".ticks", -1);
                int a = sec.getInt(key + ".output-amount", -1);
                String m = sec.getString(key + ".mode");
                if (t > 0) overrideTicks.put(id, t);
                if (a > 0) overrideOutput.put(id, a);
                if (m != null && !m.isBlank()) overrideMode.put(id, m.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    public static Settings get() { return instance; }
    public static void set(Settings s) { instance = s; }

    /** generator / recipe / null（null = 自动判） */
    public String overrideMode(String id) {
        return id == null ? null : overrideMode.get(net.me.xinzhi.knowledge.recipe.ConsumeRules.norm(id));
    }

    public boolean skipMultiblock() { return skipMultiblock; }


    /** 跟随模式的放大倍率（0.1 = 只拿上方那台发电量的 10%） */
    public double followMultiplier() { return followMultiplier; }

    public int energyCapacity() { return energyCapacity; }
    public double baseEfficiency() { return baseEfficiency; }
    public int maxCraftsPerTick() { return maxCraftsPerTick; }
    public int energyPerTickWhenUnknown() { return energyPerTickWhenUnknown; }
    public boolean useConsumeRules() { return useConsumeRules; }
    public int identifyEveryTicks() { return identifyEveryTicks; }
    public int idleSkipTicks() { return idleSkipTicks; }

    /** 这台机器的配方耗时覆盖；没有配置返回 -1。 */
    public int overrideTicks(String machineId) {
        Integer v = machineId == null ? null
                : overrideTicks.get(net.me.xinzhi.knowledge.recipe.ConsumeRules.norm(machineId));
        return v == null ? -1 : v;
    }

    /** 这台机器一次产出数量的覆盖；没有配置返回 -1。 */
    public int overrideOutputAmount(String machineId) {
        Integer v = machineId == null ? null
                : overrideOutput.get(net.me.xinzhi.knowledge.recipe.ConsumeRules.norm(machineId));
        return v == null ? -1 : v;
    }

    public int encoderCraftTicks() { return encoderCraftTicks; }
    public int encoderEnergyPerTick() { return encoderEnergyPerTick; }
    public int cardEnergyPerTick() { return cardEnergyPerTick; }
    public double baseItemMaxEmc() { return baseItemMaxEmc; }
    public boolean baseItemMobDrops() { return baseItemMobDrops; }
    public boolean baseItemBlockDrops() { return baseItemBlockDrops; }
    public double baseItemGeoPrice() { return baseItemGeoPrice; }
    public long encoderFastCost() { return encoderFastCost; }
    public int cardMachineEnergyPerTick() { return cardMachineEnergyPerTick; }

    /** 接电网时缓冲满了也攒虚拟电量（默认 true；电网取用与否我们看不到，靠这个开关取舍） */
    public boolean virtualWhenOnNet() {
        return virtualWhenOnNet;
    }
}
