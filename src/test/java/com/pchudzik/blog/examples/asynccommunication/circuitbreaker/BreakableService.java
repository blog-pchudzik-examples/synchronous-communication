package com.pchudzik.blog.examples.asynccommunication.circuitbreaker;

import com.google.gson.Gson;
import com.pchudzik.blog.examples.asynccommunication.Hello;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BreakableService {
    private static final Gson gson = new Gson();
    private final int serverPort = findOpenPort();
    private ResponseHandler responseHandler;
    private HttpServer httpServer;
    private ExecutorService executorService;

    public BreakableService(ResponseHandler responseHandler) {
        useResponseHandler(responseHandler);
    }

    public static String message(String text) {
        return gson.toJson(new Hello(text));
    }

    public void useResponseHandler(ResponseHandler responseHandler) {
        this.responseHandler = responseHandler;
    }

    private static int findOpenPort() {
        int fallbackPort = 8123;
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            return fallbackPort;
        }
    }

    public void startServer() throws Exception {
        executorService = Executors.newFixedThreadPool(2);
        httpServer = HttpServer.create(new InetSocketAddress(serverPort), 0);
        httpServer.createContext("/hello", (exchange -> {
            ResponseHandler.FixedResponse response = responseHandler.handleResponse();
            response.headers.forEach((key, value) -> exchange.getResponseHeaders().put(key, value));
            exchange.sendResponseHeaders(response.statusCode, response.response.getBytes().length);
            OutputStream output = exchange.getResponseBody();
            output.write(response.response.getBytes());
            output.flush();
            exchange.close();
        }));
        httpServer.setExecutor(executorService);
        httpServer.start();
    }

    public void stopServer() {
        httpServer.stop(2);
        executorService.shutdown();
    }

    public int getPort() {
        return serverPort;
    }

    public interface ResponseHandler {
        FixedResponse handleResponse();

        class FixedResponse {
            private int statusCode = 200;
            private String response = "hello";
            private Map<String, List<String>> headers = new HashMap<>();

            private FixedResponse() {
            }

            public static FixedResponse jsonResponse() {
                return new FixedResponse()
                        .withHeader("Content-type", "application/json");
            }

            public FixedResponse withStatusCode(int statusCode) {
                this.statusCode = statusCode;
                return this;
            }

            public FixedResponse withBody(String response) {
                this.response = response;
                return this;
            }

            public FixedResponse withHeader(String name, String value) {
                if (!headers.containsKey(name)) {
                    headers.put(name, new ArrayList<>());
                }
                headers.get(name).add(value);
                return this;
            }
        }
    }

    static class RandomResponseHandler implements ResponseHandler {
        private static final Random random = new Random();

        private final double errorRate;
        private final ResponseHandler okResponse;
        private final ResponseHandler errorResponse;

        RandomResponseHandler(double errorRate, ResponseHandler okResponse, ResponseHandler errorResponse) {
            this.errorRate = errorRate;
            this.okResponse = okResponse;
            this.errorResponse = errorResponse;
        }

        @Override
        public FixedResponse handleResponse() {
            if (random.nextDouble() > errorRate) {
                return okResponse.handleResponse();
            } else {
                return errorResponse.handleResponse();
            }
        }
    }

    static class FixedResponseHandler implements ResponseHandler {
        int statusCode = 200;
        String message = "hello world";

        public FixedResponseHandler() {
        }

        public FixedResponseHandler(int statusCode, String message) {
            this.statusCode = statusCode;
            this.message = message;
        }

        @Override
        public FixedResponse handleResponse() {
            return FixedResponse.jsonResponse()
                    .withStatusCode(statusCode)
                    .withBody(message(message));
        }
    }
}
