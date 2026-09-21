package com.ncuky.cs.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("selection_round")
public class SelectionRound {

    private Long id;
    private String name;
    private String term;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String targetGrades;
    private String targetMajors;
    /** 0 未预热 1 已把名额加载进 Redis */
    private Integer warmedUp;
    /** 0 未开始 1 进行中 2 已结束 */
    private Integer status;

    public boolean isOpenNow() {
        LocalDateTime now = LocalDateTime.now();
        return startTime != null && endTime != null
                && !now.isBefore(startTime) && !now.isAfter(endTime);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTerm() { return term; }
    public void setTerm(String term) { this.term = term; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public String getTargetGrades() { return targetGrades; }
    public void setTargetGrades(String targetGrades) { this.targetGrades = targetGrades; }
    public String getTargetMajors() { return targetMajors; }
    public void setTargetMajors(String targetMajors) { this.targetMajors = targetMajors; }
    public Integer getWarmedUp() { return warmedUp; }
    public void setWarmedUp(Integer warmedUp) { this.warmedUp = warmedUp; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
