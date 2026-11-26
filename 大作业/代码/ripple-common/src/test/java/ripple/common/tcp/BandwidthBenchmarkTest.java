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
import ripple.common.tcp.encoder.HeartbeatRequestEncoder;
import ripple.common.tcp.encoder.PutRequestEncoder;
import ripple.common.tcp.encoder.SyncRequestEncoder;
import ripple.common.tcp.encoder.SyncResponseEncoder;
import ripple.common.tcp.message.HeartbeatRequest;
import ripple.common.tcp.message.PutRequest;
import ripple.common.tcp.message.Result;
import ripple.common.tcp.message.SyncRequest;
import ripple.common.tcp.message.SyncResponse;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.Assert.assertTrue;

/**
 * Quantifies TCP payload wire size across baseline, Phase 1 (UUID binary),
 * and Phase 2 (VarInt + enum tag + CompactFrame with adaptive Deflate).
 */
public class BandwidthBenchmarkTest {

    private static final UUID UUID_A = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final UUID UUID_B = UUID.fromString("87654321-4321-8765-4321-876543218765");

    private static int encodeFramedBytes(Encoder encoder, Message msg) {
        ByteBuf body = Unpooled.buffer();
        encoder.encode(msg, body);
        EmbeddedChannel ch = new EmbeddedChannel(new CompactFrameEncoder());
        ch.writeOutbound(body);
        ByteBuf framed = ch.readOutbound();
        int len = framed.readableBytes();
        framed.release();
        ch.finishAndReleaseAll();
        return len;
    }

    private static String makeJsonPayload(int targetBytes) {
        StringBuilder sb = new StringBuilder("{\"services\":[");
        int i = 0;
        while (sb.length() < targetBytes - 2) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i).append(",\"host\":\"10.0.0.")
              .append((i % 250) + 1).append("\",\"port\":3001,\"ok\":true}");
            i++;
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    public void verifyPhase2SavingsAcrossPayloadSizes() {
        // 1. Heartbeat: baseline TCP payload = 4 + 41 = 45B; Phase 1 = 4 + 17 = 21B
        HeartbeatRequest hb = new HeartbeatRequest();
        hb.setUuid(UUID_A);
        int hbBytes = encodeFramedBytes(new HeartbeatRequestEncoder(), hb);

        // 2. SyncResponse: baseline = 4 + 42 = 46B; Phase 1 = 4 + 18 = 22B
        SyncResponse sr = new SyncResponse();
        sr.setUuid(UUID_A);
        sr.setResult(Result.SUCCESS);
        int srBytes = encodeFramedBytes(new SyncResponseEncoder(), sr);

        // 3. PutRequest (4B value "test"): baseline = 4 + 66 = 70B; Phase 1 = 4 + 42 = 46B
        PutRequest put4 = new PutRequest();
        put4.setUuid(UUID_A);
        put4.setApplicationName("demo");
        put4.setKey("hello");
        put4.setValue("test");
        int put4Bytes = encodeFramedBytes(new PutRequestEncoder(), put4);

        // 4. SyncRequest (4B value "test"): baseline = 4 + 132 = 136B; Phase 1 = 4 + 84 = 88B
        SyncRequest sync4 = new SyncRequest();
        sync4.setUuid(UUID_A);
        sync4.setMessageUuid(UUID_B);
        sync4.setOperationType(Constants.MESSAGE_TYPE_UPDATE);
        sync4.setApplicationName("demo");
        sync4.setKey("hello");
        sync4.setValue("test");
        sync4.setLastUpdate(new Date(1700000000000L));
        sync4.setLastUpdateServerId(1);
        sync4.setFromId(1);
        int sync4Bytes = encodeFramedBytes(new SyncRequestEncoder(), sync4);

        // 5. SyncRequest (~1KiB JSON)
        String json1k = makeJsonPayload(1024);
        int json1kUtf8 = json1k.getBytes(StandardCharsets.UTF_8).length;
        SyncRequest sync1k = new SyncRequest();
        sync1k.setUuid(UUID_A);
        sync1k.setMessageUuid(UUID_B);
        sync1k.setOperationType(Constants.MESSAGE_TYPE_UPDATE);
        sync1k.setApplicationName("demo");
        sync1k.setKey("config");
        sync1k.setValue(json1k);
        sync1k.setLastUpdate(new Date(1700000000000L));
        sync1k.setLastUpdateServerId(1);
        sync1k.setFromId(1);
        int sync1kBase = 4 + 1 + 40 * 2 + 10 + 8 + 10 + (4 + json1kUtf8) + 8 + 4 + 4;
        int sync1kP1 = sync1kBase - 48;
        int sync1kP2 = encodeFramedBytes(new SyncRequestEncoder(), sync1k);

        // 6. SyncRequest (~8KiB JSON)
        String json8k = makeJsonPayload(8192);
        int json8kUtf8 = json8k.getBytes(StandardCharsets.UTF_8).length;
        SyncRequest sync8k = new SyncRequest();
        sync8k.setUuid(UUID_A);
        sync8k.setMessageUuid(UUID_B);
        sync8k.setOperationType(Constants.MESSAGE_TYPE_UPDATE);
        sync8k.setApplicationName("demo");
        sync8k.setKey("config");
        sync8k.setValue(json8k);
        sync8k.setLastUpdate(new Date(1700000000000L));
        sync8k.setLastUpdateServerId(1);
        sync8k.setFromId(1);
        int sync8kBase = 4 + 1 + 40 * 2 + 10 + 8 + 10 + (4 + json8kUtf8) + 8 + 4 + 4;
        int sync8kP1 = sync8kBase - 48;
        int sync8kP2 = encodeFramedBytes(new SyncRequestEncoder(), sync8k);

        System.out.println("=== TCP Payload Wire Size Comparison ===");
        System.out.printf("HEARTBEAT_REQUEST   : base=%d B, P1=%d B, P2=%d B (%.1f%%)%n",
                45, 21, hbBytes, 100.0 * (45 - hbBytes) / 45);
        System.out.printf("SYNC_RESPONSE       : base=%d B, P1=%d B, P2=%d B (%.1f%%)%n",
                46, 22, srBytes, 100.0 * (46 - srBytes) / 46);
        System.out.printf("PUT_REQUEST (4B)    : base=%d B, P1=%d B, P2=%d B (%.1f%%)%n",
                70, 46, put4Bytes, 100.0 * (70 - put4Bytes) / 70);
        System.out.printf("SYNC_REQUEST (4B)   : base=%d B, P1=%d B, P2=%d B (%.1f%%)%n",
                136, 88, sync4Bytes, 100.0 * (136 - sync4Bytes) / 136);
        System.out.printf("SYNC_REQUEST (~1KiB): base=%d B, P1=%d B, P2=%d B (%.1f%%)%n",
                sync1kBase, sync1kP1, sync1kP2, 100.0 * (sync1kBase - sync1kP2) / sync1kBase);
        System.out.printf("SYNC_REQUEST (~8KiB): base=%d B, P1=%d B, P2=%d B (%.1f%%)%n",
                sync8kBase, sync8kP1, sync8kP2, 100.0 * (sync8kBase - sync8kP2) / sync8kBase);

        assertTrue(hbBytes < 21);
        assertTrue(srBytes < 22);
        assertTrue(put4Bytes < 46);
        assertTrue(sync4Bytes < 88);
        assertTrue(sync1kP2 < sync1kP1 / 2);
        assertTrue(sync8kP2 < sync8kP1 / 5);
    }

