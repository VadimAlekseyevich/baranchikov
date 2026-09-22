package org.example.exchange.model;

import java.util.Locale;
import java.util.Objects;

public record CurrencyPair(String base, String quote) {
    public CurrencyPair {
        base = normalize(base, "base");
        quote = normalize(quote, "quote");
        if (base.equals(quote)) {
            throw new IllegalArgumentException("Currencies in a pair must be different");
        }
    }

    private static String normalize(String value, String field) {
        Objects.requireNonNull(value, field + " currency must not be null");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " currency must not be blank");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return base + "/" + quote;
    }
}
