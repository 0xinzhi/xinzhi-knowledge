package net.me.xinzhi.knowledge;

import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import net.me.xinzhi.knowledge.config.Settings;
import net.me.xinzhi.knowledge.core.GeneratorCard;
import net.me.xinzhi.knowledge.core.ItemNames;
import net.me.xinzhi.knowledge.gui.RecipePicker;
import net.me.xinzhi.knowledge.gui.RecipeViewer;
import net.me.xinzhi.knowledge.items.CardPlaceGuard;
import net.me.xinzhi.knowledge.items.RecipeReader;
import net.me.xinzhi.knowledge.machines.BulkTemplateGenerator;
import net.me.xinzhi.knowledge.machines.CardGeneratorMachine;
import net.me.xinzhi.knowledge.machines.EncoderMachine;
import net.me.xinzhi.knowledge.machines.FusionMachine;
import net.me.xinzhi.knowledge.machines.KnowledgeGenerator;
import net.me.xinzhi.knowledge.machines.PowerGenerator;
import net.me.xinzhi.knowledge.machines.RecipeMachine;
import net.me.xinzhi.knowledge.price.BaseItems;
import net.me.xinzhi.knowledge.recipe.RecipeScanner;
import net.me.xinzhi.knowledge.tank.StorageTank;
import net.me.xinzhi.knowledge.tank.TankNet;
import net.me.xinzhi.knowledge.util.UpdateChecker;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;

/**
 * XinZhiKnowledge —— 把知识之书从道具升级成三台独立的机器。
 *
 * <ul>
 *   <li>{@code XZK_CRAFT_OUT} 知识合成机（输出优先）：左 2 列进、右 6 列出</li>
 *   <li>{@code XZK_CRAFT_IN}  知识合成机（输入优先）：左 6 列进、右 2 列出</li>
 *   <li>{@code XZK_GENERATOR} 知识生成器：只跑不消耗输入的配方</li>
 * </ul>
 *
 * <p>独立附属 ⇒ 合成材料只用原版/粘液本体，不依赖主附属，也不依赖任何第三方附属。
 */
public final class XinZhiKnowledge extends JavaPlugin implements SlimefunAddon {

    private ItemGroup group;

    /**
     * 三张「垫底价」表（生物掉落 / 方块掉落 / 地理资源）要等粘液 + 各家附属注册完才有内容：
     * MOB_DROP 与 GEOResource 注册时刻比本插件 {@code onEnable} 晚很多（实测约 100 秒），
     * RSC 的 items.yml 也要等粘液物品注册齐才能把 id 换成物品。这里轮询补读，
     * 三张都读到就停；两分钟还没齐就放弃（说明这服没装对应的东西）。
     */
    /** 按当前配置重建基础物品表（三张兜底价表在 onEnable 时通常还没注册好，靠轮询补） */
    private void reloadBaseItems() {
        Settings s = Settings.get();
        BaseItems.reload(this, s.baseItemMaxEmc(), s.baseItemMobDrops(),
                s.baseItemBlockDrops(), s.baseItemGeoPrice());
    }

