package com.connect6.client;

import com.connect6.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.TimeUnit;

public class WorkingClient extends JFrame {
    private ManagedChannel channel;
    private Connect6GameGrpc.Connect6GameStub asyncStub;
    private Connect6GameGrpc.Connect6GameBlockingStub blockingStub;

    private int playerId;
    private StoneColor myColor = StoneColor.EMPTY;
    private boolean myTurn = false;
    private boolean gameStarted = false;

    private GamePanel gamePanel;
    private JLabel statusLabel;

    private int previewX1 = -1, previewY1 = -1;
    private boolean showingPreview = false;
    private boolean selectingFirst = true;
    private boolean isFirstMoveOfGame = true;

    private StoneColor[][] board = new StoneColor[19][19];

    public WorkingClient() {
        initializeBoard();
        initializeGUI();
        connectToServer();
    }

    private void initializeBoard() {
        for (int i = 0; i < 19; i++) {
            for (int j = 0; j < 19; j++) {
                board[i][j] = StoneColor.EMPTY;
            }
        }
    }

    private void initializeGUI() {
        setTitle("Connect6 Game (gRPC)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        gamePanel = new GamePanel();
        add(gamePanel, BorderLayout.CENTER);

        statusLabel = new JLabel("Подключение к серверу Connect6...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusLabel.setFont(new Font("Arial", Font.BOLD, 12));
        add(statusLabel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void connectToServer() {
        statusLabel.setText("Подключение к серверу...");

        new Thread(() -> {
            try {
                channel = ManagedChannelBuilder.forAddress("localhost", 8080)
                        .usePlaintext()
                        .keepAliveTime(30, TimeUnit.SECONDS)
                        .keepAliveTimeout(5, TimeUnit.SECONDS)
                        .keepAliveWithoutCalls(true)
                        .build();

                asyncStub = Connect6GameGrpc.newStub(channel);
                blockingStub = Connect6GameGrpc.newBlockingStub(channel);

                StreamObserver<ConnectRequest> requestObserver =
                        asyncStub.connectPlayer(new StreamObserver<ConnectResponse>() {

                            @Override
                            public void onNext(ConnectResponse response) {
                                SwingUtilities.invokeLater(() -> handleConnectResponse(response));
                            }

                            @Override
                            public void onError(Throwable t) {
                                SwingUtilities.invokeLater(() -> {
                                    statusLabel.setText("Ошибка подключения: " + t.getMessage());
                                    JOptionPane.showMessageDialog(WorkingClient.this,
                                            "Ошибка: " + t.getMessage(),
                                            "Ошибка подключения", JOptionPane.ERROR_MESSAGE);
                                    System.exit(1);
                                });
                            }

                            @Override
                            public void onCompleted() {

                            }
                        });

                ConnectRequest request = ConnectRequest.newBuilder()
                        .setPlayerName("Игрок")
                        .build();

                requestObserver.onNext(request);

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Ошибка: " + e.getMessage());
                    JOptionPane.showMessageDialog(WorkingClient.this,
                            "Не удалось подключиться к серверу",
                            "Ошибка", JOptionPane.ERROR_MESSAGE);
                    System.exit(1);
                });
            }
        }).start();
    }

    private void handleConnectResponse(ConnectResponse response) {
        playerId = response.getPlayerId();
        myColor = response.getColor();

        statusLabel.setText(response.getMessage());

        if (myColor != StoneColor.EMPTY) {
            gameStarted = true;

            if (myColor == StoneColor.BLACK) {
                myTurn = true;
            }

            subscribeToGameUpdates();
        }
    }

    private void subscribeToGameUpdates() {
        UpdateRequest request = UpdateRequest.newBuilder()
                .setPlayerId(playerId)
                .build();

        asyncStub.getGameUpdates(request, new StreamObserver<GameUpdate>() {
            @Override
            public void onNext(GameUpdate update) {
                SwingUtilities.invokeLater(() -> handleGameUpdate(update));
            }

            @Override
            public void onError(Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Ошибка получения обновлений: " + t.getMessage());
                });
            }

            @Override
            public void onCompleted() {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Игра завершена");
                });
            }
        });
    }

    private void handleGameUpdate(GameUpdate update) {
        switch (update.getType()) {
            case GAME_STARTED:
                statusLabel.setText(update.getMessage());
                break;

            case PLAYER_MOVED:
                if (!gameStarted) {
                    return;
                }

                if (update.getPlayerId() != playerId) {
                    Position pos1 = update.getPosition1();
                    Position pos2 = update.getPosition2();

                    board[pos1.getX()][pos1.getY()] = update.getColor();
                    if (pos2.getX() != -1 && pos2.getY() != -1) {
                        board[pos2.getX()][pos2.getY()] = update.getColor();
                    }
                    gamePanel.repaint();
                    gamePanel.clearPreview();

                    if (update.getColor() != myColor) {
                        myTurn = true;

                        if (isFirstMoveOfGame && myColor == StoneColor.WHITE) {
                            statusLabel.setText("Ваш ход (первый ход белых)");
                            isFirstMoveOfGame = false;
                        } else {
                            statusLabel.setText("Ваш ход");
                        }
                    } else {
                        myTurn = false;
                        statusLabel.setText("Ход противника");
                    }
                }
                break;

            case GAME_OVER:
                gameStarted = false;
                myTurn = false;

                String playerResult = update.getColor() == myColor ?
                        "Вы победили!" : "Вы проиграли!";

                statusLabel.setText(playerResult);
                gamePanel.clearPreview();

                JOptionPane.showMessageDialog(
                        WorkingClient.this,
                        update.getMessage() + "\n" + playerResult,
                        "Игра окончена",
                        JOptionPane.INFORMATION_MESSAGE
                );
                new Timer(3000, e -> disconnect()).start();
                break;

            case ERROR:
                if (!gameStarted) {
                    return;
                }

                gamePanel.clearPreview();
                selectingFirst = true;

                JOptionPane.showMessageDialog(WorkingClient.this,
                        update.getMessage(),
                        "Ошибка хода",
                        JOptionPane.ERROR_MESSAGE);

                if (myTurn) {
                    statusLabel.setText("Ваш ход (исправьте ход)");
                }
                break;
        }
    }

    private void disconnect() {
        if (channel != null) {
            channel.shutdown();
            try {
                channel.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.exit(0);
    }

    private void sendMove(int x1, int y1, int x2, int y2) {
        if (!gameStarted || !myTurn) return;

        Position pos1 = Position.newBuilder()
                .setX(x1)
                .setY(y1)
                .build();

        Position pos2 = Position.newBuilder()
                .setX(x2)
                .setY(y2)
                .build();

        MoveRequest request = MoveRequest.newBuilder()
                .setPlayerId(playerId)
                .setPosition1(pos1)
                .setPosition2(pos2)
                .build();

        new Thread(() -> {
            try {
                MoveResponse response = blockingStub.makeMove(request);

                SwingUtilities.invokeLater(() -> {
                    if (response.getSuccess()) {
                        board[x1][y1] = myColor;
                        if (x2 != -1 && y2 != -1) {
                            board[x2][y2] = myColor;
                        }

                        myTurn = false;
                        statusLabel.setText("Ход противника");
                        gamePanel.clearPreview();
                        gamePanel.repaint();
                    } else {
                        statusLabel.setText("Ошибка хода: " + response.getMessage());
                        myTurn = true;
                        JOptionPane.showMessageDialog(WorkingClient.this,
                                response.getMessage(),
                                "Ошибка хода", JOptionPane.ERROR_MESSAGE);
                    }
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Ошибка отправки хода: " + e.getMessage());
                    myTurn = true;
                });
            }
        }).start();
    }

    class GamePanel extends JPanel {
        private static final int CELL_SIZE = 30;
        private static final int BOARD_SIZE = 19;

        public GamePanel() {
            setPreferredSize(new Dimension(BOARD_SIZE * CELL_SIZE, BOARD_SIZE * CELL_SIZE));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!myTurn) {
                        JOptionPane.showMessageDialog(WorkingClient.this,
                                "Сейчас не ваш ход!", "Предупреждение", JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                    int x = e.getX() / CELL_SIZE;
                    int y = e.getY() / CELL_SIZE;

                    if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
                        JOptionPane.showMessageDialog(WorkingClient.this,
                                "Координаты вне доски!", "Ошибка", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    if (board[x][y] != StoneColor.EMPTY) {
                        JOptionPane.showMessageDialog(WorkingClient.this,
                                "Эта клетка уже занята!", "Ошибка", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    if (isFirstMoveOfGame && myColor == StoneColor.BLACK) {
                        if (x == 9 && y == 9) {
                            showingPreview = false;
                            repaint();

                            sendMove(x, y, -1, -1);
                            myTurn = false;
                            isFirstMoveOfGame = false;
                            statusLabel.setText("Ход противника");
                        } else {
                            JOptionPane.showMessageDialog(WorkingClient.this,
                                    "Первый ход черных должен быть в центр доски (9,9)!",
                                    "Первый ход", JOptionPane.INFORMATION_MESSAGE);
                        }
                        return;
                    }

                    if (selectingFirst) {
                        previewX1 = x;
                        previewY1 = y;
                        selectingFirst = false;
                        showingPreview = true;

                        if (isFirstMoveOfGame && myColor == StoneColor.WHITE) {
                            statusLabel.setText("Выберите вторую позицию (первый ход белых)");
                        } else {
                            statusLabel.setText("Выберите вторую позицию");
                        }

                        repaint();
                    } else {
                        if (x == previewX1 && y == previewY1) {
                            JOptionPane.showMessageDialog(WorkingClient.this,
                                    "Нельзя выбрать ту же клетку для второго камня!",
                                    "Ошибка", JOptionPane.ERROR_MESSAGE);
                            return;
                        }

                        showingPreview = false;
                        repaint();

                        sendMove(previewX1, previewY1, x, y);
                        selectingFirst = true;
                        if (isFirstMoveOfGame) {
                            isFirstMoveOfGame = false;
                        }
                        myTurn = false;
                        statusLabel.setText("Ход противника");
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            drawBoard(g);
            drawStones(g);

            if (showingPreview && myTurn) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g2d.setColor(Color.GRAY);

                if (previewX1 != -1 && previewY1 != -1) {
                    g2d.fillOval(previewX1 * CELL_SIZE + 2, previewY1 * CELL_SIZE + 2,
                            CELL_SIZE - 4, CELL_SIZE - 4);
                }

                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
            }
        }

        private void drawBoard(Graphics g) {
            g.setColor(new Color(220, 179, 92));
            g.fillRect(0, 0, getWidth(), getHeight());

            g.setColor(Color.BLACK);
            for (int i = 0; i < BOARD_SIZE; i++) {
                g.drawLine(CELL_SIZE / 2, i * CELL_SIZE + CELL_SIZE / 2,
                        (BOARD_SIZE - 1) * CELL_SIZE + CELL_SIZE / 2,
                        i * CELL_SIZE + CELL_SIZE / 2);
                g.drawLine(i * CELL_SIZE + CELL_SIZE / 2, CELL_SIZE / 2,
                        i * CELL_SIZE + CELL_SIZE / 2,
                        (BOARD_SIZE - 1) * CELL_SIZE + CELL_SIZE / 2);
            }
        }

        private void drawStones(Graphics g) {
            for (int i = 0; i < BOARD_SIZE; i++) {
                for (int j = 0; j < BOARD_SIZE; j++) {
                    if (board[i][j] != StoneColor.EMPTY) {
                        g.setColor(board[i][j] == StoneColor.BLACK ? Color.BLACK : Color.WHITE);
                        g.fillOval(i * CELL_SIZE + 2, j * CELL_SIZE + 2,
                                CELL_SIZE - 4, CELL_SIZE - 4);
                        g.setColor(Color.GRAY);
                        g.drawOval(i * CELL_SIZE + 2, j * CELL_SIZE + 2,
                                CELL_SIZE - 4, CELL_SIZE - 4);
                    }
                }
            }
        }

        public void clearPreview() {
            showingPreview = false;
            previewX1 = -1;
            previewY1 = -1;
            repaint();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new WorkingClient();
        });
    }
}