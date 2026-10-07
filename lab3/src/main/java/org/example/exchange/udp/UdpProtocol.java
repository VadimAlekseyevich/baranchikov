package org.example.exchange.udp;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class UdpProtocol {
    static final int MAX_DATAGRAM_SIZE = 65_507;
    static final int ORDER_FIELD_COUNT = 8;
    static final int TRADE_FIELD_COUNT = 9;

    private UdpProtocol() {
    }

    static String[] split(String message) {
        return message.split("\\|", -1);
    }

    static String join(List<String> fields) {
        return String.join("|", fields);
    }

    static String join(String... fields) {
        return String.join("|", fields);
    }

    static void requireProtocolField(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.indexOf('|') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(fieldName + " contains a reserved protocol character");
        }
    }

    static String safeErrorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        message = message.replace('|', '/').replace('\r', ' ').replace('\n', ' ');
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    static void appendOrder(List<String> fields, Order order) {
        fields.add(Long.toString(order.id()));
        fields.add(order.clientId());
        fields.add(order.pair().base());
        fields.add(order.pair().quote());
        fields.add(order.side().name());
        fields.add(order.limitPrice().toPlainString());
        fields.add(order.originalQuantity().toPlainString());
        fields.add(order.remainingQuantity().toPlainString());
    }

    static Order readOrder(String[] fields, int offset) {
        requireAvailable(fields, offset, ORDER_FIELD_COUNT, "order");
        return new Order(
                Long.parseLong(fields[offset]),
                fields[offset + 1],
                new CurrencyPair(fields[offset + 2], fields[offset + 3]),
                OrderSide.valueOf(fields[offset + 4]),
                new BigDecimal(fields[offset + 5]),
                new BigDecimal(fields[offset + 6]),
                new BigDecimal(fields[offset + 7])
        );
    }

    static void appendTrade(List<String> fields, Trade trade) {
        fields.add(Long.toString(trade.id()));
        fields.add(trade.pair().base());
        fields.add(trade.pair().quote());
        fields.add(trade.price().toPlainString());
        fields.add(trade.quantity().toPlainString());
        fields.add(Long.toString(trade.buyOrderId()));
        fields.add(Long.toString(trade.sellOrderId()));
        fields.add(trade.buyerId());
        fields.add(trade.sellerId());
    }

    static Trade readTrade(String[] fields, int offset) {
        requireAvailable(fields, offset, TRADE_FIELD_COUNT, "trade");
        return new Trade(
                Long.parseLong(fields[offset]),
                new CurrencyPair(fields[offset + 1], fields[offset + 2]),
                new BigDecimal(fields[offset + 3]),
                new BigDecimal(fields[offset + 4]),
                Long.parseLong(fields[offset + 5]),
                Long.parseLong(fields[offset + 6]),
                fields[offset + 7],
                fields[offset + 8]
        );
    }

    static String tradeEvent(TradeNotification notification) {
        List<String> fields = new ArrayList<>();
        fields.add("EVENT");
        fields.add("TRADE");
        fields.add(notification.clientId());
        appendTrade(fields, notification.trade());
        return join(fields);
    }

    private static void requireAvailable(String[] fields, int offset, int count, String entity) {
        if (offset < 0 || fields.length < offset + count) {
            throw new IllegalArgumentException("Malformed " + entity + " payload");
        }
    }
}
