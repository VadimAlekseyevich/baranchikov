package org.example.exchange;

import org.example.exchange.api.Exchange;
import org.example.exchange.memory.InMemoryExchange;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryExchangeTest {
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    @Test
    void orderCanBePartiallyAndThenFullyFilledAndBothClientsAreNotified() {
        Exchange exchange = new InMemoryExchange();
        Queue<TradeNotification> buyerNotifications = new ConcurrentLinkedQueue<>();
        Queue<TradeNotification> sellerNotifications = new ConcurrentLinkedQueue<>();

        exchange.connect("buyer", buyerNotifications::add);
        exchange.connect("seller", sellerNotifications::add);

        exchange.placeOrder(
                "buyer", EUR_USD, OrderSide.BUY,
                bd("10"), bd("1.1000")
        );

        Order firstSell = exchange.placeOrder(
                "seller", EUR_USD, OrderSide.SELL,
                bd("4"), bd("1.0900")
        );

        assertTrue(firstSell.isFilled());
        assertEquals(1, exchange.getTrades().size());
        assertEquals(0, bd("4").compareTo(exchange.getTrades().getFirst().quantity()));
        assertEquals(0, bd("1.1000").compareTo(exchange.getTrades().getFirst().price()));

        List<Order> afterPartialFill = exchange.getOpenOrders(EUR_USD);
        assertEquals(1, afterPartialFill.size());
        assertEquals(OrderSide.BUY, afterPartialFill.getFirst().side());
        assertEquals(0, bd("6").compareTo(afterPartialFill.getFirst().remainingQuantity()));

        exchange.placeOrder(
                "seller", EUR_USD, OrderSide.SELL,
                bd("6"), bd("1.1000")
        );

        assertEquals(2, exchange.getTrades().size());
        assertTrue(exchange.getOpenOrders(EUR_USD).isEmpty());
        assertEquals(2, buyerNotifications.size());
        assertEquals(2, sellerNotifications.size());
    }

    @Test
    void notificationIsStoredWhileClientIsOfflineAndDeliveredOnReconnect() {
        Exchange exchange = new InMemoryExchange();
        Queue<TradeNotification> firstSessionNotifications = new ConcurrentLinkedQueue<>();
        Queue<TradeNotification> secondSessionNotifications = new ConcurrentLinkedQueue<>();

        exchange.connect("buyer", firstSessionNotifications::add);
        exchange.placeOrder(
                "buyer", EUR_USD, OrderSide.BUY,
                bd("5"), bd("1.1000")
        );
        exchange.disconnect("buyer");

        exchange.connect("seller", ignored -> { });
        exchange.placeOrder(
                "seller", EUR_USD, OrderSide.SELL,
                bd("5"), bd("1.0900")
        );

        assertTrue(firstSessionNotifications.isEmpty());

        exchange.connect("buyer", secondSessionNotifications::add);

        assertEquals(1, secondSessionNotifications.size());
        Trade deliveredTrade = secondSessionNotifications.peek().trade();
        assertEquals("buyer", deliveredTrade.buyerId());
        assertEquals("seller", deliveredTrade.sellerId());
    }

    @Test
    void betterPriceHasPriorityOverEarlierWorsePrice() {
        Exchange exchange = new InMemoryExchange();

        exchange.placeOrder("seller-worse", EUR_USD, OrderSide.SELL, bd("1"), bd("1.1100"));
        exchange.placeOrder("seller-better", EUR_USD, OrderSide.SELL, bd("1"), bd("1.1000"));

        exchange.placeOrder("buyer", EUR_USD, OrderSide.BUY, bd("1"), bd("1.1200"));

        Trade trade = exchange.getTrades().getFirst();
        assertEquals("seller-better", trade.sellerId());
        assertEquals(0, bd("1.1000").compareTo(trade.price()));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
