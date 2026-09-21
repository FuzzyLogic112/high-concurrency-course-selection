package com.ncuky.cs.strategy;

import com.ncuky.cs.entity.CourseSelection;
import com.ncuky.cs.entity.TeachingClass;
import com.ncuky.cs.mapper.CourseSelectionMapper;
import com.ncuky.cs.mapper.TeachingClassMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 所有落库动作集中在这里，每个方法一个独立事务。
 * <p>
 * 乐观锁策略的重试循环必须放在事务外：
 * 同一个事务里重复读同一行，在可重复读隔离级别下会一直看到旧值，
 * 重试永远不可能成功。所以这里每次 CAS 都是一个新事务。
 */
@Component
public class SelectionWriter {

    private static final Logger log = LoggerFactory.getLogger(SelectionWriter.class);

    private final TeachingClassMapper classMapper;
    private final CourseSelectionMapper selectionMapper;

    public SelectionWriter(TeachingClassMapper classMapper, CourseSelectionMapper selectionMapper) {
        this.classMapper = classMapper;
        this.selectionMapper = selectionMapper;
    }

    /**
     * 方案 A 直接扣减：读、判断、写三步之间毫无保护。
     * 这就是超选的来源，保留它是为了让论文第 1 章的问题论证有实测支撑。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeductOutcome direct(Long classId, Long studentId, Long roundId) {
        TeachingClass tc = classMapper.selectById(classId);
        if (tc == null || tc.getStatus() == null || tc.getStatus() != 1) {
            return DeductOutcome.CLASS_INVALID;
        }
        if (tc.getSelectedCount() >= tc.getCapacity()) {
            return DeductOutcome.FULL;
        }
        // 这一行与上面的判断之间是竞态窗口
        classMapper.incrementDirect(classId);
        return insert(classId, studentId, roundId);
    }

    /** 方案 B 悲观锁：拿行锁再判断再写，正确但把并发串行化了 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeductOutcome pessimistic(Long classId, Long studentId, Long roundId) {
        TeachingClass tc = classMapper.selectForUpdate(classId);
        if (tc == null || tc.getStatus() == null || tc.getStatus() != 1) {
            return DeductOutcome.CLASS_INVALID;
        }
        if (tc.getSelectedCount() >= tc.getCapacity()) {
            return DeductOutcome.FULL;
        }
        classMapper.incrementDirect(classId);
        return insert(classId, studentId, roundId);
    }

    /**
     * 方案 C 乐观锁的单次尝试。
     *
     * @return SUCCESS 成功 / FULL 满了 / null 表示 CAS 失败，调用方应重试
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeductOutcome casOnce(Long classId, Long studentId, Long roundId) {
        TeachingClass tc = classMapper.selectById(classId);
        if (tc == null || tc.getStatus() == null || tc.getStatus() != 1) {
            return DeductOutcome.CLASS_INVALID;
        }
        if (tc.getSelectedCount() >= tc.getCapacity()) {
            return DeductOutcome.FULL;
        }
        int updated = classMapper.incrementCas(classId, tc.getVersion());
        if (updated == 0) {
            return null;    // 版本被别人改了，交给调用方重试
        }
        return insert(classId, studentId, roundId);
    }

    /**
     * 方案 D 的落库动作，由 MQ 消费者调用。
     * incrementGuarded 带 selected_count &lt; capacity 条件，
     * 即使消息被重复投递也不会把计数写超。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeductOutcome persistFromMq(Long classId, Long studentId, Long roundId) {
        DeductOutcome r = insert(classId, studentId, roundId);
        if (r == DeductOutcome.SUCCESS) {
            classMapper.incrementGuarded(classId);
        }
        return r;
    }

    /**
     * 退选：置为已退 + 计数减一，同一事务内完成。
     * 缓存回补放在事务外——事务里调 Redis，一旦事务回滚缓存就补多了。
     *
     * @return true 表示确实退掉了一条，false 表示本来就没选
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean drop(Long studentId, Long classId) {
        CourseSelection cs = selectionMapper.findOne(studentId, classId);
        if (cs == null || cs.getStatus() == null
                || cs.getStatus() != CourseSelection.STATUS_SELECTED) {
            return false;
        }
        cs.setStatus(CourseSelection.STATUS_DROPPED);
        cs.setUpdatedAt(LocalDateTime.now());
        selectionMapper.updateById(cs);
        classMapper.decrement(classId);
        return true;
    }

    /** 候补补位：把队首学生直接写成已选 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeductOutcome promote(Long classId, Long studentId, Long roundId) {
        DeductOutcome r = insert(classId, studentId, roundId);
        if (r == DeductOutcome.SUCCESS) {
            classMapper.incrementGuarded(classId);
        }
        return r;
    }

    /**
     * 插入选课记录。
     * <p>
     * 唯一索引 uk_stu_class 是幂等的最后一道防线：
     * 即使消息被重复消费、即使代码里的判重被并发绕过，
     * 数据库也只会留下一条。捕获 DuplicateKeyException 当成幂等成功处理。
     */
    private DeductOutcome insert(Long classId, Long studentId, Long roundId) {
        CourseSelection cs = new CourseSelection();
        cs.setStudentId(studentId);
        cs.setTeachingClassId(classId);
        cs.setRoundId(roundId);
        cs.setStatus(CourseSelection.STATUS_SELECTED);
        cs.setCreatedAt(LocalDateTime.now());
        cs.setUpdatedAt(LocalDateTime.now());
        try {
            selectionMapper.insert(cs);
            return DeductOutcome.SUCCESS;
        } catch (DuplicateKeyException e) {
            // 唯一索引拦下了插入，但冲突的原因有两种，必须分开处理：
            //   一是这名学生退选过，行还在（status=0）—— 这是重新选课，应当复活那一行；
            //   二是同一条消息被重复消费，行本来就是已选 —— 这才是幂等要挡的情况。
            // 下面这条带 status = 0 条件的 UPDATE 同时完成判断与写入，受影响行数即答案，
            // 不用「先查再改」，也就没有并发窗口。
            int revived = selectionMapper.reactivate(studentId, classId, roundId, LocalDateTime.now());
            if (revived == 1) {
                log.debug("退选后重新选课，记录已复活 student={} class={}", studentId, classId);
                return DeductOutcome.SUCCESS;
            }
            log.debug("重复选课被唯一索引拦下 student={} class={}", studentId, classId);
            return DeductOutcome.DUPLICATE;
        }
    }
}
