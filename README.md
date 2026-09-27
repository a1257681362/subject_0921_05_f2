# EVOPS 基础工作区 —— 连锁眼镜店验光与加工底账

Spring Boot 骨架项目。技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

## 启动

1. `mvn -q -DskipTests compile` 验证构建。
2. `mvn spring-boot:run` 启动；Spring Boot 自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。
4. `mvn test` 运行集成测试（20 个用例，内存 H2）。

`docs/schema/evops.sql` 是交付副本，与 `src/main/resources/schema.sql` 保持一致。

## 统一约定

- 响应结构：`{ success, code, message, data }`；成功 `code=OK`，失败返回明确业务码（见 `BizCode`）。
- 全库**禁止物理删除**：镜片作废/恢复、加工单冲销/恢复均以状态位体现；处方编辑时旧行置 `VOID` 保留，另插 `ACTIVE` 新行；全部操作写只追加日志。
- 每次写入留存：操作人 `X-Operator-Id`、请求号 `X-Request-No`、业务时间 `X-Biz-Time`（请求头传入，自动落库）、版本号 `version_no`（乐观锁）。
- 重复提交由库层唯一约束兜底：`t_process_order.uk_order_request(request_no)` → `REQUEST_DUPLICATED`；单号/编码各有唯一约束。
- 关联表（加工单主表 + 左右眼处方）同一事务，要么全部生效，要么全部不见。

## 业务表

| 表 | 说明 |
| --- | --- |
| `t_lens` | 镜片基础信息（ACTIVE/DISCARDED） |
| `t_lens_oper_log` | 镜片 CREATE/UPDATE/DISCARD/RESTORE 记录 |
| `t_process_order` | 加工单主表 |
| `t_process_order_rx` | 左右眼处方（ACTIVE/VOID，每单每眼仅一条有效） |
| `t_process_order_status_log` | 取镜状态流转记录（只追加，含 REWORK 返修） |
| `t_sensitive_hit_log` | 敏感词命中记录（只追加，供兄弟组取数） |

## 加工单状态

```
CREATED 已建档 ──start──▶ PROCESSING 加工中 ──finish──▶ READY 待取镜 ──pickup──▶ PICKED_UP 已取镜（终态）
     │                            ▲                       │                                            
     │                            └────── rework 返修 ─────┘（成品复检不通过打回，可多次）
     └──────cancel──▶ CANCELLED 已冲销 ──restore──▶ CREATED
   （CREATED/PROCESSING/READY 均可 cancel；PICKED_UP 不可冲销）
```

处方度数（球镜必填，柱镜、下加光选填）按 0.25D（二十五度）一档校验，轴位 1~180，瞳距 20~90。

## 加工单文本入口敏感词管控

加工单建档/修改时扫描 `customerName`、`remark` 两个文本字段：

- **DFA 扫描**：前缀树驻内存，扫描时字间分隔符（空格/标点/符号）直接跳过——“假-药”“假　药”都能命中“假药”。
- **先归一**：全角转半角（含全角空格）、ASCII 大小写折叠；归一化为等长映射，命中位置即原文下标。
- **分级处理**：词级 `BLOCK` 整单拦截（返回 `SENSITIVE_WORD_BLOCKED`，报文带命中词与位置）；词级 `MASK` 命中区间替换为 `*` 后落库。
- **命中留痕**：全部命中写 `t_sensitive_hit_log`（只追加）。打码与加工单同事务；拦截时整单回滚，命中记录以独立事务落库（`biz_id` 为空、记 `order_no`）。兄弟组直接取该表即可。
- **词库热更新**：词库文件 `data/sensitive-words.txt`（`evops.sensitive.word-file` 可配），每行 `词|级别`（级别缺省 MASK，`#` 注释）。每 30s（`evops.sensitive.reload-interval-ms`）检测文件变化自动重建；也可调 reload 接口立即生效。文件缺失时从 classpath 默认词库播种。`evops.sensitive.enabled=false` 可整体关闭。

## 返修率（以加工单底账为准）

- 分母：时段内建档的加工单（按 `created_time`，含已冲销）；分子：其中有 `REWORK` 流转记录的单数；另统计返修总次数（一单可多次返修）。
- 兄弟组算完返修率后调反向核对接口，系统按底账重算并返回双方差值；对方提供返修单号清单时输出双向差集（missing/extra）。

## 接口（写接口均带三个审计请求头）

镜片：

- `POST /api/lens` 建档
- `PUT  /api/lens` 修改（body 带 `versionNo` 乐观锁）
- `GET  /api/lens/{id}`、`GET /api/lens?status=&keyword=&current=&size=`
- `POST /api/lens/{id}/discard` 作废、`POST /api/lens/{id}/restore` 恢复

加工单：

- `POST /api/orders` 建档（主表 + 左右眼处方同事务；文本字段先过敏感词管控）
- `PUT  /api/orders` 修改（仅 CREATED，处方整组原子替换）
- `GET  /api/orders/by-no/{orderNo}` 按加工单号检索（主单 + 有效处方 + 状态流转记录）
- `GET  /api/orders/{id}`、`GET /api/orders?status=&storeCode=`
- `POST /api/orders/{id}/start | /finish | /pickup | /cancel | /restore | /rework`
- `GET  /api/orders/stats/rework-rate?storeCode=&begin=&end=` 返修率统计（以加工单底账为准）
- `POST /api/orders/stats/rework-rate/reconcile` 返修率反向核对（兄弟组回传结果比对）

敏感词：

- `POST /api/sensitive-words/scan` 扫描文本，返回命中词/级别/原文下标区间（前端标记用）
- `POST /api/sensitive-words/reload` 词库立即热更新
- `GET  /api/sensitive-words/status` 词库状态（词条数、文件、最近加载时间）

示例（接口走 Shiro 认证，测试/本地可用 bootstrap/bootstrap）：

```bash
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/lens \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: LENS-0001' \
  -d '{"lensCode":"L-001","lensName":"1.60非球面","price":399.00}'
```
