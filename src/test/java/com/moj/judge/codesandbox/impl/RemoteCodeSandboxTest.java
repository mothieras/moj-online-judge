package com.moj.judge.codesandbox.impl;

import cn.hutool.http.HttpException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.moj.common.ErrorCode;
import com.moj.exception.BusinessException;
import com.moj.judge.codesandbox.model.ExecuteCodeRequest;
import com.moj.judge.codesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RemoteCodeSandbox 单元测试。
 * 纯 Mockito，不加载 Spring 上下文；使用 MockedStatic 隔离 Hutool HTTP 调用。
 */
@ExtendWith(MockitoExtension.class)
class RemoteCodeSandboxTest {

    private static final String SANDBOX_URL = "http://localhost:8090/executeCode";
    private static final String AUTH_SECRET = "testSecret";

    private RemoteCodeSandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new RemoteCodeSandbox();
        ReflectionTestUtils.setField(sandbox, "sandboxUrl", SANDBOX_URL);
        ReflectionTestUtils.setField(sandbox, "authSecret", AUTH_SECRET);
    }

    @Test
    void normalResponseDeserializesCorrectly() {
        try (MockedStatic<HttpUtil> mockedHttp = mockStatic(HttpUtil.class)) {
            HttpRequest mockRequest = mock(HttpRequest.class);
            HttpResponse mockResponse = mock(HttpResponse.class);
            String jsonResponse = "{\"outputList\":[\"3\",\"7\"],\"message\":\"OK\",\"status\":1,"
                    + "\"judgeInfo\":{\"time\":100,\"memory\":1024}}";

            mockedHttp.when(() -> HttpUtil.createPost(anyString())).thenReturn(mockRequest);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.body(anyString())).thenReturn(mockRequest);
            when(mockRequest.timeout(anyInt())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            when(mockResponse.body()).thenReturn(jsonResponse);

            ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                    .code("print(1+2)")
                    .language("python")
                    .inputList(List.of("1", "2"))
                    .build();

            ExecuteCodeResponse response = sandbox.executeCode(request);

            assertThat(response.getOutputList()).containsExactly("3", "7");
            assertThat(response.getMessage()).isEqualTo("OK");
            assertThat(response.getStatus()).isEqualTo(1);
            assertThat(response.getJudgeInfo()).isNotNull();
            assertThat(response.getJudgeInfo().getTime()).isEqualTo(100L);
            assertThat(response.getJudgeInfo().getMemory()).isEqualTo(1024L);
        }
    }

    @Test
    void emptyResponseBodyThrowsException() {
        try (MockedStatic<HttpUtil> mockedHttp = mockStatic(HttpUtil.class)) {
            HttpRequest mockRequest = mock(HttpRequest.class);
            HttpResponse mockResponse = mock(HttpResponse.class);

            mockedHttp.when(() -> HttpUtil.createPost(anyString())).thenReturn(mockRequest);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.body(anyString())).thenReturn(mockRequest);
            when(mockRequest.timeout(anyInt())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            when(mockResponse.body()).thenReturn("");

            ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                    .code("print(1)")
                    .language("python")
                    .build();

            assertThatThrownBy(() -> sandbox.executeCode(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.API_REQUEST_ERROR.getCode());
        }
    }

    @Test
    void nullResponseBodyThrowsException() {
        try (MockedStatic<HttpUtil> mockedHttp = mockStatic(HttpUtil.class)) {
            HttpRequest mockRequest = mock(HttpRequest.class);
            HttpResponse mockResponse = mock(HttpResponse.class);

            mockedHttp.when(() -> HttpUtil.createPost(anyString())).thenReturn(mockRequest);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.body(anyString())).thenReturn(mockRequest);
            when(mockRequest.timeout(anyInt())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            when(mockResponse.body()).thenReturn(null);

            ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                    .code("print(1)")
                    .language("python")
                    .build();

            assertThatThrownBy(() -> sandbox.executeCode(request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void connectTimeoutThrowsException() {
        try (MockedStatic<HttpUtil> mockedHttp = mockStatic(HttpUtil.class)) {
            HttpRequest mockRequest = mock(HttpRequest.class);

            mockedHttp.when(() -> HttpUtil.createPost(anyString())).thenReturn(mockRequest);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.body(anyString())).thenReturn(mockRequest);
            when(mockRequest.timeout(anyInt())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenThrow(new HttpException("connect timed out"));

            ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                    .code("print(1)")
                    .language("python")
                    .build();

            assertThatThrownBy(() -> sandbox.executeCode(request))
                    .isInstanceOf(HttpException.class)
                    .hasMessage("connect timed out");
        }
    }

    @Test
    void authHeaderIsSet() {
        try (MockedStatic<HttpUtil> mockedHttp = mockStatic(HttpUtil.class)) {
            HttpRequest mockRequest = mock(HttpRequest.class);
            HttpResponse mockResponse = mock(HttpResponse.class);
            String jsonResponse = "{\"outputList\":[],\"message\":\"OK\",\"status\":1}";

            mockedHttp.when(() -> HttpUtil.createPost(anyString())).thenReturn(mockRequest);
            when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
            when(mockRequest.body(anyString())).thenReturn(mockRequest);
            when(mockRequest.timeout(anyInt())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            when(mockResponse.body()).thenReturn(jsonResponse);

            ExecuteCodeRequest request = ExecuteCodeRequest.builder()
                    .code("print(1)")
                    .language("python")
                    .build();

            sandbox.executeCode(request);

            ArgumentCaptor<String> headerNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> headerValueCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockRequest).header(headerNameCaptor.capture(), headerValueCaptor.capture());

            assertThat(headerNameCaptor.getValue()).isEqualTo("auth");
            assertThat(headerValueCaptor.getValue()).isEqualTo(AUTH_SECRET);
        }
    }
}