    private void startBaseItemProbe() {
        if (BaseItems.extrasLoaded()) return;
        new org.bukkit.scheduler.BukkitRunnable() {
            private int tries;

            @Override
            public void run() {
                tries++;
                int n = BaseItems.tryLoadExtras(XinZhiKnowledge.this);
                if (n >= 0 || tries >= 60) cancel();
            }
        }.runTaskTimer(this, 40L, 40L);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings.set(new Settings(getConfig()));
        net.me.xinzhi.knowledge.core.CycleRules.load(this);
        ItemNames.load(this);
        reloadBaseItems();
        startBaseItemProbe();

        String prefix = getConfig().getString("machines.item-id-prefix", "XZK_");
        group = new ItemGroup(new NamespacedKey(this, "knowledge"),
                new CustomItemStack(Material.KNOWLEDGE_BOOK, "§b知识机器", "§7合成 / 生成，靠识别机器跑配方"));

        SlimefunItemStack craftOut = new SlimefunItemStack(prefix + "CRAFT_OUT",
                Material.CRAFTER, "§b知识合成机 §8· §7输出优先",
                "§8本附属机器 = 把一堆机器收成一台：跑自动化、省渲染",
                "§8不如逻辑工艺的堆叠机器全面（要那套请直接用逻辑）",
                "§7左边 2 列 = 输入，右边 6 列 = 输出",
                "§7第 21 格放机器读配方，第 12/39/48 格叠同款机器",
                "§7眼睛格点开选配方（选了就固定跑那条）",
                "§8效率 = 机器数 ÷ 配方 tick");
        SlimefunItemStack craftIn = new SlimefunItemStack(prefix + "CRAFT_IN",
                Material.CRAFTER, "§b知识合成机 §8· §7输入优先",
                "§8本附属机器 = 把一堆机器收成一台：跑自动化、省渲染",
                "§8不如逻辑工艺的堆叠机器全面（要那套请直接用逻辑）",
                "§7左边 6 列 = 输入，右边 2 列 = 输出",
                "§7第 25 格放机器读配方，第 16/43/52 格叠同款机器",
                "§7眼睛格点开选配方（选了就固定跑那条）",
                "§8效率 = 机器数 ÷ 配方 tick");
        SlimefunItemStack generator = new SlimefunItemStack(prefix + "GENERATOR",
                Material.LECTERN, "§b知识生成器",
                "§8本附属机器 = 把一堆机器收成一台：跑自动化、省渲染",
                "§8不如逻辑工艺的堆叠机器全面（要那套请直接用逻辑）",
                "§7只跑不消耗输入的配方",
                "§7第 50 格放机器读配方，第 51-54 格叠同款机器",
                "§7第 49 格放模板（这机器认配方用的东西，机器不扣它）",
                "§8效率 = 机器数 ÷ 配方 tick");

        SlimefunItemStack powerGen = new SlimefunItemStack(prefix + "POWER_GENERATOR",
                Material.SEA_LANTERN, "§b知识发电机",
                "§8本附属机器 = 把一堆发电机收成一台：跑自动化、省渲染",
                "§8不如逻辑工艺的堆叠机器全面（要那套请直接用逻辑）",
                "§7右边 4 列放发电机（同款叠台数），左边 4 列放燃料",
                "§7每 tick 发出「发电量 × 台数 × 效率」的电",
                "",
                "§b它认四种发电机，按下面的顺序判：",
                "§8① 跟随型 §7· 上方真摆着一台发电机：读它的发电量，只拿 §f10%§7（按台数再乘）",
                "§8② 燃料表型 §7· 粘液本体那种「烧燃料发电」的：左边放燃料，一轮一轮烧",
                "§8③ 配方型 §7· 配方里写着要扣料的：按配方扣料，跟合成机一样",
                "§8④ 无消耗型 §7· 没燃料也没配方（恒 1 J、太阳能）：直接问它自己发多少",
                "§8发电量读不到就不发电（不瞎猜）");
        ItemStack book = new ItemStack(Material.BOOK);
        ItemStack star = new ItemStack(Material.NETHER_STAR);
        ItemStack exp = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemStack lapis = new ItemStack(Material.LAPIS_LAZULI);
        ItemStack table = new ItemStack(Material.CRAFTING_TABLE);

        new RecipeMachine(group, craftOut, RecipeType.ENHANCED_CRAFTING_TABLE, new ItemStack[]{
                star, book, star,
                lapis, table, lapis,
                exp, book, exp
        }, true).register(this);

        new RecipeMachine(group, craftIn, RecipeType.ENHANCED_CRAFTING_TABLE, new ItemStack[]{
                exp, book, exp,
                lapis, table, lapis,
                star, book, star
        }, false).register(this);

        new KnowledgeGenerator(group, generator, RecipeType.ENHANCED_CRAFTING_TABLE, new ItemStack[]{
                star, exp, star,
                lapis, table, lapis,
                exp, book, exp
        }).register(this);

        ItemStack redstone = new ItemStack(Material.REDSTONE_BLOCK);
        ItemStack gold = new ItemStack(Material.GOLD_INGOT);
        new PowerGenerator(group, powerGen, RecipeType.ENHANCED_CRAFTING_TABLE, new ItemStack[]{
                redstone, gold, redstone,
                gold, book, gold,
                redstone, gold, redstone
        }).register(this);

        SlimefunItemStack reader = new SlimefunItemStack(prefix + "RECIPE_READER",
                Material.BOOK, "§b配方读取器",
                "§7拿在手上右键一台粘液机器，看它能跑哪些配方",
                "§8只读：不写方块数据、不锁配方、不改变任何东西",
                "§8想知道「知识机器能跑这台机器什么」，先拿它看一眼");
        new RecipeReader(group, reader, RecipeType.ENHANCED_CRAFTING_TABLE, new ItemStack[]{
                null, lapis, null,
                lapis, book, lapis,
                null, lapis, null
        }).register(this);

        // ---- 编码机 / 融合机 / 生成器卡（腐竹 2026-11-14）----
        // 样式：篝火 + 附魔光效（腐竹 2026-11-15「从纸张改为篝火，并添加附魔光效」）
        SlimefunItemStack card = new SlimefunItemStack(prefix + "GENERATOR_CARD",
                Material.CAMPFIRE, "§b生成器卡 §8· §7空卡",
                "§8编码机编出来的卡：放进知识生成器的识别槽，不耗输入一直产这一件东西",
                "§8卡上记着产物、间隔、每间隔几个；同产物同间隔的卡能叠在一起");
        ItemMeta cardMeta = card.getItemMeta();
        if (cardMeta != null) {
            cardMeta.setEnchantmentGlintOverride(Boolean.TRUE);
            card.setItemMeta(cardMeta);
        }
        SlimefunItemStack baseGen = new SlimefunItemStack(prefix + "GENERATOR_BASE",
                Material.LODESTONE, "§b基础生成器",
                "§7编码机的底板：放进编码机正中间，外圈 16 格各放 64 个同种基础物品",
                "§7就能编出一张那件物品的生成器卡",
                "§8基础物品 = 等价附属基础价 < " + (long) Settings.get().baseItemMaxEmc() + " 的物品");
        new SlimefunItem(group, card, RecipeType.NULL, new ItemStack[9]).register(this);
        // 新内容先不给合成配方（腐竹 2026-11-16：测试版先不开配方，等流程策划定了正式版再补）
        new SlimefunItem(group, baseGen, RecipeType.NULL, new ItemStack[9]).register(this);

        SlimefunItemStack encoder = new SlimefunItemStack(prefix + "ENCODER",
                Material.CARTOGRAPHY_TABLE, "§b编码机",
                "§8本附属机器 = 把一堆机器收成一台：跑自动化、省渲染",
                "§8不如逻辑工艺的堆叠机器全面（要那套请直接用逻辑）",
                "§75×5 网格：正中间放基础生成器，外圈 16 格各放 64 个同种基础物品",
                "§7编出一张那件物品的生成器卡（每张卡 10 tick 产 1 个）",
                "§8基础物品 = 等价附属基础价 < " + (long) Settings.get().baseItemMaxEmc() + " 的物品");
        SlimefunItemStack fusion = new SlimefunItemStack(prefix + "FUSION",
                Material.SMITHING_TABLE, "§b融合机",
                "§8本附属机器 = 把一堆机器收成一台：跑自动化、省渲染",
                "§8不如逻辑工艺的堆叠机器全面（要那套请直接用逻辑）",
                "§7左 6 列 = 放原料的生成器卡，右 2 列 = 产物",
                "§7第 25 格放机器 = 按那台机器的配方融合",
                "§7第 34 格按钮 = 打开配方选择界面（一台机器有很多条配方时用）",
                "§7第 25 格空着 = 大杂烩：只把同类卡合并，产量相加、间隔取最大",
                "§8融合一次的时间 = 参与融合的卡里最长的间隔");

        // 编码机 / 融合机：测试版先不给配方（物品保留，老存档里的照常能用）
        new EncoderMachine(group, encoder, RecipeType.NULL, new ItemStack[9]).register(this);
        new FusionMachine(group, fusion, RecipeType.NULL, new ItemStack[9]).register(this);

        SlimefunItemStack cardGen = new SlimefunItemStack(prefix + "CARD_GENERATOR",
                Material.BLAST_FURNACE, "§b编码生成器",
                "§8本附属机器 = 把一堆机器收成一台：跑自动化、省渲染",
                "§8不如逻辑工艺的堆叠机器全面（要那套请直接用逻辑）",
                "§7上 5 排 45 格全放生成器卡（不同产物可混放）",
                "§7每张卡按卡面间隔各出各的，产物从最下面一排出来",
                "§8耗电 0（腐竹：这台不吃电）");
        new CardGeneratorMachine(group, cardGen, RecipeType.NULL, new ItemStack[9]).register(this);

        // ---- 大模板生成器（腐竹 2026-11-16：模板物品不可堆叠时用，模板放进紧挨着的输入储罐）----
        SlimefunItemStack bulk = new SlimefunItemStack(prefix + "GENERATOR_BULK",
                Material.ENCHANTING_TABLE, "§b大模板生成器",
                "§8本附属机器 = 把一堆机器收成一台：跑自动化、省渲染",
                "§7专门给「模板物品不可堆叠」的机器用",
                "§7第 50 格放机器读配方，第 51-54 格叠同款机器",
                "§7模板放进紧挨着的【输入储罐】：54 格，一格放一份，不可堆叠也能数到 54 份",
                "§8只跑不消耗输入的配方；产物优先塞输出储罐");
        new BulkTemplateGenerator(group, bulk, RecipeType.NULL, new ItemStack[9]).register(this);

        // ---- 输入 / 输出储罐（腐竹 2026-11-15：同层 1 格范围内被机器认出来，先吃储罐的、先塞储罐）----
        SlimefunItemStack tankIn = new SlimefunItemStack(prefix + "TANK_IN",
                Material.BARREL, "§b输入储罐",
                "§7摆在机器同一层、紧挨着（含斜角），机器就从这里拿料",
                "§8规矩：先用储罐里的，储罐里没了才用机器槽里的",
                "§754 格，随便放");
        SlimefunItemStack tankOut = new SlimefunItemStack(prefix + "TANK_OUT",
                Material.SMOKER, "§b输出储罐",
                "§7摆在机器同一层、紧挨着（含斜角），机器的产物先往这里塞",
                "§8规矩：储罐塞满了才落回机器的产物格",
                "§754 格，随便放");
        new StorageTank(group, tankIn, RecipeType.ENHANCED_CRAFTING_TABLE, new ItemStack[]{
                null, lapis, null,
                exp, new ItemStack(Material.BARREL), exp,
                null, lapis, null
        }, "§8输入储罐").register(this);
        new StorageTank(group, tankOut, RecipeType.ENHANCED_CRAFTING_TABLE, new ItemStack[]{
                null, lapis, null,
                exp, new ItemStack(Material.SMOKER), exp,
                null, lapis, null
        }, "§8输出储罐").register(this);
        TankNet.init(prefix + "TANK_IN", prefix + "TANK_OUT");

        GeneratorCard.init(this, prefix + "GENERATOR_CARD", card.clone(), prefix + "GENERATOR_BASE");

        getServer().getPluginManager().registerEvents(new CardPlaceGuard(), this);
        getServer().getPluginManager().registerEvents(new RecipePicker.Click(), this);
        getServer().getPluginManager().registerEvents(new RecipeViewer.Click(), this);
        getLogger().info("XinZhiKnowledge 已加载：8 台机器 + 2 个储罐 + 配方读取器 · 基础物品表 "
                + BaseItems.encodableCount() + " 件（" + BaseItems.source() + "）");
        UpdateChecker.start(this);
    }

