package com.ncuky.cs.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("teaching_class")
public class TeachingClass {

    private Long id;
    private String classNo;
    private Long courseId;
    private Long teacherId;
    private String teacherName;
    private String term;
    /** 容量上限，硬约束，不可突破 */
    private Integer capacity;
    /** 已选人数。redis_mq 策略下由 MQ 消费者更新 */
    private Integer selectedCount;
    /** 乐观锁版本号。optimistic 策略在 SQL 里显式做 CAS，不走 MyBatis-Plus 的拦截器 */
    private Integer version;
    /** 上课时段 JSON，如 [{"day":3,"start":3,"end":4}] */
    private String timeSlots;
    /** 时间槽位图高 64 位（实际只用低 20 位） */
    private Long timeBitmapHi;
    /** 时间槽位图低 64 位 */
    private Long timeBitmapLo;
    private String location;
    /** 限定专业 id 数组 JSON，为空表示不限 */
    private String targetMajors;
    /** 限定年级数组 JSON，为空表示不限 */
    private String targetGrades;
    /** 0 停开 1 正常 */
    private Integer status;

    public long[] bitmap() {
        return new long[]{
                timeBitmapLo == null ? 0L : timeBitmapLo,
                timeBitmapHi == null ? 0L : timeBitmapHi
        };
    }

    public void setBitmap(long[] bm) {
        this.timeBitmapLo = bm[0];
        this.timeBitmapHi = bm[1];
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClassNo() { return classNo; }
    public void setClassNo(String classNo) { this.classNo = classNo; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Integer getSelectedCount() { return selectedCount; }
    public void setSelectedCount(Integer selectedCount) { this.selectedCount = selectedCount; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getTimeSlots() { return timeSlots; }
    public void setTimeSlots(String timeSlots) { this.timeSlots = timeSlots; }
    public Long getTimeBitmapHi() { return timeBitmapHi; }
    public void setTimeBitmapHi(Long timeBitmapHi) { this.timeBitmapHi = timeBitmapHi; }
    public Long getTimeBitmapLo() { return timeBitmapLo; }
    public void setTimeBitmapLo(Long timeBitmapLo) { this.timeBitmapLo = timeBitmapLo; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getTargetMajors() { return targetMajors; }
    public void setTargetMajors(String targetMajors) { this.targetMajors = targetMajors; }
    public String getTargetGrades() { return targetGrades; }
    public void setTargetGrades(String targetGrades) { this.targetGrades = targetGrades; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
