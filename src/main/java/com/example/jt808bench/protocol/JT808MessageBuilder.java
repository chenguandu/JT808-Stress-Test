package com.example.jt808bench.protocol;

import java.nio.charset.StandardCharsets;

/**
 * JT808 消息工厂：构建注册、鉴权、心跳消息。
 */
public class JT808MessageBuilder {

    private JT808MessageBuilder() {}

    /**
     * 构建 0x0100 注册消息。
     *
     * @param phone    终端手机号（12位数字字符串）
     * @param serialNo 流水号
     * @return 注册消息
     */
    public static JT808Message buildRegister(String phone, int serialNo) {
        // body: 省域ID(2B) + 市县域ID(2B) + 制造商ID(5B ASCII) + 终端型号(8B ASCII)
        //       + 终端ID(7B ASCII) + 车牌颜色(1B) + 车牌号(变长 GBK)
        byte[] body = new byte[25]; // 固定部分 2+2+5+8+7+1 = 25
        int pos = 0;

        // 省域ID 0x0001
        body[pos++] = 0x00;
        body[pos++] = 0x01;
        // 市县域ID 0x0001
        body[pos++] = 0x00;
        body[pos++] = 0x01;
        // 制造商ID "BENCH" (5B ASCII)
        byte[] mf = "BENCH".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(mf, 0, body, pos, Math.min(mf.length, 5));
        pos += 5;
        // 终端型号 "V1.0\0\0\0\0" (8B)
        byte[] model = "V1.0\0\0\0\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(model, 0, body, pos, Math.min(model.length, 8));
        pos += 8;
        // 终端ID: 取 phone 后7位 ASCII
        String id = phone.length() >= 7 ? phone.substring(phone.length() - 7) : phone;
        byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, body, pos, Math.min(idBytes.length, 7));
        pos += 7;
        // 车牌颜色 0x02 蓝色
        body[pos++] = 0x02;

        // 无车牌号
        byte[] finalBody = new byte[pos];
        System.arraycopy(body, 0, finalBody, 0, pos);

        return new JT808Message(0x0100, phone, serialNo, finalBody);
    }

    /**
     * 构建 0x0102 鉴权消息。
     *
     * @param phone     终端手机号
     * @param serialNo  流水号
     * @param authCode  授权码（ASCII 字符串）
     * @return 鉴权消息
     */
    public static JT808Message buildAuth(String phone, int serialNo, String authCode) {
        byte[] body = authCode.getBytes(StandardCharsets.US_ASCII);
        return new JT808Message(0x0102, phone, serialNo, body);
    }

    /**
     * 构建 0x0002 心跳消息（体为空）。
     *
     * @param phone    终端手机号
     * @param serialNo 流水号
     * @return 心跳消息
     */
    public static JT808Message buildHeartbeat(String phone, int serialNo) {
        return new JT808Message(0x0002, phone, serialNo, new byte[0]);
    }
}
