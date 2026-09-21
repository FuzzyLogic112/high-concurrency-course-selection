package com.ncuky.cs.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 候补队列。有人退选后按 seq 顺序自动补位。 */
@TableName("waiting_queue")
public class WaitingQueue {

    public static final int STATUS_WAITING = 0;
    public static final int STATUS_PROMOTED = 1;
    public static final int STATUS_CANCELLED = 2;

    private Long id;
    private Long teachingClassId;
    private Long studentId;
    private Integer seq;
    private Integer status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTeachingClassId() { return teachingClassId; }
    public void setTeachingClassId(Long teachingClassId) { this.teachingClassId = teachingClassId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
