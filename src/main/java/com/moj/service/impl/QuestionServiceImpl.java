package com.moj.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.moj.common.ErrorCode;
import com.moj.constant.CommonConstant;
import com.moj.exception.BusinessException;
import com.moj.exception.ThrowUtils;
import com.moj.mapper.QuestionMapper;
import com.moj.model.dto.question.QuestionQueryRequest;
import com.moj.model.entity.Question;
import com.moj.model.entity.User;
import com.moj.model.vo.QuestionVO;
import com.moj.model.vo.UserVO;
import com.moj.service.QuestionService;
import com.moj.service.UserService;
import com.moj.utils.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author luncer
 * @description 针对表【question(题目)】的数据库操作Service实现
 * @createDate 2025-02-14 16:02:47
 */
@Slf4j
@Service
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question>
        implements QuestionService {

    /** 题目详情缓存 key 前缀 */
    private static final String QUESTION_VO_CACHE_PREFIX = "question:vo:";
    /** 题目列表缓存 key 前缀 */
    private static final String QUESTION_LIST_CACHE_PREFIX = "question:list:vo:";
    /** 空值占位标记：表示「该 id 在库中确实不存在」，用于防缓存穿透 */
    private static final String NULL_SENTINEL = "__NULL__";
    /** 正常值缓存基础 TTL（秒）= 30 分钟 */
    private static final long CACHE_TTL_SECONDS = 30 * 60;
    /** TTL 随机抖动上限（秒）= 5 分钟，防雪崩 */
    private static final int CACHE_TTL_JITTER_SECONDS = 5 * 60;
    /** 空值占位 TTL（秒）= 60 秒 */
    private static final long NULL_CACHE_TTL_SECONDS = 60;
    /** 列表缓存 TTL（秒）= 5 分钟，比详情短 */
    private static final long LIST_CACHE_TTL_SECONDS = 5 * 60;
    /** 列表缓存 TTL 抖动上限（秒）= 2 分钟 */
    private static final int LIST_CACHE_TTL_JITTER_SECONDS = 2 * 60;

    @Resource
    private UserService userService;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;


    /**
     * 校验题目是否合法
     *
     * @param question
     * @param add
     */
    @Override
    public void validQuestion(Question question, boolean add) {
        if (question == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String title = question.getTitle();
        String content = question.getContent();
        String tags = question.getTags();
        String answer = question.getAnswer();
        String judgeCase = question.getJudgeCase();
        String judgeConfig = question.getJudgeConfig();

        // 创建时，参数不能为空
        if (add) {
            ThrowUtils.throwIf(StringUtils.isAnyBlank(title, content, tags), ErrorCode.PARAMS_ERROR);
        }
        // 有参数则校验
        if (StringUtils.isNotBlank(title) && title.length() > 80) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题过长");
        }
        if (StringUtils.isNotBlank(content) && content.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "内容过长");
        }
        if (StringUtils.isNotBlank(answer) && answer.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "答案过长");
        }
        if (StringUtils.isNotBlank(judgeCase) && judgeCase.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "判题用例过长");
        }
        if (StringUtils.isNotBlank(judgeConfig) && judgeConfig.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "判题配置过长");
        }
    }

    /**
     * 获取查询包装类（用户根据哪些字段查询，根据前端传来的请求对象，得到mybatis框架支持的查询QueryWrapper
     *
     * @param questionQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Question> getQueryWrapper(QuestionQueryRequest questionQueryRequest) {
        QueryWrapper<Question> queryWrapper = new QueryWrapper<>();
        if (questionQueryRequest == null) {
            return queryWrapper;
        }
        Long id = questionQueryRequest.getId();
        String title = questionQueryRequest.getTitle();
        String content = questionQueryRequest.getContent();
        List<String> tags = questionQueryRequest.getTags();
        String answer = questionQueryRequest.getAnswer();
        Long userId = questionQueryRequest.getUserId();
        String sortField = questionQueryRequest.getSortField();
        String sortOrder = questionQueryRequest.getSortOrder();

        // 拼接查询条件

        queryWrapper.like(StringUtils.isNotBlank(title), "title", title);
        queryWrapper.like(StringUtils.isNotBlank(content), "content", content);
        queryWrapper.like(StringUtils.isNotBlank(answer), "answer", answer);
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        queryWrapper.eq(ObjectUtils.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    @Override
    public QuestionVO getQuestionVO(Question question, HttpServletRequest request) {
        QuestionVO questionVO = QuestionVO.objToVo(question);
        // 关联查询用户信息
        Long userId = question.getUserId();
        User user = null;
        if (userId != null && userId > 0) {
            user = userService.getById(userId);
        }
        UserVO userVO = userService.getUserVO(user);
        questionVO.setUserVO(userVO);

        return questionVO;
    }

    @Override
    public Page<QuestionVO> getQuestionVOPage(Page<Question> questionPage, HttpServletRequest request) {
        List<Question> questionList = questionPage.getRecords();
        Page<QuestionVO> questionVOPage = new Page<>(questionPage.getCurrent(), questionPage.getSize(), questionPage.getTotal());
        if (CollUtil.isEmpty(questionList)) {
            return questionVOPage;
        }
        // 1. 关联查询用户信息
        Set<Long> userIdSet = questionList.stream().map(Question::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));

        // 填充信息
        List<QuestionVO> questionVOList = questionList.stream().map(question -> {
            QuestionVO questionVO = QuestionVO.objToVo(question);
            Long userId = question.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            questionVO.setUserVO(userService.getUserVO(user));
            return questionVO;
        }).collect(Collectors.toList());
        questionVOPage.setRecords(questionVOList);
        return questionVOPage;
    }

    @Override
    public QuestionVO getQuestionVOByIdWithCache(long id, HttpServletRequest request) {
        String key = QUESTION_VO_CACHE_PREFIX + id;
        // 1. 读缓存（降级：读失败当未命中，缓存故障不拖垮主流程）
        Object cached = null;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读题目缓存失败，降级查库 key={}", key, e);
        }
        if (cached != null) {
            // 命中正常值
            if (cached instanceof QuestionVO) {
                return (QuestionVO) cached;
            }
            // 命中空值占位 → 已知不存在，直接返回，不查库（防穿透）
            return null;
        }
        // 2. 未命中 → 查库
        Question question = this.getById(id);
        if (question == null) {
            // 空值占位（短 TTL）防穿透
            safeSet(key, NULL_SENTINEL, NULL_CACHE_TTL_SECONDS);
            return null;
        }
        QuestionVO questionVO = this.getQuestionVO(question, request);
        // 3. 回填（TTL 随机抖动防雪崩）
        long ttl = CACHE_TTL_SECONDS + ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER_SECONDS + 1);
        safeSet(key, questionVO, ttl);
        return questionVO;
    }

    @Override
    public void evictQuestionVOCache(long id) {
        try {
            redisTemplate.delete(QUESTION_VO_CACHE_PREFIX + id);
        } catch (Exception e) {
            log.warn("删除题目缓存失败 id={}", id, e);
        }
    }

    @Override
    public Page<QuestionVO> getQuestionVOPageWithCache(QuestionQueryRequest req, HttpServletRequest request) {
        // 仅缓存无筛选条件的默认查询，带筛选的直查 DB
        if (!isDefaultQuery(req)) {
            Page<Question> questionPage = this.page(
                    new Page<>(req.getCurrent(), req.getPageSize()),
                    this.getQueryWrapper(req));
            return this.getQuestionVOPage(questionPage, request);
        }
        String key = QUESTION_LIST_CACHE_PREFIX + req.getCurrent() + ":" + req.getPageSize()
                + ":" + req.getSortField() + ":" + req.getSortOrder();
        // 1. 读缓存
        Object cached = null;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读列表缓存失败，降级查库 key={}", key, e);
        }
        if (cached instanceof Page) {
            @SuppressWarnings("unchecked")
            Page<QuestionVO> page = (Page<QuestionVO>) cached;
            return page;
        }
        // 2. 查库
        Page<Question> questionPage = this.page(
                new Page<>(req.getCurrent(), req.getPageSize()),
                this.getQueryWrapper(req));
        Page<QuestionVO> result = this.getQuestionVOPage(questionPage, request);
        // 3. 回填（短 TTL + 抖动）
        long ttl = LIST_CACHE_TTL_SECONDS + ThreadLocalRandom.current().nextInt(LIST_CACHE_TTL_JITTER_SECONDS + 1);
        safeSet(key, result, ttl);
        return result;
    }

    @Override
    public void evictQuestionListCache() {
        try {
            var keys = redisTemplate.keys(QUESTION_LIST_CACHE_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("删除列表缓存失败", e);
        }
    }

    /** 是否无筛选条件的默认查询（仅分页 + 排序，无 title/content/tags 等过滤） */
    private boolean isDefaultQuery(QuestionQueryRequest req) {
        return req.getId() == null
                && req.getUserId() == null
                && StringUtils.isBlank(req.getTitle())
                && StringUtils.isBlank(req.getContent())
                && StringUtils.isBlank(req.getAnswer())
                && CollUtil.isEmpty(req.getTags());
    }

    /** 写缓存（失败仅告警、吞掉，不影响主流程） */
    private void safeSet(String key, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写题目缓存失败 key={}", key, e);
        }
    }
}




