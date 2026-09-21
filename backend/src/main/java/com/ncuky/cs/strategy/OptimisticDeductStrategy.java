package com.ncuky.cs.strategy;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 方案 C：版本号 CAS + 重试。
 * <p>
 * 不阻塞，但高并发下大量请求 CAS 失败后重试，重试率随并发飙升——
 * 前置验证脚本里 500 并发抢 50 个名额时重试了 3273 次，
 * CPU 大量空转在失败重试上。这就是它的代价。
 * <p>
 * 重试计数暴露出来，供论文第 6.3 节取数。
 */
@Component
public class OptimisticDeductStrategy implements DeductStrategy {

    /** 重试上限。用尽仍失败就放弃，避免极端并发下无限空转 */
    private static final int MAX_RETRY = 50;

    private final SelectionWriter writer;
    private final LongAdder retryCounter = new LongAdder();

    public OptimisticDeductStrategy(SelectionWriter writer) {
        this.writer = writer;
    }

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public DeductOutcome deduct(Long classId, Long studentId, Long roundId) {
        for (int i = 0; i < MAX_RETRY; i++) {
            // 每次都是一个新事务，否则可重复读会让重试永远看到旧版本号
            DeductOutcome r = writer.casOnce(classId, studentId, roundId);
            if (r != null) {
                return r;
            }
            retryCounter.increment();
        }
        return DeductOutcome.RETRY_EXHAUSTED;
    }

    public long retryCount() {
        return retryCounter.sum();
    }

    public void resetRetryCount() {
        retryCounter.reset();
    }
}
