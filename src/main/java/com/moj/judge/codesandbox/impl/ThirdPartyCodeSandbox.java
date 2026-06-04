package com.moj.judge.codesandbox.impl;

import com.moj.judge.codesandbox.CodeSandbox;
import com.moj.judge.codesandbox.model.ExecuteCodeRequest;
import com.moj.judge.codesandbox.model.ExecuteCodeResponse;

/**
 * 第三方代码沙箱 调用网上现成的代码沙箱
 */
public class ThirdPartyCodeSandbox implements CodeSandbox {
    /**
     * 执行代码
     * @param executeCodeRequest
     * @return
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest){
        System.out.println("第三方代码沙箱");
        return null;
    }
}
