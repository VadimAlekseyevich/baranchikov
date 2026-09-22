package org.example;

import org.example.exchange.database.PersistentExchange;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.OrderSide;

import java.math.BigDecimal;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        Path database = args.length == 0 ? Path.of("data", "exchange") : Path.of(args[0]);
        CurrencyPair eurUsd = new CurrencyPair("EUR", "USD");

        try (PersistentExchange exchange = new PersistentExchange(database)) {
            System.out.println("Recovered trades: " + exchange.getTrades().size());
            System.out.println("Recovered open EUR/USD orders: " + exchange.getOpenOrders(eurUsd).size());

            exchange.connect("buyer", notification ->
                    System.out.println("buyer notification: " + notification.trade()));
            exchange.connect("seller", notification ->
                    System.out.println("seller notification: " + notification.trade()));

            exchange.placeOrder(
                    "buyer",
                    eurUsd,
                    OrderSide.BUY,
                    new BigDecimal("10"),
                    new BigDecimal("1.1000")
            );
            exchange.placeOrder(
                    "seller",
                    eurUsd,
                    OrderSide.SELL,
                    new BigDecimal("4"),
                    new BigDecimal("1.0900")
            );

            System.out.println("Trades after demo: " + exchange.getTrades().size());
            System.out.println("Open EUR/USD orders after demo: " + exchange.getOpenOrders(eurUsd).size());
        }
    }
}
