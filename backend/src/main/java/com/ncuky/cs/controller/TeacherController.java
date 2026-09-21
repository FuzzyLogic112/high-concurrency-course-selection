package com.ncuky.cs.controller;

import com.ncuky.cs.common.BizException;
import com.ncuky.cs.common.R;
import com.ncuky.cs.common.ResultCode;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.SelectionRound;
import com.ncuky.cs.entity.TeachingClass;
import com.ncuky.cs.mapper.CourseMapper;
import com.ncuky.cs.mapper.CourseSelectionMapper;
import com.ncuky.cs.mapper.SelectionRoundMapper;
import com.ncuky.cs.mapper.TeachingClassMapper;
import com.ncuky.cs.entity.Course;
import com.ncuky.cs.security.UserContext;
import com.ncuky.cs.util.TimeSlotParser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 教师端。只读，不参与任何并发路径——教师看到的是选课结束后的稳定结果，
 * 不需要实时余量，因此全部走数据库，不碰 Redis。
 * <p>
 * 角色校验在 {@code AuthInterceptor} 里按 URI 前缀统一做；
 * 但「这个教学班是不是你教的」必须在这里逐个校验，否则改一下路径里的 id
 * 就能看到别的老师班级的学生名单，是典型的水平越权。
 */
@RestController
@RequestMapping("/api/teacher")
public class TeacherController {

    private final TeachingClassMapper classMapper;
    private final CourseSelectionMapper selectionMapper;
    private final SelectionRoundMapper roundMapper;
    private final CourseMapper courseMapper;

    public TeacherController(TeachingClassMapper classMapper,
                             CourseSelectionMapper selectionMapper,
                             SelectionRoundMapper roundMapper,
                             CourseMapper courseMapper) {
        this.classMapper = classMapper;
        this.selectionMapper = selectionMapper;
        this.roundMapper = roundMapper;
        this.courseMapper = courseMapper;
    }

    /** 我本学期任教的教学班 */
    @GetMapping("/classes")
    public R<List<Dtos.TeacherClassVO>> myClasses() {
        return R.ok(loadMyClasses());
    }

    /** 某教学班的选课名单。只能看自己任教的班 */
    @GetMapping("/classes/{classId}/roster")
    public R<List<Dtos.RosterItem>> roster(@PathVariable Long classId) {
        TeachingClass tc = classMapper.selectById(classId);
        if (tc == null || !UserContext.userId().equals(tc.getTeacherId())) {
            // 不区分「班不存在」和「不是你的班」，避免被拿来枚举教学班归属
            throw new BizException(ResultCode.FORBIDDEN);
        }
        return R.ok(selectionMapper.listRoster(classId));
    }

    private List<Dtos.TeacherClassVO> loadMyClasses() {
        SelectionRound round = roundMapper.findCurrent();
        if (round == null) {
            return List.of();
        }
        List<TeachingClass> classes =
                classMapper.listByTeacher(UserContext.userId(), round.getTerm());
        if (classes.isEmpty()) {
            return List.of();
        }

        // 一次把课程查出来做成 map，避免在循环里逐条查库
        Map<Long, Course> courses = courseMapper
                .selectBatchIds(classes.stream().map(TeachingClass::getCourseId).distinct().toList())
                .stream().collect(Collectors.toMap(Course::getId, Function.identity()));

        return classes.stream().map(tc -> {
            Course c = courses.get(tc.getCourseId());
            return new Dtos.TeacherClassVO(
                    tc.getId(), tc.getClassNo(),
                    c == null ? null : c.getCourseNo(),
                    c == null ? null : c.getName(),
                    c == null ? null : c.getCredit(),
                    tc.getCapacity(), tc.getSelectedCount(),
                    // 库里存的是 JSON，直接吐给前端会显示成一串花括号
                    TimeSlotParser.describe(tc.getTimeSlots()), tc.getLocation());
        }).toList();
    }
}