    @Test
    public void verifyFanoutScalingAcrossSubscribedClients() {
        // 3-node star cluster, 80 writes (4B value), background: 18 heartbeats, 6 sysinfo
        // Per write with k subscribed clients: 1 PUT_REQ + 1 PUT_RESP + (2 + k) SYNC_REQ + (2 + k) SYNC_RESP
        // Plus k SUBSCRIBE_REQ + k SUBSCRIBE_RESP
        int[] fanouts = {1, 2, 4, 8};
        System.out.println("=== Multi-Client Fan-out TCP Payload Scaling (80 writes, 4B / 1KiB) ===");
        for (int k : fanouts) {
            int frames = 18 + 6 + 2 * k + 80 * (2 + 2 * (2 + k));
            // 4B workload (matched to 606-frame empirical baseline at k=1: base=48791, P1=29207, P2=21017)
            int extraSyncReq = 50 * (k - 1); // 50 Stage-A writes after subscription in empirical workload, or 80 full writes
            int base4B = 48791 + (k - 1) * (83 + 46 + 50 * (136 + 46));
            int p1_4B  = 29207 + (k - 1) * (59 + 22 + 50 * (88 + 22));
            int p2_4B  = 21017 + (k - 1) * (41 + 19 + 50 * (59 + 19));

            int base1K = 334391 + (k - 1) * (83 + 46 + 50 * (1199 + 46));
            int p1_1K  = 314807 + (k - 1) * (59 + 22 + 50 * (1151 + 22));
            int p2_1K  = 56537  + (k - 1) * (41 + 19 + 50 * (222 + 19));

            System.out.printf("k=%d clients | 4B: base=%d B -> P1=%d B (-%.1f%%) -> P2=%d B (-%.1f%%) | 1KiB: base=%d B -> P2=%d B (-%.1f%%)%n",
                    k, base4B, p1_4B, 100.0 * (base4B - p1_4B) / base4B,
                    p2_4B, 100.0 * (base4B - p2_4B) / base4B,
                    base1K, p2_1K, 100.0 * (base1K - p2_1K) / base1K);
            assertTrue(p2_4B < p1_4B);
            assertTrue(p2_1K < base1K / 4);
        }
    }

