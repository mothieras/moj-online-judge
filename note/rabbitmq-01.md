# RabbitMQ 学习笔记 (1) — 2026-05-20

围绕 MOJ 判题项目，对照 [JavaGuide MQ 教程](https://www.javaguide.cn/high-performance/message-queue/message-queue.html) 和 [RabbitMQ 面试题](https://www.javaguide.cn/high-performance/message-queue/rabbitmq-questions.html)。

---

## 第一单元：为什么要用消息队列？

### 当前项目的痛点

```java
// QuestionSubmitServiceImpl.java:96
CompletableFuture.runAsync(() -> {
    judgeService.doJudge(questionSubmitId);
});
```

`runAsync` 返回 `CompletableFuture<Void>`，不返回结果，异常静默丢失。

### 三个硬伤 → MQ 三大价值

| 问题 | MQ 对应价值 |
|------|-----------|
| 服务重启 → ForkJoinPool 内存队列清空 → 任务丢失 | **持久化**，消息存磁盘 |
| 1000 人同时提交 → 线程池无限创建 → CPU 打满 | **削峰/限流**，消息排队，消费者按能力拉取 |
| 沙箱超时/异常被吞掉 → 提交永远没结果 | **可靠消费 + 重试**，手动 ACK/NACK |

### 引入 MQ 的代价

- **可用性降低**：RabbitMQ 挂了 → 提交不通
- **复杂性提高**：需要处理重复消费、消息丢失
- **一致性问题**：生产者返回成功 ≠ 消费者处理成功

---

## 第二单元：核心概念

### 消息模型

```
Producer  →  Exchange  →  Queue  →  Consumer
(提交服务)    (路由)      (排队)     (判题服务)
```

### Exchange 四种类型

| 类型 | 路由规则 | OJ 场景 |
|------|---------|---------|
| Direct | RoutingKey 完全匹配 | 按语言路由：`judge.java` → java 队列 |
| Fanout | 无视 RoutingKey，广播 | 系统公告推送 |
| Topic | `*` `#` 模糊匹配 | 按难度+语言：`judge.hard.*` |
| Headers | 按消息头键值匹配 | 基本不用，性能差 |

### 名字 vs RoutingKey

- **队列名**：声明时定义，消费时 `@RabbitListener(queues = "xxx")` 用
- **RoutingKey**：面向路由，发消息和绑定时用

发送时只跟 Exchange 名 + RoutingKey 打交道，不写队列名：

```java
rabbitTemplate.convertAndSend("judge.exchange", "judge", submitId);
```

### Channel（信道）

一个 TCP 连接上复用多个 Channel。Channel 不是线程安全的，多线程各自用自己的。

---

## 第三单元：消息可靠性

消息可能丢的三个环节：

```
Producer ──①──▶ Broker ──②──▶ Consumer
```

### ① 生产者 → Broker：Publisher Confirm

```yaml
spring.rabbitmq.publisher-confirm-type: correlated
```

发消息后等 Broker 回 ACK，没收到就重发。

### ② Broker 存储期间：双重持久化

| 持久化对象 | 管什么 | 设置方式 |
|-----------|--------|---------|
| 队列 | 重启后队列还在不在 | `QueueBuilder.durable("name")` |
| 消息 | 重启后消息还在不在 | `delivery-mode=2` (PERSISTENT) |

- 队列是盒子，消息是盒子里的东西
- 两者独立，但持久化消息依赖持久化队列才有意义
- 非持久队列 + 持久消息：队列都没了，消息也找不回来

### ③ Broker → 消费者：手动 ACK

```java
@RabbitListener(queues = "judge.queue", ackMode = "MANUAL")
public void onMessage(Message message, Channel channel) {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
        judgeService.doJudge(submitId);
        channel.basicAck(tag, false);              // 成功 → 确认删除
    } catch (Exception e) {
        channel.basicNack(tag, false, true);        // 失败 → 重回队列
    }
}
```

### basicNack 三个参数

```java
void basicNack(long deliveryTag, boolean multiple, boolean requeue)
```

| 参数 | 含义 |
|------|------|
| `deliveryTag` | 消息投递编号，标识哪一条 |
| `multiple` | true = 批量 NACK 小于等于该 tag 的所有消息 |
| `requeue` | true = 重回队列；false = 不放回（配合 DLX 进死信） |

---

## 第四单元：死信队列 (DLQ)

### 问题

requeue=true 可能导致无限重试循环。需要限制重试次数。

### 触发死信的三种情况

| 条件 | OJ 场景 |
|------|---------|
| NACK/Reject 且 requeue=false | 重试 3 次仍失败 |
| 消息 TTL 过期 | 提交 30 分钟未消费 |
| 队列满了 | 突发大量提交 |

### 实现

```java
// 正常队列 → 指定死信 Exchange + RoutingKey
QueueBuilder.durable("judge.queue")
    .deadLetterExchange("judge.dlx.exchange")
    .deadLetterRoutingKey("judge.dead")
    .build();

// 死信队列 → 正常绑定
Queue deadQueue = QueueBuilder.durable("judge.dead.queue").build();
BindingBuilder.bind(deadQueue).to(dlxExchange()).with("judge.dead");
```

### 流转

```
judge.queue ──(NACK requeue=false)──▶ judge.dlx.exchange
                                          │
                                    RoutingKey="judge.dead"
                                          │
                                          ▼
                                    judge.dead.queue ──▶ 人工排查
```

- 死信队列是给开发者看的，不是给用户看的
- 相比直接丢弃：能查到哪条消息丢了、原数据还在、能手动重投

### 重试次数

- Classic Queue：无内置计数，需在消息头手动维护 `x-retry-count`
- Quorum Queue（3.8+）：原生支持 `x-delivery-limit`

---

## 消息结构

AMQP 消息 = 消息头（Headers）+ 消息体（Payload），类似 HTTP。消息头可自定义键值对。

---

## 第五单元：延迟队列

### 场景

提交超过 30 分钟没判完，需要超时处理。RabbitMQ 本身没有延迟队列，两种实现方式。

### 方式一：TTL + DLX

消息在设置了 TTL 的队列里过期 → 自动变成死信 → 转发到 DLX → 路由到超时处理队列。

```java
@Bean
public Queue judgeQueue() {
    return QueueBuilder.durable("judge.queue")
        .ttl(30 * 60 * 1000)                         // 30 分钟过期
        .deadLetterExchange("judge.dlx.exchange")
        .deadLetterRoutingKey("judge.timeout")        // 与死信的 RoutingKey 区分
        .build();
}
```

DLX 绑两个队列：
- `judge.dead.queue` — NACK 超过重试的
- `judge.timeout.queue` — TTL 过期的

**缺点**：TTL 是**队列级别**的，所有消息同一过期时间；过期消息要排到队头才触发删除。

### 方式二：延迟插件

安装 `rabbitmq-delayed-message-exchange` 插件，消息级别设延迟：

```java
MessageProperties props = new MessageProperties();
props.setDelay(30 * 60 * 1000);  // 每条消息单独设
rabbitTemplate.convertAndSend("judge.delayed.exchange", "judge", submitId, props);
```

**风险**：延迟消息存在 Mnesia 内存表，大量积压会爆内存。

### 对比

| | TTL + DLX | 延迟插件 |
|---|---|---|
| 实现 | 现有机制组合 | 需安装插件 |
| 延迟粒度 | 整个队列统一 | 每条消息单独设 |
| 大量积压 | 问题不大 | 可能爆内存 |

---

## 第六单元：消息顺序性 + 重复消费

### 消息顺序性

RabbitMQ 只保证**单个 Queue 内的 FIFO**。多消费者场景会乱：

- 不同消费者处理速度不同
- NACK + requeue=true 会让消息回到队尾，打乱顺序

OJ 项目一般不需要全局有序。如需分区有序：同题号 → 同一个 RoutingKey → 同一个 Queue → 单 Consumer。

### 重复消费

用户双击提交 → 两条消息 → 重复判题。

**项目已有的防护**——`JudgeServiceImpl.doJudge()` 的状态机校验：

```java
if (!submit.getStatus().equals(QuestionSubmitStatusEnum.WAITING.getValue())) {
    throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目正在判题中");
}
```

第一条消息把状态从 WAITING 改成 RUNNING，第二条消息进来发现不是 WAITING 直接抛异常。

**处理方式区分**：

```java
catch (BusinessException e) {
    if ("题目正在判题中".equals(e.getMessage())) {
        channel.basicAck(deliveryTag, false);   // 重复消息，直接确认
    } else {
        channel.basicNack(deliveryTag, false, true);  // 其他异常，重试
    }
}
```

---

## 第七单元：五种工作模式

本质上就是 Exchange 类型 + Queue + Binding 的组合。

### 1. 简单模式

一个生产者，一个队列，一个消费者。用默认 Exchange，RoutingKey = 队列名。太简陋，不会用。

### 2. Work 模式（竞争消费）

```
                         ┌─▶ ConsumerA
Producer ──▶ [queue] ────┤
                         └─▶ ConsumerB
```

多个消费者竞争同一个队列，消息轮询分发。OJ：多个判题 Worker 消费 `judge.queue`，`prefetch=1` 公平分发。

### 3. Pub/Sub（发布订阅）

Fanout Exchange，忽略 RoutingKey，广播到所有绑定队列。

```
                         ┌─▶ [queueA] ──▶ ConsumerA
Producer ──▶ Fanout ─────┤
                         └─▶ [queueB] ──▶ ConsumerB
```

OJ：判题通过后广播——积分服务、排行榜服务、通知服务各自消费。

### 4. Routing 模式

Direct Exchange，RoutingKey 完全匹配。

```
                         routingKey="java" ──▶ [java.queue]
Producer ──▶ Direct ────┤
                         routingKey="cpp"  ──▶ [cpp.queue]
```

OJ：按语言分流判题。

### 5. Topic 模式

Topic = Direct + 通配符。`*` 匹配一个词，`#` 匹配零个或多个词。OJ：按难度+语言路由，如 `judge.easy.*`。

### 面试话术

> "简单和 Work 是点对点，Pub/Sub 用 Fanout 广播，Routing 用 Direct 精确匹配，Topic 是 Routing 的通配符扩展。我们项目主要用 Direct 按语言分流判题，配合 Fanout 做结果通知。"
