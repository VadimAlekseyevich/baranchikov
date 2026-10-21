package org.example.exchange;

import org.example.exchange.api.Exchange;
import org.example.exchange.api.NotificationListener;
import org.example.exchange.model.CurrencyPair;
import org.example.exchange.model.Order;
import org.example.exchange.model.OrderSide;
import org.example.exchange.model.Trade;
import org.example.exchange.udp.UdpExchangeClient;
import org.example.exchange.udp.UdpExchangeServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpTransportLayerTest {
    private static final CurrencyPair EUR_USD = new CurrencyPair("EUR", "USD");

    @Test
    void udpLayerCallsExchangeInterfaceWithoutH2() throws Exception {
        RecordingExchange exchange = new RecordingExchange();

        try (UdpExchangeServer server = new UdpExchangeServer(exchange, 0)) {
            server.start();

            try (UdpExchangeClient client = new UdpExchangeClient(
                    new InetSocketAddress(
                            InetAddress.getLoopbackAddress(),
                            server.localPort()
                    )
            )) {
                client.connect("buyer");

                Order result = client.placeOrder(
                        "buyer",
                        EUR_USD,
                        OrderSide.BUY,
                        bd("2"),
                        bd("1.1000")
                );

                assertEquals("buyer", exchange.connectedClient);
                assertEquals("buyer", exchange.orderClient);
                assertEquals(EUR_USD, exchange.orderPair);
                assertEquals(OrderSide.BUY, exchange.orderSide);
                assertEquals(0, bd("2").compareTo(exchange.orderQuantity));
                assertEquals(0, bd("1.1000").compareTo(exchange.orderPrice));
                assertTrue(result.id() > 0);
            }
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class RecordingExchange implements Exchange {
        private String connectedClient;
        private String orderClient;
        private CurrencyPair orderPair;
        private OrderSide orderSide;
        private BigDecimal orderQuantity;
        private BigDecimal orderPrice;

        @Override
        public void connect(String clientId, NotificationListener listener) {
            connectedClient = clientId;
        }

        @Override
        public void disconnect(String clientId) {
        }

        @Override
        public Order placeOrder(
                String clientId,
                CurrencyPair pair,
                OrderSide side,
                BigDecimal quantity,
                BigDecimal limitPrice
        ) {
            orderClient = clientId;
            orderPair = pair;
            orderSide = side;
            orderQuantity = quantity;
            orderPrice = limitPrice;

            return new Order(
                    1,
                    clientId,
                    pair,
                    side,
                    limitPrice,
                    quantity,
                    quantity
            );
        }

        @Override
        public List<Order> getOpenOrders(CurrencyPair pair) {
            return List.of();
        }

        @Override
        public List<Trade> getTrades() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
