package org.example.exchange.api;

import org.example.exchange.model.TradeNotification;

@FunctionalInterface
public interface NotificationListener {
    void onTrade(TradeNotification notification);
}
