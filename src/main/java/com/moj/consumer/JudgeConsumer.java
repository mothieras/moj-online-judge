package com.moj.consumer;

import com.moj.judge.JudgeService;
import com.moj.judge.SandboxException;
import com.moj.model.entity.QuestionSubmit;
import com.moj.model.enums.QuestionSubmitStatusEnum;
import com.moj.service.QuestionSubmitService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class JudgeConsumer {

    private static final int MAX_RETRY = 3;

    @Resource
    private JudgeService judgeService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "judge.queue", ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Long submitId = null;
        try {
            byte[] body = message.getBody();
            submitId = Long.parseLong(new String(body));
            QuestionSubmit submit = questionSubmitService.getById(submitId);
            if (submit == null) {
                log.error("提交 {} 不存在", submitId);
                channel.basicAck(deliveryTag, false);
                return;
            }
            if (!QuestionSubmitStatusEnum.WAITING.getValue().equals(submit.getStatus())) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            judgeService.doJudge(submitId);
            channel.basicAck(deliveryTag, false);
        } catch (SandboxException e) {
            log.error("提交 {} 沙箱基础设施异常，将重试", submitId, e);
            if (submitId != null) {
                handleRetry(message, deliveryTag, submitId, channel);
            } else {
                channel.basicNack(deliveryTag, false, false);
            }
        } catch (Exception e) {
            log.error("提交 {} 判题失败", submitId, e);
            if (submitId != null) {
                try {
                    QuestionSubmit submit = questionSubmitService.getById(submitId);
                    if (submit != null && QuestionSubmitStatusEnum.RUNNING.getValue().equals(submit.getStatus())) {
                        QuestionSubmit update = new QuestionSubmit();
                        update.setId(submitId);
                        update.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
                        questionSubmitService.updateById(update);
                    }
                } catch (Exception ignored) {
                }
                channel.basicNack(deliveryTag, false, false);
            } else {
                channel.basicNack(deliveryTag, false, false);
            }
        }
    }

    private void handleRetry(Message message, long deliveryTag, Long submitId, Channel channel) throws IOException {
        Integer retryCount = message.getMessageProperties().getHeader("x-retry-count");
        retryCount = (retryCount == null) ? 0 : retryCount;

        if (retryCount < MAX_RETRY) {
            final int nextRetry = retryCount + 1;
            log.warn("提交 {} 第 {} 次重试失败，准备重试", submitId, nextRetry);
            rabbitTemplate.convertAndSend("judge.exchange", "judge", String.valueOf(submitId), msg -> {
                msg.getMessageProperties().setHeader("x-retry-count", nextRetry);
                return msg;
            });
            channel.basicAck(deliveryTag, false);
        } else {
            log.error("提交 {} 已达最大重试次数 {}，进入死信队列", submitId, MAX_RETRY);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
