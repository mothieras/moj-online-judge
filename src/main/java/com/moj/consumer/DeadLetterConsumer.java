package com.moj.consumer;

import com.moj.model.entity.QuestionSubmit;
import com.moj.model.enums.JudgeInfoMessageEnum;
import com.moj.model.enums.QuestionSubmitStatusEnum;
import com.moj.service.QuestionSubmitService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeadLetterConsumer {

    @Resource
    private QuestionSubmitService questionSubmitService;

    @RabbitListener(queues = "judge.dead.queue")
    public void onDeadMessage(Message message) {
        try {
            byte[] body = message.getBody();
            Long submitId = Long.parseLong(new String(body));
            log.error("提交 {} 重试耗尽，进入死信队列，标记 FAILED", submitId);
            QuestionSubmit update = new QuestionSubmit();
            update.setId(submitId);
            update.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            update.setJudgeInfo("{\"message\":\"" + JudgeInfoMessageEnum.SYSTEM_ERROR.getValue() + "\"}");
            if (!questionSubmitService.updateById(update)) {
                log.error("提交 {} 死信队列标记 FAILED 失败，需人工处理", submitId);
            }
        } catch (Exception e) {
            log.error("死信消息处理失败，原始内容: {}", new String(message.getBody()), e);
        }
    }
}
