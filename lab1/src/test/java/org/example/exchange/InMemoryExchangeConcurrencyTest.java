package org.example.exchange;

import org.example.exchange.api.Exchange;
import org.example.exchange.memory.InMemoryExchange;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryExchangeConcurrencyTest {
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    @Test
    void clientsCanPlaceOrdersFromDifferentThreads() throws Exception {
        int pairs = 100;
        Exchange exchange = new InMemoryExchange();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < pairs; i++) {
                String seller = "seller-" + i;
                String buyer = "buyer-" + i;

                futures.add(pool.submit(() -> {
                    await(start);
                    exchange.placeOrder(
                            seller, EUR_USD, OrderSide.SELL,
                            BigDecimal.ONE, new BigDecimal("1.1000")
                    );
                }));

                futures.add(pool.submit(() -> {
                    await(start);
                    exchange.placeOrder(
                            buyer, EUR_USD, OrderSide.BUY,
                            BigDecimal.ONE, new BigDecimal("1.1000")
                    );
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(pairs, exchange.getTrades().size());
        assertTrue(exchange.getOpenOrders(EUR_USD).isEmpty());
        assertEquals(
                0,
                BigDecimal.valueOf(pairs).compareTo(
                        exchange.getTrades().stream()
                                .map(trade -> trade.quantity())
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                )
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test start", e);
        }
    }
}
