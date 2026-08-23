package io.github.dingljcn.games.guessword.entity;

import io.github.dingljcn.games.guessword.service.WordPoolService;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Room {
    public enum State { GROUPING, BLUE_TURN, RED_TURN, GAME_OVER }

    private String id;
    private State state = State.GROUPING;
    private List<Player> players = new CopyOnWriteArrayList<>();
    private List<Card> board = new ArrayList<>();
    private String currentTurn = "BLUE";
    private String firstTeam = "BLUE";
    private long lastActivity = System.currentTimeMillis();
    private long turnStartTime = System.currentTimeMillis();
    private long blueElapsedSeconds = 0;
    private long redElapsedSeconds = 0;

    // 新增配置
    private int rows = 5;
    private int cols = 5;
    private int blackCount = 1;
    private int teamCount = 8;      // 每队需要猜的词汇数量
    private int redCount = 8;
    private int blueCount = 8;

    private Integer highlightedIndex = null;
    private String winner = null;

    public Room(String id) {
        this.id = id;
    }

    public Player findPlayerByNickname(String nickname) {
        return players.stream()
                .filter(p -> p.getNickname().equals(nickname))
                .findFirst().orElse(null);
    }

    public Player findPlayerById(String id) {
        return players.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst().orElse(null);
    }

    public void assignSeat(Player p) {
        int idx = players.size();
        switch (idx) {
            case 0:
                p.setTeam(Player.Team.RED);
                p.setRole(Player.Role.SPYMASTER);
                p.setSeatRow(1);
                p.setSeatCol(1);
                break;
            case 1:
                p.setTeam(Player.Team.BLUE);
                p.setRole(Player.Role.SPYMASTER);
                p.setSeatRow(1);
                p.setSeatCol(2);
                break;
            case 2:
                p.setTeam(Player.Team.RED);
                p.setRole(Player.Role.OPERATIVE);
                p.setSeatRow(2);
                p.setSeatCol(1);
                break;
            case 3:
                p.setTeam(Player.Team.BLUE);
                p.setRole(Player.Role.OPERATIVE);
                p.setSeatRow(2);
                p.setSeatCol(2);
                break;
        }
    }

    public void startNewGame(WordPoolService wps) {
        board.clear();
        int total = rows * cols;
        redCount = teamCount;
        blueCount = teamCount;
        int civilian = total - redCount - blueCount - blackCount;

        List<String> words = wps.pickWords(total);
        while (words.size() < total) {
            words.add("备用词" + (words.size() + 1));
        }

        List<String> colors = new ArrayList<>();
        for (int i = 0; i < redCount; i++) colors.add("RED");
        for (int i = 0; i < blueCount; i++) colors.add("BLUE");
        for (int i = 0; i < blackCount; i++) colors.add("BLACK");
        for (int i = 0; i < civilian; i++) colors.add("CIVILIAN");
        Collections.shuffle(colors);

        for (int i = 0; i < total; i++) {
            board.add(new Card(words.get(i), colors.get(i), false));
        }

        state = "BLUE".equals(firstTeam) ? State.BLUE_TURN : State.RED_TURN;
        currentTurn = firstTeam;
        winner = null;
        highlightedIndex = null;
        blueElapsedSeconds = 0;
        redElapsedSeconds = 0;
        turnStartTime = System.currentTimeMillis();
    }

    public void settleCurrentTurn() {
        long now = System.currentTimeMillis();
        long seconds = (now - turnStartTime) / 1000;
        if (State.BLUE_TURN.equals(state)) {
            blueElapsedSeconds += seconds;
        } else if (State.RED_TURN.equals(state)) {
            redElapsedSeconds += seconds;
        }
        turnStartTime = now;
    }

    public String checkWinner() {
        int redLeft = countUnrevealed("RED");
        int blueLeft = countUnrevealed("BLUE");
        if (redLeft == 0 && blueLeft == 0) return "DRAW";
        if (redLeft == 0) return "RED";
        if (blueLeft == 0) return "BLUE";
        return null;
    }

    public int countUnrevealed(String color) {
        int count = 0;
        for (Card c : board) {
            if (!c.isRevealed() && c.getColor().equals(color)) {
                count++;
            }
        }
        return count;
    }

    // getters / setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public List<Player> getPlayers() { return players; }
    public void setPlayers(List<Player> players) { this.players = players; }
    public List<Card> getBoard() { return board; }
    public void setBoard(List<Card> board) { this.board = board; }
    public String getCurrentTurn() { return currentTurn; }
    public void setCurrentTurn(String currentTurn) { this.currentTurn = currentTurn; }
    public String getFirstTeam() { return firstTeam; }
    public void setFirstTeam(String firstTeam) { this.firstTeam = firstTeam; }
    public long getLastActivity() { return lastActivity; }
    public void setLastActivity(long lastActivity) { this.lastActivity = lastActivity; }
    public long getTurnStartTime() { return turnStartTime; }
    public void setTurnStartTime(long turnStartTime) { this.turnStartTime = turnStartTime; }
    public long getBlueElapsedSeconds() { return blueElapsedSeconds; }
    public void setBlueElapsedSeconds(long blueElapsedSeconds) { this.blueElapsedSeconds = blueElapsedSeconds; }
    public long getRedElapsedSeconds() { return redElapsedSeconds; }
    public void setRedElapsedSeconds(long redElapsedSeconds) { this.redElapsedSeconds = redElapsedSeconds; }
    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }
    public int getCols() { return cols; }
    public void setCols(int cols) { this.cols = cols; }
    public int getBlackCount() { return blackCount; }
    public void setBlackCount(int blackCount) { this.blackCount = blackCount; }
    public int getTeamCount() { return teamCount; }
    public void setTeamCount(int teamCount) { this.teamCount = teamCount; }
    public int getRedCount() { return redCount; }
    public void setRedCount(int redCount) { this.redCount = redCount; }
    public int getBlueCount() { return blueCount; }
    public void setBlueCount(int blueCount) { this.blueCount = blueCount; }
    public Integer getHighlightedIndex() { return highlightedIndex; }
    public void setHighlightedIndex(Integer highlightedIndex) { this.highlightedIndex = highlightedIndex; }
    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }
}