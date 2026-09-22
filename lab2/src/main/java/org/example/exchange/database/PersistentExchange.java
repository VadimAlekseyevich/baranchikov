package org.example.exchange.database;

import org.example.exchange.api.Exchange;
import org.example.exchange.api.NotificationListener;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class PersistentExchange implements Exchange, AutoCloseable {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, NotificationListener> onlineClients = new HashMap<>();
    private final Connection connection;
    private boolean closed;

    public PersistentExchange(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile must not be null");

        try {
            Path absolute = databaseFile.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            connection = DriverManager.getConnection(
                    "jdbc:h2:file:" + absolute + ";DB_CLOSE_ON_EXIT=FALSE",
                    "sa",
                    ""
            );
            connection.setAutoCommit(false);
            initializeSchema();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open exchange database", e);
        }
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

            long orderId = insertOrder(clientId, pair, side, quantity, limitPrice);
            BigDecimal remaining = quantity;
            List<TradeNotification> onlineNotifications = new ArrayList<>();

            while (remaining.signum() > 0) {
                StoredOrder existing = findMatchingOrder(pair, side, limitPrice);
                if (existing == null) {
                    break;
                }

                BigDecimal tradedQuantity = remaining.min(existing.remainingQuantity());
                remaining = remaining.subtract(tradedQuantity);
                BigDecimal existingRemaining = existing.remainingQuantity().subtract(tradedQuantity);

                updateRemaining(orderId, remaining);
                updateRemaining(existing.id(), existingRemaining);

                long buyOrderId = side == OrderSide.BUY ? orderId : existing.id();
                long sellOrderId = side == OrderSide.SELL ? orderId : existing.id();
                String buyerId = side == OrderSide.BUY ? clientId : existing.clientId();
                String sellerId = side == OrderSide.SELL ? clientId : existing.clientId();

                long tradeId = insertTrade(
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

            connection.commit();

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
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("Cannot place order", e);
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

            String sql = """
                    SELECT id, client_id, side, limit_price,
                           original_quantity, remaining_quantity
                    FROM exchange_orders
                    WHERE base_currency = ?
                      AND quote_currency = ?
                      AND remaining_quantity > 0
                    ORDER BY id
                    """;

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, pair.base());
                statement.setString(2, pair.quote());

                try (ResultSet rs = statement.executeQuery()) {
                    List<Order> result = new ArrayList<>();

                    while (rs.next()) {
                        result.add(new Order(
                                rs.getLong("id"),
                                rs.getString("client_id"),
                                pair,
                                OrderSide.valueOf(rs.getString("side")),
                                rs.getBigDecimal("limit_price"),
                                rs.getBigDecimal("original_quantity"),
                                rs.getBigDecimal("remaining_quantity")
                        ));
                    }

                    return List.copyOf(result);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read open orders", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Trade> getTrades() {
        lock.lock();
        try {
            ensureOpen();

            String sql = """
                    SELECT id, base_currency, quote_currency, price, quantity,
                           buy_order_id, sell_order_id, buyer_id, seller_id
                    FROM trades
                    ORDER BY id
                    """;

            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                List<Trade> result = new ArrayList<>();

                while (rs.next()) {
                    result.add(readTrade(rs));
                }

                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read trades", e);
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

            try {
                connection.commit();
                connection.close();
                closed = true;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot close exchange database", e);
            }
        } finally {
            lock.unlock();
        }
    }

    private void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS exchange_orders (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        client_id VARCHAR(255) NOT NULL,
                        base_currency VARCHAR(32) NOT NULL,
                        quote_currency VARCHAR(32) NOT NULL,
                        side VARCHAR(4) NOT NULL,
                        limit_price DECIMAL(38, 18) NOT NULL,
                        original_quantity DECIMAL(38, 18) NOT NULL,
                        remaining_quantity DECIMAL(38, 18) NOT NULL
                    )
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trades (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        base_currency VARCHAR(32) NOT NULL,
                        quote_currency VARCHAR(32) NOT NULL,
                        price DECIMAL(38, 18) NOT NULL,
                        quantity DECIMAL(38, 18) NOT NULL,
                        buy_order_id BIGINT NOT NULL,
                        sell_order_id BIGINT NOT NULL,
                        buyer_id VARCHAR(255) NOT NULL,
                        seller_id VARCHAR(255) NOT NULL
                    )
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pending_notifications (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        client_id VARCHAR(255) NOT NULL,
                        trade_id BIGINT NOT NULL,
                        CONSTRAINT fk_pending_trade
                            FOREIGN KEY (trade_id) REFERENCES trades(id)
                    )
                    """);
        }

        connection.commit();
    }

    private long insertOrder(
            String clientId,
            CurrencyPair pair,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice
    ) throws SQLException {
        String sql = """
                INSERT INTO exchange_orders(
                    client_id,
                    base_currency,
                    quote_currency,
                    side,
                    limit_price,
                    original_quantity,
                    remaining_quantity
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, clientId);
            statement.setString(2, pair.base());
            statement.setString(3, pair.quote());
            statement.setString(4, side.name());
            statement.setBigDecimal(5, limitPrice);
            statement.setBigDecimal(6, quantity);
            statement.setBigDecimal(7, quantity);
            statement.executeUpdate();

            return generatedId(statement, "order");
        }
    }

    private StoredOrder findMatchingOrder(
            CurrencyPair pair,
            OrderSide incomingSide,
            BigDecimal incomingPrice
    ) throws SQLException {
        String sql = incomingSide == OrderSide.BUY
                ? """
                    SELECT id, client_id, limit_price, remaining_quantity
                    FROM exchange_orders
                    WHERE base_currency = ?
                      AND quote_currency = ?
                      AND side = 'SELL'
                      AND remaining_quantity > 0
                      AND limit_price <= ?
                    ORDER BY id
                    FETCH FIRST 1 ROW ONLY
                    """
                : """
                    SELECT id, client_id, limit_price, remaining_quantity
                    FROM exchange_orders
                    WHERE base_currency = ?
                      AND quote_currency = ?
                      AND side = 'BUY'
                      AND remaining_quantity > 0
                      AND limit_price >= ?
                    ORDER BY id
                    FETCH FIRST 1 ROW ONLY
                    """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pair.base());
            statement.setString(2, pair.quote());
            statement.setBigDecimal(3, incomingPrice);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                return new StoredOrder(
                        rs.getLong("id"),
                        rs.getString("client_id"),
                        rs.getBigDecimal("limit_price"),
                        rs.getBigDecimal("remaining_quantity")
                );
            }
        }
    }

    private void updateRemaining(long orderId, BigDecimal remaining) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE exchange_orders SET remaining_quantity = ? WHERE id = ?")) {
            statement.setBigDecimal(1, remaining);
            statement.setLong(2, orderId);
            statement.executeUpdate();
        }
    }

    private long insertTrade(
            CurrencyPair pair,
            BigDecimal price,
            BigDecimal quantity,
            long buyOrderId,
            long sellOrderId,
            String buyerId,
            String sellerId
    ) throws SQLException {
        String sql = """
                INSERT INTO trades(
                    base_currency,
                    quote_currency,
                    price,
                    quantity,
                    buy_order_id,
                    sell_order_id,
                    buyer_id,
                    seller_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, pair.base());
            statement.setString(2, pair.quote());
            statement.setBigDecimal(3, price);
            statement.setBigDecimal(4, quantity);
            statement.setLong(5, buyOrderId);
            statement.setLong(6, sellOrderId);
            statement.setString(7, buyerId);
            statement.setString(8, sellerId);
            statement.executeUpdate();

            return generatedId(statement, "trade");
        }
    }

    private void prepareNotification(
            String clientId,
            Trade trade,
            List<TradeNotification> onlineNotifications
    ) throws SQLException {
        TradeNotification notification = new TradeNotification(clientId, trade);

        if (onlineClients.containsKey(clientId)) {
            onlineNotifications.add(notification);
        } else {
            insertPendingNotification(clientId, trade.id());
        }
    }

    private void insertPendingNotification(String clientId, long tradeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pending_notifications(client_id, trade_id) VALUES (?, ?)")) {
            statement.setString(1, clientId);
            statement.setLong(2, tradeId);
            statement.executeUpdate();
        }
    }

    private void deliverPendingNotifications(
            String clientId,
            NotificationListener listener
    ) {
        try {
            List<PendingDelivery> pending = loadPendingNotifications(clientId);

            for (PendingDelivery delivery : pending) {
                listener.onTrade(delivery.notification());
                deletePendingNotification(delivery.notificationId());
                connection.commit();
            }
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("Cannot deliver pending notifications", e);
        }
    }

    private List<PendingDelivery> loadPendingNotifications(String clientId)
            throws SQLException {
        String sql = """
                SELECT n.id AS notification_id,
                       t.id,
                       t.base_currency,
                       t.quote_currency,
                       t.price,
                       t.quantity,
                       t.buy_order_id,
                       t.sell_order_id,
                       t.buyer_id,
                       t.seller_id
                FROM pending_notifications n
                JOIN trades t ON t.id = n.trade_id
                WHERE n.client_id = ?
                ORDER BY n.id
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);

            try (ResultSet rs = statement.executeQuery()) {
                List<PendingDelivery> result = new ArrayList<>();

                while (rs.next()) {
                    result.add(new PendingDelivery(
                            rs.getLong("notification_id"),
                            new TradeNotification(clientId, readTrade(rs))
                    ));
                }

                return result;
            }
        }
    }

    private void deletePendingNotification(long notificationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM pending_notifications WHERE id = ?")) {
            statement.setLong(1, notificationId);
            statement.executeUpdate();
        }
    }

    private static Trade readTrade(ResultSet rs) throws SQLException {
        return new Trade(
                rs.getLong("id"),
                new CurrencyPair(
                        rs.getString("base_currency"),
                        rs.getString("quote_currency")
                ),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("quantity"),
                rs.getLong("buy_order_id"),
                rs.getLong("sell_order_id"),
                rs.getString("buyer_id"),
                rs.getString("seller_id")
        );
    }

    private static long generatedId(
            PreparedStatement statement,
            String entity
    ) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated id for " + entity);
            }

            return keys.getLong(1);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Exchange is closed");
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Original exception is more important.
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

    private record StoredOrder(
            long id,
            String clientId,
            BigDecimal limitPrice,
            BigDecimal remainingQuantity
    ) {
    }

    private record PendingDelivery(
            long notificationId,
            TradeNotification notification
    ) {
    }
}
