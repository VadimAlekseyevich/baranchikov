package org.example.exchange.distributed;

import org.example.exchange.wire.ExchangeWire.Packet;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

/** The three distributed layers plus the protobuf wire contract are tested independently. */
class DistributedLayersTest {
    @Test
    void facadeChecksIncomingFieldsBeforeForwarding() {
        assertThrows(IllegalArgumentException.class, () -> FacadeMain.field("buyer|bad"));
        assertThrows(IllegalArgumentException.class, () -> FacadeMain.field("\n"));
        assertThrows(IllegalArgumentException.class, () -> FacadeMain.count(new String[] {"PLACE_ORDER", "1"}, 8));
        assertDoesNotThrow(() -> FacadeMain.field("buyer-1"));
    }

    @Test
    void businessRejectsInvalidOrdersWithoutNetworkOrDatabase() {
        Packet good = Wire.packet("PLACE_ORDER").setClientId("buyer").setBase("EUR")
                .setQuote("USD").setSide("BUY").setQuantity("10").setPrice("1.1000").build();
        assertDoesNotThrow(() -> BusinessMain.validateOrder(good));
        assertThrows(IllegalArgumentException.class,
                () -> BusinessMain.validateOrder(good.toBuilder().setQuantity("-1").build()));
        assertThrows(IllegalArgumentException.class,
                () -> BusinessMain.validateOrder(good.toBuilder().setSide("HOLD").build()));
    }

    @Test
    void storageStoresAndReadsAnOrderWithoutFacadeOrBusiness() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:layers;DB_CLOSE_DELAY=-1", "sa", "")) {
            StorageMain.initialize(c);
            c.setAutoCommit(false);
            Packet order = Wire.packet("INSERT_ORDER").setClientId("buyer")
                    .setBase("EUR").setQuote("USD").setSide("BUY")
                    .setQuantity("10").setPrice("1.1000").build();
            Packet written = StorageMain.process(c, order);
            assertTrue(written.getOrderId() > 0);
            c.commit();
            Packet found = StorageMain.process(c,
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
