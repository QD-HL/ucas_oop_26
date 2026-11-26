# Ripple 消息引擎网络带宽优化（选题 3.3）

- **课程**：面向对象程序设计（2026 秋）
- **姓名 / 学号**：钱翰林 / `2023K8009929010`
- **基础项目**：[ISCAS-SSG/Ripple](https://github.com/ISCAS-SSG/Ripple)（提交基线 `468e9cb`）

---

## 1. 目录结构与交付物索引

| 路径 | 说明 |
|---|---|
| `报告/02-系统分析与设计报告-第二版.md` / `.pdf` | **系统分析与设计报告（第二版）**：包含第一版问题复盘、字段级变长与枚举编码、紧凑帧头与阈值 Deflate 压缩、核心类设计和 24 项测试实测结果 |
| `报告/01-系统分析与设计报告-初版.md` / `.pdf` | 系统分析与设计报告（第一版）：基于 UUID 16 字节二进制编码的初版分析与抓包验证 |
| `核心类图.png` | 核心类图：展示报文继承层次、`TypeHelper` 与 24 对编解码器、`CompactFrameEncoder/Decoder` 与 `FrameCompressor` 接口以及同步拓扑层 |
| `代码/` | 完整可编译 Maven 多模块工程（`ripple-common`、`ripple-server`、`ripple-client`） |
| `演示/` | 第一次课堂研讨 PPT、导出 PDF 与配套讲稿 |
| `证据/` | 三节点星形集群 `tcpdump` 原始抓包文件（8 份 `.pcap`）及数据复算说明 |

---

## 2. 核心代码改动清单

全部改动集中在线格式原语工具类 `TypeHelper`、Netty 帧编解码与压缩接口以及 8 对报文编解码器中，上层业务实体、拓扑路由与 SQLite 存储层接口保持不变：

| 文件路径 | 改动类型 | 内容说明 |
|---|---|---|
| `代码/ripple-common/.../helper/TypeHelper.java` | 修改 | `readUuid` / `writeUuid` 改为 16B 二进制；`readString` / `writeString` 长度前缀改为无符号 `VarInt`；新增 `VarInt`、`SVarInt`、`VarLong`、`SVarLong`、`Timestamp` 及 1B 枚举 `OperationType`、`AtomicOperation`（带 `0xFF` 未知值回退） |
| `代码/ripple-common/.../tcp/FrameCompressor.java` | 新增 | 帧级载荷压缩接口，声明 `compress(byte[] raw)` 与 `decompress(byte[] compressed, int maxLength)` |
| `代码/ripple-common/.../tcp/DeflateFrameCompressor.java` | 新增 | 默认 Deflate 压缩实现（单例 `INSTANCE`），支持解压输出上限校验 |
| `代码/ripple-common/.../tcp/CompactFrameEncoder.java` | 新增 | 替换 `LengthFieldPrepender(4)`，采用单 `VarInt((payloadLen << 1) \| flag)` 帧头，对 `>= 256B` 的帧按需执行压缩与不可压缩回退 |
| `代码/ripple-common/.../tcp/CompactFrameDecoder.java` | 新增 | 替换 `LengthFieldBasedFrameDecoder`，支持流式 VarInt 帧头探测、半包/粘包切分与按需解压 |
| `代码/ripple-common/.../tcp/{encoder,decoder}/*`（7 对）<br>`代码/ripple-server/.../tcp/{encoder,decoder}/AckRequest*`（1 对） | 修改 | `SyncRequest`、`DispatchRequest`、`GetResponse`、`IncrementalUpdateRequest`、`SubscribeRequest`、`UnsubscribeRequest`、`GetClientListResponse`、`AckRequest` 切换至紧凑编码原语 |
| `代码/ripple-server/.../tcp/ServerChannelInitializer.java`<br>`代码/ripple-client/.../tcp/ClientChannelInitializer.java` | 修改 | Netty 管线首部装配 `CompactFrameDecoder` 与 `CompactFrameEncoder` |
| `代码/ripple-common/src/test/java/ripple/common/tcp/CodecRoundTripTest.java` | 新增 | 原语边界、枚举回退、8 类核心报文编解码往返与 7 种报文精确字节数契约（17 项测试） |
| `代码/ripple-common/src/test/java/ripple/common/tcp/CompactFrameTest.java` | 新增 | 紧凑帧头、大包压缩、高熵随机数据不压缩回退、逐字节 TCP 拆包粘包重组测试（4 项测试） |
| `代码/ripple-common/src/test/java/ripple/common/tcp/BandwidthBenchmarkTest.java` | 新增 | 单帧与不同负载大小对比、多客户端订阅扇出扩展（1/2/4/8 客户端）、1 Mbps 弱网编码与传输总耗时测试（3 项测试） |

---

## 3. 编译与测试复现

环境要求：JDK 11+（实测环境为 OpenJDK 17）、Maven 3.8+。

```bash
cd 大作业/代码
mvn test
# Results: Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

---

## 4. 关键实测数据汇总

### 4.1 单帧含帧头 TCP 载荷字节数对比

| 报文与负载 | 原始基线 | 第一版（UUID 二进制） | 第二版（紧凑编码 + 自适应帧压缩） | 第二版相比基线降幅 |
|---|---:|---:|---:|---:|
| `HEARTBEAT_REQUEST` | 45 B | 21 B | 18 B | −60.0% |
| `SYNC_RESPONSE` / `PUT_RESPONSE` | 46 B | 22 B | 19 B | −58.7% |
| `PUT_REQUEST`（4B 值） | 70 B | 46 B | 34 B | −51.4% |
| `SYNC_REQUEST`（update，4B 值） | 136 B | 88 B | 59 B | −56.6% |
| `SYNC_REQUEST`（incremental，5B 值） | 201 B | 129 B | 76 B | −62.2% |
| `SYNC_REQUEST`（~1 KiB JSON） | 1,199 B | 1,151 B | 222 B | −81.5% |
| `SYNC_REQUEST`（~8 KiB JSON） | 8,356 B | 8,308 B | 879 B | −89.5% |

### 4.2 三节点星形集群工作负载（80 次写入 / 606 帧）多维对比

| 实验维度与场景 | 原始基线 | 第一版 | 第二版 |
|---|---:|---:|---:|
| 4B 短配置（1 客户端，606 帧总 TCP 载荷） | 48,791 B | 29,207 B（−40.1%） | 21,017 B（−56.9%） |
| 4B 短配置（8 客户端订阅扇出，总 TCP 载荷） | 113,394 B | 68,274 B（−39.8%） | 48,737 B（−57.0%） |
| ~1 KiB JSON（1 客户端，606 帧总 TCP 载荷） | 334,391 B | 314,807 B（−5.9%） | 56,537 B（−83.1%） |
| ~1 KiB JSON（8 客户端订阅扇出，总 TCP 载荷） | 771,044 B | 734,820 B（−4.7%） | 141,307 B（−81.7%） |
| ~8 KiB JSON（1 客户端，606 帧总 TCP 载荷） | 2,341,431 B | 2,321,847 B（−0.8%） | 240,689 B（−89.7%） |
| ~1 KiB JSON 在 1 Mbps 弱网下总耗时（传输 + 编解码） | 2,675 ms | 2,518 ms（−5.9%） | 485 ms（−81.9%） |
| ~8 KiB JSON 在 1 Mbps 弱网下总耗时（传输 + 编解码） | 18,731 ms | 18,575 ms（−0.8%） | 2,036 ms（−89.1%） |
