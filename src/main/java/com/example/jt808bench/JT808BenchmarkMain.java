package com.example.jt808bench;

import com.example.jt808bench.client.ConnectionStats;
import com.example.jt808bench.config.BenchmarkConfig;
import com.example.jt808bench.handler.JT808ClientHandler;
import com.example.jt808bench.protocol.JT808FrameCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JT808 压测工具主入口。
 */
public class JT808BenchmarkMain {

    private static final Logger log = LoggerFactory.getLogger(JT808BenchmarkMain.class);

    private final BenchmarkConfig config;
    private final ConnectionStats stats;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private volatile boolean running = true;

    public JT808BenchmarkMain(BenchmarkConfig config) {
        this.config = config;
        this.stats = new ConnectionStats();
    }

    public static void main(String[] args) {
        BenchmarkConfig config = new BenchmarkConfig();
        config.parseArgs(args);

        // 打印配置
        System.out.println("=== JT808 Benchmark Configuration ===");
        System.out.printf("  Host: %s:%d%n", config.getHost(), config.getPort());
        System.out.printf("  Devices: %d%n", config.getDevices());
        System.out.printf("  Connect rate: %d/s%n", config.getConnectRate());
        System.out.printf("  Server timeout: %ds, heartbeat interval: %ds%n",
                config.getServerTimeoutSec(), config.getHeartbeatIntervalSec());
        System.out.printf("  Phone prefix: %s%n", config.getPhonePrefix());
        System.out.printf("  Worker threads: %d%n", config.getWorkerThreads());
        System.out.println("=======================================");

        JT808BenchmarkMain app = new JT808BenchmarkMain(config);
        app.start();
    }

    public void start() {
        EventLoopGroup group = new NioEventLoopGroup(config.getWorkerThreads());

        try {
            Bootstrap bootstrap = createBootstrap(group);
            ConnectionStats finalStats = this.stats;

            // 启动统计打印
            scheduler.scheduleAtFixedRate(this::printStats,
                    config.getStatsIntervalSec(), config.getStatsIntervalSec(), TimeUnit.SECONDS);

            // 启动连接（异步调度，后台逐渐建连）
            connectDevices(bootstrap);

            // 所有连接请求将在后台调度完成
            System.out.println("Connecting... Press Ctrl+C to stop.");
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
            waitForCompletion();

        } catch (Exception e) {
            log.error("Fatal error", e);
        } finally {
            shutdown();
            group.shutdownGracefully();
            scheduler.shutdown();
        }
    }

    private Bootstrap createBootstrap(EventLoopGroup group) {
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.SO_SNDBUF, 65536)
                .option(ChannelOption.SO_RCVBUF, 65536)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMs())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("frameDecoder", new JT808FrameCodec.Decoder())
                                .addLast("frameEncoder", new JT808FrameCodec.Encoder())
                                .addLast("handler", new JT808ClientHandler(
                                        stats, config.getPhonePrefix(),
                                        config.getHeartbeatIntervalSec()));
                    }
                });
        return b;
    }

    /** 连接失败日志计数器（防刷屏） */
    private final AtomicInteger failLogCounter = new AtomicInteger(0);

    private void connectDevices(Bootstrap bootstrap) {
        int total = config.getDevices();
        int rate = config.getConnectRate();
        int batchSize = Math.max(rate / 10, 1); // 每 100ms 一批
        long intervalMs = 100;
        AtomicInteger connected = new AtomicInteger(0);

        System.out.printf("Connecting %d devices at %d/s (batch=%d every %dms)...%n",
                total, rate, batchSize, intervalMs);

        // 递归 schedule：每批完成后调度下一批，无竞态
        scheduleBatch(bootstrap, total, batchSize, intervalMs, connected);
    }

    private void scheduleBatch(Bootstrap bootstrap, int total, int batchSize,
                               long intervalMs, AtomicInteger connected) {
        if (!running) return;

        int remaining = total - connected.get();
        if (remaining <= 0) {
            System.out.printf("All %d connect attempts issued.%n", total);
            return;
        }

        int batch = Math.min(batchSize, remaining);
        for (int i = 0; i < batch; i++) {
            stats.incConnectAttempt();
            bootstrap.connect(config.getHost(), config.getPort())
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            stats.incConnectFail();
                            int fc = failLogCounter.incrementAndGet();
                            if (fc <= 5) {
                                log.warn("Connect failed #{}: {}", fc, future.cause());
                            } else if (fc % 500 == 0) {
                                log.warn("Connect failures reached {} (latest: {})", fc, future.cause());
                            }
                        }
                        // connectSuccess 在 handler.channelActive 中递增
                    });
        }
        connected.addAndGet(batch);

        scheduler.schedule(() -> scheduleBatch(bootstrap, total, batchSize, intervalMs, connected),
                intervalMs, TimeUnit.MILLISECONDS);
    }

    private void printStats() {
        long cur = stats.getCurrentConnections();
        long succ = stats.getConnectSuccess();
        long regSent = stats.getRegisterSent();
        long authCodeRcv = stats.getAuthCodeReceived();
        long authSucc = stats.getAuthSuccess();
        long hbSent = stats.getHeartbeatSent();
        long fail = stats.getConnectFail();
        long disc = stats.getDisconnectCount();

        System.out.printf("[%s] %s | %s%n",
                java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                stats.formatStats(),
                stats.formatStateDistribution());
    }

    private void waitForCompletion() {
        // 每 5 秒检查一次，直到用户按 Ctrl+C 或连接全部断开
        while (running) {
            try {
                Thread.sleep(5000);
                long cur = stats.getCurrentConnections();
                long totalAttempted = stats.getConnectAttempts();
                if (cur == 0 && totalAttempted >= config.getDevices()) {
                    System.out.println("All connections lost. Exiting.");
                    running = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void shutdown() {
        running = false;
        System.out.println("Shutting down...");
    }
}
