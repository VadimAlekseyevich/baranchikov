package org.example.exchange;

import org.example.exchange.database.PersistentExchange;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.OrderSide;

import java.math.BigDecimal;
import java.nio.file.Path;

public final class AbruptTerminationWriter {
    private AbruptTerminationWriter() {
    }

    public static void main(String[] args) {
        try {
            PersistentExchange exchange = new PersistentExchange(Path.of(args[0]));
            CurrencyPair pair = new CurrencyPair("EUR", "USD");
            exchange.placeOrder("buyer", pair, OrderSide.BUY, new BigDecimal("10"), new BigDecimal("1.1000"));
            exchange.placeOrder("seller", pair, OrderSide.SELL, new BigDecimal("3"), new BigDecimal("1.0900"));
            Runtime.getRuntime().halt(0);
        } catch (Throwable error) {
            error.printStackTrace();
            Runtime.getRuntime().halt(2);
        }
    }
}
