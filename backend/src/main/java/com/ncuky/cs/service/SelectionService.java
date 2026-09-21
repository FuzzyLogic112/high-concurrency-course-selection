package com.ncuky.cs.service;

import com.ncuky.cs.common.BizException;
import com.ncuky.cs.common.R;
import com.ncuky.cs.common.ResultCode;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.CourseSelection;
import com.ncuky.cs.entity.SelectionRound;
import com.ncuky.cs.entity.Student;
import com.ncuky.cs.entity.TeachingClass;
import com.ncuky.cs.entity.WaitingQueue;
import com.ncuky.cs.mapper.CourseSelectionMapper;
import com.ncuky.cs.mapper.SelectionRoundMapper;
import com.ncuky.cs.mapper.StudentMapper;
import com.ncuky.cs.mapper.TeachingClassMapper;
import com.ncuky.cs.mapper.WaitingQueueMapper;
import com.ncuky.cs.ratelimit.RateLimiter;
import com.ncuky.cs.strategy.DeductOutcome;
import com.ncuky.cs.strategy.SelectionWriter;
import com.ncuky.cs.strategy.StrategyRouter;
import com.ncuky.cs.util.RedisKeys;
import com.ncuky.cs.util.TimeSlotParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 选课编排。
 * <p>
 * 一次选课依次经过：限流 → 轮次校验 → 教学班校验 → 规则校验 → 名额扣减。
 * 前四步全部快速失败，注定失败的请求不会进入扣减环节，
 * 这样在选课高峰期 Redis 和消息队列只承载真正有效的流量。
 */
@Service
public class SelectionService {

    private static final Logger log = LoggerFactory.getLogger(SelectionService.class);

    private final StrategyRouter router;
    private final RuleService ruleService;
    private final RateLimiter rateLimiter;
    private final SelectionWriter writer;

    private final StudentMapper studentMapper;
    private final TeachingClassMapper classMapper;
    private final SelectionRoundMapper roundMapper;
    private final CourseSelectionMapper selectionMapper;
    private final WaitingQueueMapper waitingMapper;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> refundScript;

    public SelectionService(StrategyRouter router, RuleService ruleService,
                            RateLimiter rateLimiter, SelectionWriter writer,
                            StudentMapper studentMapper, TeachingClassMapper classMapper,
                            SelectionRoundMapper roundMapper,
                            CourseSelectionMapper selectionMapper,
                            WaitingQueueMapper waitingMapper,
                            StringRedisTemplate redis,
                            @Qualifier("refundScript") DefaultRedisScript<Long> refundScript) {
        this.router = router;
        this.ruleService = ruleService;
        this.rateLimiter = rateLimiter;
        this.writer = writer;
        this.studentMapper = studentMapper;
        this.classMapper = classMapper;
        this.roundMapper = roundMapper;
        this.selectionMapper = selectionMapper;
        this.waitingMapper = waitingMapper;
        this.redis = redis;
        this.refundScript = refundScript;
    }

    // ------------------------------------------------------------ 选课

    public R<Dtos.SelectResp> select(Long studentId, Long classId) {
        if (studentId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (!rateLimiter.tryAcquire(studentId)) {
            return R.of(ResultCode.RATE_LIMITED);
        }

        SelectionRound round = currentOpenRound();
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "学生信息不存在");
        }
        if (!roundMatches(round, student)) {
            return R.of(ResultCode.ROUND_NOT_MATCH);
        }

        TeachingClass tc = classMapper.selectById(classId);
        if (tc == null || tc.getStatus() == null || tc.getStatus() != 1) {
            return R.of(ResultCode.CLASS_NOT_FOUND);
        }

        Dtos.PreCheckResp pre = ruleService.check(student, tc, round);
        if (!pre.passed()) {
            return new R<>(pre.code(), pre.msg(),
                    new Dtos.SelectResp(false, null, router.current().name(),
                            pre.code(), pre.msg()));
        }

        DeductOutcome outcome = router.current().deduct(classId, studentId, round.getId());
        String strategy = router.current().name();
        ResultCode rc = outcome.resultCode();

