package com.moj.consumer;

import com.moj.model.entity.QuestionSubmit;
import com.moj.model.enums.QuestionSubmitStatusEnum;
import com.moj.service.QuestionSubmitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterConsumerTest {

    @Mock
    private QuestionSubmitService questionSubmitService;

    @InjectMocks
    private DeadLetterConsumer consumer;

    @Test
    void normalDeadMessage_marksSubmitFailed() {
        Message message = new Message("123".getBytes(), new MessageProperties());
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(true);

        consumer.onDeadMessage(message);

        ArgumentCaptor<QuestionSubmit> captor = ArgumentCaptor.forClass(QuestionSubmit.class);
        verify(questionSubmitService).updateById(captor.capture());
        QuestionSubmit update = captor.getValue();
        assertThat(update.getId()).isEqualTo(123L);
        assertThat(update.getStatus()).isEqualTo(QuestionSubmitStatusEnum.FAILED.getValue());
        assertThat(update.getJudgeInfo()).contains("System Error");
    }

    @Test
    void updateFailure_logsButDoesNotThrow() {
        Message message = new Message("456".getBytes(), new MessageProperties());
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(false);

        // Must not throw
        consumer.onDeadMessage(message);
        verify(questionSubmitService).updateById(any());
    }

    @Test
    void unparseableBody_doesNotThrow() {
        Message message = new Message("not-a-number".getBytes(), new MessageProperties());

        consumer.onDeadMessage(message);
        verify(questionSubmitService, never()).updateById(any());
    }
}
