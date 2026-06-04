package com.moj.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.moj.model.dto.question.QuestionQueryRequest;
import com.moj.model.entity.Question;
import com.moj.model.entity.Question;
import com.baomidou.mybatisplus.extension.service.IService;
import com.moj.model.vo.QuestionVO;

import jakarta.servlet.http.HttpServletRequest;

/**
* @author luncer
* @description 针对表【question(题目)】的数据库操作Service
* @createDate 2025-02-14 16:02:48
*/
public interface QuestionService extends IService<Question> {
/**
     * 校验
     *
     * @param question
     * @param add
     */
    void validQuestion(Question question, boolean add);

    /**
     * 获取查询条件
     *
     * @param questionQueryRequest
     * @return
     */
    QueryWrapper<Question> getQueryWrapper(QuestionQueryRequest questionQueryRequest);



    /**
     * 获取题目封装
     *
     * @param question
     * @param request
     * @return
     */
    QuestionVO getQuestionVO(Question question, HttpServletRequest request);

    /**
     * 分页获取题目封装
     *
     * @param questionPage
     * @param request
     * @return
     */
    Page<QuestionVO> getQuestionVOPage(Page<Question> questionPage, HttpServletRequest request);

    /**
     * 按 id 获取题目详情封装（带 Redis 缓存，Cache-Aside）
     *
     * @param id      题目 id
     * @param request 请求（透传给 getQuestionVO，本身不参与缓存 key）
     * @return 题目 VO；不存在时返回 null（并缓存空值占位防穿透）
     */
    QuestionVO getQuestionVOByIdWithCache(long id, HttpServletRequest request);

    /**
     * 失效某题目详情的缓存（写操作成功后调用）
     *
     * @param id 题目 id
     */
    void evictQuestionVOCache(long id);
}
