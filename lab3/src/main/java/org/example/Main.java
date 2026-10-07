package org.example;

import org.example.exchange.udp.UdpExchangeServer;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Path database = args.length > 0 ? Path.of(args[0]) : Path.of("data", "exchange");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9000;

        UdpExchangeServer server = new UdpExchangeServer(database, port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "udp-exchange-shutdown"));
        server.start();

        System.out.println("UDP exchange server is listening on port " + server.localPort());
        System.out.println("Database: " + database.toAbsolutePath().normalize());
        server.awaitTermination();
    }
}
