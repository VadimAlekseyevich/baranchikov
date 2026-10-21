package org.example.exchange.repository;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;

import java.math.BigDecimal;
import java.util.List;

public interface ExchangeRepository extends AutoCloseable {
    long insertOrder(
            String clientId,
            CurrencyPair pair,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice
    );

    StoredOrder findMatchingOrder(
            CurrencyPair pair,
            OrderSide incomingSide,
            BigDecimal incomingPrice
    );

    void updateRemaining(long orderId, BigDecimal remaining);

    long insertTrade(
            CurrencyPair pair,
            BigDecimal price,
            BigDecimal quantity,
            long buyOrderId,
            long sellOrderId,
            String buyerId,
            String sellerId
    );

    void insertPendingNotification(String clientId, long tradeId);

    List<PendingDelivery> loadPendingNotifications(String clientId);

    void deletePendingNotification(long notificationId);

    List<Order> getOpenOrders(CurrencyPair pair);

    List<Trade> getTrades();

    void commit();

    void rollback();

    @Override
    void close();

    record StoredOrder(
            long id,
            String clientId,
            BigDecimal limitPrice,
            BigDecimal remainingQuantity
    ) {
    }

    record PendingDelivery(
            long notificationId,
            TradeNotification notification
    ) {
    }
}
