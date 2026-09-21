package com.ncuky.cs.strategy;

import com.ncuky.cs.common.ResultCode;

/** 名额扣减的结果。四种策略共用同一套返回语义，对比实验才有可比性。 */
public enum DeductOutcome {

    /** 同步策略：已落库，选上了 */
    SUCCESS(ResultCode.OK),
    /** 异步策略：已受理，结果稍后查 */
    ACCEPTED(ResultCode.ACCEPTED),
    FULL(ResultCode.CLASS_FULL),
    DUPLICATE(ResultCode.ALREADY_SELECTED),
    NOT_WARMED(ResultCode.NOT_WARMED_UP),
    /** 乐观锁重试次数用尽。并发越高越容易出现，这正是该方案的代价 */
    RETRY_EXHAUSTED(ResultCode.CLASS_FULL),
    CLASS_INVALID(ResultCode.CLASS_NOT_FOUND);

    private final ResultCode resultCode;

    DeductOutcome(ResultCode rc) {
        this.resultCode = rc;
    }

    public ResultCode resultCode() {
        return resultCode;
    }

    public boolean accepted() {
        return this == SUCCESS || this == ACCEPTED;
    }
}
