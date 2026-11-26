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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import ripple.common.helper.TypeHelper;

import java.util.List;

/**
 * Compact frame decoder paired with {@link CompactFrameEncoder}.
 */
public class CompactFrameDecoder extends ByteToMessageDecoder {
    private final int maxFrameLength;
    private final FrameCompressor compressor;

    public CompactFrameDecoder() {
        this(CompactFrameEncoder.MAX_FRAME_LENGTH, DeflateFrameCompressor.INSTANCE);
    }

    public CompactFrameDecoder(int maxFrameLength) {
        this(maxFrameLength, DeflateFrameCompressor.INSTANCE);
    }

    public CompactFrameDecoder(int maxFrameLength, FrameCompressor compressor) {
        if (compressor == null) {
            throw new NullPointerException("compressor");
        }
        this.maxFrameLength = maxFrameLength;
        this.compressor = compressor;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        in.markReaderIndex();
        if (!hasCompleteVarInt(in)) {
            in.resetReaderIndex();
            return;
        }
        int header = TypeHelper.readVarInt(in);

        boolean compressed = (header & 1) != 0;
        int payloadLength = header >>> 1;
        if (payloadLength > maxFrameLength) {
            throw new TooLongFrameException("Frame length " + payloadLength + " exceeds " + maxFrameLength);
        }
        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }

        if (!compressed) {
            out.add(in.readRetainedSlice(payloadLength));
            return;
        }

        byte[] compressedBytes = new byte[payloadLength];
        in.readBytes(compressedBytes);
        byte[] raw = compressor.decompress(compressedBytes, maxFrameLength);
        out.add(Unpooled.wrappedBuffer(raw));
    }

    private static boolean hasCompleteVarInt(ByteBuf in) {
        for (int i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                return false;
            }
            if ((in.readByte() & 0x80) == 0) {
                in.resetReaderIndex();
                return true;
            }
        }
        throw new CorruptedFrameException("Malformed VarInt frame header");
    }
}
