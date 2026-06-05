package com.moj.judge.codesandbox;

import com.moj.judge.codesandbox.impl.ExampleCodeSandbox;
import com.moj.judge.codesandbox.impl.RemoteCodeSandbox;
import com.moj.judge.codesandbox.impl.ThirdPartyCodeSandbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class CodeSandboxRouter {

    private final ApplicationContext ctx;

    @Value("${codesandbox.type:example}")
    private String type;

    public CodeSandboxRouter(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    public CodeSandbox select() {
        switch (type) {
            case "remote":
                return ctx.getBean(RemoteCodeSandbox.class);
            case "thirdParty":
                return new ThirdPartyCodeSandbox();
            default:
                return new ExampleCodeSandbox();
        }
    }
}
