package net.me.xinzhi.knowledge.price;

import io.github.thebusybiscuit.slimefun4.api.geo.GEOResource;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.RandomMobDrop;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import net.me.xinzhi.knowledge.config.Settings;
import net.me.xinzhi.knowledge.core.ItemKey;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「基础物品表」= 等价附属基础定价表里 <b>0 &lt; EMC &lt; 门槛(默认 5000)</b> 的物品。
 *
 * <h2>来源（低 → 高，后者只补前者的缺口）</h2>
 * <ol>
 *   <li>jar 内快照 {@code base-items.tsv}（从等价附属 prices-builtin*.tsv 导出，800 条）；</li>
 *   <li>服务器上 {@code plugins/XinZhiExchange/prices.yml}（等价附属首次启动导出的全表，
 *       之后以它为准）；</li>
 *   <li><b>生物掉落物兜底价</b>（腐竹 2026-11-15：「把等价中获取生物掉落物那一套加入基础物品」）——
 *       等价附属那套口径：读粘液的 {@code getRegistry().getMobDrops()}，按掉落概率给价
 *       （≥50% → 5、≥20% → 20、≥10% → 100、≥5% → 200、更低 → 500，读不到概率 → 60），
 *       同一物品被多种生物掉落时取<b>最便宜</b>的那条。{@code encoder.base-item-mob-drops: false} 可以关。</li>
 *   <li><b>方块掉落兜底价</b>（腐竹 2026-11-15：「还有方块掉落」）——RSC 子附属常常只写
 *       {@code drop_from / drop_chance}，没有配方：能反复刷的方块按概率 5/15/30，不可再生 → 100。
 *       {@code encoder.base-item-block-drops: false} 可以关。</li>
 *   <li><b>地理资源默认价</b>（腐竹 2026-11-15：「和地理资源」）——矿机产出、没有配方，
 *       与等价附属同一口径按 {@code encoder.base-item-geo-price}（默认 30）补价，0 = 关。</li>
 * </ol>
 *
 * <p>只在锚点表<b>没有</b>这个物品时才补（垫底价，不许盖掉定价表）；几种兜底价之间取最便宜的
 * （越容易拿到越便宜，与等价附属一致）。
 *
 * <p>⚠ 只认「锚点价」，推导出来的下游价不算 —— 否则几乎所有东西都能编码。
 * 读不到等价附属（没有那个 yml）时退化成纯快照，机器照常能用。
 */
public final class BaseItems {

    private static final Map<ItemKey, Double> EMC = new ConcurrentHashMap<>();
    private static volatile double threshold = 5000;
    private static volatile String source = "未加载";

    /** 生物掉落表是否已经读到过（粘液/附属注册得晚，onEnable 时通常是空的） */
    private static volatile boolean mobDropsLoaded = false;
    /** 已补进来的生物掉落兜底条数 */
    private static volatile int mobDropCount = 0;
    /** 地理资源是否已经读到过（同 MOB_DROP，附属注册得晚） */
    private static volatile boolean geoLoaded = false;
    private static volatile int geoCount = 0;
    /** 方块掉落兜底价是否已经补过 */
    private static volatile boolean blockDropsLoaded = false;
    /** 方块掉落探测已经试了几轮（物品注册不全时别无限重试） */
    private static int blockDropsAttempts = 0;
    private static volatile int blockDropCount = 0;

    /** reload 时记住的开关，延迟补读时要用 */
    private static volatile double geoPrice = 30.0;
    private static volatile boolean blockDropsWanted = true;

    /** 由「掉落兜底价」补进来的物品 —— 同一物品两边都给价时取更便宜的那条 */
    private static final Set<ItemKey> dropKeys = ConcurrentHashMap.newKeySet();

    private BaseItems() { }

    public static void reload(JavaPlugin plugin, double maxEmc) {
        reload(plugin, maxEmc, true, true, 30.0);
    }

