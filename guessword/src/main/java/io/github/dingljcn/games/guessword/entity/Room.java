package io.github.dingljcn.games.guessword.entity;

import io.github.dingljcn.games.guessword.service.WordPoolService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Room {
    public enum State { GROUPING, PLAYING, GAME_OVER }

    private String id;
    private State state = State.GROUPING;
    private List<Player> players = new CopyOnWriteArrayList<>();       // 正式玩家
    private List<Player> spectators = new CopyOnWriteArrayList<>();    // 观战者
    private List<Card> board = new ArrayList<>();
    private String currentTurnTeam;
    private String firstTeam;
    private long lastActivity = System.currentTimeMillis();
    private long turnStartTime = System.currentTimeMillis();
    private long blueElapsedSeconds = 0;
    private long redElapsedSeconds = 0;
    private long greenElapsedSeconds = 0;
    private long purpleElapsedSeconds = 0;

    private int rows = 5;
    private int cols = 5;
    private int blackCount = 1;
    private int teamCount = 2;
    private int teamCardCount = 8;
    private boolean configLocked = false;
    private String hostId;
    private Map<String, Boolean> readyMap = new ConcurrentHashMap<>();

    public enum PendingActionType { REGROUP, RESTART }
    private PendingActionType pendingActionType;
    private String pendingActionFrom;
    private Set<String> pendingActionAgree = ConcurrentHashMap.newKeySet();
    private long pendingActionTime;

    private Integer highlightedIndex = null;
    private String winner = null;

    public Room(String id) { this.id = id; }

    public Player findPlayerByNickname(String nickname) {
        return players.stream()
                .filter(p -> p.getNickname().equals(nickname))
                .findFirst().orElse(null);
    }

    public Player findPlayerById(String id) {
        for (Player p : players) if (p.getId().equals(id)) return p;
        for (Player p : spectators) if (p.getId().equals(id)) return p;
        return null;
    }

    public Player findPlayerByClientId(String clientId) {
        for (Player p : players) if (clientId.equals(p.getClientId())) return p;
        for (Player p : spectators) if (clientId.equals(p.getClientId())) return p;
        return null;
    }

    public void assignSeat(Player p) {
        int idx = players.size();
        int teamIndex = idx / 2;
        int roleIndex = idx % 2;
        if (teamIndex >= teamCount) {
            p.setSpectator(true);
            p.setTeam(null);
            p.setRole(null);
            p.setSeatRow(0);
            p.setSeatCol(0);
            return;
        }
        Player.Team[] teams = Player.Team.values();
        Player.Team team = teams[teamIndex];
        Player.Role role = (roleIndex == 0) ? Player.Role.SPYMASTER : Player.Role.OPERATIVE;
        p.setTeam(team);
        p.setRole(role);
        p.setSeatRow(roleIndex == 0 ? 1 : 2);
        p.setSeatCol(teamIndex + 1);
        p.setSpectator(false);
    }

    public void startNewGame(WordPoolService wps) {
        board.clear();
        int total = rows * cols;
        int civilian = total - teamCardCount * teamCount - blackCount;
        if (civilian < 0) civilian = 0;

        List<String> words = wps.pickWords(total);
        while (words.size() < total) {
            words.add("备用词" + (words.size() + 1));
        }

        List<String> colors = new ArrayList<>();
        Player.Team[] teams = Player.Team.values();
        for (int t = 0; t < teamCount; t++) {
            String teamName = teams[t].name();
            for (int i = 0; i < teamCardCount; i++) colors.add(teamName);
        }
        for (int i = 0; i < blackCount; i++) colors.add("BLACK");
        for (int i = 0; i < civilian; i++) colors.add("CIVILIAN");
        Collections.shuffle(colors);

        for (int i = 0; i < total; i++) {
            board.add(new Card(words.get(i), colors.get(i), false));
        }

        state = State.PLAYING;
        currentTurnTeam = firstTeam;
        winner = null;
        highlightedIndex = null;
        blueElapsedSeconds = 0;
        redElapsedSeconds = 0;
        greenElapsedSeconds = 0;
        purpleElapsedSeconds = 0;
        turnStartTime = System.currentTimeMillis();
        readyMap.clear();
    }

    public void settleCurrentTurn() {
        long now = System.currentTimeMillis();
        long seconds = (now - turnStartTime) / 1000;
        if ("BLUE".equals(currentTurnTeam)) blueElapsedSeconds += seconds;
        else if ("RED".equals(currentTurnTeam)) redElapsedSeconds += seconds;
        else if ("GREEN".equals(currentTurnTeam)) greenElapsedSeconds += seconds;
        else if ("PURPLE".equals(currentTurnTeam)) purpleElapsedSeconds += seconds;
        turnStartTime = now;
    }

    public String checkWinner() {
        List<String> teamsInGame = new ArrayList<>();
        for (int i = 0; i < teamCount; i++) teamsInGame.add(Player.Team.values()[i].name());
        List<String> finishedTeams = new ArrayList<>();
        for (String t : teamsInGame) {
            if (countUnrevealed(t) == 0) finishedTeams.add(t);
        }
        if (finishedTeams.size() == teamCount) return "DRAW";
        if (finishedTeams.size() == 1) return finishedTeams.get(0);
        return null;
    }

    public int countUnrevealed(String color) {
        int count = 0;
        for (Card c : board) {
            if (!c.isRevealed() && c.getColor().equals(color)) count++;
        }
        return count;
    }

    public boolean allPlayersReady() {
        for (Player p : players) {
            if (!readyMap.getOrDefault(p.getId(), false)) return false;
        }
        return !players.isEmpty();
    }

    public boolean isFull() {
        return players.size() >= teamCount * 2;
    }

    // Getters and Setters ...
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public List<Player> getPlayers() { return players; }
    public void setPlayers(List<Player> players) { this.players = players; }
    public List<Player> getSpectators() { return spectators; }
    public void setSpectators(List<Player> spectators) { this.spectators = spectators; }
    public List<Card> getBoard() { return board; }
    public void setBoard(List<Card> board) { this.board = board; }
    public String getCurrentTurnTeam() { return currentTurnTeam; }
    public void setCurrentTurnTeam(String currentTurnTeam) { this.currentTurnTeam = currentTurnTeam; }
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
    public long getGreenElapsedSeconds() { return greenElapsedSeconds; }
    public void setGreenElapsedSeconds(long greenElapsedSeconds) { this.greenElapsedSeconds = greenElapsedSeconds; }
    public long getPurpleElapsedSeconds() { return purpleElapsedSeconds; }
    public void setPurpleElapsedSeconds(long purpleElapsedSeconds) { this.purpleElapsedSeconds = purpleElapsedSeconds; }
    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }
    public int getCols() { return cols; }
    public void setCols(int cols) { this.cols = cols; }
    public int getBlackCount() { return blackCount; }
    public void setBlackCount(int blackCount) { this.blackCount = blackCount; }
    public int getTeamCount() { return teamCount; }
    public void setTeamCount(int teamCount) { this.teamCount = teamCount; }
    public int getTeamCardCount() { return teamCardCount; }
    public void setTeamCardCount(int teamCardCount) { this.teamCardCount = teamCardCount; }
    public boolean isConfigLocked() { return configLocked; }
    public void setConfigLocked(boolean configLocked) { this.configLocked = configLocked; }
    public String getHostId() { return hostId; }
    public void setHostId(String hostId) { this.hostId = hostId; }
    public Map<String, Boolean> getReadyMap() { return readyMap; }
    public void setReadyMap(Map<String, Boolean> readyMap) { this.readyMap = readyMap; }
    public PendingActionType getPendingActionType() { return pendingActionType; }
    public void setPendingActionType(PendingActionType pendingActionType) { this.pendingActionType = pendingActionType; }
    public String getPendingActionFrom() { return pendingActionFrom; }
    public void setPendingActionFrom(String pendingActionFrom) { this.pendingActionFrom = pendingActionFrom; }
    public Set<String> getPendingActionAgree() { return pendingActionAgree; }
    public void setPendingActionAgree(Set<String> pendingActionAgree) { this.pendingActionAgree = pendingActionAgree; }
    public long getPendingActionTime() { return pendingActionTime; }
    public void setPendingActionTime(long pendingActionTime) { this.pendingActionTime = pendingActionTime; }
    public Integer getHighlightedIndex() { return highlightedIndex; }
    public void setHighlightedIndex(Integer highlightedIndex) { this.highlightedIndex = highlightedIndex; }
    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }
}