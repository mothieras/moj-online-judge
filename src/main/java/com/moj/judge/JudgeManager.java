package com.moj.judge;

import com.moj.judge.strategy.DefaultJudgeStrategy;
import com.moj.judge.strategy.JavaLanguageJudgeStrategy;
import com.moj.judge.strategy.JudgeContext;
import com.moj.judge.strategy.JudgeStrategy;
import com.moj.judge.codesandbox.model.JudgeInfo;
import com.moj.model.entity.QuestionSubmit;
import org.springframework.stereotype.Service;

/**
 * 判题管理，简化调用
 */
@Service
public class JudgeManager {

    /**
     * 执行判题
     *
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext) {
        QuestionSubmit questionSubmit = judgeContext.getQuestionSubmit();
        String language = questionSubmit.getLanguage();
        JudgeStrategy judgeStrategy = new DefaultJudgeStrategy();
        if ("java".equals(language)) {
            judgeStrategy = new JavaLanguageJudgeStrategy();
        }
        return judgeStrategy.doJudge(judgeContext);
    }
}
