package com.example.jt808bench.handler;

import com.example.jt808bench.client.ConnectionStats;
import com.example.jt808bench.client.DeviceConnection;
import com.example.jt808bench.client.DeviceState;
import com.example.jt808bench.protocol.JT808Message;
import com.example.jt808bench.protocol.JT808MessageBuilder;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JT808 客户端业务处理器。
 * <p>
 * 状态机驱动：UNREGISTERED → WAITING_AUTH_CODE → AUTHENTICATING → HEARTBEAT
 * </p>
 */
@ChannelHandler.Sharable
public class JT808ClientHandler extends SimpleChannelInboundHandler<JT808Message> {

    private static final Logger log = LoggerFactory.getLogger(JT808ClientHandler.class);

    private static final AttributeKey<DeviceConnection> ATTR_CONN =
            AttributeKey.valueOf("deviceConn");

    private static final int PHONE_MAX_LEN = 12;
    private static final int SEQ_DIGITS = 6;

    private final ConnectionStats stats;
    private final String phonePrefix;
    private final int heartbeatIntervalSec;

    /** 设备计数（用于生成唯一 phone） */
    private int deviceCount;

    public JT808ClientHandler(ConnectionStats stats, String phonePrefix, int heartbeatIntervalSec) {
        this.stats = stats;
        this.phonePrefix = phonePrefix;
        this.heartbeatIntervalSec = heartbeatIntervalSec;
    }

    // ============================================================
    // 连接建立
    // ============================================================
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 分配唯一 phone（确保始终为 12 位数字，不丢失前缀）
        String phone;
        synchronized (this) {
            deviceCount++;
            String seq = String.format("%0" + SEQ_DIGITS + "d", deviceCount);
            String prefix = phonePrefix.length() > (PHONE_MAX_LEN - SEQ_DIGITS)
                    ? phonePrefix.substring(0, PHONE_MAX_LEN - SEQ_DIGITS)
                    : phonePrefix;
            phone = prefix + seq;
            // 补足 12 位
            while (phone.length() < PHONE_MAX_LEN) {
                phone = "0" + phone;
            }
        }

        DeviceConnection conn = new DeviceConnection(ctx.channel(), phone, stats);
        ctx.channel().attr(ATTR_CONN).set(conn);

        // 初始化状态计数：设备进入 UNREGISTERED 状态
        stats.transitionState(null, DeviceState.UNREGISTERED);

        stats.incConnectSuccess();
        stats.incRegisterSent();

        // 发送 0x0100 注册包
        JT808Message regMsg = JT808MessageBuilder.buildRegister(phone, conn.nextSerialNo());
        conn.sendMessage(regMsg);
        conn.setState(DeviceState.WAITING_AUTH_CODE);

        if (log.isDebugEnabled()) {
            log.debug("[{}] channelActive, sent 0x0100 register", phone);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        DeviceConnection conn = ctx.channel().attr(ATTR_CONN).getAndSet(null);
        if (conn != null) {
            conn.stopHeartbeat();
            stats.incDisconnect();
            stats.transitionState(conn.getState(), null);
            log.debug("[{}] channelInactive", conn.getPhone());
        }
    }

