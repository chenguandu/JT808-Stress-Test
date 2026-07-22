package com.example.jt808bench.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.List;

/**
 * JT808 帧编解码器。
 * <p>
 * Decoder: 从字节流中查找 0x7E 分帧，解转义，校验，解析为 JT808Message。<br>
 * Encoder: 将 JT808Message 组帧，转义，计算校验码，写入 ByteBuf。
 * </p>
 */
public class JT808FrameCodec {

    private static final byte FLAG        = 0x7E;
    private static final byte ESC         = 0x7D;
    private static final byte ESC_7E      = 0x02;
    private static final byte ESC_7D      = 0x01;

    // ============================================================
    // Decoder
    // ============================================================
    public static class Decoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // 查找帧头 0x7E
            int frameStart = indexOf(in, FLAG);
            if (frameStart < 0) {
                // 没有 0x7E，丢弃所有数据（避免无效字节堆积）
                in.clear();
                return;
            }

            // 跳过帧头前的无效字节
            if (frameStart > 0) {
                in.readerIndex(in.readerIndex() + frameStart);
            }

            // 从帧头后查找下一个 0x7E（帧尾）
            int frameEnd = indexOf(in, 1, FLAG);
            if (frameEnd < 0) {
                // 帧不完整，等待更多数据
                return;
            }

            // frameEnd 是相对于缓冲区起始的索引，转换为实际读取位置
            int totalFrameLen = frameEnd - in.readerIndex() + 1;
            if (totalFrameLen < 5) { // 最小帧：7E + 至少2字节头 + 校验1B + 7E
                in.readerIndex(in.readerIndex() + totalFrameLen);
                return;
            }

