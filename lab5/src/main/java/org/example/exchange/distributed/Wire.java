package org.example.exchange.distributed;

import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.wire.ExchangeWire.Packet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.Socket;
import java.math.BigDecimal;
import java.util.Objects;

/** Shared internal protobuf framing and domain conversion. */
public final class Wire {
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    static {
        // Docker's service DNS changes when a layer is scaled. Do not pin a stale
        // JVM-cached address list for the lifetime of a facade/business process.
        java.security.Security.setProperty("networkaddress.cache.ttl", "0");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }
    private Wire() { }

    public static Packet.Builder packet(String op) {
        return Packet.newBuilder().setOp(op);
    }

    public static Socket connect(String host, int port) throws IOException {
        // Resolve on each request so Compose DNS can expose newly scaled replicas.
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new IOException("No IP addresses for " + host);
        int start = Math.floorMod(NEXT_ADDRESS.getAndIncrement(), addresses.length);
        IOException failure = null;
        for (int i = 0; i < addresses.length; i++) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(addresses[(start + i) % addresses.length], port), 3000);
                socket.setSoTimeout(15000);
                return socket;
            } catch (IOException error) {
                failure = error;
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
        throw new IOException("No reachable " + host + ":" + port, failure);
    }

    public static Packet exchange(Socket socket, Packet request) throws IOException {
        request.writeDelimitedTo(socket.getOutputStream());
        socket.getOutputStream().flush();
        Packet reply = Packet.parseDelimitedFrom(socket.getInputStream());
        if (reply == null) throw new IOException("Protobuf peer disconnected");
        if (reply.getOp().equals("ERROR")) throw new IllegalStateException(reply.getError());
        return reply;
    }

    public static Packet call(String host, int port, Packet request) throws IOException {
        try (Socket socket = connect(host, port)) {
            return exchange(socket, request);
        }
    }

    public static Packet error(Throwable failure) {
        String message = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
        return packet("ERROR").setError(message).build();
    }

    public static Packet order(Order value) {
        return packet("ORDER")
                .setOrderId(value.id())
                .setClientId(value.clientId())
                .setBase(value.pair().base())
                .setQuote(value.pair().quote())
                .setSide(value.side().name())
                .setPrice(value.limitPrice().toPlainString())
                .setQuantity(value.originalQuantity().toPlainString())
                .setRemaining(value.remainingQuantity().toPlainString())
                .build();
    }

    public static Order order(Packet p) {
        return new Order(p.getOrderId(), p.getClientId(), new CurrencyPair(p.getBase(), p.getQuote()),
                OrderSide.valueOf(p.getSide()), new BigDecimal(p.getPrice()),
                new BigDecimal(p.getQuantity()), new BigDecimal(p.getRemaining()));
    }

    public static Packet trade(Trade value) {
        return packet("TRADE")
                .setTradeId(value.id())
                .setBase(value.pair().base())
                .setQuote(value.pair().quote())
                .setPrice(value.price().toPlainString())
                .setQuantity(value.quantity().toPlainString())
                .setBuyOrderId(value.buyOrderId())
                .setSellOrderId(value.sellOrderId())
                .setBuyerId(value.buyerId())
                .setSellerId(value.sellerId())
                .build();
    }

    public static Trade trade(Packet p) {
        return new Trade(p.getTradeId(), new CurrencyPair(p.getBase(), p.getQuote()),
                new BigDecimal(p.getPrice()), new BigDecimal(p.getQuantity()),
                p.getBuyOrderId(), p.getSellOrderId(), p.getBuyerId(), p.getSellerId());
    }

    public static String env(String key, String defaultValue) {
        return System.getenv().getOrDefault(key, defaultValue);
    }
}
