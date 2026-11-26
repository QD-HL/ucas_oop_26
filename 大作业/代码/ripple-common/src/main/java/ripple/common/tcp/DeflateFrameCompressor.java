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

import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Default {@link FrameCompressor} backed by JDK Deflate / Inflate.
 */
public final class DeflateFrameCompressor implements FrameCompressor {
    public static final DeflateFrameCompressor INSTANCE = new DeflateFrameCompressor();

    private DeflateFrameCompressor() {
    }

    @Override
    public byte[] compress(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(raw.length);
            byte[] buffer = new byte[512];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count > 0) {
                    baos.write(buffer, 0, count);
                }
            }
            return baos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    @Override
    public byte[] decompress(byte[] compressed, int maxLength) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(compressed.length * 2);
            byte[] buffer = new byte[512];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new CorruptedFrameException("Truncated Deflate frame");
                    }
                    continue;
                }
                if (baos.size() + count > maxLength) {
                    throw new TooLongFrameException("Uncompressed frame exceeds " + maxLength);
                }
                baos.write(buffer, 0, count);
            }
            return baos.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
