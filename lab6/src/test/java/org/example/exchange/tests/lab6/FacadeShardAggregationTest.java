package org.example.exchange.tests.lab6;

import org.example.exchange.distributed.ShardRouting;
import org.example.exchange.distributed.Wire;
import org.example.exchange.distributed.LayerTestBridge;

import org.example.exchange.wire.ExchangeWire.Packet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FacadeShardAggregationTest {
    @Test
    void combinesTradesFromAllShardsWithUniqueGlobalIds() {
        Packet first = trade("EUR", "USD");
        Packet second = trade("GBP", "USD");
        Packet third = trade("JPY", "USD");
        List<Packet> replies = List.of(
                Wire.packet("TRADES").addItems(first).build(),
                Wire.packet("TRADES").addItems(second).build(),
                Wire.packet("TRADES").addItems(third).build());
        List<String> result = LayerTestBridge.mergeTrades(replies);
        assertEquals(3, result.size());
        assertEquals(List.of("1", "2", "3"),
                result.stream().map(value -> value.split("\\|", -1)[0]).toList());
        assertTrue(result.get(0).contains("EUR|USD"));
        assertTrue(result.get(1).contains("GBP|USD"));
        assertTrue(result.get(2).contains("JPY|USD"));
    }

    @Test
    void addsCancellationResultsFromEveryShard() {
        List<Packet> replies = List.of(
                Wire.packet("CANCELLED").setCancelledCount(2).build(),
                Wire.packet("CANCELLED").setCancelledCount(0).build(),
                Wire.packet("CANCELLED").setCancelledCount(4).build());
        assertEquals(6, LayerTestBridge.sumCancelled(replies));
    }

    private static Packet trade(String base, String quote) {
        return Wire.packet("TRADE").setTradeId(1).setBase(base).setQuote(quote)
                .setPrice("1.1").setQuantity("5").setBuyOrderId(1).setSellOrderId(2)
                .setBuyerId("buyer").setSellerId("seller").build();
    }
}
