package org.example.exchange.distributed;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.TradeNotification;
import org.example.exchange.udp.UdpExchangeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "EXCHANGE_HOST", matches = ".+")
class DockerShardingIntegrationTest {
    @Test
    void routesIndependentPairsAndCancelsEveryUsersOpenOrder() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        CurrencyPair[] pairs = new CurrencyPair[3];
        for (int i = 0; i < 300 && Arrays.stream(pairs).anyMatch(p -> p == null); i++) {
            CurrencyPair candidate = new CurrencyPair("T" + suffix + i, "USD");
            pairs[ShardRouting.index(candidate.base(), candidate.quote(), 3)] = candidate;
        }
        assertTrue(Arrays.stream(pairs).allMatch(p -> p != null));
        awaitReady(pairs);

        String owner = "owner-" + suffix;
        String seller = "seller-" + suffix;
        String other = "other-" + suffix;

        try (UdpExchangeClient buyer = client(Duration.ofSeconds(5));
             UdpExchangeClient sellerClient = client(Duration.ofSeconds(5));
             UdpExchangeClient otherClient = client(Duration.ofSeconds(5))) {
            Set<Long> ids = new HashSet<>();
            for (CurrencyPair pair : pairs) {
                Order order = buyer.placeOrder(owner, pair, OrderSide.BUY,
                        new BigDecimal("5"), new BigDecimal("1.1000"));
                assertTrue(ids.add(order.id()));
                assertEquals(1, buyer.getOpenOrders(pair).size());
            }

            otherClient.placeOrder(other, pairs[0], OrderSide.BUY,
                    new BigDecimal("1"), new BigDecimal("1.0000"));
            sellerClient.connect(seller);
            sellerClient.placeOrder(seller, pairs[2], OrderSide.SELL,
                    new BigDecimal("5"), new BigDecimal("1.0900"));

            TradeNotification receivedBySeller = sellerClient.pollNotification(Duration.ofSeconds(10));
            assertNotNull(receivedBySeller);
            assertEquals(pairs[2], receivedBySeller.trade().pair());

            buyer.connect(owner);
            TradeNotification receivedAfterReconnect = buyer.pollNotification(Duration.ofSeconds(10));
            assertNotNull(receivedAfterReconnect);
            assertEquals(owner, receivedAfterReconnect.clientId());
            assertEquals(receivedBySeller.trade().id(), receivedAfterReconnect.trade().id());

            assertTrue(buyer.getOpenOrders(pairs[2]).isEmpty());
            assertTrue(buyer.getTrades().stream().anyMatch(t ->
                    t.id() == receivedBySeller.trade().id() && t.pair().equals(pairs[2])));

            assertEquals(2L, buyer.cancelAllOrders(owner));
            assertEquals(0L, buyer.cancelAllOrders(owner));
            for (CurrencyPair pair : pairs) {
                assertTrue(buyer.getOpenOrders(pair).stream().noneMatch(o -> o.clientId().equals(owner)));
            }
            assertEquals(1, buyer.getOpenOrders(pairs[0]).size());
            assertEquals(other, buyer.getOpenOrders(pairs[0]).getFirst().clientId());
        }
    }

    private static void awaitReady(CurrencyPair[] pairs) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < until) {
            try (UdpExchangeClient client = client(Duration.ofSeconds(2))) {
                for (CurrencyPair pair : pairs) {
                    client.getOpenOrders(pair);
                }
                return;
            } catch (IllegalStateException failure) {
                Thread.sleep(300);
            }
        }
        fail("Sharded facade and all three business/storage/database services did not become ready");
    }

    private static UdpExchangeClient client(Duration timeout) {
        String host = System.getenv().getOrDefault("EXCHANGE_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("EXCHANGE_PORT", "9000"));
        return new UdpExchangeClient(new InetSocketAddress(host, port), timeout);
    }
}
