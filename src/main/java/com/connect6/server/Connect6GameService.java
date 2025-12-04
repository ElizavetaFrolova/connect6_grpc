package com.connect6.server;

import com.connect6.grpc.*;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Connect6GameService extends Connect6GameGrpc.Connect6GameImplBase {
    private final AtomicInteger playerCounter = new AtomicInteger(1);
    private final Map<Integer, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final BlockingQueue<PlayerSession> waitingQueue = new LinkedBlockingQueue<>();
    private final Map<Integer, GameSession> activeGames = new ConcurrentHashMap<>();

    static class PlayerSession {
        final int id;
        final String name;
        StoneColor color;
        StreamObserver<ConnectResponse> connectObserver;
        StreamObserver<GameUpdate> updateObserver;
        int gameId = -1;

        PlayerSession(int id, String name, StreamObserver<ConnectResponse> connectObserver) {
            this.id = id;
            this.name = name;
            this.connectObserver = connectObserver;
        }
    }

    static class GameSession {
        final int gameId;
        final PlayerSession blackPlayer;
        final PlayerSession whitePlayer;
        GameBoard gameBoard;
        int currentPlayerId;

        GameSession(int gameId, PlayerSession blackPlayer, PlayerSession whitePlayer) {
            this.gameId = gameId;
            this.blackPlayer = blackPlayer;
            this.whitePlayer = whitePlayer;
            this.gameBoard = new GameBoard();
            this.currentPlayerId = blackPlayer.id;

            blackPlayer.gameId = gameId;
            whitePlayer.gameId = gameId;
        }

        PlayerSession getOpponent(int playerId) {
            if (playerId == blackPlayer.id) return whitePlayer;
            if (playerId == whitePlayer.id) return blackPlayer;
            return null;
        }

        boolean isPlayerTurn(int playerId) {
            return currentPlayerId == playerId;
        }

        void switchTurn() {
            currentPlayerId = (currentPlayerId == blackPlayer.id) ?
                    whitePlayer.id : blackPlayer.id;
        }

    }

    public Connect6GameService() {
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                try {
                    createGameIfPossible();
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    private void createGameIfPossible() throws InterruptedException {
        if (waitingQueue.size() >= 2) {
            PlayerSession player1 = waitingQueue.take();
            PlayerSession player2 = waitingQueue.take();

            int gameId = activeGames.size() + 1;
            GameSession game = new GameSession(gameId, player1, player2);
            activeGames.put(gameId, game);

            player1.color = StoneColor.BLACK;
            player2.color = StoneColor.WHITE;

            System.out.println("✨ Создаем игру #" + gameId + ": " +
                    player1.name + " (черные, ID:" + player1.id + ") vs " +
                    player2.name + " (белые, ID:" + player2.id + ")");

            ConnectResponse blackResponse = ConnectResponse.newBuilder()
                    .setPlayerId(player1.id)
                    .setColor(StoneColor.BLACK)
                    .setMessage("Игра началась! Вы играете черными. Первый ход: один камень в центр (9,9)")
                    .build();

            ConnectResponse whiteResponse = ConnectResponse.newBuilder()
                    .setPlayerId(player2.id)
                    .setColor(StoneColor.WHITE)
                    .setMessage("Игра началась! Вы играете белыми. Ожидайте ход черных")
                    .build();

            player1.connectObserver.onNext(blackResponse);
            player2.connectObserver.onNext(whiteResponse);

            player1.connectObserver.onCompleted();
            player2.connectObserver.onCompleted();
        }
    }

    @Override
    public StreamObserver<ConnectRequest> connectPlayer(
            StreamObserver<ConnectResponse> responseObserver) {

        return new StreamObserver<ConnectRequest>() {
            private PlayerSession session;
            private boolean connected = false;

            @Override
            public void onNext(ConnectRequest request) {
                if (!connected) {
                    int playerId = playerCounter.getAndIncrement();
                    session = new PlayerSession(playerId, request.getPlayerName(), responseObserver);

                    System.out.println("🎮 Подключение: " + request.getPlayerName() + " (ID: " + playerId + ")");

                    playerSessions.put(playerId, session);

                    try {
                        waitingQueue.put(session);

                        System.out.println("⏳ Игрок " + playerId + " добавлен в очередь ожидания. В очереди: " + waitingQueue.size());

                        ConnectResponse waitResponse = ConnectResponse.newBuilder()
                                .setPlayerId(playerId)
                                .setColor(StoneColor.EMPTY)
                                .setMessage("Ожидание второго игрока... В очереди: " + waitingQueue.size())
                                .build();
                        responseObserver.onNext(waitResponse);

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    connected = true;
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("❌ Ошибка соединения с игроком: " +
                        (session != null ? session.name : "unknown") + ": " + t.getMessage());

                if (session != null) {
                    cleanupPlayer(session.id);
                }
            }

            @Override
            public void onCompleted() {
                System.out.println("🔌 Игрок отключился: " +
                        (session != null ? session.name : "unknown"));

                if (session != null) {
                    cleanupPlayer(session.id);
                }
                responseObserver.onCompleted();
            }
        };
    }

    private void cleanupPlayer(int playerId) {
        PlayerSession session = playerSessions.remove(playerId);
        if (session != null) {
            waitingQueue.remove(session);

            if (session.gameId != -1) {
                GameSession game = activeGames.get(session.gameId);
                if (game != null) {
                    PlayerSession opponent = game.getOpponent(playerId);
                    if (opponent != null) {
                        GameUpdate gameOver = GameUpdate.newBuilder()
                                .setType(GameUpdate.UpdateType.GAME_OVER)
                                .setMessage("Противник отключился. Игра завершена.")
                                .build();
                        opponent.updateObserver.onNext(gameOver);
                        opponent.updateObserver.onCompleted();
                    }
                }
            }
        }
    }

    @Override
    public void makeMove(MoveRequest request,
                         StreamObserver<MoveResponse> responseObserver) {
        System.out.println("Ход от игрока " + request.getPlayerId() +
                ": (" + request.getPosition1().getX() + "," +
                request.getPosition1().getY() + ") и (" +
                request.getPosition2().getX() + "," +
                request.getPosition2().getY() + ")");

        int playerId = request.getPlayerId();
        PlayerSession player = playerSessions.get(playerId);

        if (player == null || player.gameId == -1) {
            sendError(responseObserver, "Игрок не в игре");
            return;
        }

        GameSession game = activeGames.get(player.gameId);
        if (game == null) {
            sendError(responseObserver, "Игра не найдена");
            return;
        }

        if (!game.isPlayerTurn(playerId)) {
            sendError(responseObserver, "Сейчас не ваш ход");
            return;
        }

        int x1 = request.getPosition1().getX();
        int y1 = request.getPosition1().getY();
        int x2 = request.getPosition2().getX();
        int y2 = request.getPosition2().getY();

        if (player.color == StoneColor.BLACK && game.gameBoard.isFirstMove()) {
            if (x2 != -1 || y2 != -1) {
                sendError(responseObserver, "Первый ход черных - только один камень");
                return;
            }
            if (x1 != 9 || y1 != 9) {
                sendError(responseObserver, "Первый ход черных должен быть в центр (9,9)");
                return;
            }
        }

        boolean success = game.gameBoard.placeStones(x1, y1, x2, y2, player.color);

        if (!success) {
            sendError(responseObserver, "Невозможно сделать ход");
            return;
        }

        StoneColor winner = game.gameBoard.checkWinner();

        PlayerSession opponent = game.getOpponent(playerId);
        if (opponent != null && opponent.updateObserver != null) {
            GameUpdate opponentUpdate = GameUpdate.newBuilder()
                    .setType(GameUpdate.UpdateType.PLAYER_MOVED)
                    .setPlayerId(playerId)
                    .setPosition1(request.getPosition1())
                    .setPosition2(request.getPosition2())
                    .setColor(player.color)
                    .build();
            opponent.updateObserver.onNext(opponentUpdate);
        }

        if (winner != StoneColor.EMPTY) {
            GameUpdate gameOver = GameUpdate.newBuilder()
                    .setType(GameUpdate.UpdateType.GAME_OVER)
                    .setColor(winner)
                    .setMessage("Игра окончена! Победитель: " +
                            (winner == StoneColor.BLACK ? "Черные" : "Белые"))
                    .build();

            if (player.updateObserver != null) {
                player.updateObserver.onNext(gameOver);
                player.updateObserver.onCompleted();
            }
            if (opponent != null && opponent.updateObserver != null) {
                opponent.updateObserver.onNext(gameOver);
                opponent.updateObserver.onCompleted();
            }

            MoveResponse response = MoveResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Игра окончена! " +
                            (winner == player.color ? "Вы победили!" : "Вы проиграли!"))
                    .build();
            responseObserver.onNext(response);

        } else {
            game.switchTurn();

            MoveResponse response = MoveResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Ход принят")
                    .build();
            responseObserver.onNext(response);
        }

        responseObserver.onCompleted();
    }

    private void sendError(StreamObserver<MoveResponse> responseObserver, String message) {
        MoveResponse error = MoveResponse.newBuilder()
                .setSuccess(false)
                .setMessage(message)
                .build();
        responseObserver.onNext(error);
        responseObserver.onCompleted();
    }

    @Override
    public void getGameUpdates(UpdateRequest request,
                               StreamObserver<GameUpdate> responseObserver) {
        int playerId = request.getPlayerId();
        System.out.println("Подписка на обновления от игрока " + playerId);

        PlayerSession player = playerSessions.get(playerId);
        if (player != null) {
            player.updateObserver = responseObserver;

            GameUpdate update = GameUpdate.newBuilder()
                    .setType(GameUpdate.UpdateType.GAME_STARTED)
                    .setMessage("Вы успешно подписались на обновления игры")
                    .build();

            responseObserver.onNext(update);
        } else {
            GameUpdate error = GameUpdate.newBuilder()
                    .setType(GameUpdate.UpdateType.ERROR)
                    .setMessage("Игрок не найден")
                    .build();

            responseObserver.onNext(error);
            responseObserver.onCompleted();
        }
    }
}