package com.connect6.client;

import com.connect6.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import javax.swing.*;
import java.awt.*;

public class SimpleTestClient extends JFrame {
    private ManagedChannel channel;
    private Connect6GameGrpc.Connect6GameStub asyncStub;
    private JTextArea logArea;

    public SimpleTestClient(String playerName) {
        setTitle("Connect6 Test - " + playerName);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        logArea = new JTextArea(20, 50);
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        JButton connectBtn = new JButton("Подключиться");
        connectBtn.addActionListener(e -> connectToServer(playerName));

        add(connectBtn, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        log("Клиент запущен: " + playerName);
    }

    private void connectToServer(String playerName) {
        log("Подключение к серверу...");

        new Thread(() -> {
            try {
                channel = ManagedChannelBuilder.forAddress("localhost", 8080)
                        .usePlaintext()
                        .build();

                asyncStub = Connect6GameGrpc.newStub(channel);

                log("Отправляем запрос подключения...");

                // НОВАЯ СИГНАТУРА: двусторонний поток
                StreamObserver<ConnectRequest> requestObserver =
                        asyncStub.connectPlayer(new StreamObserver<ConnectResponse>() {

                            @Override
                            public void onNext(ConnectResponse response) {
                                SwingUtilities.invokeLater(() -> {
                                    log("Ответ сервера:");
                                    log("  ID: " + response.getPlayerId());
                                    log("  Цвет: " + response.getColor());
                                    log("  Сообщение: " + response.getMessage());

                                    if (response.getColor() != StoneColor.EMPTY) {
                                        log("✓ Игра началась! Подписываемся на обновления...");
                                        subscribeToUpdates(response.getPlayerId());
                                    }
                                });
                            }

                            @Override
                            public void onError(Throwable t) {
                                SwingUtilities.invokeLater(() -> {
                                    log("✗ Ошибка: " + t.getMessage());
                                    t.printStackTrace();
                                });
                            }

                            @Override
                            public void onCompleted() {
                                SwingUtilities.invokeLater(() -> {
                                    log("Соединение завершено сервером");
                                });
                            }
                        });

                // Отправляем запрос на подключение
                ConnectRequest request = ConnectRequest.newBuilder()
                        .setPlayerName(playerName)
                        .build();

                requestObserver.onNext(request);

                // Ждем и завершаем отправку
                Thread.sleep(1000);
                requestObserver.onCompleted();

                // Держим поток живым
                Thread.sleep(30000);

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    log("Ошибка: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        }).start();
    }

    private void subscribeToUpdates(int playerId) {
        UpdateRequest request = UpdateRequest.newBuilder()
                .setPlayerId(playerId)
                .build();

        asyncStub.getGameUpdates(request, new StreamObserver<GameUpdate>() {
            @Override
            public void onNext(GameUpdate update) {
                SwingUtilities.invokeLater(() -> {
                    log("Обновление игры:");
                    log("  Тип: " + update.getType());
                    log("  Сообщение: " + update.getMessage());
                });
            }

            @Override
            public void onError(Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    log("Ошибка обновлений: " + t.getMessage());
                });
            }

            @Override
            public void onCompleted() {
                SwingUtilities.invokeLater(() -> {
                    log("Поток обновлений завершен");
                });
            }
        });
    }

    private void log(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public static void main(String[] args) {
        String playerName = args.length > 0 ? args[0] : "Игрок";
        SwingUtilities.invokeLater(() -> new SimpleTestClient(playerName));
    }
}