package com.example.jt808bench.client;

/**
 * JT808 终端状态枚举。
 */
public enum DeviceState {
    /** TCP 已建连，尚未发注册包 */
    UNREGISTERED(0),
    /** 已发 0x0100，等待 0x8100 返回授权码 */
    WAITING_AUTH_CODE(1),
    /** 已发 0x0102，等待鉴权应答 */
    AUTHENTICATING(2),
    /** 鉴权成功，定时发送 0x0002 心跳 */
    HEARTBEAT(3);

    private final int index;

    DeviceState(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }
}
