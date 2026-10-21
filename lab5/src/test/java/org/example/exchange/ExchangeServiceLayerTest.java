package org.example.exchange;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;
import org.example.exchange.repository.ExchangeRepository;
import org.example.exchange.service.ExchangeService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeServiceLayerTest {
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    @Test
    void businessLayerMatchesOrdersWithoutUdpOrH2() {
        InMemoryRepository repository = new InMemoryRepository();
        Queue<TradeNotification> buyerNotifications = new ConcurrentLinkedQueue<>();
        Queue<TradeNotification> sellerNotifications = new ConcurrentLinkedQueue<>();

        try (ExchangeService service = new ExchangeService(repository)) {
            service.connect("buyer", buyerNotifications::add);
            service.connect("seller", sellerNotifications::add);

            service.placeOrder(
                    "buyer",
                    EUR_USD,
                    OrderSide.BUY,
                    bd("10"),
                    bd("1.1000")
            );

            Order sell = service.placeOrder(
                    "seller",
                    EUR_USD,
                    OrderSide.SELL,
                    bd("4"),
                    bd("1.0900")
            );

            assertTrue(sell.isFilled());
            assertEquals(1, service.getTrades().size());

            List<Order> openOrders = service.getOpenOrders(EUR_USD);
            assertEquals(1, openOrders.size());
            assertEquals(OrderSide.BUY, openOrders.getFirst().side());
            assertEquals(
                    0,
                    bd("6").compareTo(openOrders.getFirst().remainingQuantity())
            );

            assertEquals(1, buyerNotifications.size());
            assertEquals(1, sellerNotifications.size());
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class InMemoryRepository implements ExchangeRepository {
        private final AtomicLong orderSequence = new AtomicLong();
        private final AtomicLong tradeSequence = new AtomicLong();
        private final Map<Long, MutableOrder> orders = new LinkedHashMap<>();
        private final List<Trade> trades = new ArrayList<>();

        @Override
        public long insertOrder(
                String clientId,
                CurrencyPair pair,
                OrderSide side,
                BigDecimal quantity,
                BigDecimal limitPrice
        ) {
            long id = orderSequence.incrementAndGet();
            orders.put(id, new MutableOrder(
                    id,
                    clientId,
                    pair,
                    side,
                    limitPrice,
                    quantity,
                    quantity
            ));
            return id;
        }

        @Override
        public StoredOrder findMatchingOrder(
                CurrencyPair pair,
                OrderSide incomingSide,
                BigDecimal incomingPrice
        ) {
            for (MutableOrder order : orders.values()) {
                if (!order.pair.equals(pair)
                        || order.side == incomingSide
                        || order.remaining.signum() <= 0) {
                    continue;
                }

                boolean priceMatches = incomingSide == OrderSide.BUY
                        ? order.limitPrice.compareTo(incomingPrice) <= 0
                        : order.limitPrice.compareTo(incomingPrice) >= 0;

                if (priceMatches) {
                    return new StoredOrder(
                            order.id,
                            order.clientId,
                            order.limitPrice,
                            order.remaining
                    );
                }
            }
            return null;
        }

        @Override
        public void updateRemaining(long orderId, BigDecimal remaining) {
            MutableOrder order = orders.get(orderId);
            if (order == null) {
                throw new IllegalArgumentException("Unknown order " + orderId);
            }
            order.remaining = remaining;
        }

        @Override
        public long insertTrade(
                CurrencyPair pair,
                BigDecimal price,
                BigDecimal quantity,
                long buyOrderId,
                long sellOrderId,
                String buyerId,
                String sellerId
        ) {
            long id = tradeSequence.incrementAndGet();
            trades.add(new Trade(
                    id,
                    pair,
                    price,
                    quantity,
                    buyOrderId,
                    sellOrderId,
                    buyerId,
                    sellerId
            ));
            return id;
        }

        @Override
        public void insertPendingNotification(String clientId, long tradeId) {
            throw new AssertionError("Both clients are online in this test");
        }

        @Override
        public List<PendingDelivery> loadPendingNotifications(String clientId) {
            return List.of();
        }

        @Override
        public void deletePendingNotification(long notificationId) {
        }

        @Override
        public List<Order> getOpenOrders(CurrencyPair pair) {
            List<Order> result = new ArrayList<>();
            for (MutableOrder order : orders.values()) {
                if (order.pair.equals(pair) && order.remaining.signum() > 0) {
                    result.add(new Order(
                            order.id,
                            order.clientId,
                            order.pair,
                            order.side,
                            order.limitPrice,
                            order.originalQuantity,
                            order.remaining
                    ));
                }
            }
            return List.copyOf(result);
        }

        @Override
        public List<Trade> getTrades() {
            return List.copyOf(trades);
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }

        @Override
        public void close() {
        }

        private static final class MutableOrder {
            private final long id;
            private final String clientId;
            private final CurrencyPair pair;
            private final OrderSide side;
            private final BigDecimal limitPrice;
            private final BigDecimal originalQuantity;
            private BigDecimal remaining;

            private MutableOrder(
                    long id,
                    String clientId,
                    CurrencyPair pair,
                    OrderSide side,
                    BigDecimal limitPrice,
                    BigDecimal originalQuantity,
                    BigDecimal remaining
            ) {
                this.id = id;
                this.clientId = clientId;
                this.pair = pair;
                this.side = side;
                this.limitPrice = limitPrice;
                this.originalQuantity = originalQuantity;
                this.remaining = remaining;
            }
        }
    }
}
