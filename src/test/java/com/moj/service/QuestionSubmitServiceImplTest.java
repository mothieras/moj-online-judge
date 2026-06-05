package com.moj.service;

import com.moj.common.ErrorCode;
import com.moj.exception.BusinessException;
import com.moj.judge.JudgeService;
import com.moj.mapper.QuestionSubmitMapper;
import com.moj.model.dto.questsubmit.QuestionSubmitAddRequest;
import com.moj.model.entity.Question;
import com.moj.model.entity.QuestionSubmit;
import com.moj.model.entity.User;
import com.moj.model.enums.QuestionSubmitStatusEnum;
import com.moj.service.impl.QuestionSubmitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuestionSubmitServiceImpl doQuestionSubmit 单元测试。
 * 纯 Mockito，不加载 Spring 上下文、不依赖真实数据库或 RabbitMQ。
 */
@ExtendWith(MockitoExtension.class)
class QuestionSubmitServiceImplTest {

    private static final long QUESTION_ID = 100L;
    private static final long USER_ID = 42L;
    private static final long SUBMIT_ID = 999L;

    @Mock
    private QuestionService questionService;
    @Mock
    private UserService userService;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private JudgeService judgeService;
    @Mock
    private QuestionSubmitMapper questionSubmitMapper;

    private QuestionSubmitServiceImpl service;
    private User loginUser;

    @BeforeEach
    void setUp() {
        service = spy(new QuestionSubmitServiceImpl());
        ReflectionTestUtils.setField(service, "questionService", questionService);
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(service, "judgeService", judgeService);
        ReflectionTestUtils.setField(service, "baseMapper", questionSubmitMapper);

        loginUser = new User();
        loginUser.setId(USER_ID);
    }

    // ── unsupported language ──

    @Test
    void unsupportedLanguage_throwsBusinessException() {
        QuestionSubmitAddRequest request = new QuestionSubmitAddRequest();
        request.setLanguage("python");
        request.setCode("System.out.println(1);");
        request.setQuestionId(QUESTION_ID);

        assertThatThrownBy(() -> service.doQuestionSubmit(request, loginUser))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());

        verify(questionService, never()).getById(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(), any(), any());
    }

    // ── null language ──

    @Test
    void nullLanguage_throwsBusinessException() {
        QuestionSubmitAddRequest request = new QuestionSubmitAddRequest();
        request.setLanguage(null);
        request.setCode("System.out.println(1);");
        request.setQuestionId(QUESTION_ID);

        assertThatThrownBy(() -> service.doQuestionSubmit(request, loginUser))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());

        verify(questionService, never()).getById(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(), any(), any());
    }

    // ── blank code ──

    @Test
    void blankCode_throwsBusinessException() {
        QuestionSubmitAddRequest request = new QuestionSubmitAddRequest();
        request.setLanguage("java");
        request.setCode("   ");
        request.setQuestionId(QUESTION_ID);

        assertThatThrownBy(() -> service.doQuestionSubmit(request, loginUser))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());

        verify(questionService, never()).getById(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(), any(), any());
    }

    // ── code exceeds max length ──

    @Test
    void codeExceedsMaxLength_throwsBusinessException() {
        QuestionSubmitAddRequest request = new QuestionSubmitAddRequest();
        request.setLanguage("java");
        request.setCode("x".repeat(65537));
        request.setQuestionId(QUESTION_ID);

        assertThatThrownBy(() -> service.doQuestionSubmit(request, loginUser))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());

        verify(questionService, never()).getById(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(), any(), any());
    }

    // ── question not found ──

    @Test
    void questionNotFound_throwsBusinessException() {
        QuestionSubmitAddRequest request = new QuestionSubmitAddRequest();
        request.setLanguage("java");
        request.setCode("System.out.println(1);");
        request.setQuestionId(QUESTION_ID);

        when(questionService.getById(QUESTION_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.doQuestionSubmit(request, loginUser))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND_ERROR.getCode());

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(), any(), any());
    }

    // ── successful submit ──

    @Test
    void successfulSubmit_createsWaitingRecord_andSendsRabbitMessage() throws Exception {
        QuestionSubmitAddRequest request = new QuestionSubmitAddRequest();
        request.setLanguage("java");
        request.setCode("public class Main {}");
        request.setQuestionId(QUESTION_ID);

        Question question = new Question();
        question.setId(QUESTION_ID);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);

        // capture the saved entity and inject an ID (simulating MyBatis Plus auto-fill)
        ArgumentCaptor<QuestionSubmit> submitCaptor = ArgumentCaptor.forClass(QuestionSubmit.class);
        doAnswer(invocation -> {
            QuestionSubmit arg = invocation.getArgument(0);
            arg.setId(SUBMIT_ID);
            return true;
        }).when(service).save(submitCaptor.capture());

        long submitId = service.doQuestionSubmit(request, loginUser);

        // verify returned ID
        assertThat(submitId).isEqualTo(SUBMIT_ID);

        // verify saved entity state
        QuestionSubmit saved = submitCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getQuestionId()).isEqualTo(QUESTION_ID);
        assertThat(saved.getLanguage()).isEqualTo("java");
        assertThat(saved.getCode()).isEqualTo("public class Main {}");
        assertThat(saved.getStatus()).isEqualTo(QuestionSubmitStatusEnum.WAITING.getValue());
        assertThat(saved.getJudgeInfo()).isEqualTo("{}");

        // verify RabbitMQ message
        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MessagePostProcessor> mppCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        ArgumentCaptor<CorrelationData> correlationDataCaptor = ArgumentCaptor.forClass(CorrelationData.class);

        verify(rabbitTemplate).convertAndSend(
                exchangeCaptor.capture(),
                routingKeyCaptor.capture(),
                bodyCaptor.capture(),
                mppCaptor.capture(),
                correlationDataCaptor.capture());

        assertThat(exchangeCaptor.getValue()).isEqualTo("judge.exchange");
        assertThat(routingKeyCaptor.getValue()).isEqualTo("judge");
        assertThat(bodyCaptor.getValue()).isEqualTo(String.valueOf(SUBMIT_ID));

        // verify x-retry-count header is 0
        Message mockMessage = mock(Message.class);
        MessageProperties mockProps = mock(MessageProperties.class);
        when(mockMessage.getMessageProperties()).thenReturn(mockProps);
        mppCaptor.getValue().postProcessMessage(mockMessage);
        verify(mockProps).setHeader("x-retry-count", 0);

        // verify CorrelationData contains submit ID
        assertThat(correlationDataCaptor.getValue().getId()).isEqualTo(String.valueOf(SUBMIT_ID));
    }
}