    /**
     * 重读基础物品表。
     *
     * @param maxEmc          门槛：0 &lt; EMC &lt; 这个值才算基础物品
     * @param includeMobDrops 是否并入生物掉落兜底价
     * @param includeBlockDrop 是否并入方块掉落兜底价
     * @param geoDefault      地理资源默认价（&lt;= 0 = 不并入）
     */
    public static void reload(JavaPlugin plugin, double maxEmc, boolean includeMobDrops,
                              boolean includeBlockDrop, double geoDefault) {
        threshold = maxEmc > 0 ? maxEmc : 5000;
        geoPrice = geoDefault > 0 ? geoDefault : 0;
        blockDropsWanted = includeBlockDrop;
        EMC.clear();
        dropKeys.clear();
        int fromJar = loadBundled(plugin);
        int fromYml = loadExchangeYml(plugin);
        int fromMob = includeMobDrops ? loadMobDrops(plugin) : 0;
        mobDropsLoaded = fromMob >= 0;
        mobDropCount = Math.max(0, fromMob);
        int fromGeo = geoPrice > 0 ? loadGeoDefaults(geoPrice) : 0;
        geoLoaded = fromGeo >= 0;
        geoCount = Math.max(0, fromGeo);
        int fromBlock = includeBlockDrop ? loadBlockDrops(plugin) : 0;
        blockDropsLoaded = fromBlock >= 0;
        blockDropCount = Math.max(0, fromBlock);
        blockDropsAttempts = fromBlock >= 0 ? 0 : 1;
        source = "内置快照 " + fromJar + " 条" + (fromYml > 0 ? " + 等价附属 prices.yml " + fromYml + " 条" : "")
                + (fromMob > 0 ? " + 生物掉落兜底 " + fromMob + " 条" : "")
                + (fromBlock > 0 ? " + 方块掉落兜底 " + fromBlock + " 条" : "")
                + (fromGeo > 0 ? " + 地理资源默认 " + fromGeo + " 条" : "");
        plugin.getLogger().info("基础物品表已加载：" + source + "，门槛 EMC < " + (long) threshold);
    }