            // 读出整帧（包含头尾 0x7E）
            ByteBuf frameBuf = in.readRetainedSlice(totalFrameLen);
            try {
                byte[] frame = new byte[frameBuf.readableBytes()];
                frameBuf.readBytes(frame);
                processFrame(frame, out);
            } finally {
                frameBuf.release();
            }
        }

        private void processFrame(byte[] frame, List<Object> out) {
            if (frame.length < 2 || frame[0] != FLAG || frame[frame.length - 1] != FLAG) {
                return;
            }
            byte[] raw = unescape(frame);
            if (raw == null) {
                return;
            }
            if (!checkSum(raw)) {
                return;
            }
            JT808Message msg = parseMessage(raw);
            if (msg != null) {
                out.add(msg);
            }
        }

        /** 在 ByteBuf 从 offset 开始找指定字节 */
        private int indexOf(ByteBuf buf, int offset, byte b) {
            int ri = buf.readerIndex();
            int wi = buf.writerIndex();
            for (int i = ri + offset; i < wi; i++) {
                if (buf.getByte(i) == b) {
                    return i;
                }
            }
            return -1;
        }

        private int indexOf(ByteBuf buf, byte b) {
            return indexOf(buf, 0, b);
        }

        /** 解转义：去掉头尾 0x7E，0x7D 0x02 → 0x7E，0x7D 0x01 → 0x7D */
        private byte[] unescape(byte[] frame) {
            int payloadLen = frame.length - 2;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(payloadLen);
            int i = 1; // 跳过起始 0x7E
            while (i < frame.length - 1) { // 不包含结束 0x7E
                byte b = frame[i];
                if (b == ESC) {
                    i++;
                    if (i >= frame.length - 1) return null;
                    byte next = frame[i];
                    if (next == ESC_7E) {
                        bos.write(FLAG);
                    } else if (next == ESC_7D) {
                        bos.write(ESC);
                    } else {
                        return null;
                    }
                } else {
                    bos.write(b);
                }
                i++;
            }
            return bos.toByteArray();
        }

        /** 校验：所有字节（含校验码）逐字节异或，应为 0 */
        private boolean checkSum(byte[] raw) {
            if (raw.length < 1) return false;
            byte cs = 0;
            for (byte b : raw) cs ^= b;
            return cs == 0;
        }

        /** 解析消息体 */
        private JT808Message parseMessage(byte[] raw) {
            // raw: [msgId(2)][attr(2)][phone(6)][serial(2)][(packItem4)][body...][checkCode(1)]
            if (raw.length < 13) return null; // 12 头 + 1 校验
            int totalWithoutCheck = raw.length - 1;

            int msgId  = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
            int attr   = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
            int bodyLen = attr & 0x03FF;
            boolean hasSubPkg = (attr & (1 << 13)) != 0;

            String phone = bcdToString(raw, 4, 6);
            int serialNo  = ((raw[10] & 0xFF) << 8) | (raw[11] & 0xFF);

            Integer packCount = null;
            Integer packIndex = null;
            int bodyStart;
            if (hasSubPkg) {
                bodyStart = 16;
                if (totalWithoutCheck < bodyStart) return null;
                packCount = ((raw[12] & 0xFF) << 8) | (raw[13] & 0xFF);
                packIndex = ((raw[14] & 0xFF) << 8) | (raw[15] & 0xFF);
            } else {
                bodyStart = 12;
            }

            int bodyEnd = bodyStart + bodyLen;
            if (bodyEnd > totalWithoutCheck) return null;

            byte[] body = new byte[bodyLen];
            if (bodyLen > 0) System.arraycopy(raw, bodyStart, body, 0, bodyLen);
            return new JT808Message(msgId, phone, serialNo, body, packCount, packIndex);
        }

        private String bcdToString(byte[] data, int off, int len) {
            StringBuilder sb = new StringBuilder(len * 2);
            for (int i = off; i < off + len; i++) {
                int b = data[i] & 0xFF;
                sb.append((char) ('0' + ((b >> 4) & 0x0F)));
                sb.append((char) ('0' + (b & 0x0F)));
            }
            return sb.toString();
        }
    }

    // ============================================================
    // Encoder
    // ============================================================
    public static class Encoder extends MessageToByteEncoder<JT808Message> {
        @Override
        protected void encode(ChannelHandlerContext ctx, JT808Message msg, ByteBuf out) {
            byte[] body = msg.getBody();
            int bodyLen = body != null ? body.length : 0;

            int attr = bodyLen & 0x03FF;
            if (msg.isSubPackage()) {
                attr |= (1 << 13);
            }

            int headerLen = 12 + (msg.isSubPackage() ? 4 : 0);
            byte[] plain = new byte[headerLen + bodyLen + 1]; // +1 校验码
            int pos = 0;

            // msgId
            plain[pos++] = (byte) ((msg.getMsgId() >> 8) & 0xFF);
            plain[pos++] = (byte) (msg.getMsgId() & 0xFF);
            // attr
            plain[pos++] = (byte) ((attr >> 8) & 0xFF);
            plain[pos++] = (byte) (attr & 0xFF);
            // phone BCD
            phoneToBcd(msg.getPhone(), plain, pos);
            pos += 6;
            // serialNo
            plain[pos++] = (byte) ((msg.getSerialNo() >> 8) & 0xFF);
            plain[pos++] = (byte) (msg.getSerialNo() & 0xFF);
            // sub package
            if (msg.isSubPackage()) {
                int pc = msg.getPackCount() != null ? msg.getPackCount() : 1;
                int pi = msg.getPackIndex() != null ? msg.getPackIndex() : 1;
                plain[pos++] = (byte) ((pc >> 8) & 0xFF);
                plain[pos++] = (byte) (pc & 0xFF);
                plain[pos++] = (byte) ((pi >> 8) & 0xFF);
                plain[pos++] = (byte) (pi & 0xFF);
            }
            // body
            if (bodyLen > 0) {
                System.arraycopy(body, 0, plain, pos, bodyLen);
                pos += bodyLen;
            }
            // 校验码
            byte cs = 0;
            for (int i = 0; i < pos; i++) cs ^= plain[i];
            plain[pos++] = cs;

            // 写入帧：0x7E + 转义内容 + 0x7E
            out.writeByte(FLAG);
            for (int i = 0; i < plain.length; i++) {
                byte b = plain[i];
                if (b == FLAG) {
                    out.writeByte(ESC);
                    out.writeByte(ESC_7E);
                } else if (b == ESC) {
                    out.writeByte(ESC);
                    out.writeByte(ESC_7D);
                } else {
                    out.writeByte(b);
                }
            }
            out.writeByte(FLAG);
        }

        private void phoneToBcd(String phone, byte[] out, int off) {
            for (int i = 0; i < 6; i++) {
                int high = (i * 2 < phone.length()) ? phone.charAt(i * 2) - '0' : 0;
                int low  = (i * 2 + 1 < phone.length()) ? phone.charAt(i * 2 + 1) - '0' : 0;
                out[off + i] = (byte) ((high << 4) | low);
            }
        }
    }
}
