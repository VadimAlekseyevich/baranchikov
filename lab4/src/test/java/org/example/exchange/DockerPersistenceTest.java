package org.example.exchange;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.udp.UdpExchangeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(
        named = "PERSISTENCE_PHASE",
        matches = "seed|verify"
)
class DockerPersistenceTest {
    private static final CurrencyPair PAIR =
            new CurrencyPair("LAB4", "USD");
    private static final String CLIENT_ID =
            "docker-persistence-buyer";
    private static final BigDecimal QUANTITY =
            new BigDecimal("17");
    private static final BigDecimal PRICE =
            new BigDecimal("1.2345");

    @Test
    void namedVolumeKeepsH2StateAcrossServerRestart() throws Exception {
        String phase = System.getenv("PERSISTENCE_PHASE");

        try (UdpExchangeClient client = awaitServer()) {
            if ("seed".equals(phase)) {
                seed(client);
            } else if ("verify".equals(phase)) {
                verify(client);
            } else {
                throw new IllegalStateException(
                        "Unknown PERSISTENCE_PHASE: " + phase
                );
            }
        }
    }

    private static void seed(UdpExchangeClient client) {
        List<Order> existing = client.getOpenOrders(PAIR);
        boolean alreadySeeded = existing.stream()
                .anyMatch(order -> CLIENT_ID.equals(order.clientId()));

        if (!alreadySeeded) {
            client.placeOrder(
                    CLIENT_ID,
                    PAIR,
                    OrderSide.BUY,
                    QUANTITY,
                    PRICE
            );
        }

        verify(client);
    }

    private static void verify(UdpExchangeClient client) {
        Order persisted = client.getOpenOrders(PAIR).stream()
                .filter(order -> CLIENT_ID.equals(order.clientId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Persisted order was not restored from H2 volume"
                ));

        assertEquals(OrderSide.BUY, persisted.side());
        assertEquals(0, QUANTITY.compareTo(persisted.remainingQuantity()));
        assertEquals(0, PRICE.compareTo(persisted.limitPrice()));
        assertTrue(persisted.id() > 0);
    }

    private static UdpExchangeClient awaitServer() throws Exception {
        IllegalStateException lastError = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();

        while (System.nanoTime() < deadline) {
            UdpExchangeClient client = newClient();
            try {
                client.getOpenOrders(PAIR);
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

    private static UdpExchangeClient newClient() {
        String host = System.getenv().getOrDefault(
                "EXCHANGE_HOST",
                "127.0.0.1"
        );
        int port = Integer.parseInt(
                System.getenv().getOrDefault("EXCHANGE_PORT", "9000")
        );

        return new UdpExchangeClient(
                new InetSocketAddress(host, port),
                Duration.ofMillis(700)
        );
    }
}
