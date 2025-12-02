package com.connect6.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;

public class Connect6GrpcServer {
    private final int port;
    private final Server server;

    public Connect6GrpcServer(int port) throws IOException {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new Connect6GameService())
                .build();
    }

    public void start() throws IOException {
        server.start();
        System.out.println("✅ gRPC сервер Connect6 запущен на порту " + port);
        System.out.println("✅ Ожидаем подключения игроков...");

        // Обработка завершения работы
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("⏳ Завершение сервера...");
            Connect6GrpcServer.this.stop();
            System.out.println("❌ Сервер остановлен");
        }));
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        Connect6GrpcServer server = new Connect6GrpcServer(port);
        server.start();
        server.blockUntilShutdown();
    }
}