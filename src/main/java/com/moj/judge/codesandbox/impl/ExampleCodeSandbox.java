package com.moj.judge.codesandbox.impl;

import com.moj.judge.codesandbox.CodeSandbox;
import com.moj.judge.codesandbox.model.ExecuteCodeRequest;
import com.moj.judge.codesandbox.model.ExecuteCodeResponse;
import com.moj.judge.codesandbox.model.ExecuteMessage;
import com.moj.judge.codesandbox.model.JudgeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 示例代码沙箱（仅用于本地测试）
 */
public class ExampleCodeSandbox implements CodeSandbox {
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();

        // 模拟编译成功
        ExecuteMessage compileResult = new ExecuteMessage();
        compileResult.setExitVal(0);

        // 模拟每条用例运行成功（echo 输入作为输出）
        List<ExecuteMessage> runResults = new ArrayList<>();
        for (String input : inputList) {
            ExecuteMessage m = new ExecuteMessage();
            m.setExitVal(0);
            m.setMessage(input);
            m.setTime(100L);
            m.setMemory(100L);
            runResults.add(m);
        }

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(100L);
        judgeInfo.setMemory(100L);

        return ExecuteCodeResponse.builder()
                .compileResult(compileResult)
                .runResults(runResults)
                .outputList(inputList)
                .message("测试执行成功")
                .judgeInfo(judgeInfo)
                .build();
    }
}
