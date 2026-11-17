package net.me.xinzhi.knowledge.tank;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import net.me.xinzhi.knowledge.machines.MachineDrops;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;

/**
 * 输入 / 输出储罐：一台没有 tick 的 54 格容器，摆在机器同一层八邻格内就会被那台机器认出来。
 *
 * <p>腐蚀 2026-11-15 的口径：「输入储罐」是机器的料源（先用储罐的料，再用机器槽里的），
 * 「输出储罐」是机器的产物去处（先塞储罐，塞满了才落回机器产物格）。
 */
public final class StorageTank extends SlimefunItem {

    /** 整罐都是真存储格，拆掉时全部还给玩家 */
    private static final int[] ALL_SLOTS = slots();

    private static int[] slots() {
        int[] a = new int[TankNet.SLOTS];
        for (int i = 0; i < a.length; i++) a[i] = i;
        return a;
    }

    private final String title;

    public StorageTank(ItemGroup group, SlimefunItemStack item, RecipeType recipeType,
                       ItemStack[] recipe, String title) {
        super(group, item, recipeType, recipe);
        this.title = title;
    }

    @Override
    public void preRegister() {
        super.preRegister();
        // 拆储罐要把 54 格里的东西全掉出来（腐竹 2026-11-17：「机器破坏要掉出里面存放的东西」）
        addItemHandler(MachineDrops.dropHandler(() -> ALL_SLOTS));
        new BlockMenuPreset(getId(), title) {
            @Override
            public void init() {
                // 钉死 54 格：BlockMenuPreset 的 size 默认按「占了几格」推断，
                // 一格不占时会被截成一个 9 格的小箱子（腐竹 2026-11-17 报的
                // 「刚放下不显示格子，被机器一识别就变 54 格」）。
                setSize(TankNet.SLOTS);
            }

            @Override
            public void newInstance(@Nonnull BlockMenu menu, @Nonnull Block b) {
            }

            @Override
            public boolean canOpen(@Nonnull Block b, @Nonnull org.bukkit.entity.Player p) {
                return p.hasPermission("slimefun.inventory.bypass")
                        || io.github.thebusybiscuit.slimefun4.implementation.Slimefun
                        .getProtectionManager().hasPermission(p, b.getLocation(),
                                io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction.INTERACT_BLOCK);
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                int[] all = new int[TankNet.SLOTS];
                for (int i = 0; i < all.length; i++) all[i] = i;
                return all;
            }
        };
    }
}
