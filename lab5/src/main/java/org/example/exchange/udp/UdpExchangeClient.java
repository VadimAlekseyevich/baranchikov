package org.example.exchange.udp;

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
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class UdpExchangeClient implements AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    private final InetSocketAddress serverAddress;
    private final DatagramSocket socket;
    private final Duration requestTimeout;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<String, CompletableFuture<String>> pendingResponses = new ConcurrentHashMap<>();
    private final BlockingQueue<TradeNotification> notifications = new LinkedBlockingQueue<>();
    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread receiverThread;

    public UdpExchangeClient(InetSocketAddress serverAddress) {
        this(serverAddress, DEFAULT_TIMEOUT);
    }

    public UdpExchangeClient(InetSocketAddress serverAddress, Duration requestTimeout) {
        this.serverAddress = Objects.requireNonNull(serverAddress, "serverAddress must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }

        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            throw new IllegalStateException("Cannot open UDP client socket", e);
        }

        receiverThread = Thread.ofPlatform()
                .name("udp-exchange-client-receiver-" + socket.getLocalPort())
                .daemon(true)
                .start(this::receiveLoop);
    }

    public void connect(String clientId) {
        String[] response = successfulResponse(request("CONNECT", clientId));
        requireResponseType(response, "CONNECTED");
    }

    public void disconnect(String clientId) {
        String[] response = successfulResponse(request("DISCONNECT", clientId));
        requireResponseType(response, "DISCONNECTED");
    }

    public Order placeOrder(
            String clientId,
            CurrencyPair pair,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice
    ) {
        Objects.requireNonNull(pair, "pair must not be null");
        Objects.requireNonNull(side, "side must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(limitPrice, "limitPrice must not be null");

        String[] response = successfulResponse(request(
                "PLACE_ORDER",
                clientId,
                pair.base(),
                pair.quote(),
                side.name(),
                quantity.toPlainString(),
                limitPrice.toPlainString()
        ));
        requireResponseType(response, "ORDER");
        if (response.length != 3 + UdpProtocol.ORDER_FIELD_COUNT) {
            throw new IllegalStateException("Malformed ORDER response");
        }
        return UdpProtocol.readOrder(response, 3);
    }

    public List<Order> getOpenOrders(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair must not be null");
        String[] response = successfulResponse(request(
                "GET_OPEN_ORDERS",
                pair.base(),
                pair.quote()
        ));
        requireResponseType(response, "OPEN_ORDERS");

        int count = parseCount(response, 3, "OPEN_ORDERS");
        int expectedLength = 4 + count * UdpProtocol.ORDER_FIELD_COUNT;
        if (response.length != expectedLength) {
            throw new IllegalStateException("malformed OPEN_ORDERS response");
        }

        List<Order> result = new ArrayList<>(count);
        int offset = 4;
        for (int i = 0; i < count; i++) {
            result.add(UdpProtocol.readOrder(response, offset));
            offset += UdpProtocol.ORDER_FIELD_COUNT;
        }
        return List.copyOf(result);
    }

    public List<Trade> getTrades() {
        String[] response = successfulResponse(request("GET_TRADES"));
        requireResponseType(response, "TRADES");

        int count = parseCount(response, 3, "TRADES");
        int expectedLength = 4 + count * UdpProtocol.TRADE_FIELD_COUNT;
        if (response.length != expectedLength) {
            throw new IllegalStateException("Malformed TRADES response");
        }

        List<Trade> result = new ArrayList<>(count);
        int offset = 4;
        for (int i = 0; i < count; i++) {
            result.add(UdpProtocol.readTrade(response, offset));
            offset += UdpProtocol.TRADE_FIELD_COUNT;
        }
        return List.copyOf(result);
    }

    public TradeNotification pollNotification(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        try {
            return notifications.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for notification", e);
        }
    }

    private String request(String command, String... arguments) {
        ensureOpen();
        UdpProtocol.requireProtocolField(command, "command");
        for (int i = 0; i < arguments.length; i++) {
            UdpProtocol.requireProtocolField(arguments[i], "argument " + i);
        }

        String requestId = Long.toString(requestSequence.incrementAndGet());
        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        pendingResponses.put(requestId, responseFuture);

        List<String> fields = new ArrayList<>(arguments.length + 2);
        fields.add(command);
        fields.add(requestId);
        fields.addAll(List.of(arguments));

        try {
            send(UdpProtocol.join(fields));
            return responseFuture.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("UDP request timed out: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for UDP response", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("UDP request failed", e);
        } finally {
            pendingResponses.remove(requestId, responseFuture);
        }
    }

    private void receiveLoop() {
        while (!closed.get()) {
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
                routeIncoming(message);
            } catch (SocketException e) {
                if (!closed.get()) {
                    failPending(e);
                }
                return;
            } catch (IOException | RuntimeException e) {
                if (!closed.get()) {
                    failPending(e);
                }
            }
        }
    }

    private void routeIncoming(String message) {
        String[] fields = UdpProtocol.split(message);
        if (fields.length >= 2 && "EVENT".equals(fields[0])) {
            if (!"TRADE".equals(fields[1]) || fields.length != 3 + UdpProtocol.TRADE_FIELD_COUNT) {
                throw new IllegalArgumentException("Malformed EVENT datagram");
            }
            notifications.add(new TradeNotification(
                    fields[2],
                    UdpProtocol.readTrade(fields, 3)
            ));
            return;
        }

        if (fields.length < 2 || !("OK".equals(fields[0]) || "ERROR".equals(fields[0]))) {
            throw new IllegalArgumentException("Malformed response datagram");
        }

        CompletableFuture<String> future = pendingResponses.get(fields[1]);
        if (future != null) {
            future.complete(message);
        }
    }

    private String[] successfulResponse(String message) {
        String[] fields = UdpProtocol.split(message);
        if (fields.length < 3) {
            throw new IllegalStateException("Malformed server response");
        }
        if ("ERROR".equals(fields[0])) {
            throw new IllegalStateException(fields[2]);
        }
        if (!"OK".equals(fields[0])) {
            throw new IllegalStateException("Unexpected server response: " + fields[0]);
        }
        return fields;
    }

    private static void requireResponseType(String[] response, String expectedType) {
        if (response.length < 3 || !expectedType.equals(response[2])) {
            throw new IllegalStateException("Expected response type " + expectedType);
        }
    }

    private static int parseCount(String[] response, int index, String responseType) {
        if (response.length <= index) {
            throw new IllegalStateException("Malformed " + responseType + " response");
        }
        int count = Integer.parseInt(response[index]);
        if (count < 0) {
            throw new IllegalStateException("Negative item count in " + responseType + " response");
        }
        return count;
    }

    private void send(String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        if (data.length > UdpProtocol.MAX_DATAGRAM_SIZE) {
            throw new IllegalArgumentException("Request does not fit into one UDP datagram");
        }

        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress);
        try {
            synchronized (sendLock) {
                socket.send(packet);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot send UDP request", e);
        }
    }

    private void failPending(Throwable error) {
        for (CompletableFuture<String> future : pendingResponses.values()) {
            future.completeExceptionally(error);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        socket.close();
        failPending(new IllegalStateException("Client is closed"));
    }
}
