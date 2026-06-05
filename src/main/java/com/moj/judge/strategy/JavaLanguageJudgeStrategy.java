package com.moj.judge.strategy;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Java 判题策略（复用默认策略逻辑，仅限定语言为 Java）
 */
@Component
public class JavaLanguageJudgeStrategy extends DefaultJudgeStrategy {

    @Override
    public List<String> supportedLanguages() {
        return List.of("java");
    }
}
