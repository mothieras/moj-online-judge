package com.moj.judge.strategy;

import com.moj.judge.codesandbox.model.JudgeInfo;

import java.util.Collections;
import java.util.List;

/**
 * 判题策略
 */
public interface JudgeStrategy {
    /**
     * 支持的编程语言列表
     * @return 空列表表示无特定语言支持
     */
    default List<String> supportedLanguages() {
        return Collections.emptyList();
    }

    /**
     * 执行判题
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext);
}
