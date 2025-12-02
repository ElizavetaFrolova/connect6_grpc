package com.connect6.client;

import com.connect6.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.TimeUnit;

public class WorkingClient extends JFrame {
    private ManagedChannel channel;
    private Connect6GameGrpc.Connect6GameStub asyncStub;
    private Connect6GameGrpc.Connect6GameBlockingStub blockingStub;

    private JTextArea logArea;
    private JButton connectBtn;
    private GamePanel gamePanel;
    private String playerName;

    private int playerId;
    private StoneColor myColor = StoneColor.EMPTY;
    private boolean myTurn = false;
    private boolean gameStarted = false;

    // Для выбора хода
    private int selectedX1 = -1, selectedY1 = -1;
    private boolean selectingFirst = true;
    private boolean isFirstMoveOfGame = true;

    // Игровая доска
    private StoneColor[][] board = new StoneColor[19][19];

    public WorkingClient(String name) {
        this.playerName = name;
        initializeBoard();
        initializeUI();
    }

    private void initializeBoard() {
        for (int i = 0; i < 19; i++) {
            for (int j = 0; j < 19; j++) {
                board[i][j] = StoneColor.EMPTY;
            }
        }
    }

    private void initializeUI() {
        setTitle("Connect6 - " + playerName);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Панель логов
        logArea = new JTextArea(10, 50);
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.SOUTH);

        // Игровая панель
        gamePanel = new GamePanel();
        add(gamePanel, BorderLayout.CENTER);

        // Панель кнопок
        JPanel buttonPanel = new JPanel();
        connectBtn = new JButton("Подключиться к игре");
        connectBtn.addActionListener(e -> connectToGame());

        buttonPanel.add(connectBtn);
        add(buttonPanel, BorderLayout.NORTH);

        setSize(700, 800);
        setLocationRelativeTo(null);
        setVisible(true);

