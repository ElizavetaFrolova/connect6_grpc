package com.connect6.server;
import com.connect6.grpc.StoneColor;

public class GameBoard {
    private final int BOARD_SIZE = 19;
    private StoneColor[][] board;
    private boolean firstMove;

    public GameBoard() {
        board = new StoneColor[BOARD_SIZE][BOARD_SIZE];
        initializeBoard();
        firstMove = true;
    }

    private void initializeBoard() {
        for (int i = 0; i < BOARD_SIZE; i++) {
            for (int j = 0; j < BOARD_SIZE; j++) {
                board[i][j] = StoneColor.EMPTY;
            }
        }
    }

    public boolean placeStones(int x1, int y1, int x2, int y2, StoneColor color) {
        if (firstMove && color == StoneColor.BLACK) {
            if (x2 != -1 || y2 != -1) {
                return false;
            }
            if (!isValidPosition(x1, y1) || x1 != 9 || y1 != 9) {
                return false;
            }
            if (board[x1][y1] != StoneColor.EMPTY) {
                return false;
            }

            board[x1][y1] = color;
            firstMove = false;
            return true;
        }

        else {
            if (x1 == -1 || y1 == -1 || x2 == -1 || y2 == -1) {
                return false;
            }
            if (!isValidPosition(x1, y1) || !isValidPosition(x2, y2)) {
                return false;
            }
            if (board[x1][y1] != StoneColor.EMPTY || board[x2][y2] != StoneColor.EMPTY) {
                return false;
            }
            if (x1 == x2 && y1 == y2) {
                return false;
            }

            board[x1][y1] = color;
            board[x2][y2] = color;
            firstMove = false;
            return true;
        }
    }

    private boolean isValidPosition(int x, int y) {
        return x >= 0 && x < BOARD_SIZE && y >= 0 && y < BOARD_SIZE;
    }

    public StoneColor checkWinner() {
        for (int i = 0; i < BOARD_SIZE; i++) {
            for (int j = 0; j < BOARD_SIZE; j++) {
                if (board[i][j] != StoneColor.EMPTY) {
                    if (checkLine(i, j, 1, 0) ||
                            checkLine(i, j, 0, 1) ||
                            checkLine(i, j, 1, 1) ||
                            checkLine(i, j, 1, -1)) {
                        return board[i][j];
                    }
                }
            }
        }
        return StoneColor.EMPTY;
    }

    private boolean checkLine(int x, int y, int dx, int dy) {
        StoneColor color = board[x][y];
        if (color == StoneColor.EMPTY) return false;

        int count = 1;

        for (int i = 1; i < 6; i++) {
            int newX = x + i * dx;
            int newY = y + i * dy;
            if (!isValidPosition(newX, newY) || board[newX][newY] != color) {
                break;
            }
            count++;
        }

        for (int i = 1; i < 6; i++) {
            int newX = x - i * dx;
            int newY = y - i * dy;
            if (!isValidPosition(newX, newY) || board[newX][newY] != color) {
                break;
            }
            count++;
        }

        return count >= 6;
    }
    public boolean isFirstMove() { return firstMove; }
    public int getBoardSize() { return BOARD_SIZE; }
}