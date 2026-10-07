package org.example.exchange;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.TradeNotification;
import org.example.exchange.udp.UdpExchangeClient;
import org.example.exchange.udp.UdpExchangeServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpExchangeIntegrationTest {
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");
    private static final Duration NOTIFICATION_TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    Path tempDir;

    @Test
    void mainExchangeScenarioWorksOnlyThroughUdp() throws Exception {
        try (UdpExchangeServer server = startServer(tempDir.resolve("main/exchange"));
             UdpExchangeClient buyer = client(server);
             UdpExchangeClient seller = client(server)) {

            buyer.connect("buyer");
            seller.connect("seller");

            buyer.placeOrder(
                    "buyer", EUR_USD, OrderSide.BUY,
                    bd("10"), bd("1.1000")
            );

            Order firstSell = seller.placeOrder(
                    "seller", EUR_USD, OrderSide.SELL,
                    bd("4"), bd("1.0900")
            );

            assertTrue(firstSell.isFilled());
            assertTradeNotification(buyer.pollNotification(NOTIFICATION_TIMEOUT), "buyer", "4");
            assertTradeNotification(seller.pollNotification(NOTIFICATION_TIMEOUT), "seller", "4");

            List<Order> afterPartialFill = buyer.getOpenOrders(EUR_USD);
            assertEquals(1, afterPartialFill.size());
            assertEquals(OrderSide.BUY, afterPartialFill.getFirst().side());
            assertEquals(0, bd("6").compareTo(afterPartialFill.getFirst().remainingQuantity()));

            seller.placeOrder(
                    "seller", EUR_USD, OrderSide.SELL,
                    bd("6"), bd("1.1000")
            );

            assertTradeNotification(buyer.pollNotification(NOTIFICATION_TIMEOUT), "buyer", "6");
            assertTradeNotification(seller.pollNotification(NOTIFICATION_TIMEOUT), "seller", "6");
            assertEquals(2, buyer.getTrades().size());
            assertTrue(buyer.getOpenOrders(EUR_USD).isEmpty());
        }
    }

    @Test
    void offlineNotificationIsDeliveredToRememberedUdpEndpointAfterConnect() throws Exception {
        try (UdpExchangeServer server = startServer(tempDir.resolve("offline/exchange"));
             UdpExchangeClient buyer = client(server);
             UdpExchangeClient seller = client(server)) {

            buyer.placeOrder(
                    "buyer", EUR_USD, OrderSide.BUY,
                    bd("5"), bd("1.1000")
            );

            seller.connect("seller");
            seller.placeOrder(
                    "seller", EUR_USD, OrderSide.SELL,
                    bd("5"), bd("1.0900")
            );

            assertTradeNotification(seller.pollNotification(NOTIFICATION_TIMEOUT), "seller", "5");
            assertNull(buyer.pollNotification(Duration.ofMillis(200)));

            buyer.connect("buyer");

            TradeNotification restored = buyer.pollNotification(NOTIFICATION_TIMEOUT);
            assertTradeNotification(restored, "buyer", "5");
            assertEquals("buyer", restored.trade().buyerId());
            assertEquals("seller", restored.trade().sellerId());
        }
    }

    @Test
    void stateAndPendingNotificationsSurviveServerRestart() throws Exception {
        Path database = tempDir.resolve("restart/exchange");

        try (UdpExchangeServer firstServer = startServer(database);
             UdpExchangeClient client = client(firstServer)) {
            client.placeOrder(
                    "buyer", EUR_USD, OrderSide.BUY,
                    bd("10"), bd("1.1000")
            );
            client.placeOrder(
                    "seller", EUR_USD, OrderSide.SELL,
                    bd("3"), bd("1.0900")
            );
        }

        try (UdpExchangeServer secondServer = startServer(database);
             UdpExchangeClient client = client(secondServer)) {
            assertEquals(1, client.getTrades().size());

            List<Order> restoredOrders = client.getOpenOrders(EUR_USD);
            assertEquals(1, restoredOrders.size());
            assertEquals(0, bd("7").compareTo(restoredOrders.getFirst().remainingQuantity()));

            client.connect("buyer");
            assertTradeNotification(client.pollNotification(NOTIFICATION_TIMEOUT), "buyer", "3");

            client.connect("seller");
            assertTradeNotification(client.pollNotification(NOTIFICATION_TIMEOUT), "seller", "3");
        }
    }

    private static UdpExchangeServer startServer(Path database) {
        UdpExchangeServer server = new UdpExchangeServer(database, 0);
        server.start();
        return server;
    }

    private static UdpExchangeClient client(UdpExchangeServer server) throws Exception {
        return new UdpExchangeClient(new InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                server.localPort()
        ));
    }

    private static void assertTradeNotification(
            TradeNotification notification,
            String clientId,
            String quantity
    ) {
        assertNotNull(notification);
        assertEquals(clientId, notification.clientId());
        assertEquals(0, bd(quantity).compareTo(notification.trade().quantity()));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
