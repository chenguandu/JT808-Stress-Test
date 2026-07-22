package com.example.jt808bench.protocol;

/**
 * JT808 协议消息模型
 */
public class JT808Message {

    /** 消息ID */
    private final int msgId;

    /** 终端手机号（BCD 6字节 → 12位数字字符串） */
    private final String phone;

    /** 消息流水号 */
    private final int serialNo;

    /** 消息体（不含转义和校验码的原始 body） */
    private final byte[] body;

    /** 分包总数（仅分包消息） */
    private final Integer packCount;

    /** 包序号（仅分包消息） */
    private final Integer packIndex;

    public JT808Message(int msgId, String phone, int serialNo, byte[] body) {
        this(msgId, phone, serialNo, body, null, null);
    }

    public JT808Message(int msgId, String phone, int serialNo, byte[] body,
                        Integer packCount, Integer packIndex) {
        this.msgId = msgId;
        this.phone = phone;
        this.serialNo = serialNo;
        this.body = body != null ? body : new byte[0];
        this.packCount = packCount;
        this.packIndex = packIndex;
    }

    public int getMsgId() {
        return msgId;
    }

    public String getPhone() {
        return phone;
    }

    public int getSerialNo() {
        return serialNo;
    }

    public byte[] getBody() {
        return body;
    }

    public Integer getPackCount() {
        return packCount;
    }

    public Integer getPackIndex() {
        return packIndex;
    }

    public boolean isSubPackage() {
        return packCount != null && packIndex != null;
    }

    @Override
    public String toString() {
        return String.format("JT808Message{msgId=0x%04X, phone='%s', serialNo=%d, bodyLen=%d}",
                msgId, phone, serialNo, body.length);
    }
}
