package com.moj.judge;

import com.moj.common.ErrorCode;
import com.moj.exception.BusinessException;
import com.moj.judge.codesandbox.CodeSandbox;
import com.moj.judge.codesandbox.CodeSandboxRouter;
import com.moj.judge.codesandbox.model.ExecuteCodeResponse;
import com.moj.judge.codesandbox.model.ExecuteMessage;
import com.moj.judge.codesandbox.model.JudgeInfo;
import com.moj.model.entity.Question;
import com.moj.model.entity.QuestionSubmit;
import com.moj.model.enums.JudgeInfoMessageEnum;
import com.moj.model.enums.QuestionSubmitStatusEnum;
import com.moj.service.QuestionService;
import com.moj.service.QuestionSubmitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgeServiceImplTest {

    private static final long SUBMIT_ID = 1L;
    private static final long QUESTION_ID = 100L;
    private static final String CODE = "print('hello')";
    private static final String LANGUAGE = "python";
    private static final String JUDGE_CASE_JSON = "[{\"input\":\"1 2\",\"output\":\"3\"},{\"input\":\"4 5\",\"output\":\"9\"}]";
    private static final String JUDGE_CONFIG_JSON = "{\"timeLimit\":1000,\"memoryLimit\":256}";

    @Mock
    private QuestionService questionService;
    @Mock
    private QuestionSubmitService questionSubmitService;
    @Mock
    private JudgeManager judgeManager;
    @Mock
    private CodeSandboxRouter codeSandboxRouter;

    private JudgeServiceImpl judgeService;

    @BeforeEach
    void setUp() {
        judgeService = spy(new JudgeServiceImpl());
        ReflectionTestUtils.setField(judgeService, "questionService", questionService);
        ReflectionTestUtils.setField(judgeService, "questionSubmitService", questionSubmitService);
        ReflectionTestUtils.setField(judgeService, "judgeManager", judgeManager);
        ReflectionTestUtils.setField(judgeService, "codeSandboxRouter", codeSandboxRouter);
    }

    // ---- helpers ----

    private QuestionSubmit createSubmit(Integer status) {
        QuestionSubmit submit = new QuestionSubmit();
        submit.setId(SUBMIT_ID);
        submit.setQuestionId(QUESTION_ID);
        submit.setCode(CODE);
        submit.setLanguage(LANGUAGE);
        submit.setStatus(status);
        return submit;
    }

    private Question createQuestion() {
        Question question = new Question();
        question.setId(QUESTION_ID);
        question.setJudgeCase(JUDGE_CASE_JSON);
        question.setJudgeConfig(JUDGE_CONFIG_JSON);
        return question;
    }

    private ExecuteCodeResponse createNormalResponse(List<String> outputList) {
        ExecuteMessage runResult = new ExecuteMessage();
        runResult.setExitVal(0);
        runResult.setTimeout(false);

        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setRunResults(Arrays.asList(runResult));
        response.setOutputList(outputList);
        return response;
    }

    /**
     * Prepare sandbox mock for tests that reach the sandbox execution phase.
     * Sets up codeSandboxRouter.select() to return a mock CodeSandbox
     * that returns the given controlled response.
     */
    private void prepareSandbox(ExecuteCodeResponse response) {
        CodeSandbox sandbox = mock(CodeSandbox.class);
        when(codeSandboxRouter.select()).thenReturn(sandbox);
        when(sandbox.executeCode(any())).thenReturn(response);
    }

    // === 1. submitNotFound_throwsBusinessException ===

    @Test
    void submitNotFound_throwsBusinessException() {
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(null);

        assertThatThrownBy(() -> judgeService.doJudge(SUBMIT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND_ERROR.getCode());

        verify(questionSubmitService, never()).updateById(any());
    }

    // === 2. questionNotFound_throwsBusinessException ===

    @Test
    void questionNotFound_throwsBusinessException() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit);
        when(questionService.getById(QUESTION_ID)).thenReturn(null);

        assertThatThrownBy(() -> judgeService.doJudge(SUBMIT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND_ERROR.getCode());

        verify(questionSubmitService, never()).updateById(any());
    }

    // === 3. notWaitingStatus_throwsBusinessException ===

    @Test
    void notWaitingStatus_throwsBusinessException() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.FAILED.getValue());
        Question question = createQuestion();
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);

        assertThatThrownBy(() -> judgeService.doJudge(SUBMIT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.OPERATION_ERROR.getCode());

        verify(questionSubmitService, never()).updateById(any());
    }

    // === 4. markRunningFails_throwsBusinessException ===

    @Test
    void markRunningFails_throwsBusinessException() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = createQuestion();
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(false);

        assertThatThrownBy(() -> judgeService.doJudge(SUBMIT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
    }

    // === 5. accepted_setsSucceed ===

    @Test
    void accepted_setsSucceed() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = createQuestion();
        QuestionSubmit finished = new QuestionSubmit();
        finished.setId(SUBMIT_ID);
        finished.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
        finished.setJudgeInfo("{\"message\":\"Accepted\",\"time\":100,\"memory\":2048}");
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit).thenReturn(finished);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);
        // markRunning succeeds
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(true);

        // sandbox returns clean result
        ExecuteCodeResponse sandboxResp = createNormalResponse(Arrays.asList("3", "9"));
        prepareSandbox(sandboxResp);

        // strategy returns ACCEPTED
        JudgeInfo acceptedInfo = new JudgeInfo();
        acceptedInfo.setMessage(JudgeInfoMessageEnum.ACCEPTED.getValue());
        acceptedInfo.setTime(100L);
        acceptedInfo.setMemory(2048L);
        when(judgeManager.doJudge(any())).thenReturn(acceptedInfo);

        QuestionSubmit result = judgeService.doJudge(SUBMIT_ID);

        assertThat(result.getStatus()).isEqualTo(QuestionSubmitStatusEnum.SUCCEED.getValue());
        assertThat(result.getJudgeInfo()).contains(JudgeInfoMessageEnum.ACCEPTED.getValue());
    }

    // === 6. wrongAnswer_setsFailed ===

    @Test
    void wrongAnswer_setsFailed() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = createQuestion();
        QuestionSubmit finished = new QuestionSubmit();
        finished.setId(SUBMIT_ID);
        finished.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        finished.setJudgeInfo("{\"message\":\"Wrong Answer\",\"time\":50,\"memory\":1024}");
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit).thenReturn(finished);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(true);

        ExecuteCodeResponse sandboxResp = createNormalResponse(Arrays.asList("wrong_output"));
        prepareSandbox(sandboxResp);

        JudgeInfo wrongAnswerInfo = new JudgeInfo();
        wrongAnswerInfo.setMessage(JudgeInfoMessageEnum.WRONG_ANSWER.getValue());
        wrongAnswerInfo.setTime(50L);
        wrongAnswerInfo.setMemory(1024L);
        when(judgeManager.doJudge(any())).thenReturn(wrongAnswerInfo);

        QuestionSubmit result = judgeService.doJudge(SUBMIT_ID);

        assertThat(result.getStatus()).isEqualTo(QuestionSubmitStatusEnum.FAILED.getValue());
        assertThat(result.getJudgeInfo()).contains(JudgeInfoMessageEnum.WRONG_ANSWER.getValue());
    }

    // === 7. compileError_setsFailed ===

    @Test
    void compileError_setsFailed() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = createQuestion();
        QuestionSubmit finished = new QuestionSubmit();
        finished.setId(SUBMIT_ID);
        finished.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        finished.setJudgeInfo("{\"message\":\"Compile Error\",\"time\":0,\"memory\":0}");
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit).thenReturn(finished);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(true);

        // sandbox returns compile error
        ExecuteMessage compileResult = new ExecuteMessage();
        compileResult.setExitVal(1);
        compileResult.setErrorMessage("syntax error");

        ExecuteCodeResponse sandboxResp = new ExecuteCodeResponse();
        sandboxResp.setCompileResult(compileResult);
        prepareSandbox(sandboxResp);

        QuestionSubmit result = judgeService.doJudge(SUBMIT_ID);

        assertThat(result.getStatus()).isEqualTo(QuestionSubmitStatusEnum.FAILED.getValue());
        assertThat(result.getJudgeInfo()).contains(JudgeInfoMessageEnum.COMPILE_ERROR.getValue());
        // strategy must NOT be called (short-circuit before strategy)
        verify(judgeManager, never()).doJudge(any());
    }

    // === 8. runtimeError_setsFailed ===

    @Test
    void runtimeError_setsFailed() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = createQuestion();
        QuestionSubmit finished = new QuestionSubmit();
        finished.setId(SUBMIT_ID);
        finished.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        finished.setJudgeInfo("{\"message\":\"Runtime Error\",\"time\":0,\"memory\":0}");
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit).thenReturn(finished);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(true);

        // runResults has one with non-zero exitVal
        ExecuteMessage runResult = new ExecuteMessage();
        runResult.setExitVal(1);
        runResult.setTimeout(false);

        ExecuteCodeResponse sandboxResp = new ExecuteCodeResponse();
        sandboxResp.setRunResults(Arrays.asList(runResult));
        prepareSandbox(sandboxResp);

        QuestionSubmit result = judgeService.doJudge(SUBMIT_ID);

        assertThat(result.getStatus()).isEqualTo(QuestionSubmitStatusEnum.FAILED.getValue());
        assertThat(result.getJudgeInfo()).contains(JudgeInfoMessageEnum.RUNTIME_ERROR.getValue());
        verify(judgeManager, never()).doJudge(any());
    }

    // === 9. timeout_setsFailed ===

    @Test
    void timeout_setsFailed() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = createQuestion();
        QuestionSubmit finished = new QuestionSubmit();
        finished.setId(SUBMIT_ID);
        finished.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        finished.setJudgeInfo("{\"message\":\"Time Limit Exceeded\",\"time\":0,\"memory\":0}");
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit).thenReturn(finished);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(true);

        ExecuteMessage runResult = new ExecuteMessage();
        runResult.setExitVal(0);
        runResult.setTimeout(true);

        ExecuteCodeResponse sandboxResp = new ExecuteCodeResponse();
        sandboxResp.setRunResults(Arrays.asList(runResult));
        prepareSandbox(sandboxResp);

        QuestionSubmit result = judgeService.doJudge(SUBMIT_ID);

        assertThat(result.getStatus()).isEqualTo(QuestionSubmitStatusEnum.FAILED.getValue());
        assertThat(result.getJudgeInfo()).contains(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue());
        verify(judgeManager, never()).doJudge(any());
    }

    // === 10. sandboxInfrastructureException_propagates ===

    @Test
    void sandboxInfrastructureException_propagates() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = createQuestion();
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(true);

        // router.select() throws SandboxException — propagates out of callSandbox
        SandboxException sandboxEx = new SandboxException("沙箱不可用", new RuntimeException("timeout"));
        when(codeSandboxRouter.select()).thenThrow(sandboxEx);

        assertThatThrownBy(() -> judgeService.doJudge(SUBMIT_ID))
                .isInstanceOf(SandboxException.class)
                .isSameAs(sandboxEx);

        // verify resetToWaiting was called: second updateById sets status back to WAITING
        ArgumentCaptor<QuestionSubmit> captor = ArgumentCaptor.forClass(QuestionSubmit.class);
        verify(questionSubmitService, times(2)).updateById(captor.capture());
        List<QuestionSubmit> updates = captor.getAllValues();
        // first call: markRunning sets RUNNING
        assertThat(updates.get(0).getStatus()).isEqualTo(QuestionSubmitStatusEnum.RUNNING.getValue());
        // second call: resetToWaiting sets WAITING
        assertThat(updates.get(1).getStatus()).isEqualTo(QuestionSubmitStatusEnum.WAITING.getValue());
        assertThat(updates.get(1).getId()).isEqualTo(SUBMIT_ID);
    }

    // === 11. systemErrorFromSandbox_setsFailed ===

    @Test
    void systemErrorFromSandbox_setsFailed() {
        QuestionSubmit submit = createSubmit(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = createQuestion();
        QuestionSubmit finished = new QuestionSubmit();
        finished.setId(SUBMIT_ID);
        finished.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
        finished.setJudgeInfo("{\"message\":\"System Error\",\"time\":0,\"memory\":0}");
        when(questionSubmitService.getById(SUBMIT_ID)).thenReturn(submit).thenReturn(finished);
        when(questionService.getById(QUESTION_ID)).thenReturn(question);
        when(questionSubmitService.updateById(any(QuestionSubmit.class))).thenReturn(true);

        // null compileResult, null runResults → SYSTEM_ERROR
        ExecuteCodeResponse sandboxResp = new ExecuteCodeResponse();
        sandboxResp.setCompileResult(null);
        sandboxResp.setRunResults(null);
        prepareSandbox(sandboxResp);

        QuestionSubmit result = judgeService.doJudge(SUBMIT_ID);

        assertThat(result.getStatus()).isEqualTo(QuestionSubmitStatusEnum.FAILED.getValue());
        assertThat(result.getJudgeInfo()).contains(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
        verify(judgeManager, never()).doJudge(any());
    }
}
