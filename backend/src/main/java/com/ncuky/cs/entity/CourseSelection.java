package com.ncuky.cs.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 选课记录。
 * <p>
 * (student_id, teaching_class_id) 上有联合唯一索引 uk_stu_class，
 * 它是消费幂等的最后一道防线：即使 MQ 消息被重复消费，
 * 第二条插入也会被数据库拦下。幂等不能只靠代码里的判断。
 */
@TableName("course_selection")
public class CourseSelection {

    public static final int STATUS_SELECTED = 1;
    public static final int STATUS_DROPPED = 2;
    public static final int STATUS_WAITING = 3;

    private Long id;
    private Long studentId;
    private Long teachingClassId;
    private Long roundId;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public Long getTeachingClassId() { return teachingClassId; }
    public void setTeachingClassId(Long teachingClassId) { this.teachingClassId = teachingClassId; }
    public Long getRoundId() { return roundId; }
    public void setRoundId(Long roundId) { this.roundId = roundId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
