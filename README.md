# MOJ Backend

MOJ（Moj Online Judge）在线判题系统后端，提供用户认证、题库管理、代码提交与自动判题等核心能力。

## 技术栈

- **Java 21** + **Spring Boot 3.4**
- **MyBatis Plus** + **MySQL** — 数据持久化
- **Redis** + **Spring Session** — 分布式登录
- **Knife4j** — API 文档与在线调试
- **Hutool** / **Commons Lang3** — 通用工具

## 快速启动

```bash
# 1. 创建数据库和表
mysql -u root -p < sql/create_table.sql

# 2. 修改 application.yml 中的数据库和 Redis 配置

# 3. 启动
./mvnw spring-boot:run

# 4. 访问 API 文档
open http://localhost:8121/api/doc.html
```

## API 概览

| 模块 | 端点 | 说明 |
|------|------|------|
| 用户 | `/api/user/register` | 注册 |
| 用户 | `/api/user/login` | 登录 |
| 用户 | `/api/user/logout` | 注销 |
| 用户 | `/api/user/get/login` | 获取当前用户 |
| 题目 | `/api/question/add` | 添加题目（管理员） |
| 题目 | `/api/question/list/page/vo` | 分页查询题目 |
| 判题 | `/api/question_submit/do` | 提交代码进行判题 |
| 判题 | `/api/question_submit/list/page` | 查询提交记录 |

完整接口文档见 Knife4j 页面。

## 项目结构

```
com.moj
├── controller/          # REST 控制器
├── service/
│   └── impl/            # 业务逻辑实现
├── mapper/              # MyBatis Mapper
├── model/
│   ├── entity/          # 数据库实体
│   ├── dto/             # 请求/响应 DTO
│   ├── vo/              # 视图对象
│   └── enums/           # 枚举
├── judge/               # 判题核心
│   ├── codesandbox/     #   沙箱（工厂+代理模式）
│   └── strategy/        #   判题策略（策略模式）
├── config/              # Spring 配置
├── aop/                 # AOP（权限校验、日志）
├── common/              # 通用类（BaseResponse、ErrorCode）
├── constant/            # 常量
├── exception/           # 全局异常处理
└── utils/               # 工具类
```

## 判题流程

```
QuestionSubmitController        ← 用户提交代码
        ↓
JudgeServiceImpl                ← 编排：获取题目 & 提交信息
        ↓
CodeSandboxFactory.newInstance  ← 根据配置创建沙箱（example/remote/thirdParty）
        ↓
CodeSandboxProxy                ← 代理增强（日志记录）
        ↓
Sandbox.executeCode()           ← 在隔离环境执行代码
        ↓
JudgeManager.doJudge()          ← 按语言选择判题策略
        ↓
JudgeStrategy.doJudge()         ← 对比输出 / 检查时间内存限制
        ↓
更新 QuestionSubmit 状态         ← 结果持久化
```

## 设计模式

| 模式 | 应用位置 | 说明 |
|------|----------|------|
| 策略模式 | `judge/strategy/` | `JudgeStrategy` 接口 + `DefaultJudgeStrategy` / `JavaLanguageJudgeStrategy`，按语言分发判题逻辑 |
| 工厂模式 | `judge/codesandbox/CodeSandboxFactory` | 根据 `codesandbox.type` 配置动态创建沙箱实例 |
| 代理模式 | `judge/codesandbox/CodeSandboxProxy` | 在沙箱调用前后增加日志，不侵入沙箱实现 |

## 配置

```yaml
# 代码沙箱类型：example（本地示例）/ remote（远程）/ thirdParty（第三方）
codesandbox:
  type: remote
```

多环境配置：`application.yml`（dev 默认）、`application-test.yml`、`application-prod.yml`。
