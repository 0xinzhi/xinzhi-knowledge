package net.me.xinzhi.knowledge;

import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import net.me.xinzhi.knowledge.config.Settings;
import net.me.xinzhi.knowledge.core.ItemNames;
import net.me.xinzhi.knowledge.gui.RecipePicker;
import net.me.xinzhi.knowledge.gui.RecipeViewer;
import net.me.xinzhi.knowledge.items.RecipeReader;
import net.me.xinzhi.knowledge.machines.KnowledgeGenerator;
import net.me.xinzhi.knowledge.machines.PowerGenerator;
import net.me.xinzhi.knowledge.machines.RecipeMachine;
import net.me.xinzhi.knowledge.recipe.RecipeScanner;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings.set(new Settings(getConfig()));
        ItemNames.load(this);

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

        getServer().getPluginManager().registerEvents(new RecipePicker.Click(), this);
        getServer().getPluginManager().registerEvents(new RecipeViewer.Click(), this);
        getLogger().info("XinZhiKnowledge 已加载：4 台机器 + 配方读取器。");
    }

    @Override
    public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command cmd,
                             @Nonnull String label, @Nonnull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage("§bXinZhiKnowledge §7v" + getPluginVersion() + " §8· 构建 §7" + buildStamp());
            sender.sendMessage("§7知识合成机（输出优先 / 输入优先）+ 知识生成器 + 知识发电机 + 配方读取器");
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
            RecipeScanner.clearCache();
            net.me.xinzhi.knowledge.util.Perf.reset();
            sender.sendMessage("§a配置已重载，配方缓存与耗时统计已清空。");
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
        sender.sendMessage("§7用法：/xzk [info|reload|scan|dump <机器id>]（info 里带每台机器的平均 tick 耗时）");
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