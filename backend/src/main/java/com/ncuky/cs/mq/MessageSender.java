package com.ncuky.cs.mq;

/**
 * 消息发送通道。
 * <p>
 * 抽出这个接口是为了让 dev 环境免装 RabbitMQ 也能把全链路跑通：
 * inmemory 实现用进程内队列，行为（异步、可重投）与 RabbitMQ 一致；
 * prod 环境切回真实 RabbitMQ，业务代码不动。
 * <p>
 * 注意：进程内队列只适合开发和功能验证。
 * 论文第 6.5 节的削峰实验必须用真实 RabbitMQ 跑，
 * 因为要观测的正是「消息堆积在队列里、数据库按自己的节奏消费」这件事。
 */
public interface MessageSender {

    String name();

    /** 投递。抛异常表示投递失败，由本地消息表兜底重投 */
    void send(SelectionMessage message);
}
