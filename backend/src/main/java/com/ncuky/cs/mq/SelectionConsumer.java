package com.ncuky.cs.mq;

import com.ncuky.cs.strategy.DeductOutcome;
import com.ncuky.cs.strategy.SelectionWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 消息处理的核心逻辑。
 * <p>
 * 进程内队列和 RabbitMQ 监听器都调这里，保证两种通道的行为完全一致。
 * <p>
 * 幂等靠两道防线：
 * 一是这里捕获重复插入当成成功，二是数据库上的联合唯一索引。
 * 幂等不能只靠代码判断，一定要有数据库约束兜底。
 */
@Component
public class SelectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(SelectionConsumer.class);

    private final SelectionWriter writer;
    private final SelectionMessageService messageService;

    private final LongAdder handled = new LongAdder();
    private final LongAdder duplicated = new LongAdder();

    public SelectionConsumer(SelectionWriter writer, SelectionMessageService messageService) {
        this.writer = writer;
        this.messageService = messageService;
    }

    /**
     * 处理一条选课消息。
     *
     * @return true 表示已处理完毕，可以确认消息；false 表示需要重试
     */
    public boolean handle(SelectionMessage msg) {
        try {
            DeductOutcome r = writer.persistFromMq(msg.classId(), msg.studentId(), msg.roundId());
            if (r == DeductOutcome.DUPLICATE) {
                duplicated.increment();
            }
            handled.increment();
            messageService.confirm(msg.bizKey());
            return true;
        } catch (Exception e) {
            log.error("选课消息处理失败，等待重投 bizKey={}", msg.bizKey(), e);
            return false;
        }
    }

    public long handledCount() {
        return handled.sum();
    }

    public long duplicatedCount() {
        return duplicated.sum();
    }
}
