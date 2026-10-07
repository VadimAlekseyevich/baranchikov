package org.example.exchange.udp;

import org.example.exchange.database.PersistentExchange;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.model.TradeNotification;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UdpExchangeServer implements AutoCloseable {
    private final PersistentExchange exchange;
    private final DatagramSocket socket;
    private final ExecutorService workers;
    private final Map<String, SocketAddress> clientEndpoints = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread receiverThread;
    private volatile boolean running;

    public UdpExchangeServer(Path databaseFile, int port) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }

        exchange = new PersistentExchange(Objects.requireNonNull(databaseFile, "databaseFile must not be null"));
        try {
            socket = new DatagramSocket(new InetSocketAddress(port));
        } catch (SocketException e) {
            exchange.close();
            throw new IllegalStateException("Cannot bind UDP server", e);
        }

        int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        workers = Executors.newFixedThreadPool(workerCount);
        receiverThread = Thread.ofPlatform()
                .name("udp-exchange-receiver")
                .daemon(true)
                .unstarted(this::receiveLoop);
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Server is closed");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already started");
        }

        running = true;
        receiverThread.start();
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public void awaitTermination() throws InterruptedException {
        if (!started.get()) {
            throw new IllegalStateException("Server is not started");
        }
        receiverThread.join();
    }

    private void receiveLoop() {
        while (running) {
            byte[] buffer = new byte[UdpProtocol.MAX_DATAGRAM_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                socket.receive(packet);
                String message = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8
                );
                SocketAddress sender = packet.getSocketAddress();
                workers.execute(() -> handleDatagram(message, sender));
            } catch (SocketException e) {
                if (running) {
                    System.err.println("UDP receive error: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (running) {
                    System.err.println("UDP receive error: " + e.getMessage());
                }
            } catch (RejectedExecutionException e) {
                if (running) {
                    throw e;
                }
                return;
            }
        }
    }

    private void handleDatagram(String message, SocketAddress sender) {
        String[] fields = UdpProtocol.split(message);
        String requestId = fields.length > 1 && !fields[1].isBlank() ? fields[1] : "unknown";

        try {
            if (fields.length < 2) {
                throw new IllegalArgumentException("Request must contain command and requestId");
            }
            UdpProtocol.requireProtocolField(requestId, "requestId");

            switch (fields[0]) {
                case "CONNECT" -> connect(fields, sender);
                case "DISCONNECT" -> disconnect(fields, sender);
                case "PLACE_ORDER" -> placeOrder(fields, sender);
                case "GET_OPEN_ORDERS" -> getOpenOrders(fields, sender);
                case "GET_TRADES" -> getTrades(fields, sender);
                default -> throw new IllegalArgumentException("Unknown command: " + fields[0]);
            }
        } catch (RuntimeException error) {
            safeSend(sender, UdpProtocol.join(
                    "ERROR",
                    requestId,
                    UdpProtocol.safeErrorMessage(error)
            ));
        }
    }

    private void connect(String[] fields, SocketAddress sender) {
        requireLength(fields, 3, "CONNECT");
        String requestId = fields[1];
        String clientId = fields[2];
        UdpProtocol.requireProtocolField(clientId, "clientId");

        SocketAddress previous = clientEndpoints.put(clientId, sender);
        try {
            exchange.connect(clientId, this::sendNotification);
        } catch (RuntimeException error) {
            restoreEndpoint(clientId, sender, previous);
            throw error;
        }

        send(sender, UdpProtocol.join("OK", requestId, "CONNECTED"));
    }

    private void disconnect(String[] fields, SocketAddress sender) {
        requireLength(fields, 3, "DISCONNECT");
        String requestId = fields[1];
        String clientId = fields[2];
        UdpProtocol.requireProtocolField(clientId, "clientId");

        exchange.disconnect(clientId);
        clientEndpoints.remove(clientId);
        send(sender, UdpProtocol.join("OK", requestId, "DISCONNECTED"));
    }

    private void placeOrder(String[] fields, SocketAddress sender) {
        requireLength(fields, 8, "PLACE_ORDER");
        String requestId = fields[1];
        String clientId = fields[2];
        UdpProtocol.requireProtocolField(clientId, "clientId");

        Order order = exchange.placeOrder(
                clientId,
                new CurrencyPair(fields[3], fields[4]),
                OrderSide.valueOf(fields[5]),
                new BigDecimal(fields[6]),
                new BigDecimal(fields[7])
        );

        List<String> response = new ArrayList<>();
        response.add("OK");
        response.add(requestId);
        response.add("ORDER");
        UdpProtocol.appendOrder(response, order);
        send(sender, UdpProtocol.join(response));
    }

    private void getOpenOrders(String[] fields, SocketAddress sender) {
        requireLength(fields, 4, "GET_OPEN_ORDERS");
        String requestId = fields[1];
        List<Order> orders = exchange.getOpenOrders(new CurrencyPair(fields[2], fields[3]));

        List<String> response = new ArrayList<>();
        response.add("OK");
        response.add(requestId);
        response.add("OPEN_ORDERS");
        response.add(Integer.toString(orders.size()));
        for (Order order : orders) {
            UdpProtocol.appendOrder(response, order);
        }
        send(sender, UdpProtocol.join(response));
    }

    private void getTrades(String[] fields, SocketAddress sender) {
        requireLength(fields, 2, "GET_TRADES");
        String requestId = fields[1];
        List<Trade> trades = exchange.getTrades();

        List<String> response = new ArrayList<>();
        response.add("OK");
        response.add(requestId);
        response.add("TRADES");
        response.add(Integer.toString(trades.size()));
        for (Trade trade : trades) {
            UdpProtocol.appendTrade(response, trade);
        }
        send(sender, UdpProtocol.join(response));
    }

    private void sendNotification(TradeNotification notification) {
        SocketAddress endpoint = clientEndpoints.get(notification.clientId());
        if (endpoint != null) {
            safeSend(endpoint, UdpProtocol.tradeEvent(notification));
        }
    }

    private void send(SocketAddress destination, String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        if (data.length > UdpProtocol.MAX_DATAGRAM_SIZE) {
            throw new IllegalArgumentException("Response does not fit into one UDP datagram");
        }

        DatagramPacket packet = new DatagramPacket(data, data.length, destination);
        try {
            synchronized (sendLock) {
                socket.send(packet);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot send UDP datagram", e);
        }
    }

    private void safeSend(SocketAddress destination, String message) {
        try {
            send(destination, message);
        } catch (RuntimeException ignored) {
            // UDP delivery has no acknowledgement; a failed notification cannot be retried here.
        }
    }

    private static void requireLength(String[] fields, int expected, String command) {
        if (fields.length != expected) {
            throw new IllegalArgumentException(
                    command + " expects " + (expected - 2) + " argument(s)"
            );
        }
    }

    private void restoreEndpoint(
            String clientId,
            SocketAddress attempted,
            SocketAddress previous
    ) {
        if (previous == null) {
            clientEndpoints.remove(clientId, attempted);
        } else {
            clientEndpoints.put(clientId, previous);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        running = false;
        socket.close();
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exchange.close();
    }
}
