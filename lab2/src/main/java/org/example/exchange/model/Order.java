package org.example.exchange.model;

import java.math.BigDecimal;
import java.util.Objects;

public record Order(
        long id,
        String clientId,
        CurrencyPair pair,
        OrderSide side,
        BigDecimal limitPrice,
        BigDecimal originalQuantity,
        BigDecimal remainingQuantity
) {
    public Order {
        if (id <= 0) {
            throw new IllegalArgumentException("Order id must be positive");
        }
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(pair, "pair must not be null");
        Objects.requireNonNull(side, "side must not be null");
        requirePositive(limitPrice, "limitPrice");
        requirePositive(originalQuantity, "originalQuantity");
        Objects.requireNonNull(remainingQuantity, "remainingQuantity must not be null");
        if (remainingQuantity.signum() < 0 || remainingQuantity.compareTo(originalQuantity) > 0) {
            throw new IllegalArgumentException("remainingQuantity must be between zero and originalQuantity");
        }
    }

    public boolean isFilled() {
        return remainingQuantity.signum() == 0;
    }

    private static void requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
