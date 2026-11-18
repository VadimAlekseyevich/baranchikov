package org.example.exchange.tests.lab6;

import org.example.exchange.distributed.ShardRouting;
import org.example.exchange.distributed.Wire;
import org.example.exchange.distributed.LayerTestBridge;

import org.example.exchange.wire.ExchangeWire.Packet;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShardRoutingTest {
    @Test
    void pairIsDeterministicallyAssignedToOneShard() {
        Set<Integer> distinct = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            String base = "CUR" + i;
            int shard = ShardRouting.index(base, "USD", 3);
            assertEquals(shard, ShardRouting.index(base.toLowerCase(), "usd", 3));
            assertTrue(shard >= 0 && shard < 3);
            distinct.add(shard);
        }
        assertEquals(Set.of(0, 1, 2), distinct);
        assertThrows(IllegalArgumentException.class, () -> ShardRouting.index("EUR", "USD", 0));
    }

    @Test
    void localIdsBecomeGloballyUniqueAcrossShards() {
        Set<Long> ids = new HashSet<>();
        for (long local = 1; local <= 20; local++) {
            for (int shard = 0; shard < 3; shard++) {
                assertTrue(ids.add(ShardRouting.publicId(local, shard, 3)));
            }
        }
        assertEquals(60, ids.size());
    }

    @Test
    void cancellationCountSurvivesProtobufFraming() throws Exception {
        Packet original = Wire.packet("CANCELLED").setCancelledCount(17).build();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        original.writeDelimitedTo(buffer);
        Packet restored = Packet.parseDelimitedFrom(new ByteArrayInputStream(buffer.toByteArray()));
        assertEquals(original, restored);
        assertEquals(17, restored.getCancelledCount());
    }

    @Test
    void businessRejectsCurrencyPairBelongingToAnotherShard() {
        String base = "EUR";
        for (int i = 0; ShardRouting.index(base, "USD", 3) == 0; i++) base = "C" + i;
        Packet request = Wire.packet("PLACE_ORDER").setClientId("buyer").setBase(base)
                .setQuote("USD").setSide("BUY").setQuantity("2").setPrice("1").build();
        assertThrows(IllegalArgumentException.class, () -> LayerTestBridge.requireOwner(request));
    }
}
