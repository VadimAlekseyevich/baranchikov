package org.example.exchange.service;

import org.example.exchange.api.Exchange;
import org.example.exchange.api.NotificationListener;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;
import org.example.exchange.repository.ExchangeRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class ExchangeService implements Exchange {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, NotificationListener> onlineClients = new HashMap<>();
    private final ExchangeRepository repository;
    private boolean closed;

    public ExchangeService(ExchangeRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public void connect(String clientId, NotificationListener listener) {
        requireClientId(clientId);
        Objects.requireNonNull(listener, "listener must not be null");

        lock.lock();
        try {
            ensureOpen();
            onlineClients.put(clientId, listener);
            deliverPendingNotifications(clientId, listener);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void disconnect(String clientId) {
        requireClientId(clientId);

        lock.lock();
        try {
            ensureOpen();
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

        lock.lock();
        try {
            ensureOpen();

            long orderId;
            BigDecimal remaining = quantity;
            List<TradeNotification> onlineNotifications = new ArrayList<>();

            try {
                orderId = repository.insertOrder(
                        clientId,
                        pair,
                        side,
                        quantity,
                        limitPrice
                );

                while (remaining.signum() > 0) {
                    ExchangeRepository.StoredOrder existing =
                            repository.findMatchingOrder(pair, side, limitPrice);
                    if (existing == null) {
                        break;
                    }

                    BigDecimal tradedQuantity =
                            remaining.min(existing.remainingQuantity());
                    remaining = remaining.subtract(tradedQuantity);
                    BigDecimal existingRemaining =
                            existing.remainingQuantity().subtract(tradedQuantity);

                    repository.updateRemaining(orderId, remaining);
                    repository.updateRemaining(existing.id(), existingRemaining);

                    long buyOrderId =
                            side == OrderSide.BUY ? orderId : existing.id();
                    long sellOrderId =
                            side == OrderSide.SELL ? orderId : existing.id();
                    String buyerId =
                            side == OrderSide.BUY ? clientId : existing.clientId();
                    String sellerId =
                            side == OrderSide.SELL ? clientId : existing.clientId();

                    long tradeId = repository.insertTrade(
                            pair,
                            existing.limitPrice(),
                            tradedQuantity,
                            buyOrderId,
                            sellOrderId,
                            buyerId,
                            sellerId
                    );

                    Trade trade = new Trade(
                            tradeId,
                            pair,
                            existing.limitPrice(),
                            tradedQuantity,
                            buyOrderId,
                            sellOrderId,
                            buyerId,
                            sellerId
                    );

                    prepareNotification(buyerId, trade, onlineNotifications);
                    prepareNotification(sellerId, trade, onlineNotifications);
                }

                repository.commit();
            } catch (RuntimeException error) {
                rollbackQuietly(error);
                throw error;
            }

            for (TradeNotification notification : onlineNotifications) {
                NotificationListener listener = onlineClients.get(notification.clientId());
                if (listener != null) {
                    listener.onTrade(notification);
                }
            }

            return new Order(
                    orderId,
                    clientId,
                    pair,
                    side,
                    limitPrice,
                    quantity,
                    remaining
            );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Order> getOpenOrders(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair must not be null");

        lock.lock();
        try {
            ensureOpen();
            return repository.getOpenOrders(pair);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Trade> getTrades() {
        lock.lock();
        try {
            ensureOpen();
            return repository.getTrades();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            repository.close();
            closed = true;
            onlineClients.clear();
        } finally {
            lock.unlock();
        }
    }

    private void prepareNotification(
            String clientId,
            Trade trade,
            List<TradeNotification> onlineNotifications
    ) {
        TradeNotification notification = new TradeNotification(clientId, trade);

        if (onlineClients.containsKey(clientId)) {
            onlineNotifications.add(notification);
        } else {
            repository.insertPendingNotification(clientId, trade.id());
        }
    }

    private void deliverPendingNotifications(
            String clientId,
            NotificationListener listener
    ) {
        List<ExchangeRepository.PendingDelivery> pending =
                repository.loadPendingNotifications(clientId);

        for (ExchangeRepository.PendingDelivery delivery : pending) {
            listener.onTrade(delivery.notification());
            try {
                repository.deletePendingNotification(delivery.notificationId());
                repository.commit();
            } catch (RuntimeException error) {
                rollbackQuietly(error);
                throw error;
            }
        }
    }

    private void rollbackQuietly(RuntimeException originalError) {
        try {
            repository.rollback();
        } catch (RuntimeException rollbackError) {
            originalError.addSuppressed(rollbackError);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Exchange is closed");
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
}
