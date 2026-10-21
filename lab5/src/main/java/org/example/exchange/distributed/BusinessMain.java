package org.example.exchange.distributed;

import org.example.exchange.wire.ExchangeWire.Packet;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;

/** Layer 2: stateless business process. Matching rules live here, never in storage. */
public final class BusinessMain {
    private static final String STORAGE_HOST = Wire.env("STORAGE_HOST", "storage");
    private static final int STORAGE_PORT = Integer.parseInt(Wire.env("STORAGE_PORT", "9102"));
    private static final int PORT = Integer.parseInt(Wire.env("BUSINESS_PORT", "9101"));

    private BusinessMain() { }

    public static void main(String[] args) throws Exception {
        System.out.println("Protobuf business layer on TCP " + PORT);
        try (ServerSocket server = new ServerSocket(PORT);
             var workers = Executors.newCachedThreadPool()) {
            while (true) {
                Socket socket = server.accept();
                workers.submit(() -> handle(socket));
            }
        }
    }

    private static void handle(Socket socket) {
        try (socket) {
            Packet request = Packet.parseDelimitedFrom(socket.getInputStream());
            if (request == null) return;
            Packet reply;
            try {
                reply = process(request);
            } catch (Exception failure) {
                reply = Wire.error(failure);
            }
            reply.writeDelimitedTo(socket.getOutputStream());
            socket.getOutputStream().flush();
        } catch (Exception e) {
            System.err.println("Business request failed: " + e.getMessage());
        }
    }

    static Packet process(Packet p) throws Exception {
        switch (p.getOp()) {
            case "PLACE_ORDER" -> {
                validateOrder(p);
                return placeOrder(p);
            }
            case "OPEN_ORDERS" -> {
                requireField(p.getBase(), "base");
                requireField(p.getQuote(), "quote");
                if (p.getBase().equals(p.getQuote())) throw new IllegalArgumentException("Currencies must differ");
                return Wire.call(STORAGE_HOST, STORAGE_PORT, p);
            }
            case "TRADES" -> {
                return Wire.call(STORAGE_HOST, STORAGE_PORT, p);
            }
            case "TAKE_NOTIFICATIONS" -> {
                requireField(p.getClientId(), "clientId");
                try (Socket storage = Wire.connect(STORAGE_HOST, STORAGE_PORT)) {
                    Wire.exchange(storage, Wire.packet("BEGIN").build());
                    try {
                        Packet notifications = Wire.exchange(storage, p);
                        Wire.exchange(storage, Wire.packet("COMMIT").build());
                        return notifications;
                    } catch (Exception failure) {
                        rollback(storage, failure);
                        throw failure;
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown business operation: " + p.getOp());
        }
    }

    private static Packet placeOrder(Packet p) throws Exception {
        String clientId = p.getClientId();
        String side = p.getSide();
        BigDecimal quantity = new BigDecimal(p.getQuantity());
        BigDecimal limitPrice = new BigDecimal(p.getPrice());
        BigDecimal remaining = quantity;

        try (Socket storage = Wire.connect(STORAGE_HOST, STORAGE_PORT)) {
            Wire.exchange(storage, Wire.packet("BEGIN").build());
            try {
                long orderId = Wire.exchange(storage, Wire.packet("INSERT_ORDER")
                        .setClientId(clientId).setBase(p.getBase()).setQuote(p.getQuote())
                        .setSide(side).setQuantity(p.getQuantity()).setPrice(p.getPrice()).build()).getOrderId();
                while (remaining.signum() > 0) {
                    Packet matched = Wire.exchange(storage, Wire.packet("FIND_MATCH")
                            .setBase(p.getBase()).setQuote(p.getQuote()).setSide(side)
                            .setPrice(p.getPrice()).build());
                    if (!matched.getFound()) break;

                    BigDecimal matchedRemaining = new BigDecimal(matched.getRemaining());
                    BigDecimal traded = remaining.min(matchedRemaining);
                    remaining = remaining.subtract(traded);
                    BigDecimal otherRemaining = matchedRemaining.subtract(traded);
                    Wire.exchange(storage, Wire.packet("UPDATE_REMAINING")
                            .setOrderId(orderId).setRemaining(remaining.toPlainString()).build());
                    Wire.exchange(storage, Wire.packet("UPDATE_REMAINING")
                            .setOrderId(matched.getOrderId()).setRemaining(otherRemaining.toPlainString()).build());

                    boolean buy = side.equals("BUY");
                    String buyer = buy ? clientId : matched.getClientId();
                    String seller = buy ? matched.getClientId() : clientId;
                    long buyId = buy ? orderId : matched.getOrderId();
                    long sellId = buy ? matched.getOrderId() : orderId;
                    long tradeId = Wire.exchange(storage, Wire.packet("INSERT_TRADE")
                            .setBase(p.getBase()).setQuote(p.getQuote())
                            .setPrice(matched.getPrice()).setQuantity(traded.toPlainString())
                            .setBuyOrderId(buyId).setSellOrderId(sellId)
                            .setBuyerId(buyer).setSellerId(seller).build()).getTradeId();
                    // Both recipients are stored durably; facade drains events for connected clients.
                    Wire.exchange(storage, Wire.packet("SAVE_NOTIFICATION")
                            .setClientId(buyer).setTradeId(tradeId).build());
                    Wire.exchange(storage, Wire.packet("SAVE_NOTIFICATION")
                            .setClientId(seller).setTradeId(tradeId).build());
                }
                Wire.exchange(storage, Wire.packet("COMMIT").build());
                return Wire.packet("ORDER")
                        .setOrderId(orderId).setClientId(clientId)
                        .setBase(p.getBase()).setQuote(p.getQuote()).setSide(side)
                        .setPrice(limitPrice.toPlainString()).setQuantity(quantity.toPlainString())
                        .setRemaining(remaining.toPlainString()).build();
            } catch (Exception failure) {
                rollback(storage, failure);
                throw failure;
            }
        }
    }

    private static void rollback(Socket socket, Exception failure) {
        try { Wire.exchange(socket, Wire.packet("ROLLBACK").build()); }
        catch (Exception ignored) { failure.addSuppressed(ignored); }
    }

    static void validateOrder(Packet p) {
        requireField(p.getClientId(), "clientId");
        requireField(p.getBase(), "base");
        requireField(p.getQuote(), "quote");
        if (p.getBase().equals(p.getQuote())) throw new IllegalArgumentException("Currencies must differ");
        if (!p.getSide().equals("BUY") && !p.getSide().equals("SELL"))
            throw new IllegalArgumentException("Side must be BUY or SELL");
        if (new BigDecimal(p.getQuantity()).signum() <= 0 || new BigDecimal(p.getPrice()).signum() <= 0)
            throw new IllegalArgumentException("Quantity and price must be positive");
    }

    private static void requireField(String value, String field) {
        if (value.isBlank() || value.contains("|") || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException("Invalid " + field);
    }
}