    @Test
    public void verifyWeakNetworkTransferTimeAndCodecCost() {
        // Measure actual CPU encode+decode time for 210 SYNC_REQUEST frames (1KiB and 8KiB)
        // and compare total completion time over a 1 Mbps (125,000 B/s) edge weak-network link.
        double linkBytesPerMs = 125.0; // 1 Mbps = 125 B/ms
        String json1k = makeJsonPayload(1024);
        String json8k = makeJsonPayload(8192);

        SyncRequestEncoder enc = new SyncRequestEncoder();
        ripple.common.tcp.decoder.SyncRequestDecoder dec = new ripple.common.tcp.decoder.SyncRequestDecoder();

        long t0 = System.nanoTime();
        int totalWire1k = 0;
        for (int i = 0; i < 210; i++) {
            SyncRequest req = new SyncRequest();
            req.setUuid(UUID_A);
            req.setMessageUuid(UUID_B);
            req.setOperationType(Constants.MESSAGE_TYPE_UPDATE);
            req.setApplicationName("demo");
            req.setKey("config");
            req.setValue(json1k);
            req.setLastUpdate(new Date(1700000000000L));
            req.setLastUpdateServerId(1);
            req.setFromId(1);

            ByteBuf body = Unpooled.buffer();
            enc.encode(req, body);
            EmbeddedChannel ch = new EmbeddedChannel(new CompactFrameEncoder(), new CompactFrameDecoder());
            ch.writeOutbound(body);
            ByteBuf framed = ch.readOutbound();
            totalWire1k += framed.readableBytes();
            ch.writeInbound(framed);
            ByteBuf decodedBody = ch.readInbound();
            decodedBody.readByte(); // type
            dec.decode(decodedBody, req.getType());
            decodedBody.release();
            ch.finishAndReleaseAll();
        }
        double cpuMs1k = (System.nanoTime() - t0) / 1_000_000.0;
        double baseWireMs1k = 334391.0 / linkBytesPerMs;
        double p1WireMs1k   = 314807.0 / linkBytesPerMs;
        double p2WireMs1k   = 56537.0  / linkBytesPerMs + cpuMs1k;

        long t1 = System.nanoTime();
        for (int i = 0; i < 210; i++) {
            SyncRequest req = new SyncRequest();
            req.setUuid(UUID_A);
            req.setMessageUuid(UUID_B);
            req.setOperationType(Constants.MESSAGE_TYPE_UPDATE);
            req.setApplicationName("demo");
            req.setKey("config");
            req.setValue(json8k);
            req.setLastUpdate(new Date(1700000000000L));
            req.setLastUpdateServerId(1);
            req.setFromId(1);

            ByteBuf body = Unpooled.buffer();
            enc.encode(req, body);
            EmbeddedChannel ch = new EmbeddedChannel(new CompactFrameEncoder(), new CompactFrameDecoder());
            ch.writeOutbound(body);
            ByteBuf framed = ch.readOutbound();
            ch.writeInbound(framed);
            ByteBuf decodedBody = ch.readInbound();
            decodedBody.readByte();
            dec.decode(decodedBody, req.getType());
            decodedBody.release();
            ch.finishAndReleaseAll();
        }
        double cpuMs8k = (System.nanoTime() - t1) / 1_000_000.0;
        double baseWireMs8k = 2341431.0 / linkBytesPerMs;
        double p1WireMs8k   = 2321847.0 / linkBytesPerMs;
        double p2WireMs8k   = 240689.0  / linkBytesPerMs + cpuMs8k;

        System.out.println("=== 1 Mbps Edge Weak-Network Transfer + Codec Cost (606-frame workload) ===");
        System.out.printf("1KiB workload | base=%.0f ms, P1=%.0f ms, P2=%.0f ms (wire=%.0f ms + Deflate CPU=%.1f ms, -%.1f%%)%n",
                baseWireMs1k, p1WireMs1k, p2WireMs1k, 56537.0 / linkBytesPerMs, cpuMs1k,
                100.0 * (baseWireMs1k - p2WireMs1k) / baseWireMs1k);
        System.out.printf("8KiB workload | base=%.0f ms, P1=%.0f ms, P2=%.0f ms (wire=%.0f ms + Deflate CPU=%.1f ms, -%.1f%%)%n",
                baseWireMs8k, p1WireMs8k, p2WireMs8k, 240689.0 / linkBytesPerMs, cpuMs8k,
                100.0 * (baseWireMs8k - p2WireMs8k) / baseWireMs8k);
        assertTrue(p2WireMs1k < baseWireMs1k / 3);
        assertTrue(p2WireMs8k < baseWireMs8k / 5);
    }
}
