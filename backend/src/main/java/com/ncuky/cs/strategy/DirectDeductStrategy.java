package com.ncuky.cs.strategy;

import org.springframework.stereotype.Component;

/** 方案 A：直接扣减，无任何并发保护。论文里的反面基线。 */
@Component
public class DirectDeductStrategy implements DeductStrategy {

    private final SelectionWriter writer;

    public DirectDeductStrategy(SelectionWriter writer) {
        this.writer = writer;
    }

    @Override
    public String name() {
        return "direct";
    }

    @Override
    public DeductOutcome deduct(Long classId, Long studentId, Long roundId) {
        return writer.direct(classId, studentId, roundId);
    }
}
