package com.example.jt808bench.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局连接统计。
 */
public class ConnectionStats {

    private final AtomicLong connectAttempts        = new AtomicLong(0);
    private final AtomicLong connectSuccess         = new AtomicLong(0);
    private final AtomicLong connectFail            = new AtomicLong(0);
    private final AtomicLong registerSent           = new AtomicLong(0);
    private final AtomicLong authCodeReceived       = new AtomicLong(0);
    private final AtomicLong authSent               = new AtomicLong(0);
    private final AtomicLong authSuccess            = new AtomicLong(0);
    private final AtomicLong heartbeatSent          = new AtomicLong(0);
    private final AtomicLong heartbeatAckReceived   = new AtomicLong(0);
    private final AtomicLong disconnectCount        = new AtomicLong(0);

    /** 各状态设备数 */
    private final AtomicInteger[] stateCounts = {
            new AtomicInteger(0), // UNREGISTERED
            new AtomicInteger(0), // WAITING_AUTH_CODE
            new AtomicInteger(0), // AUTHENTICATING
            new AtomicInteger(0)  // HEARTBEAT
    };

    // ---- 增量方法 ----
    public void incConnectAttempt()    { connectAttempts.incrementAndGet(); }
    public void incConnectSuccess()    { connectSuccess.incrementAndGet(); }
    public void incConnectFail()       { connectFail.incrementAndGet(); }
    public void incRegisterSent()      { registerSent.incrementAndGet(); }
    public void incAuthCodeReceived()  { authCodeReceived.incrementAndGet(); }
    public void incAuthSent()          { authSent.incrementAndGet(); }
    public void incAuthSuccess()       { authSuccess.incrementAndGet(); }
    public void incHeartbeatSent()     { heartbeatSent.incrementAndGet(); }
    public void incHeartbeatAck()      { heartbeatAckReceived.incrementAndGet(); }
    public void incDisconnect()        { disconnectCount.incrementAndGet(); }

    public void transitionState(DeviceState from, DeviceState to) {
        if (from != null) {
            stateCounts[from.getIndex()].decrementAndGet();
        }
        stateCounts[to.getIndex()].incrementAndGet();
    }

    // ---- 读取方法 ----
    public long getConnectAttempts()    { return connectAttempts.get(); }
    public long getConnectSuccess()     { return connectSuccess.get(); }
    public long getConnectFail()        { return connectFail.get(); }
    public long getRegisterSent()       { return registerSent.get(); }
    public long getAuthCodeReceived()   { return authCodeReceived.get(); }
    public long getAuthSent()           { return authSent.get(); }
    public long getAuthSuccess()        { return authSuccess.get(); }
    public long getHeartbeatSent()      { return heartbeatSent.get(); }
    public long getHeartbeatAck()       { return heartbeatAckReceived.get(); }
    public long getDisconnectCount()    { return disconnectCount.get(); }
    public long getCurrentConnections() { return connectSuccess.get() - disconnectCount.get(); }

    public int getStateCount(DeviceState state) {
        return stateCounts[state.getIndex()].get();
    }

    public String formatStats() {
        return String.format(
                "Conn: attempts=%d, succ=%d, fail=%d, cur=%d | " +
                "Reg: sent=%d | Auth: codeRcv=%d, sent=%d, succ=%d | " +
                "HB: sent=%d, ack=%d | Disc=%d",
                getConnectAttempts(), getConnectSuccess(), getConnectFail(),
                getCurrentConnections(),
                getRegisterSent(),
                getAuthCodeReceived(), getAuthSent(), getAuthSuccess(),
                getHeartbeatSent(), getHeartbeatAck(), getDisconnectCount());
    }

    public String formatStateDistribution() {
        return String.format("State dist: UNREG=%d, W_AUTH=%d, AUTHING=%d, HB=%d",
                getStateCount(DeviceState.UNREGISTERED),
                getStateCount(DeviceState.WAITING_AUTH_CODE),
                getStateCount(DeviceState.AUTHENTICATING),
                getStateCount(DeviceState.HEARTBEAT));
    }
}
