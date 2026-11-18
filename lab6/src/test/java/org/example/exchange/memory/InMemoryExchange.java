package org.example.exchange.memory;

import org.example.exchange.api.Exchange;
import org.example.exchange.api.NotificationListener;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;

public final class InMemoryExchange implements Exchange {
    private static final Comparator<MutableOrder> BUY_PRIORITY =
            Comparator.comparing((MutableOrder order) -> order.limitPrice, Comparator.reverseOrder())
                    .thenComparingLong(order -> order.id);

    private static final Comparator<MutableOrder> SELL_PRIORITY =
            Comparator.comparing((MutableOrder order) -> order.limitPrice)
                    .thenComparingLong(order -> order.id);

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<CurrencyPair, OrderBook> books = new HashMap<>();
    private final Map<String, NotificationListener> onlineClients = new HashMap<>();
    private final Map<String, Queue<TradeNotification>> pendingNotifications = new HashMap<>();
    private final List<Trade> trades = new ArrayList<>();

    private long nextOrderId = 1;
    private long nextTradeId = 1;

    @Override
    public void connect(String clientId, NotificationListener listener) {
        requireClientId(clientId);
        Objects.requireNonNull(listener, "listener must not be null");

        List<TradeNotification> pending;
        lock.lock();
        try {
            onlineClients.put(clientId, listener);
            Queue<TradeNotification> queue = pendingNotifications.remove(clientId);
            pending = queue == null ? List.of() : List.copyOf(queue);
        } finally {
            lock.unlock();
        }

        pending.forEach(listener::onTrade);
    }

    @Override
    public void disconnect(String clientId) {
        requireClientId(clientId);
        lock.lock();
        try {
            onlineClients.remove(clientId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Order placeOrder(
            String clientId,
            CurrencyPair pair,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice
    ) {
        requireClientId(clientId);
        Objects.requireNonNull(pair, "pair must not be null");
        Objects.requireNonNull(side, "side must not be null");
        requirePositive(quantity, "quantity");
        requirePositive(limitPrice, "limitPrice");

        List<Delivery> deliveries = new ArrayList<>();
        Order result;

        lock.lock();
        try {
            OrderBook book = books.computeIfAbsent(pair, ignored -> new OrderBook());
            MutableOrder incoming = new MutableOrder(
                    nextOrderId++,
                    clientId,
                    pair,
                    side,
                    limitPrice,
                    quantity,
                    quantity
            );

            match(incoming, book, deliveries);
            if (incoming.remainingQuantity.signum() > 0) {
                book.queueFor(side).add(incoming);
            }
            result = incoming.snapshot();
        } finally {
            lock.unlock();
        }

        deliveries.forEach(Delivery::deliver);
        return result;
    }

    @Override
    public List<Order> getOpenOrders(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair must not be null");
        lock.lock();
        try {
            OrderBook book = books.get(pair);
            if (book == null) {
                return List.of();
            }
            List<Order> result = new ArrayList<>(book.buys.size() + book.sells.size());
            book.buys.stream().map(MutableOrder::snapshot).forEach(result::add);
            book.sells.stream().map(MutableOrder::snapshot).forEach(result::add);
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Trade> getTrades() {
        lock.lock();
        try {
            return List.copyOf(trades);
        } finally {
            lock.unlock();
        }
    }

    private void match(MutableOrder incoming, OrderBook book, List<Delivery> deliveries) {
        PriorityQueue<MutableOrder> opposite = book.oppositeQueueFor(incoming.side);

        while (incoming.remainingQuantity.signum() > 0 && !opposite.isEmpty()) {
            MutableOrder resting = opposite.peek();
            if (!pricesCross(incoming, resting)) {
                return;
            }

            BigDecimal executedQuantity = incoming.remainingQuantity.min(resting.remainingQuantity);
            BigDecimal tradePrice = resting.limitPrice;

            incoming.remainingQuantity = incoming.remainingQuantity.subtract(executedQuantity);
            resting.remainingQuantity = resting.remainingQuantity.subtract(executedQuantity);

            MutableOrder buy = incoming.side == OrderSide.BUY ? incoming : resting;
            MutableOrder sell = incoming.side == OrderSide.SELL ? incoming : resting;

            Trade trade = new Trade(
                    nextTradeId++,
                    incoming.pair,
                    tradePrice,
                    executedQuantity,
                    buy.id,
                    sell.id,
                    buy.clientId,
                    sell.clientId
            );
            trades.add(trade);

            scheduleNotification(buy.clientId, trade, deliveries);
            scheduleNotification(sell.clientId, trade, deliveries);

            if (resting.remainingQuantity.signum() == 0) {
                opposite.poll();
            }
        }
    }

    private boolean pricesCross(MutableOrder incoming, MutableOrder resting) {
        return switch (incoming.side) {
            case BUY -> incoming.limitPrice.compareTo(resting.limitPrice) >= 0;
            case SELL -> incoming.limitPrice.compareTo(resting.limitPrice) <= 0;
        };
    }

    private void scheduleNotification(String clientId, Trade trade, List<Delivery> deliveries) {
        TradeNotification notification = new TradeNotification(clientId, trade);
        NotificationListener listener = onlineClients.get(clientId);
        if (listener == null) {
            pendingNotifications
                    .computeIfAbsent(clientId, ignored -> new ArrayDeque<>())
                    .add(notification);
        } else {
            deliveries.add(new Delivery(listener, notification));
        }
    }

    private static void requireClientId(String clientId) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        if (clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static final class OrderBook {
        private final PriorityQueue<MutableOrder> buys = new PriorityQueue<>(BUY_PRIORITY);
        private final PriorityQueue<MutableOrder> sells = new PriorityQueue<>(SELL_PRIORITY);

        private PriorityQueue<MutableOrder> queueFor(OrderSide side) {
            return side == OrderSide.BUY ? buys : sells;
        }

        private PriorityQueue<MutableOrder> oppositeQueueFor(OrderSide side) {
            return side == OrderSide.BUY ? sells : buys;
        }
    }

    private static final class MutableOrder {
        private final long id;
        private final String clientId;
        private final CurrencyPair pair;
        private final OrderSide side;
        private final BigDecimal limitPrice;
        private final BigDecimal originalQuantity;
        private BigDecimal remainingQuantity;

        private MutableOrder(
                long id,
                String clientId,
                CurrencyPair pair,
                OrderSide side,
                BigDecimal limitPrice,
                BigDecimal originalQuantity,
                BigDecimal remainingQuantity
        ) {
            this.id = id;
            this.clientId = clientId;
            this.pair = pair;
            this.side = side;
            this.limitPrice = limitPrice;
            this.originalQuantity = originalQuantity;
            this.remainingQuantity = remainingQuantity;
        }

        private Order snapshot() {
            return new Order(
                    id,
                    clientId,
                    pair,
                    side,
                    limitPrice,
                    originalQuantity,
                    remainingQuantity
            );
        }
    }

    private record Delivery(NotificationListener listener, TradeNotification notification) {
        private void deliver() {
            listener.onTrade(notification);
        }
    }
}
