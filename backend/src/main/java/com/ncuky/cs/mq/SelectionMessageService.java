package com.ncuky.cs.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ncuky.cs.entity.LocalMessage;
import com.ncuky.cs.mapper.LocalMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表 + 投递。
 * <p>
 * 这是「Redis 扣了名额，但消息没投出去」这个风险的兜底：
 * 先把消息落在业务同一个库里（事务保证要么都成功要么都失败），再投 MQ；
 * 投递失败或服务当场宕机，定时任务都会扫出来重投。
 * <p>
 * 答辩必问：「Redis 扣了名额但消息没投出去怎么办？」——答案就是这个类。
 */
@Service
public class SelectionMessageService {

    private static final Logger log = LoggerFactory.getLogger(SelectionMessageService.class);

    /** 重试上限，超过就标记失败等人工介入，不再无限重投 */
    private static final int MAX_RETRY = 5;
    private static final int BATCH = 200;

    private final LocalMessageMapper messageMapper;
    private final MessageSender sender;
    private final ObjectMapper objectMapper;

    public SelectionMessageService(LocalMessageMapper messageMapper,
                                   MessageSender sender,
                                   ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    /** 落库 + 投递。落库在事务里，投递在事务外——投递失败由定时任务兜底 */
    public void enqueue(SelectionMessage msg) {
        persist(msg);
        try {
            sender.send(msg);
        } catch (Exception e) {
            log.warn("消息投递失败，等待定时重投 bizKey={}", msg.bizKey(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void persist(SelectionMessage msg) {
        LocalMessage lm = new LocalMessage();
        lm.setBizKey(msg.bizKey());
        try {
            lm.setPayload(objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            throw new IllegalStateException("消息序列化失败", e);
        }
        lm.setStatus(LocalMessage.STATUS_PENDING);
        lm.setRetryCount(0);
        lm.setCreatedAt(LocalDateTime.now());
        try {
            messageMapper.insert(lm);
        } catch (DuplicateKeyException e) {
            // biz_key 是 学生号:教学班号，同一对组合只会有一行。冲突有两种可能：
            //   上一次选课的消息已投递完成（status=1）—— 这次是退选后重选，把它重新打开；
            //   消息还在待投递（status=0）—— 同一次选课重复进来，属于幂等，跳过即可。
            int reopened = messageMapper.reopen(lm.getBizKey(), lm.getPayload(), LocalDateTime.now());
            if (reopened == 1) {
                log.debug("退选后重新选课，本地消息重新打开 {}", msg.bizKey());
            } else {
                log.debug("本地消息已存在且待投递，跳过 {}", msg.bizKey());
            }
        }
    }

    public void confirm(String bizKey) {
        messageMapper.confirm(bizKey);
    }

    /**
     * 定时重投未确认的消息。
     * 退避策略：第 n 次重试等待 n * 5 秒，避免下游没恢复时把它打垮。
     */
    @Scheduled(fixedDelayString = "${app.reconcile.retry-interval-ms:5000}")
    public void retryPending() {
        List<LocalMessage> pending = messageMapper.listPending(LocalDateTime.now(), BATCH);
        if (pending.isEmpty()) {
            return;
        }
        log.info("本地消息表待重投 {} 条", pending.size());
        for (LocalMessage lm : pending) {
            int retry = lm.getRetryCount() == null ? 0 : lm.getRetryCount();
            if (retry >= MAX_RETRY) {
                messageMapper.markRetry(lm.getId(), null, LocalMessage.STATUS_FAILED);
                log.error("消息重试次数用尽，标记失败待人工处理 bizKey={}", lm.getBizKey());
                continue;
            }
            try {
                SelectionMessage msg = objectMapper.readValue(lm.getPayload(), SelectionMessage.class);
                sender.send(msg);
                messageMapper.markRetry(lm.getId(),
                        LocalDateTime.now().plusSeconds((retry + 1) * 5L),
                        LocalMessage.STATUS_PENDING);
            } catch (Exception e) {
                log.warn("重投失败 bizKey={}", lm.getBizKey(), e);
                messageMapper.markRetry(lm.getId(),
                        LocalDateTime.now().plusSeconds((retry + 1) * 5L),
                        LocalMessage.STATUS_PENDING);
            }
        }
    }
}
