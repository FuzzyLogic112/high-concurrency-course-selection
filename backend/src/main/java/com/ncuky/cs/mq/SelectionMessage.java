package com.ncuky.cs.mq;

/**
 * 选课落库消息。
 *
 * @param studentId 学生
 * @param classId   教学班
 * @param roundId   选课轮次
 */
public record SelectionMessage(Long studentId, Long classId, Long roundId) {

    /** 业务唯一键。本地消息表上有唯一索引，保证同一次选课只会产生一条消息 */
    public String bizKey() {
        return studentId + ":" + classId;
    }
}
