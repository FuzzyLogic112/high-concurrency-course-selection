import com.ncuky.cs.util.TimeBitmapUtil;
import com.ncuky.cs.util.TimeSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 时间冲突检测对比实验 —— 位图 vs 朴素区间比较。
 *
 * <p>本基准直接调用生产代码 {@link TimeBitmapUtil}，不做任何简化重写，
 * 因此测出来的就是系统里真正跑的那段逻辑。早先用 Python 复刻算法测过一版，
 * 量级关系一致，但语言不同绝对耗时没有可比性，答辩时会被追问
 * 「实现是 Java、数据是 Python」，故以本文件的结果为准。
 *
 * <p>两组实验回答两个不同的问题：
 * <ul>
 *   <li>实验 A：单次校验耗时随「学生已选课程数」如何增长 —— 考察复杂度</li>
 *   <li>实验 B：渲染一整页课程列表需要多久 —— 考察真实负载</li>
 * </ul>
 *
 * <p>实验 A 里区分平均情形与最坏情形，这是必须的：朴素实现一旦命中冲突就提前
 * 返回，已选课程越多越容易早退出，平均耗时反而不随 n 增长，曲线是平的。
 * 只有构造「一定不冲突」的候选课、迫使它扫完全部已选课程，才能暴露 O(n×m)
 * 的真实复杂度。而位图无论哪种情形都只是两次按位与。
 *
 * <p>编译运行（在 verify/bitmapbench 下）：
 * <pre>
 *   javac -cp ../../backend/target/classes -d out BitmapBench.java
 *   java  -cp ../../backend/target/classes;out BitmapBench
 * </pre>
 */
public class BitmapBench {

    /** 每门课的上课时段数：典型排课是一周两次，如「周二3-4节 + 周四3-4节」 */
    private static final int SLOTS_PER_COURSE = 2;
    /** 每个时段跨几节课 */
    private static final int PERIODS_PER_SLOT = 2;

    private static final int WARMUP = 300_000;
    private static final int ITERATIONS = 2_000_000;

    /** 固定种子，保证每次跑出来的课表完全一样，实验可复现 */
    private static final long SEED = 20260919L;

    public static void main(String[] args) {
        System.out.println("时间冲突检测对比实验");
        System.out.println("JVM: " + System.getProperty("java.version")
                + "   时间槽: " + TimeBitmapUtil.DAYS + " 天 × "
                + TimeBitmapUtil.PERIODS + " 节 = " + TimeBitmapUtil.SLOTS + " 槽");
        System.out.println("每门课 " + SLOTS_PER_COURSE + " 个时段，每段 "
                + PERIODS_PER_SLOT + " 节；预热 " + WARMUP + " 次，计时 " + ITERATIONS + " 次");
        System.out.println();

        globalWarmup();
        experimentA();
        System.out.println();
        experimentB();
    }

    /**
     * 全局预热：在任何计时开始之前，把两条代码路径都跑到 JIT 完全编译。
     * <p>
     * 不加这一步的话，循环里第一、二个测点会明显偏慢——初版实测位图在 n=2、4
     * 时是 8.0ms 和 7.7ms，从 n=6 起稳定在 3.7ms。位图耗时本来就与已选课程数
     * 无关，那两个高点纯粹是解释执行阶段的产物，不是算法特性。若直接拿去画图，
     * 会得到一条「先降后平」的假曲线。
     */
    private static void globalWarmup() {
        Random rnd = new Random(SEED);
        Timetable tt = buildTimetable(8, rnd);
        List<List<TimeSlot>> cands = buildFreeCandidates(tt, 64, rnd);
        List<long[]> bms = new ArrayList<>();
        for (List<TimeSlot> c : cands) {
            bms.add(TimeBitmapUtil.encode(c));
        }
        for (int i = 0; i < 2_000_000; i++) {
            if (TimeBitmapUtil.conflictsNaive(cands.get(i % cands.size()), tt.courses)) {
                sink++;
            }
            if (TimeBitmapUtil.conflicts(bms.get(i % bms.size()), tt.bitmap)) {
                sink++;
            }
        }
        System.out.println("（全局预热完成，两条路径均已触发 JIT 编译）");
        System.out.println();
    }

