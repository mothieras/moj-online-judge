package com.moj.judge.codesandbox;

import com.moj.judge.codesandbox.impl.ExampleCodeSandbox;
import com.moj.judge.codesandbox.impl.RemoteCodeSandbox;
import com.moj.judge.codesandbox.impl.ThirdPartyCodeSandbox;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 代码沙箱工厂（根据字符串参数创建指定的代码沙箱实现）
 *
 * @deprecated 使用 {@link CodeSandboxRouter} 替代，Router 是标准 Spring Bean，更符合 DI 约定且易于测试。
 */
@Deprecated
@Component
public class CodeSandboxFactory implements ApplicationContextAware {
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        applicationContext = context;
    }

    /**
     * 创建代码沙箱实例
     *
     * @param type 沙箱类型
     * @return
     */
    public static CodeSandbox newInstance(String type) {
        switch (type) {
            case "remote":
                return applicationContext.getBean(RemoteCodeSandbox.class);
            case "thirdParty":
                return new ThirdPartyCodeSandbox();
            default:
                return new ExampleCodeSandbox();
        }
    }
}
