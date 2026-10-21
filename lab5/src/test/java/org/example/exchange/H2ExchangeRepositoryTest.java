package org.example.exchange;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.repository.h2.H2ExchangeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class H2ExchangeRepositoryTest {
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    @TempDir
    Path tempDir;

    @Test
    void persistenceLayerStoresAndRestoresOrdersWithoutBusinessOrUdpLayer() {
        Path database = tempDir.resolve("repository/exchange");

        try (H2ExchangeRepository repository = new H2ExchangeRepository(database)) {
            repository.insertOrder(
                    "buyer",
                    EUR_USD,
                    OrderSide.BUY,
                    bd("10"),
                    bd("1.1000")
            );
            repository.commit();
        }

        try (H2ExchangeRepository repository = new H2ExchangeRepository(database)) {
            List<Order> restored = repository.getOpenOrders(EUR_USD);

            assertEquals(1, restored.size());
            assertEquals("buyer", restored.getFirst().clientId());
            assertEquals(OrderSide.BUY, restored.getFirst().side());
            assertEquals(
                    0,
                    bd("10").compareTo(restored.getFirst().remainingQuantity())
            );
            assertEquals(
                    0,
                    bd("1.1000").compareTo(restored.getFirst().limitPrice())
            );
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
