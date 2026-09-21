package com.ncuky.cs.mq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 进程内队列实现（dev）。
 * <p>
 * 用有界阻塞队列 + 固定数量的消费线程，刻意模仿 RabbitMQ 的行为：
 * 生产端投完即返回，消费端按自己的速率处理，队列深度可观测。
 * 这样 dev 环境免装 RabbitMQ 也能验证「异步落库」这条链路是通的。
 */
@Component
@ConditionalOnProperty(name = "app.mq.provider", havingValue = "inmemory", matchIfMissing = true)
public class InMemoryMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageSender.class);

    /** 有界队列：满了就让生产端感知到背压，而不是无限堆内存 */
    private final LinkedBlockingQueue<SelectionMessage> queue = new LinkedBlockingQueue<>(100_000);
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "inmem-mq-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    private final ObjectProvider<SelectionConsumer> consumerProvider;

    public InMemoryMessageSender(ObjectProvider<SelectionConsumer> consumerProvider) {
        this.consumerProvider = consumerProvider;
    }

    @PostConstruct
    public void start() {
        for (int i = 0; i < 4; i++) {
            workers.submit(this::loop);
        }
        log.info("进程内消息队列已启动（dev 模式，4 个消费线程）");
    }

    private void loop() {
        SelectionConsumer consumer = consumerProvider.getObject();
        while (running) {
            try {
                SelectionMessage msg = queue.poll(200, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    continue;
                }
                if (!consumer.handle(msg)) {
                    // 处理失败：本地消息表的定时任务会重投，这里不自己重试以免打转
                    log.warn("消息处理失败，留给本地消息表重投 {}", msg.bizKey());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("消费线程异常", e);
            }
        }
    }

    @Override
    public String name() {
        return "inmemory";
    }

    @Override
    public void send(SelectionMessage message) {
        if (!queue.offer(message)) {
            throw new IllegalStateException("进程内队列已满，触发背压");
        }
    }

    /** 队列深度，供监控接口和第 6.5 节的积压观测使用 */
    public int depth() {
        return queue.size();
    }

    @PreDestroy
    public void stop() {
        running = false;
        workers.shutdownNow();
    }
}
