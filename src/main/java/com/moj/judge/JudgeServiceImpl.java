package com.moj.judge;

import cn.hutool.json.JSONUtil;
import com.moj.common.ErrorCode;
import com.moj.exception.BusinessException;
import com.moj.judge.codesandbox.CodeSandbox;
import com.moj.judge.codesandbox.CodeSandboxFactory;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class JudgeServiceImpl implements JudgeService {
    @Resource
    private QuestionService questionService;
    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private JudgeManager judgeManager;
    @Value("${codesandbox.type:example}")
    private String type;

    @Override
    public QuestionSubmit doJudge(long questionSubmitId) {
        // 1. 传入题目提交id，获取到对应的题目，提交信息
        QuestionSubmit questionSubmit = questionSubmitService.getById(questionSubmitId);
        if (questionSubmit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交信息不存在");
        }
        Long id = questionSubmit.getId();
        Long questionId = questionSubmit.getQuestionId();
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        if (!questionSubmit.getStatus().equals(QuestionSubmitStatusEnum.WAITING.getValue())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目正在判题中");
        }

        // 2. 更新为判题中
        QuestionSubmit questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setId(id);
        questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.RUNNING.getValue());
        boolean update = questionSubmitService.updateById(questionSubmitUpdate);
        if (!update) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
        }

        // 3. 调用沙箱
        CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(type);
        codeSandbox = new CodeSandboxProxy(codeSandbox);
        String code = questionSubmit.getCode();
        String language = questionSubmit.getLanguage();
        String judgeCaseStr = question.getJudgeCase();
        List<JudgeCase> judgeCases = JSONUtil.toList(judgeCaseStr, JudgeCase.class);
        List<String> inputList = judgeCases.stream().map(JudgeCase::getInput).collect(Collectors.toList());

        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .code(code).language(language).inputList(inputList).build();
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);

        // 4. 短路判断：编译/运行/超时错误
        JudgeInfo judgeInfoRes = new JudgeInfo();

        // 4a. 编译错误
        ExecuteMessage compileResult = executeCodeResponse.getCompileResult();
        if (compileResult != null && compileResult.getExitVal() != 0) {
            judgeInfoRes.setMessage(JudgeInfoMessageEnum.COMPILE_ERROR.getValue());
            judgeInfoRes.setTime(0L);
            judgeInfoRes.setMemory(0L);
        }
        // 4b. 运行超时
        else if (executeCodeResponse.getRunResults() != null
                && executeCodeResponse.getRunResults().stream().anyMatch(r -> Boolean.TRUE.equals(r.getTimeout()))) {
            JudgeInfo sandboxJudgeInfo = executeCodeResponse.getJudgeInfo();
            judgeInfoRes.setMessage(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue());
            judgeInfoRes.setTime(sandboxJudgeInfo != null ? sandboxJudgeInfo.getTime() : 0L);
            judgeInfoRes.setMemory(sandboxJudgeInfo != null ? sandboxJudgeInfo.getMemory() : 0L);
        }
        // 4c. 运行错误
        else if (executeCodeResponse.getRunResults() != null
                && executeCodeResponse.getRunResults().stream().anyMatch(r -> r.getExitVal() != null && r.getExitVal() != 0)) {
            JudgeInfo sandboxJudgeInfo = executeCodeResponse.getJudgeInfo();
            judgeInfoRes.setMessage(JudgeInfoMessageEnum.RUNTIME_ERROR.getValue());
            judgeInfoRes.setTime(sandboxJudgeInfo != null ? sandboxJudgeInfo.getTime() : 0L);
            judgeInfoRes.setMemory(sandboxJudgeInfo != null ? sandboxJudgeInfo.getMemory() : 0L);
        } else {
            // 4d. 正常执行 → 策略判题
            JudgeInfo sandboxJudgeInfo = executeCodeResponse.getJudgeInfo();
            List<String> outputList = executeCodeResponse.getOutputList();

            JudgeContext judgeContext = new JudgeContext();
            judgeContext.setJudgeInfo(sandboxJudgeInfo);
            judgeContext.setInputList(inputList);
            judgeContext.setOutputList(outputList);
            judgeContext.setJudgeCaseList(judgeCases);
            judgeContext.setQuestion(question);
            judgeContext.setQuestionSubmit(questionSubmit);

            judgeInfoRes = judgeManager.doJudge(judgeContext);
        }

        // 5. 写回数据库
        questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setId(id);
        questionSubmitUpdate.setStatus(
                JudgeInfoMessageEnum.ACCEPTED.getValue().equals(judgeInfoRes.getMessage())
                        ? QuestionSubmitStatusEnum.SUCCEED.getValue()
                        : QuestionSubmitStatusEnum.FAILED.getValue());
        questionSubmitUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfoRes));
        update = questionSubmitService.updateById(questionSubmitUpdate);
        if (!update) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目状态更新错误");
        }
        return questionSubmitService.getById(id);
    }
}
