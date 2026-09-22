package org.example.exchange;

import org.example.exchange.database.PersistentExchange;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentExchangeTest {
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    @TempDir
    Path tempDir;

    @Test
    void orderCanBePartiallyAndThenFullyFilledAndBothClientsAreNotified() {
        Queue<TradeNotification> buyerNotifications = new ConcurrentLinkedQueue<>();
        Queue<TradeNotification> sellerNotifications = new ConcurrentLinkedQueue<>();

        try (PersistentExchange exchange = exchange()) {
            exchange.connect("buyer", buyerNotifications::add);
            exchange.connect("seller", sellerNotifications::add);

            exchange.placeOrder("buyer", EUR_USD, OrderSide.BUY, bd("10"), bd("1.1000"));
            Order firstSell = exchange.placeOrder(
                    "seller", EUR_USD, OrderSide.SELL, bd("4"), bd("1.0900"));

            assertTrue(firstSell.isFilled());
            assertEquals(1, exchange.getTrades().size());
            assertEquals(0, bd("4").compareTo(exchange.getTrades().getFirst().quantity()));
            assertEquals(0, bd("1.1000").compareTo(exchange.getTrades().getFirst().price()));

            List<Order> afterPartialFill = exchange.getOpenOrders(EUR_USD);
            assertEquals(1, afterPartialFill.size());
            assertEquals(OrderSide.BUY, afterPartialFill.getFirst().side());
            assertEquals(0, bd("6").compareTo(afterPartialFill.getFirst().remainingQuantity()));

            exchange.placeOrder("seller", EUR_USD, OrderSide.SELL, bd("6"), bd("1.1000"));

            assertEquals(2, exchange.getTrades().size());
            assertTrue(exchange.getOpenOrders(EUR_USD).isEmpty());
            assertEquals(2, buyerNotifications.size());
            assertEquals(2, sellerNotifications.size());
        }
    }

    @Test
    void notificationIsStoredWhileClientIsOfflineAndDeliveredOnReconnect() {
        Queue<TradeNotification> firstSession = new ConcurrentLinkedQueue<>();
        Queue<TradeNotification> secondSession = new ConcurrentLinkedQueue<>();

        try (PersistentExchange exchange = exchange()) {
            exchange.connect("buyer", firstSession::add);
            exchange.placeOrder("buyer", EUR_USD, OrderSide.BUY, bd("5"), bd("1.1000"));
            exchange.disconnect("buyer");

            exchange.connect("seller", ignored -> { });
            exchange.placeOrder("seller", EUR_USD, OrderSide.SELL, bd("5"), bd("1.0900"));

            assertTrue(firstSession.isEmpty());
            exchange.connect("buyer", secondSession::add);

            assertEquals(1, secondSession.size());
            Trade delivered = secondSession.peek().trade();
            assertEquals("buyer", delivered.buyerId());
            assertEquals("seller", delivered.sellerId());
        }
    }

    @Test
    void betterPriceHasPriorityOverEarlierWorsePrice() {
        try (PersistentExchange exchange = exchange()) {
            exchange.placeOrder("seller-worse", EUR_USD, OrderSide.SELL, bd("1"), bd("1.1100"));
            exchange.placeOrder("seller-better", EUR_USD, OrderSide.SELL, bd("1"), bd("1.1000"));
            exchange.placeOrder("buyer", EUR_USD, OrderSide.BUY, bd("1"), bd("1.1200"));

            Trade trade = exchange.getTrades().getFirst();
            assertEquals("seller-better", trade.sellerId());
            assertEquals(0, bd("1.1000").compareTo(trade.price()));
        }
    }

    private PersistentExchange exchange() {
        return new PersistentExchange(tempDir.resolve("exchange"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
