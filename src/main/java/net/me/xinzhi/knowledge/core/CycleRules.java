package net.me.xinzhi.knowledge.core;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 周期与识别名单（腐竹 2026-11-18）：
 * 「普通改为1000tick周期，无尽为20tick。最好能加入名单功能，让腐竹能制定机器识别和配方」。
 *
 * <p>文件：plugins/XinZhiKnowledge/cycles.yml
 * <pre>
 * default-ticks: 1000        # 普通机器跑完一次配方的周期（tick）
 * addons:                    # 按附属覆盖（大小写不敏感，包含即可）
 *   InfinityExpansion: 20
 * machines:                  # 按机器 id 覆盖（最高优先）
 *   LOGITECH_REVERSE_GENERATOR: 20
 * recipes:                   # 预留：给某台机器的某条配方单独定周期
 *   LOGITECH_XXX:
 *     # 产物 id: tick
 * whitelist: []              # 额外的「按名单识别」机器 id（A=A / 模板驱动 / 模拟室这类扫不出配方的）
 * </pre>
 * 合成机用的周期取 {@code max(原配方 tick, 规则 tick)}，不会让原来就更慢的配方变快。
 */
public final class CycleRules {

    private static int defaultTicks = 1000;
    private static final Map<String, Integer> BY_ADDON = new HashMap<>();
    private static final Map<String, Integer> BY_MACHINE = new HashMap<>();
    private static final Set<String> WHITELIST = new HashSet<>();

    private CycleRules() { }

    /** 启动/重载时调用：没有文件就写一份带注释的默认文件。 */
    public static void load(JavaPlugin plugin) {
        defaultTicks = 1000;
        BY_ADDON.clear();
        BY_MACHINE.clear();
        WHITELIST.clear();
        try {
            File f = new File(plugin.getDataFolder(), "cycles.yml");
            if (!f.exists()) {
                writeDefault(f);
            }
            FileConfiguration c = YamlConfiguration.loadConfiguration(f);
            defaultTicks = Math.max(1, c.getInt("default-ticks", 1000));
            if (c.isConfigurationSection("addons")) {
                for (String k : c.getConfigurationSection("addons").getKeys(false)) {
                    BY_ADDON.put(k.toLowerCase(Locale.ROOT), Math.max(1, c.getInt("addons." + k, defaultTicks)));
                }
            }
            if (c.isConfigurationSection("machines")) {
                for (String k : c.getConfigurationSection("machines").getKeys(false)) {
                    BY_MACHINE.put(k.toUpperCase(Locale.ROOT), Math.max(1, c.getInt("machines." + k, defaultTicks)));
                }
            }
            for (String id : c.getStringList("whitelist")) {
                if (id != null && !id.isBlank()) WHITELIST.add(id.trim().toUpperCase(Locale.ROOT));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("cycles.yml 读取失败，按默认（普通 1000 tick / 无尽 20 tick）跑：" + t);
            if (BY_ADDON.isEmpty()) BY_ADDON.put("infinity", 20);
        }
        if (BY_ADDON.isEmpty()) BY_ADDON.put("infinity", 20);
    }

    private static void writeDefault(File f) {
        try {
            java.nio.file.Files.createDirectories(f.getParentFile().toPath());
            java.nio.file.Files.writeString(f.toPath(), String.join("\n",
                    "# 知识合成机的配方周期（跑完一次要多少 tick）。取 max(原配方 tick, 这里的值)。",
                    "default-ticks: 1000",
                    "# 按附属覆盖（用附属名，大小写不敏感，包含匹配）",
                    "addons:",
                    "  InfinityExpansion: 20",
                    "# 按机器 id 覆盖（最高优先）",
                    "machines: {}",
                    "# 额外按名单识别的机器 id（A=A 自输出、模板驱动、模拟室这类扫不出配方的机器）",
                    "whitelist: []",
                    ""), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    /** 这台机器跑完一次配方要多少 tick。 */
    public static int ticksFor(SlimefunItem sf, int nativeTicks) {
        int rule = defaultTicks;
        if (sf != null) {
            Integer byId = BY_MACHINE.get(sf.getId().toUpperCase(Locale.ROOT));
            if (byId != null && byId < rule) rule = byId;
            String addon = addonName(sf);
            if (addon != null) {
                String low = addon.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, Integer> e : BY_ADDON.entrySet()) {
                    if (low.contains(e.getKey()) && e.getValue() < rule) rule = e.getValue();
                }
            }
        }
        return Math.max(1, Math.max(nativeTicks, rule));
    }

    private static String addonName(SlimefunItem sf) {
        try {
            SlimefunAddon a = sf.getAddon();
            if (a != null && a.getName() != null) return a.getName();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 腐竹自己在 cycles.yml 里加的「按名单识别」机器。 */
    public static boolean whitelisted(SlimefunItem sf) {
        if (sf == null || WHITELIST.isEmpty()) return false;
        String id = sf.getId().toUpperCase(Locale.ROOT);
        if (WHITELIST.contains(id)) return true;
        for (String w : WHITELIST) {
            if (w.length() > 3 && id.contains(w)) return true;
        }
        return false;
    }
}
