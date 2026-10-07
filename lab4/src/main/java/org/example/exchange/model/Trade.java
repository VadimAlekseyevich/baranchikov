package org.example.exchange.model;

import java.math.BigDecimal;
import java.util.Objects;

public record Trade(
        long id,
        CurrencyPair pair,
        BigDecimal price,
        BigDecimal quantity,
        long buyOrderId,
        long sellOrderId,
        String buyerId,
        String sellerId
) {
    public Trade {
        if (id <= 0) {
            throw new IllegalArgumentException("Trade id must be positive");
        }
        Objects.requireNonNull(pair, "pair must not be null");
        requirePositive(price, "price");
        requirePositive(quantity, "quantity");
        if (buyOrderId <= 0 || sellOrderId <= 0) {
            throw new IllegalArgumentException("Order ids must be positive");
        }
        Objects.requireNonNull(buyerId, "buyerId must not be null");
        Objects.requireNonNull(sellerId, "sellerId must not be null");
    }

    private static void requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
