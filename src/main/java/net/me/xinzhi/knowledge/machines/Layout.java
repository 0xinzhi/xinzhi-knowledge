package net.me.xinzhi.knowledge.machines;

/**
 * 三种机器的槽位表（从腐竹 2026-11-07 的三张界面图反推）。
 *
 * <h2>配方机器 A「输出多」（图1）</h2>
 * 机器所在列 = 第 3 列（0 基下标 2）。左侧 2 列 = 输入（12 格），右侧 6 列 = 输出（36 格），
 * 机器列本身是保留区（橙色），机器槽 20、配方选择 29。
 *
 * <h2>配方机器 B「输入多」（图2）</h2>
 * 机器所在列 = 第 7 列（0 基下标 6）。左侧 6 列 = 输入（36 格），右侧 2 列 = 输出（12 格），
 * 机器槽 24、配方选择 33。
 *
 * <h2>生成器（图3）</h2>
 * 上五排 0–44 = 输出；机器槽 49（识别用）；模板区 48；49 右边的 50–53 是附加机器槽
 * （同款机器叠台数）；45 状态、46/47 保留。
 *
 * <p>⚠ 界面里的箱子/眼睛图标只是腐竹标出来的「这里放机器 / 这里点配方」，
 * 机器列的其他格子一律留空，放东西会干扰识别。
 */
public final class Layout {

    private Layout() { }

    // ---------------- 配方机器 A：输出 6 列 ----------------

    public static final int[] CRAFT_A_INPUT = {
            0, 1,
            9, 10,
            18, 19,
            27, 28,
            36, 37,
            45, 46
    };

    public static final int[] CRAFT_A_OUTPUT = {
            3, 4, 5, 6, 7, 8,
            12, 13, 14, 15, 16, 17,
            21, 22, 23, 24, 25, 26,
            30, 31, 32, 33, 34, 35,
            39, 40, 41, 42, 43, 44,
            48, 49, 50, 51, 52, 53
    };

    public static final int CRAFT_A_MACHINE = 20;
    public static final int CRAFT_A_RECIPE = 29;
    public static final int CRAFT_A_STATUS = 2;
    /** 机器列里剩下的 3 格：同款机器叠台数（不参与配方识别） */
    public static final int[] CRAFT_A_EXTRA_MACHINE = {11, 38, 47};

    // ---------------- 配方机器 B：输入 6 列 ----------------

    public static final int[] CRAFT_B_INPUT = {
            0, 1, 2, 3, 4, 5,
            9, 10, 11, 12, 13, 14,
            18, 19, 20, 21, 22, 23,
            27, 28, 29, 30, 31, 32,
            36, 37, 38, 39, 40, 41,
            45, 46, 47, 48, 49, 50
    };

    public static final int[] CRAFT_B_OUTPUT = {
            7, 8,
            16, 17,
            25, 26,
            34, 35,
            43, 44,
            52, 53
    };

    public static final int CRAFT_B_MACHINE = 24;
    public static final int CRAFT_B_RECIPE = 33;
    public static final int CRAFT_B_STATUS = 6;
    /** 机器列里剩下的 3 格：同款机器叠台数（不参与配方识别） */
    public static final int[] CRAFT_B_EXTRA_MACHINE = {15, 42, 51};

    // ---------------- 生成器 ----------------

    public static final int[] GEN_OUTPUT = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44
    };

    /** 模板区：识别槽左边那一格（放卡片/模具，认配方用） */
    public static final int[] GEN_TEMPLATE = {48};
    public static final int GEN_MACHINE = 49;
    /** 识别槽右边的 4 格：同款机器叠台数（腐竹 2026-11-08） */
    public static final int[] GEN_EXTRA_MACHINE = {50, 51, 52, 53};

    /** 状态显示格（信息面板，不可点） */
    public static final int STATUS = 45;

    // ---------------- 知识发电机（腐竹 2026-11-08：中列显示，左边燃料，右边发电机） ----------------

    /** 左边 4 列 = 燃料（36 格里取 24 格：0-3 列 × 6 行） */
    public static final int[] GEN2_FUEL = {
            0, 1, 2, 3,
            9, 10, 11, 12,
            18, 19, 20, 21,
            27, 28, 29, 30,
            36, 37, 38, 39,
            45, 46, 47, 48
    };

    /** 右边 4 列 = 发电机（5-8 列 × 6 行）；第一格 5 拿来识别，其余叠同款 */
    public static final int[] GEN2_MACHINE = {
            5, 6, 7, 8,
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35,
            41, 42, 43, 44,
            50, 51, 52, 53
    };

    /** 中列（0 基第 5 列）只用来显示：4 状态卡、13 发电信息 */
    public static final int GEN2_STATUS = 4;
    public static final int GEN2_INFO = 13;
    /** 过载按钮（腐竹 2026-11-08：学等价附属 EMC 发电机的过载输出） */
    public static final int GEN2_OVERLOAD = 22;

    public static boolean contains(int[] arr, int v) {
        for (int i : arr) if (i == v) return true;
        return false;
    }
}
