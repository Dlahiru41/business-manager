package com.bmp.printagent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The cloud as a print agent sees it (documents.yaml, plus the identity provider's token endpoint): a template, jobs
 * handed out by long polling, results kept with their idempotency keys. It can be made unreachable (503) and made to
 * lose results.
 */
final class FakeCloud implements AutoCloseable {

    static final String TOKEN = "fake-access-token";

    /** A result as the agent sent it. */
    record Result(String jobId, String body, String idempotencyKey) {}

    private final HttpServer server;
    private final BlockingQueue<String> jobs = new LinkedBlockingQueue<>();
    private final List<Result> results = new CopyOnWriteArrayList<>();
    private final Map<String, String> tokenForms = new ConcurrentHashMap<>();
    private final AtomicInteger resultsToLose = new AtomicInteger();
    private volatile boolean reachable = true;
    static final String FIRST_TEMPLATE = """
            {"version": 0, "shopName": "Lanka Mart", "addressLines": ["12 Main Street"], "footerLines": ["Thank you"],
             "paperWidthMm": 80, "showQr": false, "openDrawerOnCash": true, "language": "en"}""";

    private volatile String template = FIRST_TEMPLATE;

    private FakeCloud(HttpServer server) {
        this.server = server;
        server.createContext("/token", this::token);
        server.createContext("/api/v1/print-agents/", this::agent);
        server.createContext("/api/v1/print-jobs/", this::result);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    static FakeCloud start() {
        try {
            return new FakeCloud(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reachable(boolean reachable) {
        this.reachable = reachable;
    }

    void template(String json) {
        this.template = json;
    }

    /** Hands this job out at the agent's next poll. */
    void offer(String job) {
        jobs.add(job);
    }

    /** The next results are answered 503, as if lost on the way. */
    void loseResults(int count) {
        resultsToLose.set(count);
    }

    List<Result> results() {
        return List.copyOf(results);
    }

    Map<String, String> tokenForms() {
        return Map.copyOf(tokenForms);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void token(HttpExchange exchange) throws IOException {
        if (!reachable) {
            answer(exchange, 503, "");
            return;
        }
        String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        tokenForms.put("last", form);
        answer(exchange, 200, "{\"access_token\": \"" + TOKEN + "\", \"expires_in\": 300, \"token_type\": \"Bearer\"}");
    }

    private void agent(HttpExchange exchange) throws IOException {
        if (refused(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/template")) {
            answer(exchange, 200, template);
        } else if (path.endsWith("/jobs:next")) {
            try {
                String job = jobs.poll(1, TimeUnit.SECONDS);
                answer(exchange, job == null ? 204 : 200, job == null ? "" : job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                answer(exchange, 503, "");
            }
        } else {
            answer(exchange, 404, "");
        }
    }

    private void result(HttpExchange exchange) throws IOException {
        if (refused(exchange)) {
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (resultsToLose.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            answer(exchange, 503, "");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String jobId = path.substring("/api/v1/print-jobs/".length(), path.length() - "/result".length());
        results.add(new Result(jobId, body, exchange.getRequestHeaders().getFirst("Idempotency-Key")));
        answer(exchange, 200, "{}");
    }

    private boolean refused(HttpExchange exchange) throws IOException {
        if (!reachable) {
            answer(exchange, 503, "");
            return true;
        }
        if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            answer(exchange, 401, "");
            return true;
        }
        return false;
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
