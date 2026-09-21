package com.ncuky.cs.controller;

import com.ncuky.cs.common.R;
import com.ncuky.cs.config.AppProps;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.SelectionRound;
import com.ncuky.cs.mapper.SelectionRoundMapper;
import com.ncuky.cs.mq.InMemoryMessageSender;
import com.ncuky.cs.mq.SelectionConsumer;
import com.ncuky.cs.service.ReconcileService;
import com.ncuky.cs.service.WarmupService;
import com.ncuky.cs.strategy.OptimisticDeductStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教务端接口。拦截器已限定只有教务角色能访问 /api/admin/**。
 * <p>
 * 后三个接口是专门为论文第 6 章准备的取数口子——
 * 不要到测试阶段再手工写 SQL 翻数据库。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final WarmupService warmupService;
    private final ReconcileService reconcileService;
    private final SelectionRoundMapper roundMapper;
    private final AppProps props;
    private final SelectionConsumer consumer;
    private final OptimisticDeductStrategy optimistic;
    private final ObjectProvider<InMemoryMessageSender> inMemorySender;

    public AdminController(WarmupService warmupService, ReconcileService reconcileService,
                           SelectionRoundMapper roundMapper, AppProps props,
                           SelectionConsumer consumer, OptimisticDeductStrategy optimistic,
                           ObjectProvider<InMemoryMessageSender> inMemorySender) {
        this.warmupService = warmupService;
        this.reconcileService = reconcileService;
        this.roundMapper = roundMapper;
        this.props = props;
        this.consumer = consumer;
        this.optimistic = optimistic;
        this.inMemorySender = inMemorySender;
    }

    @GetMapping("/rounds")
    public R<List<SelectionRound>> rounds() {
        return R.ok(roundMapper.selectList(null));
    }

    /** 名额预热。选课开放前必做，压测前也必做 */
    @PostMapping("/warmup/{roundId}")
    public R<Dtos.WarmupResp> warmup(@PathVariable Long roundId) {
        return R.ok(warmupService.warmup(roundId));
    }

    /** 清空该轮次的缓存状态，让每轮压测起点一致 */
    @PostMapping("/reset-cache/{roundId}")
    public R<Void> resetCache(@PathVariable Long roundId) {
        warmupService.resetCache(roundId);
        return R.ok();
    }

    /**
     * 对账报告：Redis 余量 与 容量−数据库选课数 的偏差。
     * 第 6.5 节一致性验证直接调这个，偏差必须是 0。
     */
    @GetMapping("/reconcile")
    public R<Dtos.ReconcileResp> reconcile(
            @RequestParam(defaultValue = "false") boolean autoFix) {
        return R.ok(reconcileService.reconcile(autoFix));
    }

    /** 运行时指标，供论文取数与答辩演示 */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("deductStrategy", props.getDeductStrategy());
        m.put("mqProvider", props.getMq().getProvider());
        m.put("rateLimitQps", props.getRateLimit().getQps());
        m.put("consumerHandled", consumer.handledCount());
        m.put("consumerDuplicated", consumer.duplicatedCount());
        m.put("optimisticRetries", optimistic.retryCount());
        InMemoryMessageSender sender = inMemorySender.getIfAvailable();
        m.put("mqQueueDepth", sender == null ? -1 : sender.depth());
        return R.ok(m);
    }

    /** 重置计数器，每轮压测前调一次 */
    @PostMapping("/stats/reset")
    public R<Void> resetStats() {
        optimistic.resetRetryCount();
        return R.ok();
    }

    /**
     * 运行时切换扣减策略。
     * <p>
     * 第 6.3 节四方案对比实验用：不重启服务就能换方案，
     * JVM 预热状态、连接池、数据集全部保持一致，对比条件更干净。
     * 正式环境不应暴露这个接口。
     */
    @PostMapping("/strategy")
    public R<Map<String, Object>> switchStrategy(@RequestParam String name) {
        List<String> allowed = List.of("direct", "pessimistic", "optimistic", "redis_mq");
        if (!allowed.contains(name)) {
            return R.fail(com.ncuky.cs.common.ResultCode.PARAM_INVALID,
                    "策略只能是 " + allowed);
        }
        String old = props.getDeductStrategy();
        props.setDeductStrategy(name);
        return R.ok(Map.of("from", old, "to", name));
    }

    /** 实验用：把一个教学班彻底复位，让每轮压测从同一起点开始 */
    @PostMapping("/reset-experiment/{classId}")
    public R<Void> resetExperiment(@PathVariable Long classId) {
        warmupService.resetExperiment(classId);
        optimistic.resetRetryCount();
        return R.ok();
    }
}
