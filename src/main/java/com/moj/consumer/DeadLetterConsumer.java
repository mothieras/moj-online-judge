package com.moj.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeadLetterConsumer {

    @RabbitListener(queues = "judge.dead.queue")
    public void onDeadMessage(Message message) {
        try {
            byte[] body = message.getBody();
            Long submitId = Long.parseLong(new String(body));
            log.error("提交 {} 进入死信队列，需人工排查", submitId);
        } catch (Exception e) {
            log.error("死信消息解析失败，原始内容: {}", new String(message.getBody()));
        }
    }
}
