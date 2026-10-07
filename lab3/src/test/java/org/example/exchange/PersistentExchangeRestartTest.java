package org.example.exchange;

import org.example.exchange.database.PersistentExchange;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.TradeNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentExchangeRestartTest {
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    @TempDir
    Path tempDir;

    @Test
    void stateIsRestoredAfterNormalShutdown() {
        Path database = tempDir.resolve("normal/exchange");

        try (PersistentExchange firstRun = new PersistentExchange(database)) {
            firstRun.placeOrder("buyer", EUR_USD, OrderSide.BUY, bd("10"), bd("1.1000"));
        }

        try (PersistentExchange secondRun = new PersistentExchange(database)) {
            List<Order> restored = secondRun.getOpenOrders(EUR_USD);
            assertEquals(1, restored.size());
            assertEquals("buyer", restored.getFirst().clientId());
            assertEquals(0, bd("10").compareTo(restored.getFirst().remainingQuantity()));

            secondRun.placeOrder("seller", EUR_USD, OrderSide.SELL, bd("4"), bd("1.0900"));
        }

        Queue<TradeNotification> buyerNotifications = new ConcurrentLinkedQueue<>();
        Queue<TradeNotification> sellerNotifications = new ConcurrentLinkedQueue<>();
        try (PersistentExchange thirdRun = new PersistentExchange(database)) {
            assertEquals(1, thirdRun.getTrades().size());
            assertEquals(1, thirdRun.getOpenOrders(EUR_USD).size());
            assertEquals(0, bd("6").compareTo(thirdRun.getOpenOrders(EUR_USD).getFirst().remainingQuantity()));

            thirdRun.connect("buyer", buyerNotifications::add);
            thirdRun.connect("seller", sellerNotifications::add);
        }

        assertEquals(1, buyerNotifications.size());
        assertEquals(1, sellerNotifications.size());
    }

    @Test
    void committedStateSurvivesAbruptProcessTermination() throws Exception {
        Path database = tempDir.resolve("crash/exchange");
        Process process = startCrashWriter(database);

        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Crash helper did not finish in time");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);

        Queue<TradeNotification> buyerNotifications = new ConcurrentLinkedQueue<>();
        Queue<TradeNotification> sellerNotifications = new ConcurrentLinkedQueue<>();

        try (PersistentExchange recovered = new PersistentExchange(database)) {
            assertEquals(1, recovered.getTrades().size());
            assertEquals(1, recovered.getOpenOrders(EUR_USD).size());
            assertEquals(0, bd("7").compareTo(recovered.getOpenOrders(EUR_USD).getFirst().remainingQuantity()));

            recovered.connect("buyer", buyerNotifications::add);
            recovered.connect("seller", sellerNotifications::add);
        }

        assertEquals(1, buyerNotifications.size());
        assertEquals(1, sellerNotifications.size());
    }

    private Process startCrashWriter(Path database) throws IOException {
        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path")
        );

        return new ProcessBuilder(
                javaExecutable,
                "-cp",
                classPath,
                AbruptTerminationWriter.class.getName(),
                database.toString()
        ).redirectErrorStream(true).start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
