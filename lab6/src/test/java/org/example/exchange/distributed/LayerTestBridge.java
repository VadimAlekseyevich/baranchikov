package org.example.exchange.distributed;

import java.sql.Connection;
import java.util.List;
import org.example.exchange.wire.ExchangeWire.Packet;

public final class LayerTestBridge {
    private LayerTestBridge() {
    }

    public static void field(String value) {
        FacadeMain.field(value);
    }

    public static void count(String[] fields, int expected) {
        FacadeMain.count(fields, expected);
    }

    public static void validateOrder(Packet request) {
        BusinessMain.validateOrder(request);
    }

    public static void requireOwner(Packet request) {
        BusinessMain.requireOwner(request);
    }

    public static void initialize(Connection connection) throws Exception {
        StorageMain.initialize(connection);
    }

    public static Packet process(Connection connection, Packet request) throws Exception {
        return StorageMain.process(connection, request);
    }

    public static List<String> mergeTrades(List<Packet> replies) {
        return FacadeMain.mergeTrades(replies);
    }

    public static long sumCancelled(List<Packet> replies) {
        return FacadeMain.sumCancelled(replies);
    }
}
