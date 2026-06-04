package com.moj.service;

import com.moj.model.entity.Question;
import com.moj.model.vo.QuestionVO;
import com.moj.service.impl.QuestionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 题目详情 Redis 缓存（Cache-Aside）单元测试。
 * 纯 Mockito，不加载 Spring 上下文、不依赖真实 Redis/MySQL。
 */
@ExtendWith(MockitoExtension.class)
class QuestionVoCacheTest {

    private static final long QUESTION_ID = 7L;
    private static final String KEY = "question:vo:" + QUESTION_ID;
    private static final String NULL_SENTINEL = "__NULL__";

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;

    private QuestionServiceImpl service;

    @BeforeEach
    void setUp() {
        // spy 真实 service，注入 mock RedisTemplate；getById/getQuestionVO 在各测试里按需 stub
        service = spy(new QuestionServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
    }

    @Test
    void cacheHit_returnsCachedVo_withoutQueryingDb() {
        QuestionVO cached = new QuestionVO();
        cached.setId(QUESTION_ID);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(cached);

        QuestionVO result = service.getQuestionVOByIdWithCache(QUESTION_ID, null);

        assertThat(result).isSameAs(cached);
        verify(service, never()).getById(any());
    }

    @Test
    void cacheMiss_found_queriesDbAndBackfillsCache() {
        Question question = new Question();
        question.setId(QUESTION_ID);
        QuestionVO vo = new QuestionVO();
        vo.setId(QUESTION_ID);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(null);
        doReturn(question).when(service).getById(QUESTION_ID);
        doReturn(vo).when(service).getQuestionVO(any(Question.class), any());

        QuestionVO result = service.getQuestionVOByIdWithCache(QUESTION_ID, null);

        assertThat(result).isSameAs(vo);
        // 回填缓存（TTL 随机抖动，不断言具体值）
        verify(valueOps).set(eq(KEY), eq(vo), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void cacheMiss_absent_cachesNullSentinel_andReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(null);
        doReturn(null).when(service).getById(QUESTION_ID);

        QuestionVO result = service.getQuestionVOByIdWithCache(QUESTION_ID, null);

        assertThat(result).isNull();
        verify(valueOps).set(eq(KEY), eq(NULL_SENTINEL), anyLong(), eq(TimeUnit.SECONDS));
        verify(service, never()).getQuestionVO(any(), any());
    }

    @Test
    void cacheHitNullSentinel_returnsNull_withoutQueryingDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(NULL_SENTINEL);

        QuestionVO result = service.getQuestionVOByIdWithCache(QUESTION_ID, null);

        assertThat(result).isNull();
        verify(service, never()).getById(any());
    }

    @Test
    void evict_deletesCorrectKey() {
        service.evictQuestionVOCache(QUESTION_ID);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    void redisGetThrows_degradesToDb() {
        Question question = new Question();
        question.setId(QUESTION_ID);
        QuestionVO vo = new QuestionVO();
        vo.setId(QUESTION_ID);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenThrow(new RuntimeException("redis down"));
        doReturn(question).when(service).getById(QUESTION_ID);
        doReturn(vo).when(service).getQuestionVO(any(Question.class), any());

        QuestionVO result = service.getQuestionVOByIdWithCache(QUESTION_ID, null);

        // 缓存读失败时降级查库，业务正常返回
        assertThat(result).isSameAs(vo);
        verify(service).getById(QUESTION_ID);
    }
}
