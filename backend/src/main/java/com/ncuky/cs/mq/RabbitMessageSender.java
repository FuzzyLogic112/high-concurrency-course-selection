package com.ncuky.cs.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 实现（prod）。
 * <p>
 * 队列绑定死信交换机：消费失败重试到上限后消息进死信队列，不会无限重投堵住正常流量，
 * 也不会静默丢弃——运维可以从死信队列里捞出来人工处理。
 */
@Component
@ConditionalOnProperty(name = "app.mq.provider", havingValue = "rabbit")
public class RabbitMessageSender implements MessageSender {

    public static final String EXCHANGE = "cs.selection.exchange";
    public static final String QUEUE = "cs.selection.queue";
    public static final String ROUTING_KEY = "cs.selection";

    public static final String DLX_EXCHANGE = "cs.selection.dlx";
    public static final String DLX_QUEUE = "cs.selection.dlq";
    public static final String DLX_ROUTING_KEY = "cs.selection.dead";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final SelectionConsumer consumer;

    public RabbitMessageSender(RabbitTemplate rabbitTemplate,
                               ObjectMapper objectMapper,
                               SelectionConsumer consumer) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.consumer = consumer;
    }

    @Bean
    public DirectExchange selectionExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange selectionDlx() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue selectionQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue selectionDlq() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding selectionBinding() {
        return BindingBuilder.bind(selectionQueue()).to(selectionExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding selectionDlqBinding() {
        return BindingBuilder.bind(selectionDlq()).to(selectionDlx()).with(DLX_ROUTING_KEY);
    }

    @Override
    public String name() {
        return "rabbit";
    }

    @Override
    public void send(SelectionMessage message) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY,
                    objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            throw new IllegalStateException("投递选课消息失败 " + message.bizKey(), e);
        }
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(String body) throws Exception {
        SelectionMessage msg = objectMapper.readValue(body, SelectionMessage.class);
        if (!consumer.handle(msg)) {
            // 抛异常让 RabbitMQ 走重试/死信，不要吞掉
            throw new IllegalStateException("处理失败，交给重试机制 " + msg.bizKey());
        }
    }
}
