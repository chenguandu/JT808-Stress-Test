# JT808 Benchmark

基于 Netty 的 JT808 协议压测工具，模拟大量终端设备对 JT808 服务端进行压力测试。

## 架构

```
com.example.jt808bench
├── JT808BenchmarkMain.java          # 主入口
├── config/
│   └── BenchmarkConfig.java         # 命令行参数解析
├── protocol/
│   ├── JT808Message.java            # 消息模型
│   ├── JT808FrameCodec.java         # 帧编解码器（Decoder + Encoder）
│   └── JT808MessageBuilder.java     # 消息工厂
├── client/
│   ├── DeviceState.java             # 状态枚举
│   ├── DeviceConnection.java        # 连接封装（状态机 + 心跳定时器）
│   └── ConnectionStats.java         # 全局统计
└── handler/
    └── JT808ClientHandler.java      # 核心业务处理器
```

## 状态机流程

```
TCP建连 → UNREGISTERED
   ↓ 发送 0x0100（终端注册）
WAITING_AUTH_CODE
   ↓ 收到 0x8100 → 解析授权码 → 发送 0x0102（终端鉴权）
AUTHENTICATING
   ↓ 收到 0x8102（鉴权应答）或 0x8001（通用应答，应答ID=0x0102且结果=0）
HEARTBEAT → 定时发送 0x0002（心跳，间隔 = 服务端超时 - 10秒）
```

## 协议要点

| 项目 | 说明 |
|------|------|
| 帧边界 | `0x7E` 标识帧头/帧尾 |
| 转义规则 | `0x7E` → `0x7D 0x02`，`0x7D` → `0x7D 0x01` |
| 校验 | 消息ID到消息体逐字节异或 |
| 手机号 | 6 字节 BCD 编码 → 12 位数字 |
| 授权码 | 变长 ASCII 字符串，以 `0x00` 结尾，从 0x8100 body[3] 开始解析 |

## 构建

```bash
mvn package -DskipTests
# 生成 target/jt808-benchmark-1.0-SNAPSHOT.jar（fat JAR，包含全部依赖）
```

## 使用

```bash
# 调试模式（1 台设备）
java -jar target/jt808-benchmark-1.0-SNAPSHOT.jar --devices 1 --connect-rate 1

# 小规模测试
java -jar target/jt808-benchmark-1.0-SNAPSHOT.jar --devices 100 --connect-rate 50

# 全量压测
java -jar target/jt808-benchmark-1.0-SNAPSHOT.jar \
    --host api.example.com --port 2020 \
    --devices 30000 --connect-rate 500 --server-timeout 120
```

## 参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--host` | 127.0.0.1 | 服务端地址 |
| `--port` | 8800 | 服务端端口 |
| `--devices` | 30000 | 模拟设备数 |
| `--connect-rate` | 500 | 每秒建连速率 |
| `--server-timeout` | 120 | 服务端超时秒数，心跳间隔 = 此值 − 10 |
| `--phone-prefix` | 138000000000 | 手机号前缀（取前 6 位 + 6 位序号，共 12 位） |
| `--stats-interval` | 10 | 统计输出间隔秒 |
| `--connect-timeout` | 5000 | TCP 连接超时毫秒 |
| `--worker-threads` | CPU×2 | EventLoop 线程数 |

## 统计输出示例

```
[15:42:35] Conn: attempts=5000, succ=3743, fail=0, cur=3743 |
           Reg: sent=3743 | Auth: codeRcv=3743, sent=3743, succ=3743 |
           HB: sent=0, ack=0 | Disc=0 |
           State dist: UNREG=0, W_AUTH=0, AUTHING=0, HB=3743
```

- **attempts/succ/fail/cur** — 连接尝试 / 成功 / 失败 / 当前存活
- **Reg sent** — 已发送 0x0100 注册包
- **Auth codeRcv/sent/succ** — 收到授权码 / 已发鉴权 / 鉴权成功
- **HB sent/ack** — 心跳发送 / 心跳应答
- **Disc** — 断连数
- **State dist** — 各状态设备分布

## 依赖

- Java 17+
- io.netty:netty-all:4.1.118.Final
- ch.qos.logback:logback-classic:1.5.13

## 注意事项

1. **大并发客户端**：30 000 连接需确保客户端端口充足（`net.ipv4.ip_local_port_range=1024 65535`），单 IP 最多约 64 000 连接
2. **失败排查**：连接失败时控制台会打印 `Connect failed #N:` 日志，可据此区分超时 / 拒绝 / fd 耗尽
3. **心跳延迟**：首次心跳在鉴权成功后 `server-timeout − 10` 秒触发，运行初期 `HB: sent=0` 属正常