    // ============================================================
    // 消息处理
    // ============================================================
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, JT808Message msg) {
        DeviceConnection conn = ctx.channel().attr(ATTR_CONN).get();
        if (conn == null) {
            return;
        }

        switch (msg.getMsgId()) {
            case 0x8100:
                handleRegisterResponse(conn, msg);
                break;
            case 0x8001:
                handleGeneralResponse(conn, msg);
                break;
            case 0x8102:
                handleAuthResponse(conn, msg);
                break;
            default:
                if (log.isTraceEnabled()) {
                    log.trace("[{}] unhandled msgId=0x{:04X}", conn.getPhone(), msg.getMsgId());
                }
        }
    }

    // ============================================================
    // 0x8100 注册应答 → 提取授权码 → 发送 0x0102 鉴权
    // ============================================================
    private void handleRegisterResponse(DeviceConnection conn, JT808Message msg) {
        if (conn.getState() != DeviceState.WAITING_AUTH_CODE) {
            log.warn("[{}] unexpected 0x8100 in state {}", conn.getPhone(), conn.getState());
            return;
        }

        byte[] body = msg.getBody();
        if (body.length < 3) {
            log.warn("[{}] 0x8100 body too short: {}", conn.getPhone(), body.length);
            conn.close();
            return;
        }

        // body[0-1]: 应答流水号 (2B 大端，忽略)
        // body[2]:   结果 (0=成功)
        // body[3..]: 授权码（变长 ASCII，以 0x00 结尾）
        int result = body[2] & 0xFF;
        if (result != 0) {
            log.warn("[{}] 0x8100 register failed, result={}", conn.getPhone(), result);
            conn.close();
            return;
        }

        // 提取授权码：从 body[3] 开始到 0x00 结束
        int authEnd = -1;
        for (int i = 3; i < body.length; i++) {
            if (body[i] == 0x00) {
                authEnd = i;
                break;
            }
        }
        if (authEnd < 0) {
            authEnd = body.length;
        }
        String authCode = new String(body, 3, authEnd - 3, java.nio.charset.StandardCharsets.US_ASCII);
        conn.setAuthCode(authCode);
        stats.incAuthCodeReceived();

        if (log.isDebugEnabled()) {
            log.debug("[{}] got authCode='{}', sending 0x0102 auth", conn.getPhone(), authCode);
        }

        // 发送 0x0102 鉴权包
        JT808Message authMsg = JT808MessageBuilder.buildAuth(conn.getPhone(), conn.nextSerialNo(), authCode);
        conn.sendMessage(authMsg);
        conn.setState(DeviceState.AUTHENTICATING);
        stats.incAuthSent();
    }

    // ============================================================
    // 0x8001 通用应答 → 检查是否为鉴权应答成功
    // ============================================================
    private void handleGeneralResponse(DeviceConnection conn, JT808Message msg) {
        byte[] body = msg.getBody();
        if (body.length < 5) {
            return;
        }

        // body[0-1]: 应答流水号
        // body[2-3]: 应答消息ID
        // body[4]:   结果 (0=成功)
        int replyMsgId = ((body[2] & 0xFF) << 8) | (body[3] & 0xFF);
        int result = body[4] & 0xFF;

        if (replyMsgId == 0x0102 && result == 0 && conn.getState() == DeviceState.AUTHENTICATING) {
            // 鉴权成功
            enterHeartbeat(conn);
        }
    }

    // ============================================================
    // 0x8102 鉴权应答 → 鉴权成功
    // ============================================================
    private void handleAuthResponse(DeviceConnection conn, JT808Message msg) {
        if (conn.getState() != DeviceState.AUTHENTICATING) {
            log.warn("[{}] unexpected 0x8102 in state {}", conn.getPhone(), conn.getState());
            return;
        }
        // 0x8102 收到即视为鉴权成功
        enterHeartbeat(conn);
    }

    // ============================================================
    // 鉴权成功 → 启动心跳
    // ============================================================
    private void enterHeartbeat(DeviceConnection conn) {
        if (conn.getState() == DeviceState.HEARTBEAT) {
            return; // 防重复进入
        }
        conn.setState(DeviceState.HEARTBEAT);
        stats.incAuthSuccess();
        conn.startHeartbeat(heartbeatIntervalSec);

        if (log.isDebugEnabled()) {
            log.debug("[{}] auth success, entering heartbeat (interval={}s)",
                    conn.getPhone(), heartbeatIntervalSec);
        }
    }

    // ============================================================
    // 异常处理
    // ============================================================
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        DeviceConnection conn = ctx.channel().attr(ATTR_CONN).get();
        String phone = conn != null ? conn.getPhone() : "unknown";
        if (log.isDebugEnabled()) {
            log.debug("[{}] exceptionCaught: {}", phone, cause.getMessage());
        }
        // 不关闭连接，让 Netty 自己处理或等待 channelInactive
    }
}
