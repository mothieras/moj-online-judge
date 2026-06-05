package com.moj.consumer;

import com.moj.judge.JudgeService;
import com.moj.judge.SandboxException;
import com.moj.model.entity.QuestionSubmit;
import com.moj.model.enums.QuestionSubmitStatusEnum;
import com.moj.service.QuestionSubmitService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JudgeConsumer 单元测试。
 * 纯 Mockito，不加载 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
class JudgeConsumerTest {

    private static final long SUBMIT_ID = 123L;

    @Mock
    private JudgeService judgeService;

    @Mock
    private QuestionSubmitService questionSubmitService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private Channel channel;

    private JudgeConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new JudgeConsumer();
        ReflectionTestUtils.setField(consumer, "judgeService", judgeService);
        ReflectionTestUtils.setField(consumer, "questionSubmitService", questionSubmitService);
        ReflectionTestUtils.setField(consumer, "rabbitTemplate", rabbitTemplate);
    }

    /**
     * 创建 Mock Message，body 为 submitId 的字符串形式。
     */
    private Message createMessage(String body, Integer retryCount) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(1L);
        if (retryCount != null) {
            props.setHeader("x-retry-count", retryCount);
        }
        return new Message(body.getBytes(), props);
    }

    /**
     * 创建 WAITING 状态的提交。
     */
    private QuestionSubmit createWaitingSubmit() {
        QuestionSubmit submit = new QuestionSubmit();
        submit.setId(SUBMIT_ID);
        submit.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
        return submit;
    }

    // ==================== 1. submitNotFound_acksAndReturns ====================

    @Test
    void submitNotFound_acksAndReturns() throws Exception {
        Message message = createMessage(String.valueOf(SUBMIT_ID), null);
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(null);

        consumer.onMessage(message, channel);

        verify(questionSubmitService).getById(SUBMIT_ID);
        verify(channel).basicAck(1L, false);
        verify(judgeService, never()).doJudge(anyLong());
    }

    // ==================== 2. nonWaitingStatus_acksAndReturns ====================

    @Test
    void nonWaitingStatus_acksAndReturns() throws Exception {
        Message message = createMessage(String.valueOf(SUBMIT_ID), null);
        QuestionSubmit submit = new QuestionSubmit();
        submit.setId(SUBMIT_ID);
        submit.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit);

        consumer.onMessage(message, channel);

        verify(channel).basicAck(1L, false);
        verify(judgeService, never()).doJudge(anyLong());
    }

    // ==================== 3. normalExecution_acks ====================

    @Test
    void normalExecution_acks() throws Exception {
        Message message = createMessage(String.valueOf(SUBMIT_ID), null);
        QuestionSubmit submit = createWaitingSubmit();
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit);
        when(judgeService.doJudge(SUBMIT_ID)).thenReturn(submit);

        consumer.onMessage(message, channel);

        verify(judgeService).doJudge(SUBMIT_ID);
        verify(channel).basicAck(1L, false);
    }

    // ==================== 4. sandboxException_withRetriesLeft_requeues ====================

    @Test
    void sandboxException_withRetriesLeft_requeues() throws Exception {
        Message message = createMessage(String.valueOf(SUBMIT_ID), 0);
        QuestionSubmit submit = createWaitingSubmit();
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit);
        doThrow(new SandboxException("sandbox error", new RuntimeException("cause")))
                .when(judgeService).doJudge(SUBMIT_ID);

        consumer.onMessage(message, channel);

        // 重新发布到交换机，header x-retry-count 递增为 1
        verify(rabbitTemplate).convertAndSend(
                eq("judge.exchange"), eq("judge"), eq(String.valueOf(SUBMIT_ID)),
                any(MessagePostProcessor.class));
        // 原始消息 ack
        verify(channel).basicAck(1L, false);
    }

    // ==================== 5. sandboxException_exhaustedRetries_nacksToDeadLetter ====================

    @Test
    void sandboxException_exhaustedRetries_nacksToDeadLetter() throws Exception {
        // x-retry-count=3 == MAX_RETRY，已重试 3 次，不再重试
        Message message = createMessage(String.valueOf(SUBMIT_ID), 3);
        QuestionSubmit submit = createWaitingSubmit();
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit);
        doThrow(new SandboxException("sandbox error", new RuntimeException("cause")))
                .when(judgeService).doJudge(SUBMIT_ID);

        consumer.onMessage(message, channel);

        // nack 且不 requeue，消息进入死信队列
        verify(channel).basicNack(1L, false, false);
        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(), any(MessagePostProcessor.class));
    }

    // ==================== 6. unexpectedException_setsRunningToFailed_thenNacks ====================

    @Test
    void unexpectedException_setsRunningToFailed_thenNacks() throws Exception {
        Message message = createMessage(String.valueOf(SUBMIT_ID), null);

        QuestionSubmit waitingSubmit = createWaitingSubmit();
        QuestionSubmit runningSubmit = new QuestionSubmit();
        runningSubmit.setId(SUBMIT_ID);
        runningSubmit.setStatus(QuestionSubmitStatusEnum.RUNNING.getValue());

        // 第一次 getById 在 try 块内（通过状态检查），第二次在 catch 块内（安全网检查）
        when(questionSubmitService.getById(SUBMIT_ID))
                .thenReturn(waitingSubmit)
                .thenReturn(runningSubmit);
        doThrow(new RuntimeException("unexpected error"))
                .when(judgeService).doJudge(SUBMIT_ID);

        consumer.onMessage(message, channel);

        // 安全网：将 RUNNING 状态的提交标记为 FAILED
        ArgumentCaptor<QuestionSubmit> captor = ArgumentCaptor.forClass(QuestionSubmit.class);
        verify(questionSubmitService).updateById(captor.capture());
        QuestionSubmit updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(SUBMIT_ID);
        assertThat(updated.getStatus()).isEqualTo(QuestionSubmitStatusEnum.FAILED.getValue());

        // nack 不 requeue（非 SandboxException 不重试）
        verify(channel).basicNack(1L, false, false);
    }

    // ==================== 7. nullSubmitId_nacksWithoutRequeue ====================

    @Test
    void nullSubmitId_nacksWithoutRequeue() throws Exception {
        // body 无法解析为 Long
        Message message = createMessage("not-a-number", null);

        consumer.onMessage(message, channel);

        verify(channel).basicNack(1L, false, false);
        verify(questionSubmitService, never()).getById(anyLong());
        verify(judgeService, never()).doJudge(anyLong());
    }
}
