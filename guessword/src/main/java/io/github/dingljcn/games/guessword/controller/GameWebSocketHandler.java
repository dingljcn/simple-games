package io.github.dingljcn.games.guessword.controller;

import io.github.dingljcn.games.guessword.entity.Card;
import io.github.dingljcn.games.guessword.entity.Player;
import io.github.dingljcn.games.guessword.entity.Room;
import io.github.dingljcn.games.guessword.service.RoomManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final RoomManager roomManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Player> sessionPlayerMap = new ConcurrentHashMap<>();

    public GameWebSocketHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Player p = sessionPlayerMap.remove(session.getId());
        if (p != null && p.getSession() != null && p.getSession().getId().equals(session.getId())) {
            p.setConnected(false);
            Room room = roomManager.getRoom(p.getRoomId());
            if (room != null) broadcast(room);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = objectMapper.readValue(message.getPayload(), Map.class);
        String type = (String) msg.get("type");
        if (type == null) return;

        Player player = sessionPlayerMap.get(session.getId());
        Room room = player != null ? roomManager.getRoom(player.getRoomId()) : null;
        if (room != null) roomManager.touch(room);

        switch (type) {
            case "join":
                handleJoin(session, msg);
                break;
            case "ping":
                if (room != null) broadcast(room);
                break;
            case "set_first_team":
                if (room != null && room.getState() == Room.State.GROUPING) {
                    String team = String.valueOf(msg.get("team")).toUpperCase();
                    room.setFirstTeam(team);
                    broadcast(room);
                }
                break;
            case "start_game":
                if (room != null && room.getState() == Room.State.GROUPING) {
                    if (room.getPlayers().size() < 4) {
                        sendError(session, "需要4名玩家才能开始");
                    } else {
                        // 解析配置参数
                        int rows = parseIntOrDefault(msg.get("rows"), room.getRows());
                        int cols = parseIntOrDefault(msg.get("cols"), room.getCols());
                        int blackCount = parseIntOrDefault(msg.get("blackCount"), room.getBlackCount());
                        int teamCount = parseIntOrDefault(msg.get("teamCount"), room.getTeamCount());

                        // 基础校验
                        if (rows < 2) rows = 2;
                        if (cols < 2) cols = 2;
                        if (blackCount < 0) blackCount = 0;
                        if (teamCount < 0) teamCount = 0;

                        int total = rows * cols;
                        if (total <= blackCount) {
                            sendError(session, "棋盘格数必须大于杀手数量");
                            return;
                        }
                        int civilian = total - blackCount - 2 * teamCount;
                        if (civilian < 0) {
                            sendError(session, "需要猜的牌太多了，平民牌不能为负数");
                            return;
                        }

                        room.setRows(rows);
                        room.setCols(cols);
                        room.setBlackCount(blackCount);
                        room.setTeamCount(teamCount);
                        room.startNewGame(roomManager.getWordPoolService());
                        broadcast(room);
                    }
                }
                break;
            case "select_card":
                handleSelectCard(player, room, msg, session);
                break;
            case "reveal_result":
                handleReveal(player, room, session);
                break;
            case "end_turn":
                handleEndTurn(player, room, session);
                break;
            case "swap_request":
                handleSwapRequest(player, room, msg, session);
                break;
            case "swap_accept":
                handleSwapAccept(player, room, msg, session);
                break;
            case "adjust_groups":
                if (room != null && room.getState() == Room.State.GAME_OVER) {
                    room.setState(Room.State.GROUPING);
                    room.getBoard().clear();
                    room.setWinner(null);
                    room.setHighlightedIndex(null);
                    room.setBlueElapsedSeconds(0);
                    room.setRedElapsedSeconds(0);
                    room.setTurnStartTime(System.currentTimeMillis());
                    broadcast(room);
                }
                break;
            case "play_again":
                if (room != null && room.getState() == Room.State.GAME_OVER) {
                    room.startNewGame(roomManager.getWordPoolService());
                    broadcast(room);
                }
                break;
            default:
                break;
        }
    }

    private int parseIntOrDefault(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void handleJoin(WebSocketSession session, Map<String, Object> msg) throws IOException {
        String roomId = (String) msg.get("roomId");
        String nickname = (String) msg.get("nickname");
        if (roomId == null || !roomId.matches("\\d{4}")) {
            sendError(session, "房间号必须为4位数字");
            return;
        }
        if (nickname == null || nickname.trim().isEmpty()) {
            sendError(session, "昵称不能为空");
            return;
        }
        Room room = roomManager.getOrCreateRoom(roomId);
        roomManager.touch(room);

        Player existing = room.findPlayerByNickname(nickname);
        if (existing != null) {
            if (existing.getSession() != null && existing.getSession().isOpen()
                    && !existing.getSession().getId().equals(session.getId())) {
                sessionPlayerMap.remove(existing.getSession().getId());
                existing.getSession().close();
            }
            existing.setSession(session);
            existing.setConnected(true);
            sessionPlayerMap.put(session.getId(), existing);
            sendJoined(session, existing);
            broadcast(room);
            return;
        }

        if (room.getPlayers().size() >= 4) {
            sendError(session, "房间已满");
            return;
        }
        if (room.getState() != Room.State.GROUPING) {
            sendError(session, "游戏已开始，无法加入");
            return;
        }

        Player p = new Player();
        p.setId(UUID.randomUUID().toString().substring(0, 8));
        p.setNickname(nickname);
        p.setSession(session);
        p.setConnected(true);
        p.setRoomId(roomId);
        room.assignSeat(p);
        room.getPlayers().add(p);
        sessionPlayerMap.put(session.getId(), p);
        sendJoined(session, p);
        broadcast(room);
    }

    private void handleSelectCard(Player player, Room room, Map<String, Object> msg,
                                  WebSocketSession session) throws IOException {
        if (player == null || room == null) return;
        if (room.getState() != Room.State.BLUE_TURN && room.getState() != Room.State.RED_TURN) {
            sendError(session, "当前不是行动阶段");
            return;
        }
        String turn = room.getCurrentTurn();
        if (!player.getTeam().name().equals(turn) || player.getRole() != Player.Role.OPERATIVE) {
            sendError(session, "只有当前行动队伍的指认者可以指认");
            return;
        }
        Integer index = (Integer) msg.get("index");
        if (index == null || index < 0 || index >= room.getBoard().size()) return;
        if (room.getBoard().get(index).isRevealed()) {
            sendError(session, "该卡片已翻出");
            return;
        }
        room.setHighlightedIndex(index);
        broadcast(room);
    }

    private void handleReveal(Player player, Room room, WebSocketSession session) throws IOException {
        if (player == null || room == null) return;
        if (room.getState() != Room.State.BLUE_TURN && room.getState() != Room.State.RED_TURN) return;
        String turn = room.getCurrentTurn();
        if (!player.getTeam().name().equals(turn) || player.getRole() != Player.Role.SPYMASTER) {
            sendError(session, "只有当前行动队伍的描述者可以确认");
            return;
        }
        Integer idx = room.getHighlightedIndex();
        if (idx == null) {
            sendError(session, "请先选择卡片");
            return;
        }
        Card card = room.getBoard().get(idx);
        card.setRevealed(true);
        room.setHighlightedIndex(null);

        if ("BLACK".equals(card.getColor())) {
            String win = "BLUE".equals(turn) ? "RED" : "BLUE";
            room.setState(Room.State.GAME_OVER);
            room.setWinner(win);
        }
        broadcast(room);
    }

    private void handleEndTurn(Player player, Room room, WebSocketSession session) throws IOException {
        if (player == null || room == null) return;
        if (room.getState() != Room.State.BLUE_TURN && room.getState() != Room.State.RED_TURN) return;
        String turn = room.getCurrentTurn();
        if (!player.getTeam().name().equals(turn) || player.getRole() != Player.Role.OPERATIVE) {
            sendError(session, "只有当前行动队伍的指认者可以结束行动");
            return;
        }

        room.settleCurrentTurn();
        room.setHighlightedIndex(null);
        String newTurn = "BLUE".equals(turn) ? "RED" : "BLUE";
        room.setCurrentTurn(newTurn);
        room.setTurnStartTime(System.currentTimeMillis());
        room.setState("BLUE".equals(newTurn) ? Room.State.BLUE_TURN : Room.State.RED_TURN);

        if (room.getFirstTeam().equals(newTurn)) {
            String winner = room.checkWinner();
            if (winner != null) {
                room.setState(Room.State.GAME_OVER);
                room.setWinner(winner);
            }
        }
        broadcast(room);
    }

    private void handleSwapRequest(Player from, Room room, Map<String, Object> msg,
                                   WebSocketSession session) throws IOException {
        if (from == null || room == null || room.getState() != Room.State.GROUPING) return;
        String targetId = (String) msg.get("targetPlayerId");
        Player target = room.findPlayerById(targetId);
        if (target == null || !target.isConnected()) {
            sendError(session, "目标玩家不在线");
            return;
        }
        Map<String, Object> req = new HashMap<>();
        req.put("type", "swap_request");
        req.put("fromPlayerId", from.getId());
        req.put("fromNickname", from.getNickname());
        req.put("targetPlayerId", targetId);
        sendToSession(target.getSession(), req);
    }

    private void handleSwapAccept(Player from, Room room, Map<String, Object> msg,
                                  WebSocketSession session) throws IOException {
        if (from == null || room == null || room.getState() != Room.State.GROUPING) return;
        String fromPlayerId = (String) msg.get("fromPlayerId");
        Player requester = room.findPlayerById(fromPlayerId);
        if (requester == null) return;

        Player p1 = requester;
        Player p2 = from;
        Player.Team tempTeam = p1.getTeam();
        Player.Role tempRole = p1.getRole();
        int tempRow = p1.getSeatRow();
        int tempCol = p1.getSeatCol();

        p1.setTeam(p2.getTeam());
        p1.setRole(p2.getRole());
        p1.setSeatRow(p2.getSeatRow());
        p1.setSeatCol(p2.getSeatCol());

        p2.setTeam(tempTeam);
        p2.setRole(tempRole);
        p2.setSeatRow(tempRow);
        p2.setSeatCol(tempCol);

        broadcast(room);
    }

    private void sendJoined(WebSocketSession session, Player p) throws IOException {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "joined");
        msg.put("playerId", p.getId());
        msg.put("roomId", p.getRoomId());
        sendToSession(session, msg);
    }

    private void sendError(WebSocketSession session, String message) throws IOException {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "error");
        msg.put("message", message);
        sendToSession(session, msg);
    }

    private void sendToSession(WebSocketSession session, Map<String, Object> msg) throws IOException {
        if (session != null && session.isOpen()) {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            }
        }
    }

    private void broadcast(Room room) throws IOException {
        Map<String, Object> state = buildRoomState(room);
        String json = objectMapper.writeValueAsString(state);
        TextMessage text = new TextMessage(json);
        for (Player p : room.getPlayers()) {
            if (p.getSession() != null && p.getSession().isOpen()) {
                synchronized (p.getSession()) {
                    p.getSession().sendMessage(text);
                }
            }
        }
    }

    private Map<String, Object> buildRoomState(Room room) {
        Map<String, Object> state = new HashMap<>();
        state.put("type", "room_state");
        state.put("roomId", room.getId());
        state.put("state", room.getState().name());
        state.put("currentTurn", room.getCurrentTurn());
        state.put("firstTeam", room.getFirstTeam());
        state.put("winner", room.getWinner());
        state.put("highlightedIndex", room.getHighlightedIndex());
        state.put("rows", room.getRows());
        state.put("cols", room.getCols());
        state.put("blackCount", room.getBlackCount());
        state.put("teamCount", room.getTeamCount());
        state.put("redCount", room.getRedCount());
        state.put("blueCount", room.getBlueCount());

        long now = System.currentTimeMillis();
        long blueTotal = room.getBlueElapsedSeconds();
        long redTotal = room.getRedElapsedSeconds();
        if (room.getState() == Room.State.BLUE_TURN) {
            blueTotal += (now - room.getTurnStartTime()) / 1000;
        } else if (room.getState() == Room.State.RED_TURN) {
            redTotal += (now - room.getTurnStartTime()) / 1000;
        }
        state.put("blueElapsedSeconds", blueTotal);
        state.put("redElapsedSeconds", redTotal);
        state.put("redLeft", room.countUnrevealed("RED"));
        state.put("blueLeft", room.countUnrevealed("BLUE"));
        state.put("civilianLeft", room.countUnrevealed("CIVILIAN"));

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : room.getPlayers()) {
            Map<String, Object> pm = new HashMap<>();
            pm.put("id", p.getId());
            pm.put("nickname", p.getNickname());
            pm.put("team", p.getTeam().name());
            pm.put("roleType", p.getRole().name());
            pm.put("seatRow", p.getSeatRow());
            pm.put("seatCol", p.getSeatCol());
            pm.put("connected", p.isConnected());
            players.add(pm);
        }
        state.put("players", players);

        List<Map<String, Object>> board = new ArrayList<>();
        for (int i = 0; i < room.getBoard().size(); i++) {
            Card c = room.getBoard().get(i);
            Map<String, Object> cm = new HashMap<>();
            cm.put("index", i);
            cm.put("word", c.getWord());
            cm.put("color", c.getColor());
            cm.put("revealed", c.isRevealed());
            board.add(cm);
        }
        state.put("board", board);
        return state;
    }
}