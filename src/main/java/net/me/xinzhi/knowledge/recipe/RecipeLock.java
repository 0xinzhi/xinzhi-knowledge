package net.me.xinzhi.knowledge.recipe;

import net.me.xinzhi.knowledge.machines.MachineUtil;
import org.bukkit.Location;

import java.util.List;

/**
 * 配方锁定（腐竹 2026-11-15：「无论怎么锁定都是使用第一条」）。
 *
 * <h2>为什么改成「锁签名」而不是锁下标</h2>
 * 原来只存一个下标（{@code xzk-recipe=178}），机器再去 {@code scans.get(178)} 取。
 * 下标只有在「选择时看到的列表」和「tick 时用的列表」是同一份、同一顺序时才对得上：
 * 列表一旦重建（{@code /xzk reload} 清缓存、附属热载、扫描规则变化），同一个下标指向的
 * 就是另一条配方 —— 而且越界时会<b>静默回落到自动模式</b>（永远跑第一条能跑的），
 * 表现正是玩家说的「怎么锁都是第一条」。
 *
 * <p>签名取自配方内容本身（扣料表 + 产物表 + 耗时 + 随机候选），与顺序无关；
 * 老数据里只有下标的话，解析成功时顺手把签名补写进去（迁移），以后不再受顺序影响。
 */
public final class RecipeLock {

    /** 下标（老数据 / 配方界面高亮用） */
    public static final String KEY_INDEX = "xzk-recipe";
    /** 内容签名（真正用来定位的那一份） */
    public static final String KEY_SIG = "xzk-recipe-sig";

    private RecipeLock() {
    }

    /** 锁定一条配方：下标给界面高亮用，签名用来真正定位 */
    public static void write(Location loc, Scan s, int idx) {
        MachineUtil.set(loc, KEY_INDEX, String.valueOf(idx));
        MachineUtil.set(loc, KEY_SIG, s == null ? null : s.signature());
    }

    /** 取消锁定 */
    public static void clear(Location loc) {
        MachineUtil.unset(loc, KEY_INDEX);
        MachineUtil.unset(loc, KEY_SIG);
    }

    /** 玩家是不是选过配方（值可能已经失效，例如换了机器 / 配方被过滤掉） */
    public static boolean requested(Location loc) {
        return MachineUtil.info(loc, KEY_SIG) != null || MachineUtil.intInfo(loc, KEY_INDEX, -1) >= 0;
    }

    /**
     * 解析出被锁定的那条配方。
     *
     * @return 锁定的配方；{@code null} = 没选 / 选的这条已经不在这台机器的列表里
     *         （用 {@link #requested(Location)} 区分这两种情况 —— 后者要给玩家一句明话，
     *         绝不能静默换一条跑）
     */
    public static Scan resolve(Location loc, List<Scan> scans) {
        if (scans == null || scans.isEmpty()) return null;
        String sig = MachineUtil.info(loc, KEY_SIG);
        if (sig != null) {
            for (Scan s : scans) {
                if (sig.equals(s.signature())) return s;
            }
            return null;
        }
        // 老数据只有下标：能对上的话顺手迁移成签名
        int idx = MachineUtil.intInfo(loc, KEY_INDEX, -1);
        if (idx >= 0 && idx < scans.size()) {
            Scan s = scans.get(idx);
            MachineUtil.set(loc, KEY_SIG, s.signature());
            return s;
        }
        return null;
    }
}
