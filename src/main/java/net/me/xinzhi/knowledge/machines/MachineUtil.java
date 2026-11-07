package net.me.xinzhi.knowledge.machines;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 机器公用小工具：方块数据读写、主人登记、昵称缓存。 */
public final class MachineUtil {

    private static final String KEY_OWNER = "xzk-owner";

    private MachineUtil() { }

    public static String info(Location loc, String key) {
        try {
            return BlockStorage.getLocationInfo(loc, key);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void set(Location loc, String key, String value) {
        try {
            BlockStorage.addBlockInfo(loc, key, value);
        } catch (Throwable ignored) {
        }
    }

    public static void unset(Location loc, String key) {
        try {
            BlockStorage.addBlockInfo(loc, key, null);
        } catch (Throwable ignored) {
        }
    }

    public static int intInfo(Location loc, String key, int def) {
        String s = info(loc, key);
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static long longInfo(Location loc, String key, long def) {
        String s = info(loc, key);
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static UUID owner(Location loc) {
        String s = info(loc, KEY_OWNER);
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setOwner(Location loc, Player p) {
        if (p == null || loc == null) return;
        set(loc, KEY_OWNER, p.getUniqueId().toString());
    }

    public static UUID ownerOrClaim(Location loc, Player p) {
        UUID id = owner(loc);
        if (id == null && p != null) {
            setOwner(loc, p);
            id = p.getUniqueId();
        }
        return id;
    }

    private static final Map<UUID, String> NAME_CACHE = new ConcurrentHashMap<>();

    public static String ownerName(Location loc) {
        UUID id = owner(loc);
        if (id == null) return "未登记";
        String cached = NAME_CACHE.get(id);
        if (cached != null) return cached;
        String name = null;
        Player online = Bukkit.getPlayer(id);
        if (online != null && online.isOnline()) {
            name = online.getName();
        } else {
            try {
                OfflinePlayer off = Bukkit.getOfflinePlayer(id);
                if (off != null) name = off.getName();
            } catch (Throwable ignored) {
            }
        }
        if (name == null || name.isBlank()) return "未知(" + id.toString().substring(0, 8) + ")";
        NAME_CACHE.put(id, name);
        return name;
    }

    /** 坐标的可读描述（日志/诊断用） */
    public static String describe(Location loc) {
        if (loc == null || loc.getWorld() == null) return "?";
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /** 机器槽里的物品对应的 SlimefunItem（不是机器就返回 null） */
    public static SlimefunItem machineIn(org.bukkit.inventory.ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        try {
            return SlimefunItem.getByItem(it);
        } catch (Throwable t) {
            return null;
        }
    }
}