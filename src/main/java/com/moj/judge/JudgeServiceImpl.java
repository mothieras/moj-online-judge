package com.moj.judge;

import cn.hutool.json.JSONUtil;
import com.moj.common.ErrorCode;
import com.moj.exception.BusinessException;
import com.moj.judge.codesandbox.CodeSandbox;
import com.moj.judge.codesandbox.CodeSandboxRouter;
import com.moj.judge.codesandbox.CodeSandboxProxy;
import com.moj.judge.codesandbox.model.ExecuteCodeRequest;
import com.moj.judge.codesandbox.model.ExecuteCodeResponse;
import com.moj.judge.codesandbox.model.ExecuteMessage;
import com.moj.judge.strategy.JudgeContext;
import com.moj.model.dto.question.JudgeCase;
import com.moj.judge.codesandbox.model.JudgeInfo;
import com.moj.model.enums.JudgeInfoMessageEnum;
import com.moj.model.entity.Question;
import com.moj.model.entity.QuestionSubmit;
import com.moj.model.enums.QuestionSubmitStatusEnum;
import com.moj.service.QuestionService;
import com.moj.service.QuestionSubmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JudgeServiceImpl implements JudgeService {
    @Resource
    private QuestionService questionService;
    @Resource
    private QuestionSubmitService questionSubmitService;
    @Resource
    private JudgeManager judgeManager;
    @Resource
    private CodeSandboxRouter codeSandboxRouter;

    @Override
    public QuestionSubmit doJudge(long questionSubmitId) {
        QuestionSubmit submit = questionSubmitService.getById(questionSubmitId);
        if (submit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交信息不存在");
        }
        Question question = questionService.getById(submit.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        if (!submit.getStatus().equals(QuestionSubmitStatusEnum.WAITING.getValue())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目正在判题中");
        }

        markRunning(submit.getId());

        List<JudgeCase> judgeCases = JSONUtil.toList(question.getJudgeCase(), JudgeCase.class);
        List<String> inputList = judgeCases.stream().map(JudgeCase::getInput).collect(Collectors.toList());
        ExecuteCodeRequest req = ExecuteCodeRequest.builder()
                .code(submit.getCode()).language(submit.getLanguage()).inputList(inputList).build();

        JudgeInfo result;
        try {
            ExecuteCodeResponse resp = callSandbox(req);
            result = classifySandboxResult(resp);
            if (result == null) {
                result = applyJudgeStrategy(resp, inputList, judgeCases, submit, question);
            }
        } catch (SandboxException e) {
            log.error("沙箱基础设施异常，提交ID {}，重置为WAITING等待重试", submit.getId(), e);
            resetToWaiting(submit.getId());
            throw e;
        }

        return finishSubmit(submit.getId(), result);
    }

    private void markRunning(Long submitId) {
        QuestionSubmit update = new QuestionSubmit();
        update.setId(submitId);
        update.setStatus(QuestionSubmitStatusEnum.RUNNING.getValue());
        if (!questionSubmitService.updateById(update)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
        }
    }

    private ExecuteCodeResponse callSandbox(ExecuteCodeRequest req) {
        CodeSandbox codeSandbox = codeSandboxRouter.select();
        codeSandbox = new CodeSandboxProxy(codeSandbox);
        try {
            return codeSandbox.executeCode(req);
        } catch (Exception e) {
            throw new SandboxException("沙箱调用失败", e);
        }
    }

    /**
     * 短路检查沙箱返回结果：编译错误、系统错误、超时、运行错误。
     * 返回 null 表示执行正常，需要调用策略判题。
     */
    private JudgeInfo classifySandboxResult(ExecuteCodeResponse resp) {
        ExecuteMessage compileResult = resp.getCompileResult();
        List<ExecuteMessage> runResults = resp.getRunResults();

        if (compileResult == null && (runResults == null || runResults.isEmpty())) {
            JudgeInfo info = new JudgeInfo();
            info.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
            info.setTime(0L);
            info.setMemory(0L);
            return info;
        }
        if (compileResult != null && compileResult.getExitVal() != 0) {
            JudgeInfo info = new JudgeInfo();
            info.setMessage(JudgeInfoMessageEnum.COMPILE_ERROR.getValue());
            info.setTime(0L);
            info.setMemory(0L);
            return info;
        }
        if (runResults.stream().anyMatch(r -> Boolean.TRUE.equals(r.getTimeout()))) {
            JudgeInfo sandboxJudgeInfo = resp.getJudgeInfo();
            JudgeInfo info = new JudgeInfo();
            info.setMessage(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue());
            info.setTime(sandboxJudgeInfo != null ? sandboxJudgeInfo.getTime() : 0L);
            info.setMemory(sandboxJudgeInfo != null ? sandboxJudgeInfo.getMemory() : 0L);
            return info;
        }
        if (runResults.stream().anyMatch(r -> r.getExitVal() != null && r.getExitVal() != 0)) {
            JudgeInfo sandboxJudgeInfo = resp.getJudgeInfo();
            JudgeInfo info = new JudgeInfo();
            info.setMessage(JudgeInfoMessageEnum.RUNTIME_ERROR.getValue());
            info.setTime(sandboxJudgeInfo != null ? sandboxJudgeInfo.getTime() : 0L);
            info.setMemory(sandboxJudgeInfo != null ? sandboxJudgeInfo.getMemory() : 0L);
            return info;
        }
        return null;
    }

    private JudgeInfo applyJudgeStrategy(ExecuteCodeResponse resp, List<String> inputList,
                                          List<JudgeCase> judgeCases, QuestionSubmit submit, Question question) {
        JudgeContext ctx = new JudgeContext();
        ctx.setJudgeInfo(resp.getJudgeInfo());
        ctx.setInputList(inputList);
        ctx.setOutputList(resp.getOutputList());
        ctx.setJudgeCaseList(judgeCases);
        ctx.setQuestion(question);
        ctx.setQuestionSubmit(submit);
        return judgeManager.doJudge(ctx);
    }

    private void resetToWaiting(Long submitId) {
        QuestionSubmit update = new QuestionSubmit();
        update.setId(submitId);
        update.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
        if (!questionSubmitService.updateById(update)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "重置状态为WAITING失败");
        }
    }

    private QuestionSubmit finishSubmit(Long submitId, JudgeInfo judgeInfo) {
        QuestionSubmit update = new QuestionSubmit();
        update.setId(submitId);
        update.setStatus(
                JudgeInfoMessageEnum.ACCEPTED.getValue().equals(judgeInfo.getMessage())
                        ? QuestionSubmitStatusEnum.SUCCEED.getValue()
                        : QuestionSubmitStatusEnum.FAILED.getValue());
        update.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
        if (!questionSubmitService.updateById(update)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
        }
        return questionSubmitService.getById(submitId);
    }
}
