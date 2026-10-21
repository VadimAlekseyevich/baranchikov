package org.example.exchange.distributed;

import org.example.exchange.wire.ExchangeWire.Packet;

import java.math.BigDecimal;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Layer 1: validates and translates external text UDP into internal protobuf. */
public final class FacadeMain {
    private static final String BUSINESS_HOST = Wire.env("BUSINESS_HOST", "business");
    private static final int BUSINESS_PORT = Integer.parseInt(Wire.env("BUSINESS_PORT", "9101"));
    private static final int UDP_PORT = Integer.parseInt(Wire.env("FACADE_PORT", "9000"));
    private final Map<String, SocketAddress> endpoints = new ConcurrentHashMap<>();
    private final DatagramSocket udp;
    private final Object sendLock = new Object();

    private FacadeMain(int port) throws Exception {
        udp = new DatagramSocket(port);
    }

    public static void main(String[] args) throws Exception {
        FacadeMain facade = new FacadeMain(UDP_PORT);
        Runtime.getRuntime().addShutdownHook(new Thread(facade.udp::close));
        facade.run();
    }

    private void run() throws Exception {
        System.out.println("UDP facade on port " + udp.getLocalPort());
        var workers = Executors.newCachedThreadPool();
        var notifications = Executors.newSingleThreadScheduledExecutor();
        notifications.scheduleWithFixedDelay(this::drainNotifications, 250, 250, TimeUnit.MILLISECONDS);
        byte[] buffer;
        while (!udp.isClosed()) {
            buffer = new byte[65_507];
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            udp.receive(datagram);
            String message = new String(datagram.getData(), datagram.getOffset(), datagram.getLength(), StandardCharsets.UTF_8);
            SocketAddress sender = datagram.getSocketAddress();
            workers.submit(() -> handle(message, sender));
        }
    }

    private void handle(String message, SocketAddress sender) {
        String[] f = message.split("\\|", -1);
        String requestId = f.length > 1 && !f[1].isBlank() ? f[1] : "unknown";
        try {
            if (f.length < 2) throw new IllegalArgumentException("Missing request ID");
            field(requestId);
            switch (f[0]) {
                case "CONNECT" -> {
                    count(f, 3);
                    field(f[2]);
                    endpoints.put(f[2], sender);
                    send(sender, "OK|" + requestId + "|CONNECTED");
                }
                case "DISCONNECT" -> {
                    count(f, 3);
                    field(f[2]);
                    endpoints.remove(f[2]);
                    send(sender, "OK|" + requestId + "|DISCONNECTED");
                }
                case "PLACE_ORDER" -> {
                    count(f, 8);
                    for (int i = 2; i < 8; i++) field(f[i]);
                    if (f[3].trim().equalsIgnoreCase(f[4].trim())) throw new IllegalArgumentException("Currencies must differ");
                    if (!f[5].equals("BUY") && !f[5].equals("SELL")) throw new IllegalArgumentException("Unknown order side");
                    if (new BigDecimal(f[6]).signum() <= 0 || new BigDecimal(f[7]).signum() <= 0)
                        throw new IllegalArgumentException("Quantity and price must be positive");
                    Packet reply = business(Wire.packet("PLACE_ORDER")
                            .setClientId(f[2]).setBase(f[3].trim().toUpperCase(java.util.Locale.ROOT)).setQuote(f[4].trim().toUpperCase(java.util.Locale.ROOT))
                            .setSide(f[5]).setQuantity(f[6]).setPrice(f[7]).build());
                    send(sender, "OK|" + requestId + "|ORDER|" + orderFields(reply));
                }
                case "GET_OPEN_ORDERS" -> {
                    count(f, 4);
                    field(f[2]); field(f[3]);
                    Packet reply = business(Wire.packet("OPEN_ORDERS")
                            .setBase(f[2].trim().toUpperCase(java.util.Locale.ROOT)).setQuote(f[3].trim().toUpperCase(java.util.Locale.ROOT)).build());
                    List<String> parts = new ArrayList<>(List.of("OK", requestId, "OPEN_ORDERS", Integer.toString(reply.getItemsCount())));
                    for (Packet item : reply.getItemsList()) parts.add(orderFields(item));
                    send(sender, String.join("|", parts));
                }
                case "GET_TRADES" -> {
                    count(f, 2);
                    Packet reply = business(Wire.packet("TRADES").build());
                    List<String> parts = new ArrayList<>(List.of("OK", requestId, "TRADES", Integer.toString(reply.getItemsCount())));
                    for (Packet item : reply.getItemsList()) parts.add(tradeFields(item));
                    send(sender, String.join("|", parts));
                }
                default -> throw new IllegalArgumentException("Unknown UDP command: " + f[0]);
            }
        } catch (Exception error) {
            String msg = String.valueOf(error.getMessage()).replace('|', '/').replace('\n', ' ').replace('\r', ' ');
            try { send(sender, "ERROR|" + requestId + "|" + msg); }
            catch (Exception ignored) { }
        }
    }

    private static Packet business(Packet p) throws Exception {
        return Wire.call(BUSINESS_HOST, BUSINESS_PORT, p);
    }

    private void drainNotifications() {
        for (var client : endpoints.entrySet()) {
            try {
                Packet reply = business(Wire.packet("TAKE_NOTIFICATIONS").setClientId(client.getKey()).build());
                for (Packet event : reply.getItemsList()) {
                    if (event.getItemsCount() > 0) {
                        send(client.getValue(), "EVENT|TRADE|" + client.getKey() + "|" + tradeFields(event.getItems(0)));
                    }
                }
            } catch (Exception e) {
                // The storage keeps notifications until a successful TAKE; UDP itself has no ACK.
            }
        }
    }

    private void send(SocketAddress destination, String message) throws Exception {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_507) throw new IllegalArgumentException("UDP datagram exceeds max size");
        synchronized (sendLock) {
            udp.send(new DatagramPacket(bytes, bytes.length, destination));
        }
    }

    private static String orderFields(Packet p) {
        return String.join("|", Long.toString(p.getOrderId()), p.getClientId(), p.getBase(), p.getQuote(),
                p.getSide(), p.getPrice(), p.getQuantity(), p.getRemaining());
    }

    private static String tradeFields(Packet p) {
        return String.join("|", Long.toString(p.getTradeId()), p.getBase(), p.getQuote(), p.getPrice(),
                p.getQuantity(), Long.toString(p.getBuyOrderId()), Long.toString(p.getSellOrderId()),
                p.getBuyerId(), p.getSellerId());
    }

    static void count(String[] f, int expected) {
        if (f.length != expected) throw new IllegalArgumentException("Expected " + (expected - 2) + " arguments");
    }

    static void field(String value) {
        if (value.isBlank() || value.contains("|") || value.contains("\r") || value.contains("\n"))
            throw new IllegalArgumentException("Invalid UDP protocol field");
    }
}
