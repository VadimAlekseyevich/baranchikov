package org.example.exchange.distributed;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Reproducible end-to-end read benchmark: CSV summary and latency percentiles. */
public final class BenchmarkMain {
    private BenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        int requests = Integer.parseInt(Wire.env("BENCH_REQUESTS", "500"));
        int threads = Integer.parseInt(Wire.env("BENCH_THREADS", "8"));
        int warmup = Integer.parseInt(Wire.env("BENCH_WARMUP", "40"));
        String host = Wire.env("EXCHANGE_HOST", "facade");
        int port = Integer.parseInt(Wire.env("EXCHANGE_PORT", "9000"));
        InetAddress[] backends = InetAddress.getAllByName(host);
        if (backends.length == 0) throw new IllegalStateException("No facade IPs");
        System.out.println("Benchmark: " + requests + " requests, " + threads + " threads, " + backends.length + " facade IPs");

        try (var executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < warmup; i++) {
                int index = i;
                tasks.add(() -> request(backends[index % backends.length], port, index));
            }
            for (Future<Long> f : executor.invokeAll(tasks)) f.get();
            tasks.clear();
            for (int i = 0; i < requests; i++) {
                int index = i + warmup;
                tasks.add(() -> request(backends[index % backends.length], port, index));
            }
            long start = System.nanoTime();
            List<Future<Long>> results = executor.invokeAll(tasks);
            long elapsed = System.nanoTime() - start;
            List<Long> latencies = new ArrayList<>();
            int errors = 0;
            for (Future<Long> f : results) {
                try { latencies.add(f.get()); }
                catch (Exception ignored) { errors++; }
            }
            latencies.sort(Long::compare);
            int done = latencies.size();
            double seconds = elapsed / 1_000_000_000.0;
            double rps = done / seconds;
            double p50 = percentile(latencies, .50) / 1_000_000.0;
            double p95 = percentile(latencies, .95) / 1_000_000.0;
            System.out.printf(Locale.US, "RESULT,%d,%d,%d,%d,%.3f,%.3f,%.3f%n", threads, requests, done, errors, rps, p50, p95);
            if (errors != 0) throw new IllegalStateException(errors + " benchmark requests failed");
        }
    }

    private static long request(InetAddress ip, int port, int id) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(10000);
            byte[] sent = ("GET_OPEN_ORDERS|" + id + "|BENCH|USD").getBytes(StandardCharsets.UTF_8);
            long start = System.nanoTime();
            socket.send(new DatagramPacket(sent, sent.length, new InetSocketAddress(ip, port)));
            byte[] bytes = new byte[65_507];
            DatagramPacket incoming = new DatagramPacket(bytes, bytes.length);
            socket.receive(incoming);
            String text = new String(incoming.getData(), incoming.getOffset(), incoming.getLength(), StandardCharsets.UTF_8);
            if (!text.startsWith("OK|" + id + "|OPEN_ORDERS|")) throw new IllegalStateException("Unexpected UDP reply: " + text);
            return System.nanoTime() - start;
        }
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1));
    }
}
