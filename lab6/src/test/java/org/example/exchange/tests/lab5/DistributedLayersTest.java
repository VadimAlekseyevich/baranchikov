package org.example.exchange.tests.lab5;

import org.example.exchange.distributed.LayerTestBridge;
import org.example.exchange.distributed.Wire;

import org.example.exchange.wire.ExchangeWire.Packet;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class DistributedLayersTest {
    @Test
    void facadeChecksIncomingFieldsBeforeForwarding() {
        assertThrows(IllegalArgumentException.class, () -> LayerTestBridge.field("buyer|bad"));
        assertThrows(IllegalArgumentException.class, () -> LayerTestBridge.field("\n"));
        assertThrows(IllegalArgumentException.class, () -> LayerTestBridge.count(new String[] {"PLACE_ORDER", "1"}, 8));
        assertDoesNotThrow(() -> LayerTestBridge.field("buyer-1"));
    }

    @Test
    void businessRejectsInvalidOrdersWithoutNetworkOrDatabase() {
        Packet good = Wire.packet("PLACE_ORDER").setClientId("buyer").setBase("EUR")
                .setQuote("USD").setSide("BUY").setQuantity("10").setPrice("1.1000").build();
        assertDoesNotThrow(() -> LayerTestBridge.validateOrder(good));
        assertThrows(IllegalArgumentException.class,
                () -> LayerTestBridge.validateOrder(good.toBuilder().setQuantity("-1").build()));
        assertThrows(IllegalArgumentException.class,
                () -> LayerTestBridge.validateOrder(good.toBuilder().setSide("HOLD").build()));
    }

    @Test
    void storageStoresAndReadsAnOrderWithoutFacadeOrBusiness() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:layers;DB_CLOSE_DELAY=-1", "sa", "")) {
            LayerTestBridge.initialize(c);
            c.setAutoCommit(false);
            Packet order = Wire.packet("INSERT_ORDER").setClientId("buyer")
                    .setBase("EUR").setQuote("USD").setSide("BUY")
                    .setQuantity("10").setPrice("1.1000").build();
            Packet written = LayerTestBridge.process(c, order);
            assertTrue(written.getOrderId() > 0);
            c.commit();
            Packet found = LayerTestBridge.process(c,
                    Wire.packet("OPEN_ORDERS").setBase("EUR").setQuote("USD").build());
            assertEquals(1, found.getItemsCount());
            assertEquals(written.getOrderId(), found.getItems(0).getOrderId());
            c.rollback();
        }
    }

    @Test
    void protobufContractRoundTripsFramedMessages() throws Exception {
        Packet source = Wire.packet("PLACE_ORDER").setClientId("buyer").setBase("EUR")
                .setQuote("USD").setSide("BUY").setQuantity("10").setPrice("1.1000").build();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        source.writeDelimitedTo(buffer);
        Packet decoded = Packet.parseDelimitedFrom(new ByteArrayInputStream(buffer.toByteArray()));
        assertEquals(source, decoded);
    }
}
