package com.ncuky.cs.controller;

import com.ncuky.cs.common.R;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.SelectionRound;
import com.ncuky.cs.mapper.SelectionRoundMapper;
import com.ncuky.cs.security.UserContext;
import com.ncuky.cs.service.CourseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CourseController {

    private final CourseService courseService;
    private final SelectionRoundMapper roundMapper;

    public CourseController(CourseService courseService, SelectionRoundMapper roundMapper) {
        this.courseService = courseService;
        this.roundMapper = roundMapper;
    }

    /** 教学班列表，带 Redis 里的实时剩余名额 */
    @GetMapping("/teaching-classes")
    public R<List<Dtos.TeachingClassVO>> list() {
        return R.ok(courseService.listClasses(UserContext.studentId()));
    }

    /** 我的课表 */
    @GetMapping("/selection/my")
    public R<List<Dtos.TimetableItem>> myTimetable() {
        return R.ok(courseService.myTimetable(UserContext.studentId()));
    }

    /** 当前选课轮次及开放状态 */
    @GetMapping("/rounds/current")
    public R<SelectionRound> current() {
        return R.ok(roundMapper.findCurrent());
    }
}