    // ================================================================
    // 实验 A：单次校验耗时 vs 已选课程数
    // ================================================================
    private static void experimentA() {
        System.out.println("── 实验 A：单次冲突校验耗时随已选课程数的变化 ──");
        System.out.printf("%-10s %14s %14s %12s %10s%n",
                "已选课程", "朴素-平均(ms)", "朴素-最坏(ms)", "位图(ms)", "最坏/位图");

        // 已选 2..12 门，覆盖一名学生一学期真实的选课量。
        //
        // 关键的实验设计：所有测点【共用同一套候选课】。
        // 做法是先按最大课表（12 门）构造一批不冲突的候选课，再让 n=2..12
        // 各取该课表的前 n 门作为已选集合——小课表是大课表的子集，所以这批候选课
        // 对每个 n 都同样「不冲突」，最坏情形的性质对所有测点一致成立。
        //
        // 初版不是这么写的：每个 n 各自重新构造候选课，结果位图那一列在
        // n=2、4 时是 8.1ms / 7.6ms，n≥6 后稳定在 3.3ms。排查后发现与 JIT 无关
        // （加了全局预热也照旧），真正的原因是课表越稀疏、能塞下的候选课就越多，
        // 于是 n 小时构造出 64 个互不相同的 long[]，n 大时只找得到少数几个再重复
        // 填充。前者工作集大、后者全部落在 L1 缓存里。那条「先降后平」的曲线
        // 量的是缓存局部性，不是算法复杂度。共用候选集就消除了这个变量。
        Random rnd = new Random(SEED);
        Timetable full = buildTimetable(12, rnd);
        List<List<TimeSlot>> freeCands = buildFreeCandidates(full, 64, rnd);
        List<long[]> freeCandBms = new ArrayList<>();
        for (List<TimeSlot> c : freeCands) {
            freeCandBms.add(TimeBitmapUtil.encode(c));
        }
        // 平均情形：候选课随机生成，可能冲突也可能不冲突
        List<List<TimeSlot>> avgCands = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            avgCands.add(randomCourse(rnd));
        }

        for (int n = 2; n <= 12; n += 2) {
            List<List<TimeSlot>> selected = full.courses.subList(0, n);
            long[] ttBm = TimeBitmapUtil.empty();
            for (List<TimeSlot> c : selected) {
                ttBm = TimeBitmapUtil.merge(ttBm, TimeBitmapUtil.encode(c));
            }

            double naiveAvg = timeNaive(avgCands, selected);
            double naiveWorst = timeNaive(freeCands, selected);
            double bitmap = timeBitmap(freeCandBms, ttBm);

            System.out.printf("%-10d %14.1f %14.1f %12.1f %9.1fx%n",
                    n, naiveAvg, naiveWorst, bitmap, naiveWorst / bitmap);
            csvRows.add(String.format("%d,%.1f,%.1f,%.1f", n, naiveAvg, naiveWorst, bitmap));
        }