    private static int loadBundled(JavaPlugin plugin) {
        int n = 0;
        InputStream raw = null;
        try {
            raw = plugin.getResource("base-items.tsv");
        } catch (Throwable ignored) {
            return 0;
        }
        if (raw == null) return 0;
        try (InputStream in = raw) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                ItemKey k = ItemKey.fromStored(line.substring(0, tab).trim());
                if (k == null) continue;
                try {
                    EMC.put(k, Double.parseDouble(line.substring(tab + 1).trim()));
                    n++;
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 等价附属导出的 prices.yml：vanilla / slimefun / manual(里面再分 vanilla|slimefun) */
    private static int loadExchangeYml(JavaPlugin plugin) {
        File f;
        try {
            f = new File(exchangeDir(plugin), "prices.yml");
        } catch (Throwable t) {
            return 0;
        }
        if (!f.isFile()) return 0;
        int n = 0;
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            n += readSection(yml.getConfigurationSection("vanilla"), "VANILLA:");
            n += readSection(yml.getConfigurationSection("slimefun"), "SF:");
            ConfigurationSection manual = yml.getConfigurationSection("manual");
            if (manual != null) {
                n += readSection(manual.getConfigurationSection("vanilla"), "VANILLA:");
                n += readSection(manual.getConfigurationSection("slimefun"), "SF:");
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private static int readSection(ConfigurationSection sec, String prefix) {
        if (sec == null) return 0;
        int n = 0;
        for (String key : sec.getKeys(false)) {
            double v;
            try {
                v = sec.getDouble(key, Double.NaN);
            } catch (Throwable t) {
                continue;
            }
            if (Double.isNaN(v)) continue;
            ItemKey k = ItemKey.fromStored(prefix + key.trim());
            if (k == null) continue;
            EMC.put(k, v);
            n++;
        }
        return n;
    }

    private static File exchangeDir(JavaPlugin plugin) {
        File plugins = plugin.getDataFolder().getParentFile();
        return new File(plugins, "XinZhiExchange");
    }

    // ------------------------------------------------------------------
    // 兜底价 1：生物掉落物（等价附属 MobDropPrices 同一套口径，腐竹 2026-10-26 定的档位）
    // ------------------------------------------------------------------

    /**
     * 只补缺口：锚点 / prices.yml 里已经有价的物品不动。
     * 同一物品被多种生物掉落、概率不同时取最便宜的那条。
     *
     * @return 新补进来的条数；{@code -1} = 掉落表还没注册好（读不到），可以稍后再试
     */
    private static int loadMobDrops(JavaPlugin plugin) {
        Map<EntityType, Set<ItemStack>> table;
        try {
            table = Slimefun.getRegistry().getMobDrops();
        } catch (Throwable t) {
            plugin.getLogger().warning("读取粘液生物掉落表失败：" + t);
            return -1;
        }
        if (table == null || table.isEmpty()) {
            // 粘液本体 + 各家附属是把 MOB_DROP 注册进来的，注册时刻晚于本插件 onEnable
            // ⇒ 这里为空是正常现象，交给 tryLoadExtras 定期补读
            return -1;
        }
        Map<ItemKey, Double> best = new LinkedHashMap<>();
        for (Map.Entry<EntityType, Set<ItemStack>> e : table.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            for (ItemStack stack : e.getValue()) {
                if (stack == null || stack.getType().isAir()) continue;
                ItemKey k;
                try {
                    k = ItemKey.of(stack);
                } catch (Throwable ignored) {
                    continue;
                }
                if (k == null) continue;
                double price = mobDropPrice(chanceOf(stack));
                if (!(price > 0)) continue;
                best.merge(k, price, Math::min);
            }
        }
        int n = putDropPrices(best);
        plugin.getLogger().info("生物掉落表：" + table.size() + " 种生物 / " + best.size()
                + " 种掉落物，新补 " + n + " 条。");
        return n;
    }

    /** 概率档位（≥ 该值生效，从高到低）与对应单价 —— 与等价附属一致 */
    private static final int[] MOB_TIERS = {50, 20, 10, 5};
    private static final double[] MOB_TIER_PRICES = {5.0, 20.0, 100.0, 200.0};
    /** 概率低于最后一档（< 5%）的价 */
    private static final double MOB_BELOW_TIER = 500.0;
    /** 读不到概率时的兜底价（腐竹：「兜底按 60 计算吧」） */
    private static final double MOB_DROP_UNKNOWN = 60.0;

    /** chance &lt; 0 = 读不到概率 */
    private static double mobDropPrice(int chance) {
        if (chance < 0) return MOB_DROP_UNKNOWN;
        for (int i = 0; i < MOB_TIERS.length; i++) {
            if (chance >= MOB_TIERS[i]) return MOB_TIER_PRICES[i];
        }
        return MOB_BELOW_TIER;
    }

    /** 掉落概率：实现了 {@link RandomMobDrop} 才有，返回 0~100 的百分比；否则 -1 */
    private static int chanceOf(ItemStack stack) {
        try {
            SlimefunItem item = SlimefunItem.getByItem(stack);
            if (item instanceof RandomMobDrop rmd) {
                return Math.max(0, Math.min(100, rmd.getMobDropChance()));
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 兜底价 2：破坏方块概率掉落（等价附属 BlockDropPrices 同一套口径，腐竹 2026-10-28）
    // ------------------------------------------------------------------

    /** 方块掉落定价规则（等价附属 {@code price.block-drop} 那段，读不到就用这里的默认值） */
    private record DropRules(int highChance, double highEmc, int midChance, double midEmc,
                             double lowEmc, double nonRenewableEmc, Set<String> renewable) {
        static DropRules defaults() {
            Set<String> rb = new LinkedHashSet<>();
            rb.add("NETHER_PORTAL");
            return new DropRules(30, 5.0, 5, 15.0, 30.0, 100.0, rb);
        }

        double priceFor(int chance, boolean renewableBlock) {
            if (!renewableBlock) return nonRenewableEmc;
            if (chance >= highChance) return highEmc;
            if (chance >= midChance) return midEmc;
            return lowEmc;
        }
    }

    /**
     * 扫 RSC（RykenSlimefunCustomizer）各附属 {@code items.yml} 里的 {@code drop_from} 声明给价。
     *
     * <p>口径：能反复刷的方块（{@code Material.isItem()} 或配置点名，如下界传送门）按概率分档
     * 5 / 15 / 30；挖了不掉回自身、也造不出来的（紫水晶母岩这类）按 100。
     *
     * @return 新补进来的条数；0 = 没装 RSC 或没人写 drop_from；{@code -1} = 粘液物品还没注册好，稍后再试
     */
    private static int loadBlockDrops(JavaPlugin plugin) {
        File pluginsDir;
        try {
            pluginsDir = plugin.getDataFolder().getParentFile();
        } catch (Throwable t) {
            return 0;
        }
        File addons = new File(new File(pluginsDir, "RykenSlimefunCustomizer"), "addons");
        if (!addons.isDirectory()) return 0;
        List<File> files = new ArrayList<>();
        collectItemYml(addons, files, 0);
        if (files.isEmpty()) return 0;
        Map<String, SlimefunItem> index = itemIndex();
        if (index.isEmpty()) return -1;
        Map<String, SlimefunItem> byName = itemNameIndex();
        DropRules rules = readDropRules(pluginsDir);
        Map<ItemKey, Double> best = new LinkedHashMap<>();
        boolean[] seen = {false};
        for (File f : files) {
            try {
                parseBlockDrops(f, index, byName, rules, best, seen);
            } catch (Throwable ignored) {
                // 单个附属的 items.yml 坏了不影响其它附属
            }
        }
        if (best.isEmpty()) {
            if (seen[0]) return -1;   // 写了 drop_from 但物品还没注册齐 ⇒ 稍后再试
            plugin.getLogger().info("方块掉落兜底价：读到 " + files.size() + " 份 items.yml，没有 drop_from 声明。");
            return 0;
        }
        int n = putDropPrices(best);
        plugin.getLogger().info("方块掉落兜底价：" + files.size() + " 份 items.yml，涉及物品 "
                + best.size() + " 个，新补 " + n + " 条。");
        return n;
    }

    private static void collectItemYml(File dir, List<File> out, int depth) {
        File[] es;
        try {
            es = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (es == null) return;
        for (File f : es) {
            if (f == null) continue;
            if (f.isDirectory()) {
                if (depth < 3) collectItemYml(f, out, depth + 1);
            } else if (f.getName().equalsIgnoreCase("items.yml")) {
                out.add(f);
            }
        }
    }

    private static void parseBlockDrops(File file, Map<String, SlimefunItem> index,
                                        Map<String, SlimefunItem> byName,
                                        DropRules rules, Map<ItemKey, Double> out, boolean[] seen) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(key);
            if (sec == null) continue;
            ConfigurationSection src = sec.isSet("drop_from") ? sec : sec.getConfigurationSection("item");
            if (src == null || !src.isSet("drop_from")) continue;
            String from = src.getString("drop_from");
            if (from == null || from.isBlank()) continue;
            seen[0] = true;
            Material mat = Material.matchMaterial(from.trim().toUpperCase(Locale.ROOT));
            boolean renewable = mat != null && (mat.isItem()
                    || rules.renewable().contains(mat.name().toUpperCase(Locale.ROOT)));
            int chance = Math.max(0, Math.min(100, src.getInt("drop_chance", 100)));
            double price = rules.priceFor(chance, renewable);
            if (!(price > 0)) continue;
            // 四种查法：小写索引（最稳）→ Slimefun 原样 id → 大写 id → 显示名兜底
            SlimefunItem sf = index.get(key.toLowerCase(Locale.ROOT));
            if (sf == null) sf = SlimefunItem.getById(key);
            if (sf == null) sf = SlimefunItem.getById(key.toUpperCase(Locale.ROOT));
            if (sf == null) {
                String name = plainName(src.getString("name"));
                if (name != null) sf = byName.get(name);
            }
            if (sf == null) continue;   // 物品没注册（附属没装 / 已删）→ 不给价
            ItemKey item = ItemKey.of(sf);
            if (item == null) continue;
            out.merge(item, price, Math::min);
        }
    }

    /** 显示名 → 物品（id 对不上时的兜底查法；item 段里写的是 {@code name}） */
    private static Map<String, SlimefunItem> itemNameIndex() {
        Map<String, SlimefunItem> map = new HashMap<>(1 << 14);
        try {
            for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
                if (item == null) continue;
                String n = plainName(item.getItemName());
                if (n != null) map.putIfAbsent(n, item);
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    /** 去掉颜色代码后的名字（yml 里常写 {@code &f} / {@code &#RRGGBB} 之类） */
    private static String plainName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        s = s.replaceAll("&[#0-9a-fk-orA-FK-OR]", "");
        s = s.replaceAll("&#[0-9a-fA-F]{6}", "");
        s = org.bukkit.ChatColor.stripColor(s);
        s = s.replace("§", "").trim();
        return s.isEmpty() ? null : s;
    }

    /** 读等价附属的 {@code price.block-drop.*}（没有那个文件就用默认口径） */
    private static DropRules readDropRules(File pluginsDir) {
        try {
            File cfg = new File(new File(pluginsDir, "XinZhiExchange"), "config.yml");
            if (!cfg.isFile()) return DropRules.defaults();
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(cfg);
            DropRules d = DropRules.defaults();
            Set<String> rb = new LinkedHashSet<>();
            for (String s : yml.getStringList("price.block-drop.renewable-blocks")) {
                if (s != null && !s.isBlank()) rb.add(s.trim().toUpperCase(Locale.ROOT));
            }
            if (rb.isEmpty()) rb.addAll(d.renewable());
            return new DropRules(
                    Math.max(1, yml.getInt("price.block-drop.high-chance", d.highChance())),
                    Math.max(0.0, yml.getDouble("price.block-drop.high-emc", d.highEmc())),
                    Math.max(0, yml.getInt("price.block-drop.mid-chance", d.midChance())),
                    Math.max(0.0, yml.getDouble("price.block-drop.mid-emc", d.midEmc())),
                    Math.max(0.0, yml.getDouble("price.block-drop.low-emc", d.lowEmc())),
                    Math.max(0.0, yml.getDouble("price.block-drop.non-renewable-emc", d.nonRenewableEmc())),
                    rb);
        } catch (Throwable t) {
            return DropRules.defaults();
        }
    }

    /** 物品 id（小写）→ 已注册的粘液物品，用来把 yml 的顶层键换成真正的 {@link ItemKey} */
    private static Map<String, SlimefunItem> itemIndex() {
        Map<String, SlimefunItem> map = new HashMap<>(1 << 14);
        try {
            for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
                if (item == null || item.getId() == null) continue;
                map.putIfAbsent(item.getId().toLowerCase(Locale.ROOT), item);
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    // ------------------------------------------------------------------
    // 兜底价 3：地理资源默认价（等价附属 geo-resource-default，腐竹 2026-10-18「都按 30 算」）
    // ------------------------------------------------------------------

    /**
     * 地理资源由矿机产出、没有合成配方，按固定价补一条，让它们能进基础物品表。
     * 两处来源都扫：附属注册的资源是 {@code SlimefunItem implements GEOResource}，
     * 粘液本体的资源只挂在 {@code getRegistry().getGEOResources()} 那张表里。
     *
     * @return 新补进来的条数；{@code -1} = 两张表都还是空的（附属还没注册完，稍后再试）
     */
    private static int loadGeoDefaults(double price) {
        if (!(price > 0)) return 0;
        Map<ItemKey, Double> found = new LinkedHashMap<>();
        try {
            for (SlimefunItem item : Slimefun.getRegistry().getAllSlimefunItems()) {
                if (item instanceof GEOResource geo) putGeo(found, geo, price);
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Map.Entry<org.bukkit.NamespacedKey, GEOResource> e
                    : Slimefun.getRegistry().getGEOResources()) {
                putGeo(found, e.getValue(), price);
            }
        } catch (Throwable ignored) {
        }
        if (found.isEmpty()) return -1;
        int n = 0;
        for (Map.Entry<ItemKey, Double> e : found.entrySet()) {
            if (EMC.putIfAbsent(e.getKey(), e.getValue()) == null) n++;
        }
        return n;
    }

    private static void putGeo(Map<ItemKey, Double> out, GEOResource geo, double price) {
        if (geo == null) return;
        try {
            ItemKey k = ItemKey.of(geo.getItem());
            if (k != null) out.putIfAbsent(k, price);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 兜底价的写入与延迟补读
    // ------------------------------------------------------------------

    /**
     * 写入掉落兜底价：锚点表里已经有价的不动；已经由另一种掉落兜底价补过的取<b>更便宜</b>的那条
     * （越容易拿到越便宜，与等价附属 {@code dropDefaults()} 一致）。
     *
     * @return 新增条数（不含「改便宜了」的那些）
     */
    private static int putDropPrices(Map<ItemKey, Double> best) {
        int n = 0;
        for (Map.Entry<ItemKey, Double> e : best.entrySet()) {
            ItemKey k = e.getKey();
            double v = e.getValue();
            Double cur = EMC.get(k);
            if (cur == null) {
                EMC.put(k, v);
                dropKeys.add(k);
                n++;
            } else if (dropKeys.contains(k) && v < cur) {
                EMC.put(k, v);
            }
        }
        return n;
    }

    /**
     * 稍后补读三张晚注册的表（粘液/附属注册 MOB_DROP、GEOResource 比本插件 onEnable 晚）。
     *
     * @return 本轮新补进来的总条数；{@code -1} = 还有表没注册好，调用方稍后再试
     */
    public static int tryLoadExtras(JavaPlugin plugin) {
        int added = 0;
        boolean pending = false;
        if (!mobDropsLoaded) {
            int n = loadMobDrops(plugin);
            if (n >= 0) {
                mobDropsLoaded = true;
                mobDropCount = n;
                added += n;
            } else {
                pending = true;
            }
        }
        if (!geoLoaded && geoPrice > 0) {
            int n = loadGeoDefaults(geoPrice);
            if (n >= 0) {
                geoLoaded = true;
                geoCount = n;
                added += n;
            } else {
                pending = true;
            }
        }
        if (!blockDropsLoaded && blockDropsWanted) {
            int n = loadBlockDrops(plugin);
            if (n >= 0) {
                blockDropsLoaded = true;
                blockDropCount = n;
                added += n;
            } else if (++blockDropsAttempts >= 3) {
                // items.yml 里写了 drop_from，但那批物品根本没注册（常见于没被 RSC 加载的附属目录）
                // ⇒ 试三轮就认，不再拖住整个补读
                blockDropsLoaded = true;
                blockDropCount = 0;
                plugin.getLogger().info("方块掉落兜底价：items.yml 里有 drop_from，但对应物品没注册"
                        + "（多半是没被 RSC 加载的附属目录），跳过。");
            } else {
                pending = true;
            }
        }
        if (added > 0) {
            source = source + "（延迟补读 +" + added + " 条）";
            plugin.getLogger().info("基础物品表兜底价补读完成：新增 " + added + " 条，现有 "
                    + encodableCount() + " 件可编码。");
        }
        return pending ? -1 : added;
    }

    /** 三张兜底价表都读到了没有（false = 还要再试） */
    public static boolean extrasLoaded() {
        return mobDropsLoaded && geoLoaded && blockDropsLoaded;
    }

    /** 生物掉落表读到了没有（false = 还要再试） */
    public static boolean mobDropsLoaded() { return mobDropsLoaded; }

    /** 锚点价；没有 = -1 */
    public static double emc(ItemKey k) {
        Double v = k == null ? null : EMC.get(k);
        return v == null ? -1 : v;
    }

    /** 能不能放进编码机：有价，且 0 &lt; EMC &lt; 门槛 */
    public static boolean isBase(ItemKey k) {
        double v = emc(k);
        return v > 0 && v < threshold;
    }

    public static double threshold() { return threshold; }

    public static String source() { return source; }

    /** 门槛内可编码物品总数 */
    public static int encodableCount() {
        int n = 0;
        for (double v : EMC.values()) if (v > 0 && v < threshold) n++;
        return n;
    }

    public static Map<ItemKey, Double> snapshot() {
        return new LinkedHashMap<>(EMC);
    }
}