        if (outcome == DeductOutcome.ACCEPTED) {
            // 异步落库：返回受理凭证，前端拿它轮询最终结果
            String ticket = studentId + ":" + classId;
            return R.of(ResultCode.OK,
                    new Dtos.SelectResp(true, ticket, strategy, rc.code(), "选课请求已受理"));
        }
        if (outcome == DeductOutcome.SUCCESS) {
            return R.of(ResultCode.OK,
                    new Dtos.SelectResp(true, null, strategy, ResultCode.OK.code(), "选课成功"));
        }
        return new R<>(rc.code(), rc.msg(),
                new Dtos.SelectResp(false, null, strategy, rc.code(), rc.msg()));
    }

    /** 异步链路的结果查询：落库了才算真的选上 */
    public R<Dtos.SelectResp> queryStatus(Long studentId, Long classId) {
        CourseSelection cs = selectionMapper.findOne(studentId, classId);
        if (cs != null && cs.getStatus() == CourseSelection.STATUS_SELECTED) {
            return R.of(ResultCode.OK,
                    new Dtos.SelectResp(true, null, router.current().name(),
                            ResultCode.OK.code(), "选课成功"));
        }
        return R.of(ResultCode.OK,
                new Dtos.SelectResp(false, studentId + ":" + classId, router.current().name(),
                        ResultCode.ACCEPTED.code(), "处理中，请稍候刷新"));
    }

    // ------------------------------------------------------------ 退选

    /**
     * 退选。三方动作必须按顺序来：
     * 先库（事务内），成功了再补缓存，最后才触发候补补位。
     * 反过来先补缓存的话，一旦数据库回滚，名额就凭空多出来一个。
     */
    public R<Void> drop(Long studentId, Long classId) {
        if (studentId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        boolean dropped = writer.drop(studentId, classId);
        if (!dropped) {
            return R.of(ResultCode.NOT_SELECTED);
        }

        try {
            redis.execute(refundScript,
                    List.of(RedisKeys.remain(classId), RedisKeys.selected(classId)),
                    String.valueOf(studentId));
        } catch (Exception e) {
            // 缓存回补失败不影响退选本身，定时对账会把偏差补上
            log.warn("退选后缓存回补失败 class={} student={}，交给对账任务", classId, studentId, e);
        }

        promoteFromWaitingQueue(classId);
        return R.ok();
    }

    /** 候补补位：有人退选就把队首学生顶上去 */
    private void promoteFromWaitingQueue(Long classId) {
        WaitingQueue head = waitingMapper.peekFirst(classId);
        if (head == null) {
            return;
        }
        SelectionRound round = roundMapper.findCurrent();
        if (round == null) {
            return;
        }
        DeductOutcome r = writer.promote(classId, head.getStudentId(), round.getId());
        if (r == DeductOutcome.SUCCESS || r == DeductOutcome.DUPLICATE) {
            head.setStatus(WaitingQueue.STATUS_PROMOTED);
            waitingMapper.updateById(head);
            // 缓存里也要占掉这个名额，否则余量会多算一个
            try {
                redis.opsForValue().decrement(RedisKeys.remain(classId));
                redis.opsForSet().add(RedisKeys.selected(classId),
                        String.valueOf(head.getStudentId()));
            } catch (Exception e) {
                log.warn("候补补位后缓存同步失败 class={}", classId, e);
            }
            log.info("候补补位成功 class={} student={}", classId, head.getStudentId());
        }
    }

    /** 名额满时排队 */
    public R<Void> joinWaitingQueue(Long studentId, Long classId) {
        CourseSelection exist = selectionMapper.findOne(studentId, classId);
        if (exist != null && exist.getStatus() == CourseSelection.STATUS_SELECTED) {
            return R.of(ResultCode.ALREADY_SELECTED);
        }
        WaitingQueue wq = new WaitingQueue();
        wq.setTeachingClassId(classId);
        wq.setStudentId(studentId);
        wq.setSeq(waitingMapper.maxSeq(classId) + 1);
        wq.setStatus(WaitingQueue.STATUS_WAITING);
        wq.setCreatedAt(LocalDateTime.now());
        try {
            waitingMapper.insert(wq);
        } catch (DuplicateKeyException e) {
            // 这名学生以前排过队。若已出队（补位成功或已取消），重新排到队尾；
            // 若仍在队中，说明是重复点击，保持原有位置不动。
            // 原先这里 catch 的是 Exception 并一律返回成功，
            // 既把真正的数据库故障吞了，也会让重新排队的学生以为排上了其实没有。
            int requeued = waitingMapper.requeue(classId, studentId, wq.getSeq(), LocalDateTime.now());
            log.debug("{} class={} student={}",
                    requeued == 1 ? "重新排入候补队列" : "已在候补队列中，忽略重复请求",
                    classId, studentId);
        }
        return R.ok();
    }

    // ------------------------------------------------------------ 前置校验（不扣名额）

    public R<Dtos.PreCheckResp> preCheck(Long studentId, Long classId) {
        SelectionRound round = currentOpenRound();
        Student student = studentMapper.selectById(studentId);
        TeachingClass tc = classMapper.selectById(classId);
        if (student == null || tc == null) {
            return R.of(ResultCode.CLASS_NOT_FOUND);
        }
        return R.ok(ruleService.check(student, tc, round));
    }

    // ------------------------------------------------------------ 内部

    private SelectionRound currentOpenRound() {
        SelectionRound round = roundMapper.findCurrent();
        if (round == null || !round.isOpenNow()) {
            throw new BizException(ResultCode.ROUND_NOT_OPEN);
        }
        return round;
    }

    private boolean roundMatches(SelectionRound round, Student student) {
        List<Long> grades = TimeSlotParser.parseIdArray(round.getTargetGrades());
        List<Long> majors = TimeSlotParser.parseIdArray(round.getTargetMajors());
        boolean gradeOk = grades.isEmpty()
                || (student.getGrade() != null && grades.contains(student.getGrade().longValue()));
        boolean majorOk = majors.isEmpty() || majors.contains(student.getMajorId());
        return gradeOk && majorOk;
    }
}
