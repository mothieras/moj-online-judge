package com.moj.judge;

import com.moj.judge.strategy.JudgeContext;
import com.moj.judge.strategy.JudgeStrategy;
import com.moj.judge.codesandbox.model.JudgeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 判题管理，策略分发
 */
@Slf4j
@Component
public class JudgeManager {

    private final Map<String, JudgeStrategy> strategyMap = new HashMap<>();

    public JudgeManager(List<JudgeStrategy> strategies) {
        for (JudgeStrategy s : strategies) {
            for (String lang : s.supportedLanguages()) {
                strategyMap.put(lang, s);
            }
        }
        log.info("JudgeManager loaded strategies: {}", strategyMap.keySet());
    }

    /**
     * 执行判题
     */
    public JudgeInfo doJudge(JudgeContext judgeContext) {
        String language = judgeContext.getQuestionSubmit().getLanguage();
        JudgeStrategy strategy = strategyMap.get(language);
        if (strategy == null) {
            strategy = strategyMap.get("*");
        }
        if (strategy == null) {
            log.warn("No strategy for language '{}' and no fallback", language);
            JudgeInfo error = new JudgeInfo();
            error.setMessage("System Error");
            return error;
        }
        return strategy.doJudge(judgeContext);
    }
}
