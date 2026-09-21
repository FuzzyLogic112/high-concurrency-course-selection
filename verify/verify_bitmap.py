# -*- coding: utf-8 -*-
"""
上课时间冲突检测：时间位图 vs 朴素区间比较

毕业设计《基于缓存与消息队列的高校选课系统的设计与实现》
第 6.4 节「冲突检测算法对比实验」。

时间槽编码：一周 7 天 × 每天 12 节 = 84 个槽
           槽位下标 = (day - 1) * 12 + (period - 1)，day ∈ [1,7]，period ∈ [1,12]

  naive   待选课程的每个时段 × 已选课程的每个时段，两两比较区间是否重叠，O(n×m)
  bitmap  两个 84 位位图做一次按位与，非零即冲突，O(1)

预期：naive 的耗时随已选课程数线性增长，bitmap 那条线是平的。

用法：
  python verify/verify_bitmap.py
  python verify/verify_bitmap.py --iterations 200000
"""
import argparse
import random
import time

DAYS = 7
PERIODS = 12


# ---------------------------------------------------------------- 两种实现

def to_bitmap(slots):
    """[(day, start, end), ...] -> 84 位整数位图"""
    bm = 0
    for day, start, end in slots:
        for p in range(start, end + 1):
            bm |= 1 << ((day - 1) * PERIODS + (p - 1))
    return bm


def conflict_bitmap(class_bm, timetable_bm):
    """一次按位与，与已选课程数无关"""
    return (class_bm & timetable_bm) != 0


def conflict_naive(class_slots, selected_slots_list):
    """两两比较区间：同一天且区间有重叠即冲突"""
    for day_a, s_a, e_a in class_slots:
        for slots in selected_slots_list:
            for day_b, s_b, e_b in slots:
                if day_a == day_b and not (e_a < s_b or e_b < s_a):
                    return True
    return False


# ---------------------------------------------------------------- 数据构造

def random_slots(rng, n_sessions=2, max_len=2):
    """随机生成一门课的上课时段"""
    slots = []
    for _ in range(n_sessions):
        day = rng.randint(1, DAYS)
        length = rng.randint(1, max_len)
        start = rng.randint(1, PERIODS - length + 1)
        slots.append((day, start, start + length - 1))
    return slots


def build_timetable(rng, n_courses):
    """构造一张互不冲突的课表，返回 (时段列表的列表, 合并位图)"""
    chosen, total_bm = [], 0
    guard = 0
    while len(chosen) < n_courses and guard < n_courses * 400:
        guard += 1
        slots = random_slots(rng)
        bm = to_bitmap(slots)
        if bm & total_bm:
            continue
        chosen.append(slots)
        total_bm |= bm
    return chosen, total_bm


def build_free_candidates(rng, timetable_bm, count):
    """构造一批与课表不冲突的候选课程。

    这是 naive 实现的最坏情形：没有任何冲突可以提前返回，
    必须把已选课程的每个时段都扫一遍才能得出结论。
    O(n×m) 的线性增长只有在这组数据上才看得出来。
    """
    out, guard = [], 0
    while len(out) < count and guard < count * 500:
        guard += 1
        slots = random_slots(rng)
        if to_bitmap(slots) & timetable_bm:
            continue
        out.append(slots)
    return out


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(description="时间冲突检测算法对比")
    ap.add_argument("--iterations", type=int, default=100000, help="每档跑多少次检测")
    ap.add_argument("--sizes", default="5,10,15,20", help="已选课程数，逗号分隔")
    ap.add_argument("--seed", type=int, default=20270618)
    args = ap.parse_args()

    sizes = [int(x) for x in args.sizes.split(",")]
    rng = random.Random(args.seed)

    # 正确性自检：两种实现在随机样本上必须给出完全一致的判定
    mismatch = 0
    for _ in range(5000):
        sel, tt_bm = build_timetable(rng, 8)
        cand = random_slots(rng)
        if conflict_naive(cand, sel) != conflict_bitmap(to_bitmap(cand), tt_bm):
            mismatch += 1
    if mismatch:
        raise SystemExit(f"两种实现判定不一致 {mismatch} 次，位图编码有 bug，先修这个再跑性能")
    print("正确性自检通过：5000 组随机样本上两种实现判定完全一致\n")

    print(f"每档 {args.iterations} 次检测\n")
    print("说明：naive 一旦命中冲突就提前返回，所以要分两种情形测——")
    print("  平均情形  随机候选，可能很早就撞上冲突")
    print("  最坏情形  候选与课表不冲突，必须扫完全部已选课程（这才是 O(n×m) 的真实体现）\n")

    header = (f"{'已选课程数':<12}{'naive平均(ms)':>16}{'naive最坏(ms)':>16}"
              f"{'bitmap(ms)':>13}{'最坏/位图':>12}")
    print(header)
    print("-" * 72)

    rows = []
    for n in sizes:
        sel, tt_bm = build_timetable(rng, n)
        rand_cands = [random_slots(rng) for _ in range(200)]
        free_cands = build_free_candidates(rng, tt_bm, 200)
        if not free_cands:                      # 课表太满，找不到不冲突的候选
            free_cands = rand_cands
        cand_bms = [to_bitmap(c) for c in rand_cands]

        t0 = time.perf_counter()
        for i in range(args.iterations):
            conflict_naive(rand_cands[i % 200], sel)
        t_avg = (time.perf_counter() - t0) * 1000

        t0 = time.perf_counter()
        m = len(free_cands)
        for i in range(args.iterations):
            conflict_naive(free_cands[i % m], sel)
        t_worst = (time.perf_counter() - t0) * 1000

        t0 = time.perf_counter()
        for i in range(args.iterations):
            conflict_bitmap(cand_bms[i % 200], tt_bm)
        t_bitmap = (time.perf_counter() - t0) * 1000

        ratio = t_worst / t_bitmap if t_bitmap > 0 else 0
        rows.append((n, t_avg, t_worst, t_bitmap, ratio))
        print(f"{n:<12}{t_avg:>16.1f}{t_worst:>16.1f}{t_bitmap:>13.1f}{ratio:>11.1f}x")

    print("\n--- 以下可直接复制进 Excel 或喂给 Matplotlib 画图 6-4 ---")
    print("selected_courses,naive_avg_ms,naive_worst_ms,bitmap_ms")
    for n, ta, tw, tb, _ in rows:
        print(f"{n},{ta:.1f},{tw:.1f},{tb:.1f}")

    print("\n答辩预判：老师会问「为什么平均情形那条线是平的」。"
          "标准答法——naive 命中冲突就提前返回，已选课程越多越容易早退出，"
          "所以平均耗时反而不增长；只有最坏情形（无冲突、必须全扫）"
          "才暴露出 O(n×m) 的真实复杂度。而位图无论哪种情形都是一次按位与。")

    print("\n注：本脚本用 Python 验证算法思路与量级关系，"
          "论文正式数据请用 Java 实现（BitSet 或两个 long）重测，"
          "语言不同绝对耗时会有差异，但两条曲线的形状是一致的。")


if __name__ == "__main__":
    main()
