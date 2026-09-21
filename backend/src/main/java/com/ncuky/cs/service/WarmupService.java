package com.ncuky.cs.service;

import com.ncuky.cs.common.BizException;
import com.ncuky.cs.common.ResultCode;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.SelectionRound;
import com.ncuky.cs.entity.TeachingClass;
import com.ncuky.cs.mapper.CourseSelectionMapper;
import com.ncuky.cs.mapper.SelectionRoundMapper;
import com.ncuky.cs.mapper.TeachingClassMapper;
import com.ncuky.cs.util.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 名额预热。
 * <p>
 * 选课开放前必须执行：把每个教学班的剩余名额与已选学生集合加载进 Redis。
 * 不预热的话 Lua 脚本会返回 -2，请求全部降级走数据库，吞吐直接塌掉——
 * 这是本系统上线前的必做动作，也是压测前的必做动作。
 */
@Service
public class WarmupService {

    private static final Logger log = LoggerFactory.getLogger(WarmupService.class);

    private final SelectionRoundMapper roundMapper;
    private final TeachingClassMapper classMapper;
    private final CourseSelectionMapper selectionMapper;
    private final StringRedisTemplate redis;

    public WarmupService(SelectionRoundMapper roundMapper, TeachingClassMapper classMapper,
                         CourseSelectionMapper selectionMapper, StringRedisTemplate redis) {
        this.roundMapper = roundMapper;
        this.classMapper = classMapper;
        this.selectionMapper = selectionMapper;
        this.redis = redis;
    }

    public Dtos.WarmupResp warmup(Long roundId) {
        long t0 = System.currentTimeMillis();
        SelectionRound round = roundMapper.selectById(roundId);
        if (round == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "轮次不存在：" + roundId);
        }

        List<TeachingClass> classes = classMapper.listByTerm(round.getTerm());
        for (TeachingClass tc : classes) {
            warmOne(tc);
        }
        roundMapper.markWarmed(roundId, 1);

        long cost = System.currentTimeMillis() - t0;
        log.info("名额预热完成 round={} 教学班 {} 个 耗时 {}ms", roundId, classes.size(), cost);
        return new Dtos.WarmupResp(roundId, classes.size(), cost);
    }

    /**
     * 单个教学班的预热。
     * 余量以数据库为准重新算，而不是直接写 capacity——
     * 否则重复预热会把已经选上的人的名额又放出来。
     */
    private void warmOne(TeachingClass tc) {
        int used = selectionMapper.countSelected(tc.getId());
        int remain = Math.max(0, tc.getCapacity() - used);

        String remainKey = RedisKeys.remain(tc.getId());
        String selectedKey = RedisKeys.selected(tc.getId());

        redis.opsForValue().set(remainKey, String.valueOf(remain));

        // 重建判重集合：Lua 脚本靠它挡住重复选课
        redis.delete(selectedKey);
        List<Long> students = selectionMapper.listStudentIds(tc.getId());
        if (!students.isEmpty()) {
            String[] ids = students.stream().map(String::valueOf).toArray(String[]::new);
            redis.opsForSet().add(selectedKey, ids);
        }
    }

    /**
     * 实验用：把一个教学班彻底复位——清空选课记录、计数归零、缓存按容量重置。
     * <p>
     * 第 6.3 节四方案对比必须每轮从完全相同的起点开始，否则数据没有可比性。
     * 只在实验场景调用，正式环境不应暴露。
     */
    public void resetExperiment(Long classId) {
        TeachingClass tc = classMapper.selectById(classId);
        if (tc == null) {
            throw new BizException(ResultCode.CLASS_NOT_FOUND);
        }
        selectionMapper.deleteByClass(classId);
        classMapper.resetCount(classId);
        redis.delete(RedisKeys.selected(classId));
        redis.opsForValue().set(RedisKeys.remain(classId), String.valueOf(tc.getCapacity()));
        log.info("教学班 {} 已复位，余量重置为 {}", tc.getClassNo(), tc.getCapacity());
    }

    /** 压测前重置：清空选课记录之外的缓存状态，让每轮测试条件一致 */
    public void resetCache(Long roundId) {
        SelectionRound round = roundMapper.selectById(roundId);
        if (round == null) {
            return;
        }
        for (TeachingClass tc : classMapper.listByTerm(round.getTerm())) {
            redis.delete(List.of(RedisKeys.remain(tc.getId()), RedisKeys.selected(tc.getId())));
        }
        roundMapper.markWarmed(roundId, 0);
    }
}
