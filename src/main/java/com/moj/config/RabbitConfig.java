package com.moj.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitConfig {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void initConfirmCallback() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData != null ? correlationData.getId() : "unknown";
            if (ack) {
                log.info("消息已到达 Broker: {}", id);
            } else {
                log.error("消息发送失败: {}, 原因: {}", id, cause);
            }
        });
    }

    // ========== 正常判题 ==========
    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange("judge.exchange", true, false);
    }

    @Bean
    public Queue judgeQueue() {
        return QueueBuilder.durable("judge.queue")
                .deadLetterExchange("judge.dlx.exchange")
                .deadLetterRoutingKey("judge.dead")
                .build();
    }

    @Bean
    public Binding judgeBinding() {
        return BindingBuilder.bind(judgeQueue())
                .to(judgeExchange())
                .with("judge");
    }
    // ========== 死信 ==========

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange("judge.dlx.exchange", true, false);
    }

    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable("judge.dead.queue").build();
    }

    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue())
                .to(dlxExchange())
                .with("judge.dead");
    }
}
