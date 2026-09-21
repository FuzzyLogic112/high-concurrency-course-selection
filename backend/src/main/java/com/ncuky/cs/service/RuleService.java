package com.ncuky.cs.service;

import com.ncuky.cs.common.ResultCode;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.Course;
import com.ncuky.cs.entity.SelectionRound;
import com.ncuky.cs.entity.Student;
import com.ncuky.cs.entity.TeachingClass;
import com.ncuky.cs.mapper.CourseMapper;
import com.ncuky.cs.mapper.CourseSelectionMapper;
import com.ncuky.cs.util.TimeBitmapUtil;
import com.ncuky.cs.util.TimeSlotParser;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 选课规则校验。
 * <p>
 * 全部放在扣减之前做，不通过就快速失败，请求不进入后续链路——
 * 这样在选课高峰期，大量注定失败的请求不会挤占 Redis 和消息队列的资源。
 */
@Service
public class RuleService {

    private final CourseSelectionMapper selectionMapper;
    private final CourseMapper courseMapper;

    public RuleService(CourseSelectionMapper selectionMapper, CourseMapper courseMapper) {
        this.selectionMapper = selectionMapper;
        this.courseMapper = courseMapper;
    }

    /**
     * 四项校验：重复选课、专业年级限制、时间冲突、学分上限、先修课程。
     * 顺序按「代价从低到高」排，便宜的判断先做。
     */
    public Dtos.PreCheckResp check(Student student, TeachingClass tc, SelectionRound round) {

        // 1. 重复选课。最便宜的一查，放最前面。
        // 必须用 findActive 而不是 findOne：退选是软删除，findOne 连退掉的记录也算数，
        // 会让学生退选之后永远无法重选。
        if (selectionMapper.findActive(student.getId(), tc.getId()) != null) {
            return fail(ResultCode.ALREADY_SELECTED);
        }

        // 2. 教学班的专业与年级限制
        if (!matchTarget(tc.getTargetMajors(), student.getMajorId())
                || !matchTargetGrade(tc.getTargetGrades(), student.getGrade())) {
            return fail(ResultCode.MAJOR_GRADE_LIMITED);
        }

        String term = round.getTerm();
        List<TeachingClass> selected = selectionMapper.listSelectedClasses(student.getId(), term);

        // 3. 时间冲突：位图按位与，与已选课程数无关
        long[] timetable = TimeBitmapUtil.empty();
        for (TeachingClass s : selected) {
            timetable = TimeBitmapUtil.merge(timetable, s.bitmap());
        }
        long[] candidate = tc.bitmap();
        if (TimeBitmapUtil.conflicts(candidate, timetable)) {
            List<String> where = TimeBitmapUtil.describeConflicts(candidate, timetable);
            return new Dtos.PreCheckResp(false, ResultCode.TIME_CONFLICT.code(),
                    "上课时间冲突：" + String.join("、", where), where);
        }

        // 4. 学分上限
        Course course = courseMapper.selectById(tc.getCourseId());
        if (course == null) {
            return fail(ResultCode.CLASS_NOT_FOUND);
        }
        BigDecimal used = selectionMapper.sumSelectedCredit(student.getId(), term);
        if (used == null) {
            used = BigDecimal.ZERO;
        }
        BigDecimal limit = student.getCreditLimit() == null
                ? new BigDecimal("30") : student.getCreditLimit();
        if (used.add(course.getCredit()).compareTo(limit) > 0) {
            return new Dtos.PreCheckResp(false, ResultCode.CREDIT_EXCEEDED.code(),
                    "超出学分上限：已选 " + used + " 学分，本课 " + course.getCredit()
                            + " 学分，上限 " + limit + " 学分", List.of());
        }

        // 5. 先修课程
        List<Long> prereq = TimeSlotParser.parseIdArray(course.getPrerequisiteIds());
        if (!prereq.isEmpty()) {
            Set<Long> done = new HashSet<>(
                    selectionMapper.listCompletedCourseIds(student.getId(), term));
            for (Long need : prereq) {
                if (!done.contains(need)) {
                    Course pc = courseMapper.selectById(need);
                    String nm = pc == null ? ("课程#" + need) : pc.getName();
                    return new Dtos.PreCheckResp(false, ResultCode.PREREQUISITE_MISSING.code(),
                            "未修完先修课程：" + nm, List.of());
                }
            }
        }

        return new Dtos.PreCheckResp(true, ResultCode.OK.code(), "可以选课", List.of());
    }

    private Dtos.PreCheckResp fail(ResultCode rc) {
        return new Dtos.PreCheckResp(false, rc.code(), rc.msg(), List.of());
    }

    /** 限定列表为空表示不限 */
    private boolean matchTarget(String json, Long value) {
        List<Long> list = TimeSlotParser.parseIdArray(json);
        return list.isEmpty() || list.contains(value);
    }

    private boolean matchTargetGrade(String json, Integer grade) {
        List<Long> list = TimeSlotParser.parseIdArray(json);
        return list.isEmpty() || (grade != null && list.contains(grade.longValue()));
    }
}
