package net.me.xinzhi.knowledge.items;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import net.me.xinzhi.knowledge.gui.RecipeViewer;
import net.me.xinzhi.knowledge.recipe.RecipeScanner;
import net.me.xinzhi.knowledge.recipe.Scan;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方读取器：拿在手上右键一台粘液机器，把它能被扫描出来的配方摊开给你看。
 *
 * <p><b>只读</b>：不写方块数据、不锁配方、不改任何东西；想知道「这台机器知识机器能跑什么」时用它，
 * 不必先去合成一台知识机器试。
 */
public final class RecipeReader extends SlimefunItem {

    public RecipeReader(ItemGroup group, SlimefunItemStack item,
                        RecipeType recipeType, ItemStack[] recipe) {
        super(group, item, recipeType, recipe);
        addItemHandler((ItemUseHandler) e -> {
            Player p = e.getPlayer();
            Block b = e.getClickedBlock().orElse(null);
            if (b == null) {
                p.sendMessage("§7对着粘液机器右键，才能读它的配方。");
                return;
            }
            SlimefunItem sf = e.getSlimefunBlock().orElse(null);
            if (sf == null) {
                p.sendMessage("§7这个方块不是粘液科技的物品。");
                return;
            }
            e.cancel();
            List<Scan> all = new ArrayList<>();
            try {
                all.addAll(RecipeScanner.craftable(sf));
                all.addAll(RecipeScanner.generators(sf));
            } catch (Throwable t) {
                p.sendMessage("§c读取配方出错：" + t.getClass().getSimpleName());
                return;
            }
            String name = ChatColor.stripColor(sf.getItemName());
            if (all.isEmpty()) {
                p.sendMessage("§7《" + name + "》扫不出配方。");
                if (RecipeScanner.isMultiblock(sf)) {
                    p.sendMessage("§8它是多方块核心：配方得在结构建成的地方用，知识机器不模拟结构。");
                }
                return;
            }
            p.sendMessage("§7《" + name + "》§f" + all.size() + " §7条配方");
            RecipeViewer.open(p, sf.getItemName(), all);
        });
    }
}
