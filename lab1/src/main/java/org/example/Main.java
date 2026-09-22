package org.example;

import org.example.exchange.api.Exchange;
import org.example.exchange.memory.InMemoryExchange;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.OrderSide;

import java.math.BigDecimal;

public class Main {
    public static void main(String[] args) {
        Exchange exchange = new InMemoryExchange();
        CurrencyPair eurUsd = new CurrencyPair("EUR", "USD");

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
    }
}