        log("Клиент готов: " + playerName);
    }

    // Внутренний класс для игровой панели
    class GamePanel extends JPanel {
        private static final int CELL_SIZE = 35;
        private static final int BOARD_SIZE = 19;

        public GamePanel() {
            setPreferredSize(new Dimension(BOARD_SIZE * CELL_SIZE, BOARD_SIZE * CELL_SIZE));
            setBackground(new Color(220, 179, 92)); // Цвет доски

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!gameStarted || !myTurn) {
                        log("Сейчас не ваш ход!");
                        return;
                    }

                    int x = e.getX() / CELL_SIZE;
                    int y = e.getY() / CELL_SIZE;

                    if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
                        return;
                    }

                    handleCellClick(x, y);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            drawBoard(g);
            drawStones(g);

            // Рисуем выделение выбранной клетки
            if (selectedX1 != -1 && selectedY1 != -1) {
                g.setColor(Color.RED);
                g.drawRect(selectedX1 * CELL_SIZE, selectedY1 * CELL_SIZE,
                        CELL_SIZE, CELL_SIZE);
            }
        }

        private void drawBoard(Graphics g) {
            g.setColor(Color.BLACK);

            // Вертикальные линии
            for (int i = 0; i < BOARD_SIZE; i++) {
                g.drawLine(i * CELL_SIZE + CELL_SIZE / 2, CELL_SIZE / 2,
                        i * CELL_SIZE + CELL_SIZE / 2,
                        (BOARD_SIZE - 1) * CELL_SIZE + CELL_SIZE / 2);
            }

            // Горизонтальные линии
            for (int i = 0; i < BOARD_SIZE; i++) {
                g.drawLine(CELL_SIZE / 2, i * CELL_SIZE + CELL_SIZE / 2,
                        (BOARD_SIZE - 1) * CELL_SIZE + CELL_SIZE / 2,
                        i * CELL_SIZE + CELL_SIZE / 2);
            }

            // Центральная точка
            g.fillOval(9 * CELL_SIZE + CELL_SIZE / 2 - 3,
                    9 * CELL_SIZE + CELL_SIZE / 2 - 3, 6, 6);
        }

        private void drawStones(Graphics g) {
            for (int i = 0; i < BOARD_SIZE; i++) {
                for (int j = 0; j < BOARD_SIZE; j++) {
                    if (board[i][j] != StoneColor.EMPTY) {
                        g.setColor(board[i][j] == StoneColor.BLACK ?
                                Color.BLACK : Color.WHITE);
                        g.fillOval(i * CELL_SIZE + 3, j * CELL_SIZE + 3,
                                CELL_SIZE - 6, CELL_SIZE - 6);
                        g.setColor(Color.GRAY);
                        g.drawOval(i * CELL_SIZE + 3, j * CELL_SIZE + 3,
                                CELL_SIZE - 6, CELL_SIZE - 6);
                    }
                }
            }
        }
    }

    private void handleCellClick(int x, int y) {
        // Проверка, что клетка свободна
        if (board[x][y] != StoneColor.EMPTY) {
            log("Клетка уже занята!");
            return;
        }

        // Первый ход черных - один камень в центр
        if (isFirstMoveOfGame && myColor == StoneColor.BLACK) {
            if (x == 9 && y == 9) {
                sendMove(x, y, -1, -1);
                board[x][y] = myColor;
                isFirstMoveOfGame = false;
                myTurn = false;
                gamePanel.repaint();
                log("Первый ход сделан! Ожидайте белых...");
            } else {
                log("Первый ход черных должен быть в центр (9,9)!");
            }
            return;
        }

        // Обычный ход (выбор двух камней)
        if (selectingFirst) {
            selectedX1 = x;
            selectedY1 = y;
            selectingFirst = false;
            log("Выбрана первая позиция (" + x + "," + y + "). Выберите вторую.");
            gamePanel.repaint();
        } else {
            if (x == selectedX1 && y == selectedY1) {
                log("Нельзя выбрать ту же клетку!");
                return;
            }

            sendMove(selectedX1, selectedY1, x, y);

            // Обновляем доску
            board[selectedX1][selectedY1] = myColor;
            board[x][y] = myColor;

            // Сбрасываем состояние
            selectedX1 = selectedY1 = -1;
            selectingFirst = true;
            isFirstMoveOfGame = false;
            myTurn = false;

            gamePanel.repaint();
            log("Ход отправлен! Ожидайте ответа противника...");
        }
    }

    private void sendMove(int x1, int y1, int x2, int y2) {
        Position pos1 = Position.newBuilder().setX(x1).setY(y1).build();
        Position pos2 = Position.newBuilder().setX(x2).setY(y2).build();

        MoveRequest request = MoveRequest.newBuilder()
                .setPlayerId(playerId)
                .setPosition1(pos1)
                .setPosition2(pos2)
                .build();

        // Используем блокирующий вызов для простоты
        new Thread(() -> {
            try {
                MoveResponse response = blockingStub.makeMove(request);

                SwingUtilities.invokeLater(() -> {
                    if (response.getSuccess()) {
                        log("✓ Ход принят сервером: " + response.getMessage());
                    } else {
                        log("❌ Ошибка хода: " + response.getMessage());
                        myTurn = true; // Возвращаем ход
                    }
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    log("❌ Ошибка отправки хода: " + e.getMessage());
                    myTurn = true;
                });
            }
        }).start();
    }

    private void connectToGame() {
        connectBtn.setEnabled(false);

        new Thread(() -> {
            try {
                log("Создаем соединение с сервером...");

                channel = ManagedChannelBuilder.forAddress("localhost", 8080)
                        .usePlaintext()
                        .keepAliveTime(30, TimeUnit.SECONDS)
                        .keepAliveTimeout(5, TimeUnit.SECONDS)
                        .keepAliveWithoutCalls(true)
                        .build();

                asyncStub = Connect6GameGrpc.newStub(channel);
                blockingStub = Connect6GameGrpc.newBlockingStub(channel);

                log("Соединение установлено. Отправляем запрос...");

                // ДВУСТОРОННИЙ ПОТОКОВЫЙ вызов
                StreamObserver<ConnectRequest> requestObserver =
                        asyncStub.connectPlayer(new StreamObserver<ConnectResponse>() {

                            @Override
                            public void onNext(ConnectResponse response) {
                                SwingUtilities.invokeLater(() -> {
                                    log("=== Ответ сервера ===");
                                    log("ID игрока: " + response.getPlayerId());
                                    log("Цвет: " + response.getColor());
                                    log("Сообщение: " + response.getMessage());
                                    log("===================");

                                    playerId = response.getPlayerId();
                                    myColor = response.getColor();

                                    if (myColor != StoneColor.EMPTY) {
                                        gameStarted = true;

                                        log("✨✨✨ ИГРА НАЧАЛАСЬ! ✨✨✨");
                                        log("Вы играете за " +
                                                (myColor == StoneColor.BLACK ? "ЧЁРНЫХ" : "БЕЛЫХ"));

                                        if (myColor == StoneColor.BLACK) {
                                            myTurn = true;
                                            log("⚠ ВАЖНО: Первый ход черных - ОДИН камень в центр доски (9,9)");
                                        } else {
                                            log("⏳ Ожидайте ход черных...");
                                        }

                                        // Подписываемся на обновления
                                        subscribeToUpdates(playerId);
                                    } else {
                                        log("⏳ Ожидаем второго игрока... " + response.getMessage());
                                    }
                                });
                            }

                            @Override
                            public void onError(Throwable t) {
                                SwingUtilities.invokeLater(() -> {
                                    log("❌ Ошибка соединения: " + t.getMessage());
                                    connectBtn.setEnabled(true);
                                });
                            }

                            @Override
                            public void onCompleted() {
                                SwingUtilities.invokeLater(() -> {
                                    log("✓ Соединение с сервером завершено");
                                });
                            }
                        });

                // Отправляем запрос на подключение
                ConnectRequest request = ConnectRequest.newBuilder()
                        .setPlayerName(playerName)
                        .build();

                requestObserver.onNext(request);
                // НЕ закрываем соединение!

                // Держим соединение живым
                while (true) {
                    Thread.sleep(1000);
                }

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    log("❌ Ошибка: " + e.getMessage());
                    connectBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private void subscribeToUpdates(int playerId) {
        log("Подписываюсь на обновления игры...");

        UpdateRequest request = UpdateRequest.newBuilder()
                .setPlayerId(playerId)
                .build();

        asyncStub.getGameUpdates(request, new StreamObserver<GameUpdate>() {
            @Override
            public void onNext(GameUpdate update) {
                SwingUtilities.invokeLater(() -> {
                    log("📢 Обновление: " + update.getType() + " - " + update.getMessage());

                    // Обработка ходов противника
                    if (update.getType() == GameUpdate.UpdateType.PLAYER_MOVED &&
                            update.getPlayerId() != playerId) {

                        // Обновляем доску
                        if (update.hasPosition1()) {
                            Position pos1 = update.getPosition1();
                            board[pos1.getX()][pos1.getY()] = update.getColor();
                        }
                        if (update.hasPosition2() &&
                                update.getPosition2().getX() != -1 &&
                                update.getPosition2().getY() != -1) {
                            Position pos2 = update.getPosition2();
                            board[pos2.getX()][pos2.getY()] = update.getColor();
                        }

                        gamePanel.repaint();
                        myTurn = true;
                        log("✓ Ход противника принят. Теперь ваш ход!");
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    log("❌ Ошибка в обновлениях: " + t.getMessage());
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
        String name = args.length > 0 ? args[0] : "Игрок";
        SwingUtilities.invokeLater(() -> new WorkingClient(name));
    }
}