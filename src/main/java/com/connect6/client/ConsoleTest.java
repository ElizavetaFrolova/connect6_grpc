package com.connect6.client;

import com.connect6.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class ConsoleTest {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== ТЕСТ gRPC соединения (двусторонний поток) ===");

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 8080)
                .usePlaintext()
                .build();

        try {
            Connect6GameGrpc.Connect6GameStub stub = Connect6GameGrpc.newStub(channel);

            String playerName = args.length > 0 ? args[0] : "ТестИгрок";

            System.out.println("Отправляем запрос как: " + playerName);

            // ДВУСТОРОННИЙ ПОТОКОВЫЙ вызов
            // connectPlayer теперь принимает только StreamObserver<ConnectResponse>
            // и возвращает StreamObserver<ConnectRequest> для отправки запросов
            StreamObserver<ConnectRequest> requestObserver =
                    stub.connectPlayer(new StreamObserver<ConnectResponse>() {

                        @Override
                        public void onNext(ConnectResponse response) {
                            System.out.println("\n✅ Ответ сервера:");
                            System.out.println("   ID: " + response.getPlayerId());
                            System.out.println("   Цвет: " + response.getColor());
                            System.out.println("   Сообщение: " + response.getMessage());
                        }

                        @Override
                        public void onError(Throwable t) {
                            System.err.println("❌ Ошибка: " + t.getMessage());
                            t.printStackTrace();
                        }

                        @Override
                        public void onCompleted() {
                            System.out.println("Завершено");
                        }
                    });

            // Отправляем запрос на подключение через requestObserver
            ConnectRequest request = ConnectRequest.newBuilder()
                    .setPlayerName(playerName)
                    .build();

            requestObserver.onNext(request);

            // Ждем немного и завершаем отправку запросов
            Thread.sleep(1000);
            requestObserver.onCompleted();

            // Ждем ответ
            System.out.println("Ожидаем ответа... (30 сек)");
            Thread.sleep(30000);

        } finally {
            channel.shutdown();
        }
    }
}