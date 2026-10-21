package org.example.exchange.database;

import org.example.exchange.api.Exchange;
import org.example.exchange.api.NotificationListener;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.repository.h2.H2ExchangeRepository;
import org.example.exchange.service.ExchangeService;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

public final class PersistentExchange implements Exchange {
    private final ExchangeService delegate;

    public PersistentExchange(Path databaseFile) {
        delegate = new ExchangeService(new H2ExchangeRepository(databaseFile));
    }

    @Override
    public void connect(String clientId, NotificationListener listener) {
        delegate.connect(clientId, listener);
    }

    @Override
    public void disconnect(String clientId) {
        delegate.disconnect(clientId);
    }

    @Override
    public Order placeOrder(
            String clientId,
            CurrencyPair pair,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice
    ) {
        return delegate.placeOrder(clientId, pair, side, quantity, limitPrice);
    }

    @Override
    public List<Order> getOpenOrders(CurrencyPair pair) {
        return delegate.getOpenOrders(pair);
    }

    @Override
    public List<Trade> getTrades() {
        return delegate.getTrades();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
