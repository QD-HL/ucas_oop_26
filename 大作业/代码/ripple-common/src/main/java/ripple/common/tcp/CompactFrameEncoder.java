// Copyright (c) 2023 Institute of Software, Chinese Academy of Sciences
// Ripple is licensed under Mulan PSL v2.
// You can use this software according to the terms and conditions of the Mulan PSL v2.
// You may obtain a copy of Mulan PSL v2 at:
//          http://license.coscl.org.cn/MulanPSL2
// THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
// EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
// MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
// See the Mulan PSL v2 for more details.

package ripple.common.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import ripple.common.helper.TypeHelper;

/**
 * Compact frame encoder: VarInt length header + adaptive compression for large frames.
 * Header format: VarInt((payloadLength << 1) | compressedFlag).
 */
public class CompactFrameEncoder extends MessageToByteEncoder<ByteBuf> {
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;
    public static final int COMPRESS_THRESHOLD = 256;

    private final int compressThreshold;
    private final FrameCompressor compressor;

    public CompactFrameEncoder() {
        this(COMPRESS_THRESHOLD, DeflateFrameCompressor.INSTANCE);
    }

    public CompactFrameEncoder(int compressThreshold) {
        this(compressThreshold, DeflateFrameCompressor.INSTANCE);
    }

    public CompactFrameEncoder(int compressThreshold, FrameCompressor compressor) {
        if (compressor == null) {
            throw new NullPointerException("compressor");
        }
        this.compressThreshold = compressThreshold;
        this.compressor = compressor;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int length = msg.readableBytes();
        if (length >= compressThreshold) {
            byte[] raw = new byte[length];
            msg.getBytes(msg.readerIndex(), raw);
            byte[] compressed = compressor.compress(raw);
            if (compressed.length < length) {
                TypeHelper.writeVarInt((compressed.length << 1) | 1, out);
                out.writeBytes(compressed);
                return;
            }
        }
        TypeHelper.writeVarInt(length << 1, out);
        out.writeBytes(msg, msg.readerIndex(), length);
    }
}