        System.out.println();
        System.out.println("CSV: selected_courses,naive_avg_ms,naive_worst_ms,bitmap_ms");
        for (String r : csvRows) {
            System.out.println(r);
        }
    }

    private static final List<String> csvRows = new ArrayList<>();

    // ================================================================
    // 实验 B：渲染一整页课程列表
    // ================================================================
    private static void experimentB() {
        System.out.println("── 实验 B：整页课程列表的冲突标注耗时（已选 10 门，真实场景）──");
        System.out.println("选课页要为列表里每门课标注「是否与我的课表冲突」，");
        System.out.println("因此单页耗时 = 候选课门数 × 单次校验耗时。");
        System.out.printf("%-12s %14s %14s %10s%n", "候选课门数", "朴素(ms)", "位图(ms)", "倍率");

        Random rnd = new Random(SEED);
        Timetable tt = buildTimetable(10, rnd);
        int rounds = 2000;

        for (int pageSize : new int[]{50, 100, 200, 300, 500}) {
            List<List<TimeSlot>> page = buildFreeCandidates(tt, pageSize, rnd);
            List<long[]> pageBms = new ArrayList<>();
            for (List<TimeSlot> c : page) {
                pageBms.add(TimeBitmapUtil.encode(c));
            }

            // 预热
            for (int i = 0; i < 2000; i++) {
                scanPageNaive(page, tt.courses);
                scanPageBitmap(pageBms, tt.bitmap);
            }

            long t0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                sink += scanPageNaive(page, tt.courses);
            }
            double naive = (System.nanoTime() - t0) / 1e6 / rounds;

            t0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                sink += scanPageBitmap(pageBms, tt.bitmap);
            }
            double bm = (System.nanoTime() - t0) / 1e6 / rounds;

            System.out.printf("%-12d %14.4f %14.4f %9.1fx%n", pageSize, naive, bm, naive / bm);
            pageRows.add(String.format("%d,%.4f,%.4f", pageSize, naive, bm));
        }

        System.out.println();
        System.out.println("CSV: page_size,naive_ms,bitmap_ms");
        for (String r : pageRows) {
            System.out.println(r);
        }
        System.out.println();
        System.out.println("（sink=" + sink + "，防止 JIT 把循环体判定为死代码而整段消除）");
    }

    private static final List<String> pageRows = new ArrayList<>();
    private static long sink = 0;

    private static int scanPageNaive(List<List<TimeSlot>> page, List<List<TimeSlot>> selected) {
        int hit = 0;
        for (List<TimeSlot> c : page) {
            if (TimeBitmapUtil.conflictsNaive(c, selected)) {
                hit++;
            }
        }
        return hit;
    }

    private static int scanPageBitmap(List<long[]> page, long[] tt) {
        int hit = 0;
        for (long[] bm : page) {
            if (TimeBitmapUtil.conflicts(bm, tt)) {
                hit++;
            }
        }
        return hit;
    }

    // ================================================================
    // 计时
    // ================================================================
    /**
     * 每个测点重复 {@value #REPEATS} 轮，取最小值。
     * <p>
     * 取最小而不是取平均，是基准测试的通行做法：干扰只会让耗时变长，不会让它变短，
     * 所以最小值最接近「没有干扰时该花多久」，平均值则会被偶发的调度抢占拉高。
     * <p>
     * 这一步是被数据逼出来的。共用候选集之后，朴素那条曲线已经很干净，位图却仍在
     * n=2、4 两个测点偏高（6.9ms / 6.8ms，其余为 3.6ms 上下）。候选集完全相同、
     * JIT 也已预热，唯一还在变的就是测点的先后顺序——靠前的测点跑在 CPU 尚未睿频
     * 的状态下。重复取最小值把这个因素消掉了。
     */
    private static final int REPEATS = 3;

    private static double timeNaive(List<List<TimeSlot>> cands, List<List<TimeSlot>> selected) {
        int m = cands.size();
        for (int i = 0; i < WARMUP; i++) {
            if (TimeBitmapUtil.conflictsNaive(cands.get(i % m), selected)) {
                sink++;
            }
        }
        double best = Double.MAX_VALUE;
        for (int r = 0; r < REPEATS; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                if (TimeBitmapUtil.conflictsNaive(cands.get(i % m), selected)) {
                    sink++;
                }
            }
            best = Math.min(best, (System.nanoTime() - t0) / 1e6);
        }
        return best;
    }

    private static double timeBitmap(List<long[]> cands, long[] tt) {
        int m = cands.size();
        for (int i = 0; i < WARMUP; i++) {
            if (TimeBitmapUtil.conflicts(cands.get(i % m), tt)) {
                sink++;
            }
        }
        double best = Double.MAX_VALUE;
        for (int r = 0; r < REPEATS; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                if (TimeBitmapUtil.conflicts(cands.get(i % m), tt)) {
                    sink++;
                }
            }
            best = Math.min(best, (System.nanoTime() - t0) / 1e6);
        }
        return best;
    }

    // ================================================================
    // 构造数据
    // ================================================================
    private static class Timetable {
        List<List<TimeSlot>> courses = new ArrayList<>();
        long[] bitmap = TimeBitmapUtil.empty();
        boolean[] occupied = new boolean[TimeBitmapUtil.SLOTS];
    }

    /** 构造一张 n 门课、彼此不冲突的真实课表 */
    private static Timetable buildTimetable(int n, Random rnd) {
        Timetable tt = new Timetable();
        int guard = 0;
        while (tt.courses.size() < n && guard++ < 100000) {
            List<TimeSlot> course = randomCourse(rnd);
            if (fits(tt, course)) {
                tt.courses.add(course);
                long[] bm = TimeBitmapUtil.encode(course);
                tt.bitmap = TimeBitmapUtil.merge(tt.bitmap, bm);
                mark(tt, course);
            }
        }
        if (tt.courses.size() < n) {
            throw new IllegalStateException("无法构造 " + n + " 门互不冲突的课程，请减小 n");
        }
        return tt;
    }

    /** 候选课必须完全落在空闲槽位上，这样朴素实现无法提前返回 */
    private static List<List<TimeSlot>> buildFreeCandidates(Timetable tt, int count, Random rnd) {
        List<List<TimeSlot>> out = new ArrayList<>();
        int guard = 0;
        while (out.size() < count && guard++ < 1000000) {
            List<TimeSlot> c = randomCourse(rnd);
            if (fits(tt, c)) {
                out.add(c);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("课表已排满，构造不出不冲突的候选课");
        }
        // 空闲槽位有限时凑不满 count，把已构造出来的循环补齐。
        // 补进去的仍然是「不冲突」的课程，最坏情形的性质不变，不影响耗时测量。
        int distinct = out.size();
        for (int i = 0; out.size() < count; i++) {
            out.add(out.get(i % distinct));
        }
        return out;
    }

    private static boolean fits(Timetable tt, List<TimeSlot> course) {
        for (TimeSlot s : course) {
            for (int p = s.start(); p <= s.end(); p++) {
                if (tt.occupied[(s.day() - 1) * TimeBitmapUtil.PERIODS + (p - 1)]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void mark(Timetable tt, List<TimeSlot> course) {
        for (TimeSlot s : course) {
            for (int p = s.start(); p <= s.end(); p++) {
                tt.occupied[(s.day() - 1) * TimeBitmapUtil.PERIODS + (p - 1)] = true;
            }
        }
    }

    private static List<TimeSlot> randomCourse(Random rnd) {
        List<TimeSlot> out = new ArrayList<>(SLOTS_PER_COURSE);
        for (int i = 0; i < SLOTS_PER_COURSE; i++) {
            // 在完整的 7 天上取值，与位图的定义域一致。
            // 若限制为周一至周五，12 门课就会占掉 5×12=60 个槽位中的 48 个，
            // 余下的空闲槽排不出足够多互不相同的候选课，反而会把工作集压进 L1 缓存
            int day = 1 + rnd.nextInt(TimeBitmapUtil.DAYS);
            int start = 1 + rnd.nextInt(TimeBitmapUtil.PERIODS - PERIODS_PER_SLOT + 1);
            out.add(new TimeSlot(day, start, start + PERIODS_PER_SLOT - 1));
        }
        return out;
    }
}
