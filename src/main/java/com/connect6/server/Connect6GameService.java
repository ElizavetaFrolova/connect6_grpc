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

    // Существующие поля...
    private final Map<Integer, Boolean> newGameRequests = new ConcurrentHashMap<>();
    private final BlockingQueue<PlayerSession> newGameWaitingQueue = new LinkedBlockingQueue<>();
    // ... остальной код

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
        int currentPlayerId; // ID игрока, чей ход

        GameSession(int gameId, PlayerSession blackPlayer, PlayerSession whitePlayer) {
            this.gameId = gameId;
            this.blackPlayer = blackPlayer;
            this.whitePlayer = whitePlayer;
            this.gameBoard = new GameBoard();
            this.currentPlayerId = blackPlayer.id; // Черные ходят первыми

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

        // ДОБАВЛЯЕМ ЭТОТ МЕТОД:
        void resetGame() {
            this.gameBoard = new GameBoard();
            this.currentPlayerId = blackPlayer.id; // Черные ходят первыми
        }
    }

    public Connect6GameService() {
        // Фоновая задача для создания игр
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

            // Уведомляем черного игрока
            ConnectResponse blackResponse = ConnectResponse.newBuilder()
                    .setPlayerId(player1.id)
                    .setColor(StoneColor.BLACK)
                    .setMessage("Игра началась! Вы играете черными. Первый ход: один камень в центр (9,9)")
                    .build();

            // Уведомляем белого игрока
            ConnectResponse whiteResponse = ConnectResponse.newBuilder()
                    .setPlayerId(player2.id)
                    .setColor(StoneColor.WHITE)
                    .setMessage("Игра началась! Вы играете белыми. Ожидайте ход черных")
                    .build();

            player1.connectObserver.onNext(blackResponse);
            player2.connectObserver.onNext(whiteResponse);

            // Закрываем соединения для connectPlayer
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

                    // Добавляем в очередь ожидания
                    try {
                        waitingQueue.put(session);

                        System.out.println("⏳ Игрок " + playerId + " добавлен в очередь ожидания. В очереди: " + waitingQueue.size());

                        // Отправляем ответ о ожидании
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
            newGameWaitingQueue.remove(session);  // ← ДОБАВЬТЕ ЭТУ СТРОКУ
            newGameRequests.remove(playerId);     // ← ДОБАВЬТЕ ЭТУ СТРОКУ

            // Если игрок был в игре, завершаем игру
            if (session.gameId != -1) {
                GameSession game = activeGames.remove(session.gameId);
                if (game != null) {
                    PlayerSession opponent = game.getOpponent(playerId);
                    if (opponent != null && opponent.updateObserver != null) {
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

        // Проверяем ход
        int x1 = request.getPosition1().getX();
        int y1 = request.getPosition1().getY();
        int x2 = request.getPosition2().getX();
        int y2 = request.getPosition2().getY();

        // Для первого хода черных
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

        // Размещаем камни на доске
        boolean success = game.gameBoard.placeStones(x1, y1, x2, y2, player.color);

        if (!success) {
            sendError(responseObserver, "Невозможно сделать ход");
            return;
        }

        // Проверяем победителя
        StoneColor winner = game.gameBoard.checkWinner();

        // Уведомляем противника
        PlayerSession opponent = game.getOpponent(playerId);
        if (opponent != null && opponent.updateObserver != null) {
            GameUpdate opponentUpdate = GameUpdate.newBuilder()
                    .setType(GameUpdate.UpdateType.PLAYER_MOVED)
                    .setPlayerId(playerId)
                    .setPosition1(request.getPosition1())
                    .setPosition2(request.getPosition2())
                    .setColor(player.color)
                    .setMessage("Противник сделал ход")
                    .build();
            opponent.updateObserver.onNext(opponentUpdate);
        }

        if (winner != StoneColor.EMPTY) {
            // Конец игры
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

            // Удаляем игру
            activeGames.remove(player.gameId);
            player.gameId = -1;
            if (opponent != null) opponent.gameId = -1;

            MoveResponse response = MoveResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Ход принят. Игра окончена! " +
                            (winner == player.color ? "Вы победили!" : "Вы проиграли!"))
                    .build();
            responseObserver.onNext(response);

        } else {
            // Продолжаем игру
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

            // Отправляем начальное обновление
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

    @Override
    public void requestNewGame(NewGameRequest request,
                               StreamObserver<NewGameResponse> responseObserver) {
        int playerId = request.getPlayerId();
        PlayerSession player = playerSessions.get(playerId);

        if (player == null) {
            NewGameResponse response = NewGameResponse.newBuilder()
                    .setAccepted(false)
                    .setMessage("Игрок не найден")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }

        System.out.println("📝 Игрок " + playerId + " запросил новую игру");

        // Добавляем игрока в очередь ожидания новой игры
        try {
            newGameWaitingQueue.put(player);
            newGameRequests.put(playerId, true);

            NewGameResponse response = NewGameResponse.newBuilder()
                    .setAccepted(true)
                    .setMessage("Запрос принят. Ожидаем второго игрока...")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

            // Запускаем поиск пары для новой игры
            tryCreateNewGameFromQueue();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            NewGameResponse response = NewGameResponse.newBuilder()
                    .setAccepted(false)
                    .setMessage("Ошибка: " + e.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    private void tryCreateNewGameFromQueue() {
        new Thread(() -> {
            try {
                // Ждем двух игроков
                if (newGameWaitingQueue.size() >= 2) {
                    PlayerSession player1 = newGameWaitingQueue.take();
                    PlayerSession player2 = newGameWaitingQueue.take();

                    // Убираем из запросов
                    newGameRequests.remove(player1.id);
                    newGameRequests.remove(player2.id);

                    // Создаем новую игру
                    createNewGameForPlayers(player1, player2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void createNewGameForPlayers(PlayerSession player1, PlayerSession player2) {
        int gameId = activeGames.size() + 1;

        // Определяем цвета для новой игры
        // Чередуем цвета: если в предыдущей игре был черным, теперь будет белым
        StoneColor color1 = (player1.color == StoneColor.BLACK) ?
                StoneColor.WHITE : StoneColor.BLACK;
        StoneColor color2 = (color1 == StoneColor.BLACK) ?
                StoneColor.WHITE : StoneColor.BLACK;

        // Создаем новую игровую сессию
        GameSession game = new GameSession(gameId,
                color1 == StoneColor.BLACK ? player1 : player2,
                color2 == StoneColor.BLACK ? player1 : player2);

        activeGames.put(gameId, game);

        // Обновляем цвета у игроков
        player1.color = color1;
        player2.color = color2;
        player1.gameId = gameId;
        player2.gameId = gameId;

        System.out.println("🔄 Новая игра #" + gameId + ": " +
                player1.name + " (" + player1.color + ", ID:" + player1.id + ") vs " +
                player2.name + " (" + player2.color + ", ID:" + player2.id + ")");

        // Уведомляем игроков
        notifyPlayersNewGameStarted(player1, player2, game);
    }

    private void notifyPlayersNewGameStarted(PlayerSession player1, PlayerSession player2, GameSession game) {
        // Уведомляем игрока 1
        if (player1.updateObserver != null) {
            GameUpdate player1Update = GameUpdate.newBuilder()
                    .setType(GameUpdate.UpdateType.GAME_STARTED)
                    .setMessage("Новая игра началась! Вы играете " +
                            (player1.color == StoneColor.BLACK ? "черными" : "белыми"))
                    .build();
            player1.updateObserver.onNext(player1Update);

            // Если это черные, уведомляем о ходе
            if (player1.color == StoneColor.BLACK) {
                GameUpdate blackTurn = GameUpdate.newBuilder()
                        .setType(GameUpdate.UpdateType.PLAYER_MOVED)
                        .setMessage("Ваш ход (первый ход - один камень в центр)")
                        .build();
                player1.updateObserver.onNext(blackTurn);
            }
        }

        // Уведомляем игрока 2
        if (player2.updateObserver != null) {
            GameUpdate player2Update = GameUpdate.newBuilder()
                    .setType(GameUpdate.UpdateType.GAME_STARTED)
                    .setMessage("Новая игра началась! Вы играете " +
                            (player2.color == StoneColor.BLACK ? "черными" : "белыми"))
                    .build();
            player2.updateObserver.onNext(player2Update);

            // Если это черные, уведомляем о ходе
            if (player2.color == StoneColor.BLACK) {
                GameUpdate blackTurn = GameUpdate.newBuilder()
                        .setType(GameUpdate.UpdateType.PLAYER_MOVED)
                        .setMessage("Ваш ход (первый ход - один камень в центр)")
                        .build();
                player2.updateObserver.onNext(blackTurn);
            }
        }
    }
}