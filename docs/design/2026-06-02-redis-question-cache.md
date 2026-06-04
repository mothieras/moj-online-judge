# 题目详情 Redis 缓存 — 设计文档

**日期**：2026-06-02
**范围**：只缓存「题目详情」读路径（`GET /question/get/vo`），让简历「Redis 缓存高频接口」成立且经得起面试深挖。
**前提**：已确认 `getQuestionVO(question, request)` 与登录用户无关（只关联题目作者信息），故按题目 id 缓存正确，不串用户。

## 目标 / 非目标

**目标**
- 题目详情读请求命中 Redis，不打 MySQL。
- 配套缓存穿透 / 雪崩防护 + 写操作缓存失效。
- 提供清晰的面试讲点（穿透 / 雪崩 / 击穿 / 一致性的边界与取舍）。

**非目标（YAGNI，仅作为面试「可扩展点」口述，不写进代码）**
- 列表 / 分页查询缓存
- 布隆过滤器
- 分布式锁防击穿
- 延迟双删

## 架构（Cache-Aside 旁路缓存）

```
GET /question/get/vo?id=X
  → QuestionController.getQuestionVOById(id, request)
      → QuestionService.getQuestionVOByIdWithCache(id, request)   ← 新增
           key = "question:vo:" + id
           ├─ 命中正常值      → 反序列化返回
           ├─ 命中空值哨兵    → 直接返回 null（防穿透，不查库）
           └─ 未命中          → questionService.getById(id)
                                  ├─ 查不到 → 写空值哨兵, TTL=60s         → 返回 null
                                  └─ 查到   → getQuestionVO 组装 VO
                                              → 写缓存, TTL=30min ± 随机抖动 → 返回 VO
```

**写操作失效**（`QuestionServiceImpl` 内，成功后执行 `redisTemplate.delete("question:vo:"+id)`）：
- `updateQuestion`、`editQuestion`、`deleteQuestion` → 删 key（删而非更新，避免并发写脏）
- `addQuestion` → 不处理（新 id 尚无缓存）

## 组件 / 改动清单

1. **`config/RedisConfig`（新增）** — `RedisTemplate<String,Object>` Bean：
   - key/hashKey：`StringRedisSerializer`
   - value/hashValue：`GenericJackson2JsonRedisSerializer`（缓存内容 JSON 可读、可 `redis-cli` 排查）
   - 当前项目无此 Bean，默认 JDK 序列化会产生二进制乱码，必须加。

2. **`QuestionService` / `QuestionServiceImpl`** — 新增两个方法（缓存逻辑收敛在 service 层）：
   - `QuestionVO getQuestionVOByIdWithCache(long id, HttpServletRequest request)`：实现上面的 Cache-Aside 逻辑。
   - `void evictQuestionVOCache(long id)`：`redisTemplate.delete("question:vo:"+id)`，供写操作调用（key 构造只在 service 里有一处）。
   - 注入 `RedisTemplate`。
   - **空值哨兵（明确实现）**：用字符串常量 `NULL_SENTINEL = "__NULL__"`。`RedisTemplate<String,Object>` 配 `GenericJackson2JsonRedisSerializer` 会带类型信息，故反序列化后真实值是 `QuestionVO`、哨兵是 `String`。读时判断：取到的对象若 `equals(NULL_SENTINEL)`（或非 QuestionVO 类型）→ 视为「已知不存在」返回 null；否则强转 QuestionVO 返回。

3. **失效触发放在 Controller 写处理后**（key 构造仍在 service 的 `evictQuestionVOCache`）：
   - `QuestionController.updateQuestion` / `editQuestion` / `deleteQuestion` 在 `questionService` 写库**成功之后**调 `questionService.evictQuestionVOCache(id)`。
   - `addQuestion` 不处理（新 id 尚无缓存）。
   - 选「删缓存」而非「更新缓存」，避免并发写脏。

4. **`QuestionController.getQuestionVOById`** — 改为调用 `getQuestionVOByIdWithCache`，替换原 `getById + getQuestionVO`。

## 关键常量

| 项 | 值 | 理由 |
|----|-----|------|
| key 前缀 | `question:vo:{id}` | 统一前缀便于排查 / 批量管理 |
| 正常值 TTL | 30min + 随机(0~5min) | 抖动防雪崩 |
| 空值哨兵 TTL | 60s | 短，挡刷不存在 id 又不长期占位 |

## 错误处理

- Redis 不可用时：缓存读写包 try/catch 或降级——**缓存失败不应拖垮主流程**，降级为直接查库（记 warn 日志）。实现时确认：读缓存异常 → 走 DB；写缓存异常 → 吞掉并 log。
- 题目不存在：返回 null（与现有行为一致），不抛异常；由 Controller/前端处理空。

## 测试策略（TDD）

- **单测**（mock `RedisTemplate` / `QuestionService.getById`）：
  1. 命中缓存 → 不调用 `getById`
  2. 未命中 + 查到 → 调 `getById`、回填缓存、返回 VO
  3. 未命中 + 查不到 → 写空值哨兵、返回 null
  4. 命中空值哨兵 → 不调 `getById`、返回 null
  5. 写操作（update/edit/delete）→ 调 `redisTemplate.delete(正确 key)`
- **手动联调**：起 Redis + 应用，造一条题目，连续两次 `GET /question/get/vo?id=X`，第二次应命中（日志 / `redis-cli -n 2 keys 'question:vo:*'` 验证）；改一次题目后 key 应消失。

## 面试讲点（口述，不进代码）

| 问题 | 回答 |
|------|------|
| 穿透 | 空值哨兵（短 TTL）；更强可上布隆过滤器 |
| 雪崩 | TTL 随机抖动；更强可做多级缓存 / 热点预热 |
| 击穿 | 单题非单一超热 key，当前不做；可加 setnx 互斥重建（知道边界） |
| 一致性 | 删缓存 + TTL 兜底；极端并发有短暂脏窗口，可上延迟双删 |
| 作者信息陈旧 | 缓存含作者 UserVO，作者改资料后 TTL 内短暂陈旧，可接受 |
