# 大作业交付物索引 —— Ripple 3.3 网络带宽优化

> 课程：面向对象程序设计（2026 秋）
> 姓名 / 学号：钱翰林 / 2023K8009929010
> 当前阶段：第 13 周（报告第二版 + 第二阶段全链路紧凑编码与自适应帧压缩实现）

---

## 一、交付物

| 路径 | 内容 |
|---|---|
| `报告/02-系统分析与设计报告-第二版.md` / `.pdf` | **系统分析与设计报告（第二版）**：方案迭代过程、面向对象设计（策略模式 `FrameCompressor` + 紧凑帧管线 + 原语扩展）、代码改动清单与全负载区间（4B ~ 8KiB）实测结果 |
| `报告/01-系统分析与设计报告-初版.md` / `.pdf` | 系统分析与设计报告初版（第 8、9 周阶段，散文式成稿） |
| `说明-仓库框架与改动.md` / `.pdf` | **仓库框架 + 第一/二阶段改动完整说明**（模块结构、核心对象、帧协议、两阶段代码改动对照、22 个单元测试、实测数据、复现步骤） |
| `核心类图.png` | 核心类图（含报文家族、24 对编解码器与 `TypeHelper`、`CompactFrameEncoder/Decoder` 与 `FrameCompressor` 策略模式、`Overlay` 拓扑四层结构） |
| `代码/ripple-common/.../helper/TypeHelper.java` | 线格式原语工具类（16B 二进制 UUID、VarInt/SVarInt/VarLong/SVarLong、紧凑时间戳、VarInt 长度前缀字符串、1B 操作类型枚举 Tag） |
| `代码/ripple-common/.../tcp/FrameCompressor.java` | 帧级压缩策略接口 |
| `代码/ripple-common/.../tcp/DeflateFrameCompressor.java` | 基于 JDK Deflate/Inflate 的压缩策略单例实现（含防压缩炸弹 `maxLength` 保护） |
| `代码/ripple-common/.../tcp/CompactFrameEncoder.java` | 紧凑帧编码器：`VarInt((payloadLen << 1) \| compressedFlag)` 帧头 + 256B 阈值自适应压缩与不可压缩回退 |
| `代码/ripple-common/.../tcp/CompactFrameDecoder.java` | 紧凑帧解码器：支持任意 TCP 分片/粘包的流式 VarInt 帧头解析与按需解压 |
| `代码/ripple-common/.../tcp/CodecRoundTripTest.java` | 原语与报文编解码往返 + 尺寸契约回归测试（17 个用例） |
| `代码/ripple-common/.../tcp/CompactFrameTest.java` | 紧凑帧头、大 JSON 自适应压缩、高熵数据回退、单字节分片粘包测试（4 个用例） |
| `代码/ripple-common/.../tcp/BandwidthBenchmarkTest.java` | 基线 vs 第一版 vs 第二版全尺寸（4B / 1KiB / 8KiB）TCP 载荷大小基准测试（1 个用例） |
| `证据/B3-实测结果-20261019.md` | 第一阶段集群抓包实测数据与出处说明（含 8 份 `.pcap` 原始抓包文件） |
| `演示/Ripple-B3-第一次课堂研讨.pptx` / `.pdf` | 第一次课堂研讨 PPT（14 页）及讲稿、提词卡 |

---

## 二、第一版与第二版改动演进摘要

### 2.1 第一阶段（初版）
- **定位瓶颈**：`TypeHelper.readUuid` / `writeUuid` 将 128 位 UUID 转为 36 字符 ASCII 字符串加 4 字节长度前缀（共 40 字节）。
- **改动**：改为直接读写两个 8 字节 `long`（16 字节二进制），每个 UUID 固定省 24 字节，全仓 64 处调用点零改动。
- **局限**：固定字节节省在大负载下被稀释（4B 值降 42.2%，1KiB 值仅降 5.9%，8KiB 值仅降 0.8%）。

