package com.ncuky.cs.strategy;

/**
 * 名额扣减策略。
 * <p>
 * 四种实现共用这一个接口，业务代码（前置校验、规则校验、结果封装）完全相同，
 * 切换只改配置项 app.deduct-strategy，压测脚本也完全相同。
 * <p>
 * 论文第 6.3 节「四方案对比实验」的公平性，靠的就是这个抽象——
 * 答辩被问「你的对比实验公平吗」，指着这个接口回答。
 */
public interface DeductStrategy {

    /** 策略名，与配置项取值一一对应 */
    String name();

    /**
     * 扣减一个名额。
     *
     * @param classId   教学班
     * @param studentId 学生，一律来自 JWT，不接受前端传参
     * @param roundId   选课轮次
     */
    DeductOutcome deduct(Long classId, Long studentId, Long roundId);
}
