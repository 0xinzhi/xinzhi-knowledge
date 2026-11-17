package net.me.xinzhi.knowledge.items;

import net.me.xinzhi.knowledge.core.GeneratorCard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 生成器卡 / 基础生成器是「本来能摆」的材质（篝火、磁石）。
 *
 * <p>一旦被当成方块摆下去，方块里没有 PDC，拆回来就是一件普通篝火/磁石
 * —— 卡上的产物和间隔全丢。所以直接拦住摆放。
 *
 * <p>腐竹 2026-11-15 第 3 条：「禁止摆放没拦到位」。只挂 {@link BlockPlaceEvent} 不够 ——
 * 有的路径（副手、别的插件的放置流程、客户端预判）会先走右键交互，所以再压一层
 * {@link PlayerInteractEvent}：只要手里拿着卡点方块，先一步取消。
 */
public final class CardPlaceGuard implements Listener {

    private static boolean blocked(ItemStack it) {
        return GeneratorCard.isCard(it) || GeneratorCard.isBase(it);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlaceLowest(BlockPlaceEvent e) {
        if (blocked(e.getItemInHand()) || blocked(e.getPlayer().getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getItemInHand()) || blocked(e.getPlayer().getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    /**
     * 右键方块时手里拿着卡：直接吞掉这次交互（不取消整个事件会让客户端先摆出去）。
     * 只处理「右键方块」，空挥/右键空气不用管；主副手都看。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (blocked(e.getItem())) {
            e.setCancelled(true);
            return;
        }
        if (e.getHand() != EquipmentSlot.OFF_HAND
                && blocked(e.getPlayer().getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }
}
