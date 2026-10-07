package org.example.exchange.api;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;

import java.math.BigDecimal;
import java.util.List;

public interface Exchange {
    void connect(String clientId, NotificationListener listener);

    void disconnect(String clientId);

    Order placeOrder(
            String clientId,
            CurrencyPair pair,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice
    );

    List<Order> getOpenOrders(CurrencyPair pair);

    List<Trade> getTrades();
}
