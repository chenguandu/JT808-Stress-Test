package com.example.jt808bench.config;

/**
 * 压测配置类。
 */
public class BenchmarkConfig {

    private String host = "127.0.0.1";
    private int port = 8800;
    private int devices = 30000;
    private int connectRate = 500;
    private int serverTimeoutSec = 120;
    private String phonePrefix = "138000000000";
    private int statsIntervalSec = 10;
    private int workerThreads = 0; // 0 = auto (CPU cores * 2)
    private int connectTimeoutMs = 5000;

    // ---- 解析命令行参数 ----
    public void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--devices":
                    devices = Integer.parseInt(args[++i]);
                    break;
                case "--connect-rate":
                    connectRate = Integer.parseInt(args[++i]);
                    break;
                case "--server-timeout":
                    serverTimeoutSec = Integer.parseInt(args[++i]);
                    break;
                case "--phone-prefix":
                    phonePrefix = args[++i];
                    break;
                case "--stats-interval":
                    statsIntervalSec = Integer.parseInt(args[++i]);
                    break;
                case "--worker-threads":
                    workerThreads = Integer.parseInt(args[++i]);
                    break;
                case "--connect-timeout":
                    connectTimeoutMs = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }
    }

    public int getHeartbeatIntervalSec() {
        // 服务端超时 - 10秒
        return Math.max(serverTimeoutSec - 10, 10);
    }

    public int getWorkerThreads() {
        if (workerThreads <= 0) {
            return Runtime.getRuntime().availableProcessors() * 2;
        }
        return workerThreads;
    }

    private void printUsage() {
        System.err.println("Usage: java -jar jt808-benchmark.jar [options]");
        System.err.println("Options:");
        System.err.println("  --host <host>              Server address (default: 127.0.0.1)");
        System.err.println("  --port <port>              Server port (default: 8800)");
        System.err.println("  --devices <n>              Number of virtual devices (default: 30000)");
        System.err.println("  --connect-rate <n>         Connections per second (default: 500)");
        System.err.println("  --server-timeout <sec>     Server timeout in seconds (default: 120)");
        System.err.println("  --phone-prefix <prefix>    Phone number 12-digit prefix (default: 138000000000)");
        System.err.println("  --stats-interval <sec>     Stats print interval (default: 10)");
        System.err.println("  --worker-threads <n>       EventLoop threads (default: CPU*2)");
        System.err.println("  --connect-timeout <ms>     Connection timeout ms (default: 5000)");
    }

    // ---- Getter ----
    public String getHost()               { return host; }
    public int getPort()                  { return port; }
    public int getDevices()               { return devices; }
    public int getConnectRate()           { return connectRate; }
    public int getServerTimeoutSec()      { return serverTimeoutSec; }
    public String getPhonePrefix()        { return phonePrefix; }
    public int getStatsIntervalSec()      { return statsIntervalSec; }
    public int getConnectTimeoutMs()      { return connectTimeoutMs; }
}