### 2.2 第二阶段（第二版）
1. **字段级紧凑编码（`TypeHelper` + 8 对编解码器对称改造）**：
   - 字符串长度前缀从 4 字节定长 `int` 改为 `VarInt`（短字符串长度前缀仅 1 字节，全仓 24 对编解码器自动生效）；
   - `operationType`（`update` / `delete` / `incremental_update`）与 `atomicOperation`（`add_entry` / `remove_entry`）从完整字符串（10~22 字节）改为 1 字节枚举 Tag（未知值以 `0xFF` 标签自动回退为字符串）；
   - 节点 ID（`lastUpdateServerId`、`fromId`、`sourceId`、`nodeId`）从 4 字节 `int` 改为 ZigZag `SVarInt`（常见节点号仅 1 字节）；端口号与列表项数改为 `VarInt`；
   - 时间戳 `lastUpdate` 从 8 字节 `long` 改为 ZigZag `SVarLong`（现代毫秒时间戳占 6 字节）。
2. **帧协议与策略模式自适应压缩（`CompactFrameEncoder/Decoder` + `FrameCompressor`）**：
   - 替换 `ServerChannelInitializer` 与 `ClientChannelInitializer` 中固定的 4 字节 `LengthFieldBasedFrameDecoder` / `LengthFieldPrepender`；
   - 帧头采用单个 `VarInt((payloadLength << 1) | compressedFlag)`，短包帧头从 4 字节缩至 1 字节；
   - 引入策略模式接口 `FrameCompressor` 与默认实现 `DeflateFrameCompressor`，对 `>= 256` 字节的帧自适应试压缩（仅当压缩后更小时才置 `compressedFlag = 1`，高熵不可压缩数据自动回退）。

---

## 三、如何复现

### 3.1 环境
- WSL2 Ubuntu 24.04，OpenJDK 17，Maven 3.8.7
- 代码目录：`D:\Q_Hanlin\UCAS\26_autumn\oop\大作业\代码`（WSL 下 `/mnt/d/Q_Hanlin/UCAS/26_autumn/oop/大作业/代码`）

### 3.2 运行全部单元测试与带宽基准测试（共 22 个用例）

```bash
cd /mnt/d/Q_Hanlin/UCAS/26_autumn/oop/大作业/代码
mvn test
# Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
```

其中 `BandwidthBenchmarkTest` 会直接输出基线、第一版（P1）、第二版（P2）在各典型报文及负载下的线上字节数对比。

---

## 四、关键实测结论

### 4.1 单帧含帧头 TCP 载荷字节数对比

| 报文与负载 | 原始基线 | 第一版（P1） | 第二版（P2） | 第二版相比基线降幅 |
|---|---:|---:|---:|---:|
| `HEARTBEAT_REQUEST` | 45 B | 21 B | **18 B** | **−60.0%** |
| `SYNC_RESPONSE` | 46 B | 22 B | **19 B** | **−58.7%** |
| `PUT_REQUEST`（4B 值） | 70 B | 46 B | **34 B** | **−51.4%** |
| `SYNC_REQUEST`（4B 值） | 136 B | 88 B | **59 B** | **−56.6%** |
| `SYNC_REQUEST`（~1 KiB JSON） | 1199 B | 1151 B | **222 B** | **−81.5%** |
| `SYNC_REQUEST`（~8 KiB JSON） | 8356 B | 8308 B | **879 B** | **−89.5%** |

### 4.2 三节点星形集群 606 帧标准工作负载对比

| 负载规模（606 帧） | 原始基线（TCP 载荷） | 第一版（TCP 载荷） | 第二版（TCP 载荷） |
|---|---:|---:|---:|
| 4 B 短配置值 | 48,791 B | 29,207 B（−40.1%） | **21,017 B（−56.9%）** |
| ~1 KiB JSON 配置值 | 334,391 B | 314,807 B（−5.9%） | **56,537 B（−83.1%）** |
| ~8 KiB JSON 配置值 | 2,341,431 B | 2,321,847 B（−0.8%） | **240,689 B（−89.7%）** |
