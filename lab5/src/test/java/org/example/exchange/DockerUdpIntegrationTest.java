package org.example.exchange;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.TradeNotification;
import org.example.exchange.udp.UdpExchangeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "EXCHANGE_HOST", matches = ".+")
class DockerUdpIntegrationTest {
    private static final Duration NOTIFICATION_TIMEOUT = Duration.ofSeconds(3);
    private static final CurrencyPair READY_PAIR =
            new CurrencyPair("READY", "USD");

    @Test
    void testContainerCommunicatesWithServerContainerOverUdp() throws Exception {
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);

        CurrencyPair pair = new CurrencyPair("D" + suffix, "USD");
        String buyerId = "docker-buyer-" + suffix;
        String sellerId = "docker-seller-" + suffix;

        try (UdpExchangeClient buyer = awaitServer();
             UdpExchangeClient seller = newClient(Duration.ofSeconds(3))) {

            buyer.connect(buyerId);
            seller.connect(sellerId);

            buyer.placeOrder(
                    buyerId,
                    pair,
                    OrderSide.BUY,
                    new BigDecimal("5"),
                    new BigDecimal("1.1000")
            );

            seller.placeOrder(
                    sellerId,
                    pair,
                    OrderSide.SELL,
                    new BigDecimal("5"),
                    new BigDecimal("1.0900")
            );

            TradeNotification buyerNotification =
                    buyer.pollNotification(NOTIFICATION_TIMEOUT);
            TradeNotification sellerNotification =
                    seller.pollNotification(NOTIFICATION_TIMEOUT);

            assertNotNull(buyerNotification);
            assertNotNull(sellerNotification);
            assertEquals(buyerId, buyerNotification.clientId());
            assertEquals(sellerId, sellerNotification.clientId());
            assertEquals(pair, buyerNotification.trade().pair());
            assertEquals(buyerId, buyerNotification.trade().buyerId());
            assertEquals(sellerId, buyerNotification.trade().sellerId());
            assertEquals(
                    0,
                    new BigDecimal("5").compareTo(
                            buyerNotification.trade().quantity()
                    )
            );

            assertEquals(
                    buyerNotification.trade().id(),
                    sellerNotification.trade().id()
            );
            assertTrue(buyer.getOpenOrders(pair).isEmpty());
        }
    }

    private static UdpExchangeClient awaitServer() throws Exception {
        IllegalStateException lastError = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();

        while (System.nanoTime() < deadline) {
            UdpExchangeClient client = newClient(Duration.ofMillis(700));
            try {
                client.getOpenOrders(READY_PAIR);
                return client;
            } catch (IllegalStateException error) {
                lastError = error;
                client.close();
                Thread.sleep(250);
            }
        }

        throw new IllegalStateException(
                "UDP exchange server did not become ready",
                lastError
        );
    }

    private static UdpExchangeClient newClient(Duration timeout) {
        String host = System.getenv().getOrDefault(
                "EXCHANGE_HOST",
                "127.0.0.1"
        );
        int port = Integer.parseInt(
                System.getenv().getOrDefault("EXCHANGE_PORT", "9000")
        );

        return new UdpExchangeClient(
                new InetSocketAddress(host, port),
                timeout
        );
    }
}
