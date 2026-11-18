package org.example.exchange.distributed;

import org.example.exchange.wire.ExchangeWire.Packet;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class StorageShardOperationsTest {
    @Test
    void cancellationChangesOnlyOwnOrdersAndOnlyOneShard() throws Exception {
        try (Connection first = DriverManager.getConnection("jdbc:h2:mem:lab6_first;DB_CLOSE_DELAY=-1");
             Connection second = DriverManager.getConnection("jdbc:h2:mem:lab6_second;DB_CLOSE_DELAY=-1")) {
            StorageMain.initialize(first);
            StorageMain.initialize(second);
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            insert(first, "user", "EUR");
            insert(first, "user", "EUR");
            insert(first, "other", "EUR");
            insert(second, "user", "GBP");
            first.commit();
            second.commit();

            Packet firstCancellation = StorageMain.process(first,
                    Wire.packet("CANCEL_ALL").setClientId("user").build());
            assertEquals(2, firstCancellation.getCancelledCount());
            first.commit();

            assertEquals(0, StorageMain.process(first,
                    Wire.packet("CANCEL_ALL").setClientId("user").build()).getCancelledCount());
            assertEquals(1, StorageMain.process(first,
                    Wire.packet("OPEN_ORDERS").setBase("EUR").setQuote("USD").build()).getItemsCount());
            assertEquals("other", StorageMain.process(first,
                    Wire.packet("OPEN_ORDERS").setBase("EUR").setQuote("USD").build()).getItems(0).getClientId());
            assertEquals(1, StorageMain.process(second,
                    Wire.packet("OPEN_ORDERS").setBase("GBP").setQuote("USD").build()).getItemsCount());
            first.rollback();
            second.rollback();
        }
    }

    private static void insert(Connection connection, String client, String base) throws Exception {
        StorageMain.process(connection, Wire.packet("INSERT_ORDER").setClientId(client)
                .setBase(base).setQuote("USD").setSide("BUY")
                .setQuantity("5").setPrice("1.1000").build());
    }
}
