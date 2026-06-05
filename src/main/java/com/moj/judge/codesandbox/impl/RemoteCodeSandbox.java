package com.moj.judge.codesandbox.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.moj.common.ErrorCode;
import com.moj.exception.BusinessException;
import com.moj.judge.codesandbox.CodeSandbox;
import com.moj.judge.codesandbox.model.ExecuteCodeRequest;
import com.moj.judge.codesandbox.model.ExecuteCodeResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 远程代码沙箱
 */
@Component
public class RemoteCodeSandbox implements CodeSandbox {
    // 鉴权请求头
    private static final String AUTH_REQUEST_HEADER = "auth";

    @Value("${codesandbox.remote.url:http://localhost:8090/executeCode}")
    private String sandboxUrl;

    @Value("${codesandbox.remote.auth-secret:secretKey}")
    private String authSecret;

    @Value("${codesandbox.remote.timeout:35000}")
    private int timeoutMs;

    /**
     * 执行代码
     *
     * @param executeCodeRequest
     * @return
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String json = JSONUtil.toJsonStr(executeCodeRequest);
        String responseBody = HttpUtil.createPost(sandboxUrl)
                .header(AUTH_REQUEST_HEADER, authSecret)
                .body(json)
                .timeout(timeoutMs)
                .execute().body();
        if (StringUtils.isBlank(responseBody)) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "executeCode remoteSandbox error");

        }
        return JSONUtil.toBean(responseBody, ExecuteCodeResponse.class);
    }
}
