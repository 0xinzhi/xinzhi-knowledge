package net.me.xinzhi.knowledge.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import net.me.xinzhi.knowledge.config.Settings;
import net.me.xinzhi.knowledge.gui.RecipePicker;
import net.me.xinzhi.knowledge.recipe.Scan;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识机器的共同骨架：菜单、锁格、耗电接口、状态卡、配方选择入口。
 *
 * <p>耗电靠 Slimefun 的 {@link EnergyNetComponent}（CONSUMER + 有容量 = 电力网络会把电灌进来），
 * 每次合成本机自己扣。被扫描机器的耗电读不到 = 0 = 本机不吃电（腐竹 2026-11-07 第 4 条）。
 */
public abstract class BaseKnowledgeMachine extends AbstractMachine implements EnergyNetComponent {

        protected BaseKnowledgeMachine(ItemGroup group, SlimefunItemStack item,
                                   RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
    }

    // ---------------- 子类要填的槽位 ----------------

    protected abstract int[] inputSlots();
    protected abstract int[] outputSlots();
    protected abstract int machineSlot();
    /**
     * 附加机器槽（腐竹 2026-11-08：把功能列剩下的格子利用起来）。
     * <p>只数台数、不认配方：识别一律只读 {@link #machineSlot()} 那一格，
     * 附加槽里的机器必须和它是同款，否则不算台数。
     */
    protected int[] extraMachineSlots() { return new int[0]; }
    /** 配方选择格；生成器没有 = -1 */
    protected int recipeSlot() { return -1; }
    /** 生成器的模板区；其余 = 空 */
    protected int[] templateSlots() { return new int[0]; }
    /** 状态显示格（不可放物品）；-1 = 不要 */
    protected int statusSlot() { return Layout.STATUS; }

    /**
     * 说明展示格（不可放物品，纯信息）；-1 = 不要。
     * <p>腐竹 2026-11-14：编码生成器中间那一列是「功能槽，用来分隔和显示信息」。
     */
    protected int infoSlot() { return -1; }

    /** 说明格里的文字（{@link #infoSlot()} >= 0 时才有意义） */
    protected String[] infoLines() { return new String[0]; }

    /** 状态卡第二行：告诉玩家哪个槽放机器读配方、哪些格叠台数（腐竹 2026-11-08） */
    protected String hintLore() {
        return "§8机器列：上面那格放机器读配方，其余格放同款机器叠台数";
    }
    /**
     * 这台机器要看被识别机器的<b>哪一类</b>配方。
     * <p>配方机只看「要扣料」的、生成器只看「不消耗输入」的 —— 两边都不许越界，
     * 否则要么白送产物（生成器配方进了合成机），要么把模板当成原料扣掉。
     */
    protected abstract java.util.List<Scan> scansFor(SlimefunItem sf);

    protected boolean isFree(int slot) {
        return Layout.contains(inputSlots(), slot)
                || Layout.contains(outputSlots(), slot)
                || Layout.contains(templateSlots(), slot)
                || Layout.contains(machineSlots(), slot)
                || slot == recipeSlot();
    }

    /** 识别槽 + 附加机器槽 */
    protected int[] machineSlots() {
        int[] extra = extraMachineSlots();
        int slot = machineSlot();
        int n = (slot >= 0 ? 1 : 0) + extra.length;
        int[] all = new int[n];
        int i = 0;
        if (slot >= 0) all[i++] = slot;
        for (int v : extra) if (v >= 0) all[i++] = v;
        return java.util.Arrays.copyOf(all, i);
    }

    /**
     * 数台数：识别槽 + 附加机器槽里<b>同款</b>机器的堆叠数之和。
     * <p>附加槽放了别的机器不算台数（否则等于凭空加台），并置 {@code mismatch[0] = true}。
     */
    protected int countMachines(BlockMenu menu, SlimefunItem sf, boolean[] mismatch) {
        int total = 0;
        for (int slot : machineSlots()) {
            ItemStack it = menu.getItemInSlot(slot);
            SlimefunItem other = MachineUtil.machineIn(it);
            if (other == null) continue;
            if (!other.getId().equals(sf.getId())) {
                if (mismatch != null && mismatch.length > 0) mismatch[0] = true;
                continue;
            }
            total += Math.max(1, it.getAmount());
        }
        return Math.max(1, total);
    }

    // ---------------- EnergyNetComponent ----------------

    @Override public EnergyNetComponentType getEnergyComponentType() { return EnergyNetComponentType.CONSUMER; }

    @Override public int getCapacity() {
        Settings s = Settings.get();
        return s == null ? 2000000000 : s.energyCapacity();
    }

    // ---------------- 菜单 ----------------

