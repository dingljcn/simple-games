package io.github.dingljcn.games.guessword.entity;

import org.springframework.web.socket.WebSocketSession;

public class Player {
    public enum Team { RED, BLUE, GREEN, PURPLE }
    public enum Role { SPYMASTER, OPERATIVE }

    private String id;
    private String clientId;          // 浏览器标签页唯一标识
    private String nickname;
    private Team team;
    private Role role;
    private int seatRow;
    private int seatCol;
    private String roomId;
    private boolean connected;
    private boolean spectator;
    private WebSocketSession session;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public int getSeatRow() { return seatRow; }
    public void setSeatRow(int seatRow) { this.seatRow = seatRow; }
    public int getSeatCol() { return seatCol; }
    public void setSeatCol(int seatCol) { this.seatCol = seatCol; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public boolean isSpectator() { return spectator; }
    public void setSpectator(boolean spectator) { this.spectator = spectator; }
    public WebSocketSession getSession() { return session; }
    public void setSession(WebSocketSession session) { this.session = session; }
}