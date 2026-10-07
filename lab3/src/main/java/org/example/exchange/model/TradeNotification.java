package org.example.exchange.model;

import java.util.Objects;

public record TradeNotification(String clientId, Trade trade) {
    public TradeNotification {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(trade, "trade must not be null");
        if (!clientId.equals(trade.buyerId()) && !clientId.equals(trade.sellerId())) {
            throw new IllegalArgumentException("Notification recipient must participate in the trade");
        }
    }
}
