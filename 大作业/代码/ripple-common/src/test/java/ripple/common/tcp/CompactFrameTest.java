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
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import ripple.common.entity.Constants;
import ripple.common.helper.TypeHelper;
import ripple.common.tcp.decoder.PutRequestDecoder;
import ripple.common.tcp.decoder.SyncRequestDecoder;
import ripple.common.tcp.encoder.PutRequestEncoder;
import ripple.common.tcp.encoder.SyncRequestEncoder;
import ripple.common.tcp.message.PutRequest;
import ripple.common.tcp.message.SyncRequest;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link CompactFrameEncoder} and {@link CompactFrameDecoder}:
 * VarInt frame header, adaptive Deflate compression, incompressible fallback,
 * and TCP fragmentation / coalescing.
 */
public class CompactFrameTest {

    private static final UUID UUID_A = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final UUID UUID_B = UUID.fromString("87654321-4321-8765-4321-876543218765");

    @Test
    public void smallFrameUsesOneByteHeaderWithoutCompression() {
        PutRequest put = new PutRequest();
        put.setUuid(UUID_A);
        put.setApplicationName("demo");
        put.setKey("hello");
        put.setValue("world");

        ByteBuf payload = Unpooled.buffer();
        new PutRequestEncoder().encode(put, payload);
        assertEquals(34, payload.readableBytes());

        EmbeddedChannel outCh = new EmbeddedChannel(new CompactFrameEncoder());
        assertTrue(outCh.writeOutbound(payload));
        ByteBuf wire = outCh.readOutbound();
        assertEquals("1 字节帧头 + 34 字节报文体 = 35 字节", 35, wire.readableBytes());

        int header = TypeHelper.readVarInt(wire.duplicate());
        assertEquals("小包压缩标志位必须为 0", 0, header & 1);
        assertEquals(34, header >>> 1);

        EmbeddedChannel inCh = new EmbeddedChannel(new CompactFrameDecoder());
        assertTrue(inCh.writeInbound(wire));
        ByteBuf decodedBuf = inCh.readInbound();
        assertEquals(MessageType.PUT_REQUEST.getValue(), decodedBuf.readByte());
        PutRequest decoded = (PutRequest) new PutRequestDecoder().decode(decodedBuf, MessageType.PUT_REQUEST);
        assertEquals("world", decoded.getValue());
        assertEquals(0, decodedBuf.readableBytes());
        decodedBuf.release();
        outCh.finishAndReleaseAll();
        inCh.finishAndReleaseAll();
    }

    @Test
    public void largeJsonPayloadTriggersAdaptiveCompression() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"items\":[");
        for (int i = 0; i < 60; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i).append(",\"service\":\"ripple-edge-node\",\"enabled\":true}");
        }
        sb.append("]}");
        String largeJson = sb.toString();

        SyncRequest sync = new SyncRequest();
        sync.setUuid(UUID_A);
        sync.setMessageUuid(UUID_B);
        sync.setOperationType(Constants.MESSAGE_TYPE_UPDATE);
        sync.setApplicationName("demo");
        sync.setKey("routes");
        sync.setValue(largeJson);
        sync.setLastUpdate(new Date(1700000000000L));
        sync.setLastUpdateServerId(1);
        sync.setFromId(1);

        ByteBuf raw = Unpooled.buffer();
        new SyncRequestEncoder().encode(sync, raw);
        int rawLen = raw.readableBytes();
        assertTrue(rawLen > CompactFrameEncoder.COMPRESS_THRESHOLD);

        EmbeddedChannel outCh = new EmbeddedChannel(new CompactFrameEncoder());
        assertTrue(outCh.writeOutbound(raw));
        ByteBuf wire = outCh.readOutbound();
        int wireLen = wire.readableBytes();
        assertTrue("大 JSON 压缩后应显著小于原始帧长", wireLen < rawLen / 2);

        int header = TypeHelper.readVarInt(wire.duplicate());
        assertEquals("压缩标志位必须为 1", 1, header & 1);

        EmbeddedChannel inCh = new EmbeddedChannel(new CompactFrameDecoder());
        assertTrue(inCh.writeInbound(wire));
        ByteBuf decodedBuf = inCh.readInbound();
        assertEquals(MessageType.SYNC_REQUEST.getValue(), decodedBuf.readByte());
        SyncRequest decoded = (SyncRequest) new SyncRequestDecoder().decode(decodedBuf, MessageType.SYNC_REQUEST);
        assertEquals(largeJson, decoded.getValue());
        assertEquals(0, decodedBuf.readableBytes());
        decodedBuf.release();
        outCh.finishAndReleaseAll();
        inCh.finishAndReleaseAll();
    }

    @Test
    public void incompressibleLargeFrameFallsBackWithoutInflation() {
        byte[] randomBytes = new byte[400];
        new Random(42L).nextBytes(randomBytes);
        ByteBuf raw = Unpooled.wrappedBuffer(randomBytes);

        EmbeddedChannel outCh = new EmbeddedChannel(new CompactFrameEncoder());
        assertTrue(outCh.writeOutbound(raw));
        ByteBuf wire = outCh.readOutbound();

        int header = TypeHelper.readVarInt(wire.duplicate());
        assertEquals("不可压缩数据必须回退为未压缩 (bit0 = 0)", 0, header & 1);
        assertEquals(400, header >>> 1);

        EmbeddedChannel inCh = new EmbeddedChannel(new CompactFrameDecoder());
        assertTrue(inCh.writeInbound(wire));
        ByteBuf decoded = inCh.readInbound();
        byte[] outBytes = new byte[decoded.readableBytes()];
        decoded.readBytes(outBytes);
        assertEquals(new String(randomBytes, StandardCharsets.ISO_8859_1),
                new String(outBytes, StandardCharsets.ISO_8859_1));
        decoded.release();
        outCh.finishAndReleaseAll();
        inCh.finishAndReleaseAll();
    }

    @Test
    public void fragmentedAndCoalescedFramesDecodeCleanly() {
        EmbeddedChannel outCh = new EmbeddedChannel(new CompactFrameEncoder());
        byte[] first = "short-msg".getBytes(StandardCharsets.UTF_8);
        byte[] second = new byte[512];
        for (int i = 0; i < second.length; i++) {
            second[i] = (byte) ('A' + (i % 4));
        }

        assertTrue(outCh.writeOutbound(Unpooled.wrappedBuffer(first)));
        assertTrue(outCh.writeOutbound(Unpooled.wrappedBuffer(second)));
        ByteBuf wire1 = outCh.readOutbound();
        ByteBuf wire2 = outCh.readOutbound();

        ByteBuf combined = Unpooled.buffer();
        combined.writeBytes(wire1);
        combined.writeBytes(wire2);
        wire1.release();
        wire2.release();

        EmbeddedChannel inCh = new EmbeddedChannel(new CompactFrameDecoder());
        // Feed 1 byte at a time to exercise VarInt and payload fragmentation
        while (combined.isReadable()) {
            inCh.writeInbound(combined.readRetainedSlice(1));
        }
        combined.release();

        ByteBuf dec1 = inCh.readInbound();
        ByteBuf dec2 = inCh.readInbound();
        assertNotNull(dec1);
        assertNotNull(dec2);
        assertEquals(first.length, dec1.readableBytes());
        assertEquals(second.length, dec2.readableBytes());
        dec1.release();
        dec2.release();
        outCh.finishAndReleaseAll();
        inCh.finishAndReleaseAll();
    }
}
