package com.ncuky.cs.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 上课时间位图。
 * <p>
 * 把一周离散为 {@value #DAYS} 天 × 每天 {@value #PERIODS} 节 = {@value #SLOTS} 个时间槽，
 * 槽位下标 = (day - 1) * PERIODS + (period - 1)，用两个 long 承载（低 84 位有效）。
 * <p>
 * 冲突判定因此退化为两次按位与，耗时与学生已选课程数无关；
 * 而朴素实现需要把待选课程的每个时段与已选课程的每个时段两两比较，复杂度 O(n×m)。
 * <p>
 * 槽位数是可调的：若学校开设晚间第 13、14 节，把 {@link #PERIODS} 改成 14 即可
 * （7 × 14 = 98 位，仍在两个 long 的容量内）。
 * <p>
 * 本类不依赖 Spring，可单独编译与单元测试。
 */
public final class TimeBitmapUtil {

    /** 一周天数 */
    public static final int DAYS = 7;
    /** 每天节次数 */
    public static final int PERIODS = 12;
    /** 时间槽总数 */
    public static final int SLOTS = DAYS * PERIODS;

    private TimeBitmapUtil() {
    }

    static {
        if (SLOTS > 128) {
            throw new IllegalStateException("时间槽超过 128 个，两个 long 装不下，需改用 BitSet");
        }
    }

    /** 空位图：{lo, hi} */
    public static long[] empty() {
        return new long[]{0L, 0L};
    }

    /** 把一组时段编码成位图 */
    public static long[] encode(List<TimeSlot> slots) {
        long[] bm = empty();
        if (slots == null) {
            return bm;
        }
        for (TimeSlot s : slots) {
            for (int p = s.start(); p <= s.end(); p++) {
                setBit(bm, (s.day() - 1) * PERIODS + (p - 1));
            }
        }
        return bm;
    }

    private static void setBit(long[] bm, int idx) {
        if (idx < 64) {
            bm[0] |= 1L << idx;
        } else {
            bm[1] |= 1L << (idx - 64);
        }
    }

    private static boolean getBit(long[] bm, int idx) {
        return idx < 64
                ? (bm[0] & (1L << idx)) != 0
                : (bm[1] & (1L << (idx - 64))) != 0;
    }

    /**
     * 冲突判定：一次按位与，与已选课程数无关。
     * 这就是本系统把 O(n×m) 降到常数级的地方。
     */
    public static boolean conflicts(long[] a, long[] b) {
        return (a[0] & b[0]) != 0 || (a[1] & b[1]) != 0;
    }

    /** 合并两张位图（把一门新课并进学生课表） */
    public static long[] merge(long[] a, long[] b) {
        return new long[]{a[0] | b[0], a[1] | b[1]};
    }

    /** 位图中已占用的槽位数量 */
    public static int cardinality(long[] bm) {
        return Long.bitCount(bm[0]) + Long.bitCount(bm[1]);
    }

    /**
     * 列出具体冲突在哪几节课，用于给学生一条能看懂的提示，
     * 而不是干巴巴一句「时间冲突」。
     *
     * @return 形如 ["周三第3节", "周三第4节"]
     */
    public static List<String> describeConflicts(long[] a, long[] b) {
        List<String> out = new ArrayList<>();
        long lo = a[0] & b[0];
        long hi = a[1] & b[1];
        for (int idx = 0; idx < SLOTS; idx++) {
            boolean hit = idx < 64
                    ? (lo & (1L << idx)) != 0
                    : (hi & (1L << (idx - 64))) != 0;
            if (hit) {
                out.add("周" + WEEK[idx / PERIODS] + "第" + (idx % PERIODS + 1) + "节");
            }
        }
        return out;
    }

    private static final String[] WEEK = {"一", "二", "三", "四", "五", "六", "日"};

    /** 位图转可读文本，调试与日志用 */
    public static String toReadable(long[] bm) {
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < SLOTS; idx++) {
            if (getBit(bm, idx)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append("周").append(WEEK[idx / PERIODS]).append('-').append(idx % PERIODS + 1);
            }
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }

    // ---------------------------------------------------------------
    // 朴素实现：只用于第 6.4 节的对比实验，生产路径不走这里
    // ---------------------------------------------------------------

    /**
     * 朴素的区间两两比较，O(n×m)。
     * 保留它是为了论文第 6.4 节能在同一套代码里做公平对比。
     */
    public static boolean conflictsNaive(List<TimeSlot> candidate, List<List<TimeSlot>> selected) {
        for (TimeSlot a : candidate) {
            for (List<TimeSlot> course : selected) {
                for (TimeSlot b : course) {
                    if (a.day() == b.day() && !(a.end() < b.start() || b.end() < a.start())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
