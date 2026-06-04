package com.moj.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.moj.model.dto.question.QuestionQueryRequest;
import com.moj.model.dto.questsubmit.QuestionSubmitAddRequest;
import com.moj.model.dto.questsubmit.QuestionSubmitQueryRequest;
import com.moj.model.entity.Question;
import com.moj.model.entity.QuestionSubmit;
import com.baomidou.mybatisplus.extension.service.IService;
import com.moj.model.entity.User;
import com.moj.model.vo.QuestionSubmitVO;
import com.moj.model.vo.QuestionVO;

import jakarta.servlet.http.HttpServletRequest;

/**
 * @author luncer
 * @description 针对表【question_submit(题目提交)】的数据库操作Service
 * @createDate 2025-02-14 16:02:33
 */
public interface QuestionSubmitService extends IService<QuestionSubmit> {
    /**
     * 题目提交
     *
     * @param questionSubmitAddRequest
     * @param loginUser
     * @return
     */
    long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser);

    /**
     * 获取查询条件
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest);


   /**
     * 获取题目封装
     *
     * @param questionSubmit
     * @param loginUser
     * @return
     */
    QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, User loginUser);

    /**
     * 分页获取题目封装
     *
     * @param questionSubmitPage
     * @param loginUser
     * @return
     */
    Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage, User loginUser);
}
