package com.ncuky.cs.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 本地消息表。
 * <p>
 * 与业务在同一个数据库事务里写入，再投递 MQ。
 * 定时任务扫描未确认的记录重投，配合指数退避。
 * 这张表兜的是「Redis 扣了名额但消息没投出去」这个风险——
 * 没有它，服务在投递瞬间宕机就会永久丢掉一条选课。
 */
@TableName("local_message")
public class LocalMessage {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_FAILED = 2;

    private Long id;
    /** 业务唯一键，学生id:教学班id。唯一索引保证同一次选课只会有一条消息 */
    private String bizKey;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