    @Override
    public void onDisable() {
        // 关服时才换 jar（运行中的 jar 被 JVM 锁着），脚本等进程退出后覆盖，下次启动即新版
        UpdateChecker.installOnShutdown(this);
    }

    @Override
    public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command cmd,
                             @Nonnull String label, @Nonnull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage("§bXinZhiKnowledge §7v" + getPluginVersion() + " §8· 构建 §7" + buildStamp());
            sender.sendMessage("§7知识合成机（输出优先 / 输入优先）+ 知识生成器 + 知识发电机"
                    + " + 编码机 + 融合机 + 编码生成器 + 输入/输出储罐 + 配方读取器");
            sender.sendMessage("§7基础物品表：§f" + BaseItems.encodableCount() + " §7件可编码"
                    + " §8(" + BaseItems.source() + " · 门槛 EMC < " + (long) BaseItems.threshold() + ")");
            sender.sendMessage("§7效率 = 机器数 × 基准效率 ÷ 配方 tick（小于 1 tick 就一 tick 多次）");
            sender.sendMessage("§7配方只认精确配方，展示配方（getDisplayRecipes）一律不采信");
            sender.sendMessage("§8—— 运行耗时（本机 tick 平均）——");
            for (String line : net.me.xinzhi.knowledge.util.Perf.report()) sender.sendMessage(line);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("xzk.admin")) {
                sender.sendMessage("§c没有权限。");
                return true;
            }
            reloadConfig();
            Settings.set(new Settings(getConfig()));
            net.me.xinzhi.knowledge.core.CycleRules.load(this);
            RecipeScanner.clearCache();
            reloadBaseItems();
            net.me.xinzhi.knowledge.util.Perf.reset();
            sender.sendMessage("§a配置已重载，配方缓存、基础物品表与耗时统计已清空（基础物品表 "
                    + BaseItems.encodableCount() + " 件可编码）。");
            return true;
        }
        if (args[0].equalsIgnoreCase("scan")) {
            // 诊断：把你手里那台机器扫出来的东西摊开看（配方数 / 可合成 / 不耗输入 / 耗电）
            SlimefunItem sf;
            if (args.length > 1) {
                sf = SlimefunItem.getById(args[1].toUpperCase(java.util.Locale.ROOT));
                if (sf == null) { sender.sendMessage("§c没找到 id：" + args[1]); return true; }
            } else if (sender instanceof Player p) {
                sf = SlimefunItem.getByItem(p.getInventory().getItemInMainHand());
                if (sf == null) { sender.sendMessage("§c手里拿的不是粘液物品。用法：/xzk scan <机器id>"); return true; }
            } else {
                sender.sendMessage("§7控制台用法：/xzk scan <机器id>");
                return true;
            }
            for (String line : RecipeScanner.describe(sf)) sender.sendMessage(line);
            return true;
        }
        if (args[0].equalsIgnoreCase("find")) {
            // 诊断：按 id / 显示名找粘液物品（弄清「这台机器到底叫什么 id」）
            if (args.length < 2) {
                sender.sendMessage("§7用法：/xzk find <关键字> §8(按 id / 显示名找粘液物品)");
                return true;
            }
            String q = args[1].toLowerCase(java.util.Locale.ROOT);
            int hits = 0;
            for (SlimefunItem sf : io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getRegistry().getAllSlimefunItems()) {
                if (sf == null) continue;
                String id = sf.getId() == null ? "" : sf.getId().toLowerCase(java.util.Locale.ROOT);
                String nm = sf.getItemName() == null ? "" : sf.getItemName().toLowerCase(java.util.Locale.ROOT);
                if (!id.contains(q) && !nm.contains(q)) continue;
                sender.sendMessage("§b" + sf.getId() + " §7· " + sf.getItemName()
                        + " §8[" + sf.getClass().getSimpleName() + "]");
                if (++hits >= 40) { sender.sendMessage("§8…还有更多，换个关键字"); break; }
            }
            sender.sendMessage(hits == 0 ? "§c没找到含「" + args[1] + "」的物品" : "§7共 " + hits + " 个");
            return true;
        }
        if (args[0].equalsIgnoreCase("dump")) {
            // 诊断：逐条列出原始配方类名 / 产物数 / 概率表 / chooseOne（查「谁把概率吃了」）
            SlimefunItem sf;
            if (args.length > 1) {
                sf = SlimefunItem.getById(args[1].toUpperCase(java.util.Locale.ROOT));
                if (sf == null) { sender.sendMessage("§c没找到 id：" + args[1]); return true; }
            } else if (sender instanceof Player p) {
                sf = SlimefunItem.getByItem(p.getInventory().getItemInMainHand());
                if (sf == null) { sender.sendMessage("§c手里拿的不是粘液物品。用法：/xzk dump <机器id>"); return true; }
            } else {
                sender.sendMessage("§7控制台用法：/xzk dump <机器id>");
                return true;
            }
            for (String line : RecipeScanner.dump(sf)) sender.sendMessage(line);
            return true;
        }
        if (args[0].equalsIgnoreCase("update")) {
            if (!sender.hasPermission("xzk.update") && !sender.hasPermission("xzk.admin")) {
                sender.sendMessage("§c没有权限。");
                return true;
            }
            sender.sendMessage("§b[知识机器] 当前 v" + getPluginVersion() + " §8· " + UpdateChecker.status());
            boolean wasPending = UpdateChecker.status().startsWith("§e");
            UpdateChecker.checkNow(this);
            sender.sendMessage("§7已向 GitHub 查一次，结果看控制台（十几秒内）" + (wasPending ? "" : "；有新版本会下到 plugins/update/"));
            return true;
        }
        sender.sendMessage("§7用法：/xzk [info|reload|scan|dump <机器id>|update]（info 里带每台机器的平均 tick 耗时）");
        return true;
    }

    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    @Override
    public String getBugTrackerURL() {
        return "https://github.com/0xinzhi/xinzhi-knowledge/issues";
    }

    /** 版本号取 plugin.yml（maven 过滤），没有就退回 unknown */
    public String getPluginVersion() {
        String v = getDescription().getVersion();
        return v == null || v.isBlank() ? "unknown" : v;
    }

    public ItemGroup getGroup() {
        return group;
    }

    /** 这个 jar 是什么时候构建的（build.properties，maven 过滤写入）—— 用来区分「服里跑的是新 jar 还是旧 jar」 */
    static String buildStamp() {
        try (java.io.InputStream in = XinZhiKnowledge.class.getResourceAsStream("/build.properties")) {
            if (in == null) return "?";
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            String v = props.getProperty("build", "?");
            try {
                // maven 写的是 UTC（2026-11-07T14:24:14Z），按本机时区显示，免得对着差 8 小时的时间犯迷糊
                return java.time.OffsetDateTime.parse(v)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            } catch (Exception ignored) {
                return v;
            }
        } catch (Exception e) {
            return "?";
        }
    }
}