package com.example.jt808bench.client;

import com.example.jt808bench.protocol.JT808Message;
import com.example.jt808bench.protocol.JT808Message;
import com.example.jt808bench.protocol.JT808MessageBuilder;
import io.netty.channel.Channel;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每连接封装：持有 Channel、状态、授权码、心跳定时器。
 */
public class DeviceConnection {

    private final Channel channel;
    private final String phone;
    private final AtomicInteger serialNoGen;
    private volatile DeviceState state;
    private volatile String authCode;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private final ConnectionStats stats;

    public DeviceConnection(Channel channel, String phone, ConnectionStats stats) {
        this.channel = channel;
        this.phone = phone;
        this.stats = stats;
        this.serialNoGen = new AtomicInteger(1);
        this.state = DeviceState.UNREGISTERED;
    }

    // ---- 流水号 ----

    /** 获取自增流水号（1~65535 循环） */
    public int nextSerialNo() {
        int sn = serialNoGen.getAndIncrement();
        if (sn > 65535) {
            serialNoGen.set(1);
            sn = 1;
        }
        return sn;
    }

    // ---- 发送 ----

    public void sendMessage(JT808Message msg) {
        if (channel.isActive()) {
            channel.writeAndFlush(msg);
        }
    }

    // ---- 状态机 ----

    public DeviceState getState() {
        return state;
    }

    public void setState(DeviceState newState) {
        DeviceState old = this.state;
        this.state = newState;
        if (stats != null && old != newState) {
            stats.transitionState(old, newState);
        }
    }

    // ---- 授权码 ----

    public String getAuthCode() {
        return authCode;
    }

    public void setAuthCode(String authCode) {
        this.authCode = authCode;
    }

    // ---- 心跳 ----

    public void startHeartbeat(long intervalSec) {
        stopHeartbeat();
        this.heartbeatFuture = channel.eventLoop().scheduleAtFixedRate(
                () -> channel.writeAndFlush(
                        JT808MessageBuilder.buildHeartbeat(phone, nextSerialNo())),
                intervalSec,
                intervalSec,
                TimeUnit.SECONDS);
    }

    public void stopHeartbeat() {
        if (heartbeatFuture != null && !heartbeatFuture.isCancelled()) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    // ---- 关闭 ----

    public void close() {
        stopHeartbeat();
        if (channel.isActive()) {
            channel.close();
        }
    }

    // ---- 访问器 ----

    public Channel getChannel() {
        return channel;
    }

    public String getPhone() {
        return phone;
    }

    public ConnectionStats getStats() {
        return stats;
    }
}