    @Override
    protected void initMenu(@Nonnull BlockMenuPreset preset) {
        // BlockMenuPreset 的 size 默认是 -1（按占用格自动推断）：右边几列没占格，
        // 整个界面就被截窄了（腐竹 2026-11-08 报的「列数被省去了」）。这里钉死 54 格 = 9×6。
        preset.setSize(54);
        int info = infoSlot();
        for (int i = 0; i < 54; i++) {
            if (i == info) continue;   // 说明格自己画，别先铺成锁死板
            if (!isFree(i)) preset.addItem(i, LOCKED_PANE, (p, s, item, action) -> false);
        }
        if (info >= 0) {
            String[] lines = infoLines();
            preset.addItem(info, net.me.xinzhi.knowledge.util.GuiItems.of(
                    Material.KNOWLEDGE_BOOK, menuTitle(),
                    lines.length == 0 ? new String[]{"§7左 4 列放生成器卡 · 右 4 列出产物"} : lines),
                    (p, s, item, action) -> false);
        }
        int rs = recipeSlot();
        if (rs >= 0) {
            preset.addItem(rs, RecipePicker.icon(null), (p, slot, item, action) -> {
                // 点击回调只给 (player, slot, item)，方块要从玩家当前打开的那个菜单反查
                openPicker(p);
                return false;
            });
        }
        if (statusSlot() >= 0) {
            preset.addItem(statusSlot(), net.me.xinzhi.knowledge.util.GuiItems.of(
                    Material.KNOWLEDGE_BOOK, "§b知识机器", "§7状态会显示在这里", hintLore()),
                    (p, s, item, action) -> false);
        }
    }

    private void openPicker(org.bukkit.entity.Player p) {
        Block b = openedBlock(p);
        if (b == null) return;
        BlockMenu menu = MachineDrops.menu(b.getLocation());
        if (menu == null) return;
        String id = MachineUtil.info(b.getLocation(), "xzk-machine");
        if (id == null) return;
        SlimefunItem sf = SlimefunItem.getById(id);
        List<Scan> list = scansFor(sf);
        if (list.isEmpty()) {
            p.sendMessage(noRecipeMessage(sf));
            return;
        }
        // 高亮跟「签名」走：下标可能是老数据，签名才是机器真正在跑的那一条
        int cur = MachineUtil.intInfo(b.getLocation(), net.me.xinzhi.knowledge.recipe.RecipeLock.KEY_INDEX, -1);
        Scan lockedNow = net.me.xinzhi.knowledge.recipe.RecipeLock.resolve(b.getLocation(), list);
        if (lockedNow != null) cur = list.indexOf(lockedNow);
        RecipePicker.open(p, b.getLocation(), id, list, cur, menu);
    }

    /** 玩家当前打开的机器界面属于哪个方块；不是本插件的界面就 null */
    private static Block openedBlock(org.bukkit.entity.Player p) {
        try {
            org.bukkit.inventory.Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof BlockMenu bm) return bm.getBlock();
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------- tick ----------------

    @Override
    protected void onTick(@Nonnull Block b, @Nonnull SlimefunItem item, SlimefunBlockData data) {
        BlockMenu menu = MachineDrops.menu(b.getLocation());
        if (menu == null) return;
        long t0 = System.nanoTime();
        try {
            tick(b, menu);
        } catch (Throwable t) {
            render(menu, "§c内部错误：" + t.getClass().getSimpleName());
        } finally {
            net.me.xinzhi.knowledge.util.Perf.add(menuTitle(), System.nanoTime() - t0);
        }
    }

    protected abstract void tick(Block b, BlockMenu menu);

    protected void render(BlockMenu menu, String text) {
        int slot = statusSlot();
        if (slot < 0) return;
        String lore = "§7" + text;
        String hint = hintLore();
        // 以「格子里实际躺着的那张卡」为准：内存缓存一旦和真实格子不一致，状态就会卡在旧文字上
        ItemStack cur = menu.getItemInSlot(slot);
        if (cur != null && cur.getType() == Material.KNOWLEDGE_BOOK) {
            org.bukkit.inventory.meta.ItemMeta meta = cur.getItemMeta();
            if (meta != null) {
                java.util.List<String> l = meta.getLore();
                if (l != null && l.size() == 2 && lore.equals(l.get(0)) && hint.equals(l.get(1))) return;
            }
        }
        menu.replaceExistingItem(slot, net.me.xinzhi.knowledge.util.GuiItems.of(
                Material.KNOWLEDGE_BOOK, "§b知识机器", lore, hint), true);
    }

    /** 机器名（认出来时给玩家看，免得「显示这个」不知道是哪台） */
    /**
     * 配方选择列表为空时给玩家的一句话。
     *
     * <p>子类可以覆盖成更具体的解释（融合机只认「要扣料」的配方，碰到纯生成器配方要说清原因，
     * 否则玩家只会看到一句「扫不出配方」——腐竹 2026-11-14 连报两轮）。
     */
    protected String noRecipeMessage(io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf) {
        String id = sf == null ? "?" : sf.getId();
        return "§c这台机器扫不出配方，没法选。 §8(/xzk dump " + id + " 看扫描结果)";
    }

    protected static String nameOf(io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf) {
        try {
            String n = sf.getItemName();
            if (n != null && !n.isBlank()) {
                String plain = org.bukkit.ChatColor.stripColor(n);
                return plain == null || plain.isBlank() ? sf.getId() : plain;
            }
        } catch (Throwable ignored) {
        }
        return sf.getId();
    }

    @Override
    public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
        return flow == ItemTransportFlow.INSERT ? inputSlots() : outputSlots();
    }

    @Override
    protected int[] storageSlotsForDrop() {
        int[] in = inputSlots();
        int[] out = outputSlots();
        int[] tpl = templateSlots();
        int[] mach = machineSlots();
        int[] all = new int[in.length + out.length + tpl.length + mach.length + 1];
        int i = 0;
        for (int v : in) all[i++] = v;
        for (int v : out) all[i++] = v;
        for (int v : tpl) all[i++] = v;
        for (int v : mach) all[i++] = v;
        all[i] = -1;
        return java.util.Arrays.stream(all).filter(v -> v >= 0).toArray();
    }
}