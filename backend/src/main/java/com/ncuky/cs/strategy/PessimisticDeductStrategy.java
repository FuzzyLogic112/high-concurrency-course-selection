package com.ncuky.cs.strategy;

import org.springframework.stereotype.Component;

/** 方案 B：SELECT ... FOR UPDATE 行锁。正确，但请求被串行化，吞吐随并发劣化。 */
@Component
public class PessimisticDeductStrategy implements DeductStrategy {

    private final SelectionWriter writer;

    public PessimisticDeductStrategy(SelectionWriter writer) {
        this.writer = writer;
    }

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public DeductOutcome deduct(Long classId, Long studentId, Long roundId) {
        return writer.pessimistic(classId, studentId, roundId);
    }
}
