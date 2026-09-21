package com.ncuky.cs.service;

import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.Course;
import com.ncuky.cs.entity.SelectionRound;
import com.ncuky.cs.entity.TeachingClass;
import com.ncuky.cs.mapper.CourseMapper;
import com.ncuky.cs.mapper.CourseSelectionMapper;
import com.ncuky.cs.mapper.SelectionRoundMapper;
import com.ncuky.cs.mapper.TeachingClassMapper;
import com.ncuky.cs.util.RedisKeys;
import com.ncuky.cs.util.TimeSlotParser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CourseService {

    private final TeachingClassMapper classMapper;
    private final CourseMapper courseMapper;
    private final CourseSelectionMapper selectionMapper;
    private final SelectionRoundMapper roundMapper;
    private final StringRedisTemplate redis;

    public CourseService(TeachingClassMapper classMapper, CourseMapper courseMapper,
                         CourseSelectionMapper selectionMapper,
                         SelectionRoundMapper roundMapper, StringRedisTemplate redis) {
        this.classMapper = classMapper;
        this.courseMapper = courseMapper;
        this.selectionMapper = selectionMapper;
        this.roundMapper = roundMapper;
        this.redis = redis;
    }

    /**
     * 教学班列表，带实时剩余名额。
     * <p>
     * 余量优先读 Redis——学生刷新列表的频率远高于选课，
     * 每次都回表查 count 会把数据库压垮。Redis 没有才回落到数据库算。
     */
    public List<Dtos.TeachingClassVO> listClasses(Long studentId) {
        SelectionRound round = roundMapper.findCurrent();
        String term = round == null ? null : round.getTerm();
        List<TeachingClass> classes = term == null ? List.of() : classMapper.listByTerm(term);
        if (classes.isEmpty()) {
            return List.of();
        }

        Map<Long, Course> courses = new HashMap<>();
        for (Course c : courseMapper.selectBatchIds(
                classes.stream().map(TeachingClass::getCourseId).collect(Collectors.toSet()))) {
            courses.put(c.getId(), c);
        }

        Set<Long> mine = studentId == null
                ? Set.of() : new HashSet<>(selectionMapper.listClassIds(studentId));

        return classes.stream().map(tc -> {
            Course c = courses.get(tc.getCourseId());
            Long remain = readRemain(tc);
            return new Dtos.TeachingClassVO(
                    tc.getId(), tc.getClassNo(),
                    c == null ? null : c.getCourseNo(),
                    c == null ? null : c.getName(),
                    tc.getTeacherName(),
                    c == null ? null : c.getCredit(),
                    tc.getCapacity(), tc.getSelectedCount(), remain,
                    TimeSlotParser.describe(tc.getTimeSlots()),
                    tc.getLocation(), mine.contains(tc.getId()));
        }).toList();
    }

    private Long readRemain(TeachingClass tc) {
        String v = redis.opsForValue().get(RedisKeys.remain(tc.getId()));
        if (v != null) {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException ignored) {
                // 落到下面的数据库口径
            }
        }
        int used = selectionMapper.countSelected(tc.getId());
        return (long) Math.max(0, tc.getCapacity() - used);
    }

    /** 我的课表 */
    public List<Dtos.TimetableItem> myTimetable(Long studentId) {
        SelectionRound round = roundMapper.findCurrent();
        if (round == null) {
            return List.of();
        }
        List<TeachingClass> selected =
                selectionMapper.listSelectedClasses(studentId, round.getTerm());
        if (selected.isEmpty()) {
            return List.of();
        }
        Map<Long, Course> courses = new HashMap<>();
        for (Course c : courseMapper.selectBatchIds(
                selected.stream().map(TeachingClass::getCourseId).collect(Collectors.toSet()))) {
            courses.put(c.getId(), c);
        }
        return selected.stream().map(tc -> {
            Course c = courses.get(tc.getCourseId());
            return new Dtos.TimetableItem(tc.getId(),
                    c == null ? null : c.getName(),
                    tc.getTeacherName(),
                    TimeSlotParser.describe(tc.getTimeSlots()),
                    tc.getLocation(),
                    c == null ? null : c.getCredit());
        }).toList();
    }
}
