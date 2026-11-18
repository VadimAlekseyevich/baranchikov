package org.example.exchange.distributed;

import org.example.exchange.wire.ExchangeWire.Packet;

import java.math.BigDecimal;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class FacadeMain {
    private static final List<String> SHARDS = List.of(Wire.env("BUSINESS_SHARDS", "business-0,business-1,business-2").split(","));
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
        System.out.println("Sharded UDP facade on port " + udp.getLocalPort());
        var workers = Executors.newCachedThreadPool();
        var notifications = Executors.newSingleThreadScheduledExecutor();
        notifications.scheduleWithFixedDelay(this::drainNotifications, 250, 250, TimeUnit.MILLISECONDS);
        while (!udp.isClosed()) {
            byte[] buffer = new byte[65_507];
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                udp.receive(datagram);
            } catch (java.net.SocketException closed) {
                if (udp.isClosed()) break;
                throw closed;
            }
            String message = new String(datagram.getData(), datagram.getOffset(), datagram.getLength(), StandardCharsets.UTF_8);
            SocketAddress sender = datagram.getSocketAddress();
            workers.submit(() -> handle(message, sender));
        }
        workers.shutdownNow();
        notifications.shutdownNow();
    }

    private void handle(String message, SocketAddress sender) {
        String[] fields = message.split("\\|", -1);
        String requestId = fields.length > 1 && !fields[1].isBlank() ? fields[1] : "unknown";
        try {
            if (fields.length < 2) throw new IllegalArgumentException("Missing request ID");
            field(requestId);
            switch (fields[0]) {
                case "CONNECT" -> {
                    count(fields, 3);
                    field(fields[2]);
                    endpoints.put(fields[2], sender);
                    send(sender, "OK|" + requestId + "|CONNECTED");
                }
                case "DISCONNECT" -> {
                    count(fields, 3);
                    field(fields[2]);
                    endpoints.remove(fields[2], sender);
                    send(sender, "OK|" + requestId + "|DISCONNECTED");
                }
                case "PLACE_ORDER" -> {
                    count(fields, 8);
                    for (int i = 2; i < 8; i++) field(fields[i]);
                    String base = normalize(fields[3]);
                    String quote = normalize(fields[4]);
                    if (base.equals(quote)) throw new IllegalArgumentException("Currencies must differ");
                    if (!fields[5].equals("BUY") && !fields[5].equals("SELL"))
                        throw new IllegalArgumentException("Unknown order side");
                    if (new BigDecimal(fields[6]).signum() <= 0 || new BigDecimal(fields[7]).signum() <= 0)
                        throw new IllegalArgumentException("Quantity and price must be positive");
                    int shard = ShardRouting.index(base, quote, SHARDS.size());
                    Packet reply = call(shard, Wire.packet("PLACE_ORDER")
                            .setClientId(fields[2]).setBase(base).setQuote(quote)
                            .setSide(fields[5]).setQuantity(fields[6]).setPrice(fields[7]).build());
                    send(sender, "OK|" + requestId + "|ORDER|" + orderFields(reply, shard));
                }
                case "GET_OPEN_ORDERS" -> {
                    count(fields, 4);
                    field(fields[2]);
                    field(fields[3]);
                    String base = normalize(fields[2]);
                    String quote = normalize(fields[3]);
                    if (base.equals(quote)) throw new IllegalArgumentException("Currencies must differ");
                    int shard = ShardRouting.index(base, quote, SHARDS.size());
                    Packet reply = call(shard, Wire.packet("OPEN_ORDERS").setBase(base).setQuote(quote).build());
                    List<String> result = new ArrayList<>(List.of("OK", requestId, "OPEN_ORDERS", Integer.toString(reply.getItemsCount())));
                    for (Packet item : reply.getItemsList()) result.add(orderFields(item, shard));
                    send(sender, String.join("|", result));
                }
                case "GET_TRADES" -> {
                    count(fields, 2);
                    List<String> all = mergeTrades(scatter(Wire.packet("TRADES").build()));
                    List<String> result = new ArrayList<>(List.of("OK", requestId, "TRADES", Integer.toString(all.size())));
                    result.addAll(all);
                    send(sender, String.join("|", result));
                }
                case "CANCEL_ALL_ORDERS" -> {
                    count(fields, 3);
                    field(fields[2]);
                    long total = sumCancelled(scatter(Wire.packet("CANCEL_ALL").setClientId(fields[2]).build()));
                    send(sender, "OK|" + requestId + "|CANCELLED|" + total);
                }
                default -> throw new IllegalArgumentException("Unknown UDP command: " + fields[0]);
            }
        } catch (Exception failure) {
            String description = String.valueOf(failure.getMessage()).replace('|', '/').replace('\n', ' ').replace('\r', ' ');
            try {
                send(sender, "ERROR|" + requestId + "|" + description);
            } catch (Exception ignored) {
            }
        }
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static Packet call(int shard, Packet request) throws Exception {
        return Wire.call(SHARDS.get(shard).trim(), BUSINESS_PORT, request);
    }

    private static List<Packet> scatter(Packet request) throws Exception {
        List<Packet> responses = new ArrayList<>(SHARDS.size());
        for (int shard = 0; shard < SHARDS.size(); shard++) {
            responses.add(call(shard, request));
        }
        return responses;
    }

    static List<String> mergeTrades(List<Packet> replies) {
        List<String> values = new ArrayList<>();
        for (int shard = 0; shard < replies.size(); shard++) {
            for (Packet trade : replies.get(shard).getItemsList()) {
                values.add(tradeFields(trade, shard));
            }
        }
        values.sort(Comparator.comparingLong(s -> Long.parseLong(s.substring(0, s.indexOf('|')))));
        return values;
    }

    static long sumCancelled(List<Packet> replies) {
        return replies.stream().mapToLong(Packet::getCancelledCount).sum();
    }

    private void drainNotifications() {
        for (var client : endpoints.entrySet()) {
            for (int shard = 0; shard < SHARDS.size(); shard++) {
                try {
                    Packet reply = call(shard, Wire.packet("TAKE_NOTIFICATIONS").setClientId(client.getKey()).build());
                    for (Packet event : reply.getItemsList()) {
                        if (event.getItemsCount() > 0) {
                            send(client.getValue(), "EVENT|TRADE|" + client.getKey() + "|" + tradeFields(event.getItems(0), shard));
                        }
                    }
                } catch (Exception ignored) {
                }
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

    private static String orderFields(Packet p, int shard) {
        return String.join("|", Long.toString(ShardRouting.publicId(p.getOrderId(), shard, SHARDS.size())),
                p.getClientId(), p.getBase(), p.getQuote(), p.getSide(), p.getPrice(), p.getQuantity(), p.getRemaining());
    }

    private static String tradeFields(Packet p, int shard) {
        return String.join("|", Long.toString(ShardRouting.publicId(p.getTradeId(), shard, SHARDS.size())),
                p.getBase(), p.getQuote(), p.getPrice(), p.getQuantity(),
                Long.toString(ShardRouting.publicId(p.getBuyOrderId(), shard, SHARDS.size())),
                Long.toString(ShardRouting.publicId(p.getSellOrderId(), shard, SHARDS.size())),
                p.getBuyerId(), p.getSellerId());
    }

    static void count(String[] fields, int expected) {
        if (fields.length != expected) throw new IllegalArgumentException("Expected " + (expected - 2) + " arguments");
    }

    static void field(String value) {
        if (value.isBlank() || value.contains("|") || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("Invalid UDP protocol field");
        }
    }
}
