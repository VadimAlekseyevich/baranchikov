package org.example.exchange.distributed;

import org.h2.tools.Server;

import java.util.concurrent.CountDownLatch;

public final class DatabaseMain {
    private DatabaseMain() { }

    public static void main(String[] args) throws Exception {
        Server db = Server.createTcpServer("-tcpPort", "9092", "-tcpAllowOthers", "-ifNotExists", "-baseDir", "/app/data").start();
        Runtime.getRuntime().addShutdownHook(new Thread(db::stop));
        System.out.println("H2 TCP database listening on 9092");
        new CountDownLatch(1).await();
    }
}
