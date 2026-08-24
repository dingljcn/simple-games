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
            if (room != null) {
                room.getReadyMap().remove(p.getId());
                broadcast(room);
            }
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
            case "get_rooms":
                sendRoomList(session);
                break;
            case "create_room":
                handleCreateRoom(session, msg);
                break;
            case "join":
                handleJoin(session, msg);
                break;
            case "ping":
                if (room != null) broadcast(room);
                break;
            case "set_first_team":
                if (room != null && room.getState() == Room.State.GROUPING && !room.isConfigLocked()) {
                    String team = String.valueOf(msg.get("team")).toUpperCase();
                    room.setFirstTeam(team);
                    broadcast(room);
                }
                break;
            case "start_game":
                if (room != null && room.getState() == Room.State.GROUPING) {
                    if (!room.allPlayersReady()) {
                        sendError(session, "所有玩家都准备好后才能开始");
                    } else {
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
            case "leave_room":
                handleLeaveRoom(player, room, session);
                break;
            case "dissolve_room":
                handleDissolveRoom(player, room, session);
                break;
            case "toggle_ready":
                handleToggleReady(player, room, session);
                break;
            case "regroup_request":
            case "restart_request":
                handlePendingAction(player, room, type.equals("regroup_request") ? Room.PendingActionType.REGROUP : Room.PendingActionType.RESTART, session);
                break;
            case "pending_action_agree":
                handlePendingAgree(player, room, session);
                break;
            case "adjust_groups":
                if (room != null && room.getState() == Room.State.GAME_OVER && player != null && player.getId().equals(room.getHostId())) {
                    handlePendingAction(player, room, Room.PendingActionType.REGROUP, session);
                }
                break;
            case "play_again":
                if (room != null && room.getState() == Room.State.GAME_OVER && player != null && player.getId().equals(room.getHostId())) {
                    handlePendingAction(player, room, Room.PendingActionType.RESTART, session);
                }
                break;
            case "discard_words":
                handleDiscardWords(player, room, msg, session);
                break;
            default:
                break;
        }
    }

    private void sendRoomList(WebSocketSession session) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Room r : roomManager.getAllRooms()) {
            Map<String, Object> info = new HashMap<>();
            info.put("roomId", r.getId());
            info.put("host", r.getHostId() != null ? (r.findPlayerById(r.getHostId()) != null ? r.findPlayerById(r.getHostId()).getNickname() : "未知") : "未知");
            info.put("rows", r.getRows());
            info.put("cols", r.getCols());
            info.put("blackCount", r.getBlackCount());
            info.put("teamCount", r.getTeamCount());
            info.put("teamCardCount", r.getTeamCardCount());
            info.put("state", r.getState().name());
            info.put("playerCount", r.getPlayers().size());
            info.put("maxPlayers", r.getTeamCount() * 2);
            list.add(info);
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "room_list");
        resp.put("rooms", list);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));
    }

    private void handleCreateRoom(WebSocketSession session, Map<String, Object> msg) throws IOException {
        String roomId = (String) msg.get("roomId");
        String nickname = (String) msg.get("nickname");
        String clientId = (String) msg.get("clientId");
        if (roomId == null || !roomId.matches("\\d{4}")) {
            sendError(session, "房间号必须为4位数字");
            return;
        }
        if (nickname == null || nickname.trim().isEmpty()) {
            sendError(session, "昵称不能为空");
            return;
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            sendError(session, "客户端标识无效");
            return;
        }
        if (roomManager.getRoom(roomId) != null) {
            sendError(session, "房间已存在");
            return;
        }

        Room room = roomManager.getOrCreateRoom(roomId);
        room.setRows(parseIntOrDefault(msg.get("rows"), 5));
        room.setCols(parseIntOrDefault(msg.get("cols"), 5));
        room.setBlackCount(parseIntOrDefault(msg.get("blackCount"), 1));
        room.setTeamCount(parseIntOrDefault(msg.get("teamCount"), 2));
        room.setTeamCardCount(parseIntOrDefault(msg.get("teamCardCount"), 8));
        room.setFirstTeam(Player.Team.values()[0].name());
        room.setConfigLocked(true);
        roomManager.touch(room);

        Player p = new Player();
        p.setId(UUID.randomUUID().toString().substring(0, 8));
        p.setClientId(clientId);
        p.setNickname(nickname);
        p.setSession(session);
        p.setConnected(true);
        p.setRoomId(roomId);
        p.setSpectator(false);
        room.assignSeat(p);
        room.getPlayers().add(p);
        room.setHostId(p.getId());
        sessionPlayerMap.put(session.getId(), p);
        sendJoined(session, p);
        broadcast(room);
    }

    private void handleJoin(WebSocketSession session, Map<String, Object> msg) throws IOException {
        String roomId = (String) msg.get("roomId");
        String nickname = (String) msg.get("nickname");
        String clientId = (String) msg.get("clientId");
        if (roomId == null || !roomId.matches("\\d{4}")) {
            sendError(session, "房间号必须为4位数字");
            return;
        }
        if (nickname == null || nickname.trim().isEmpty()) {
            sendError(session, "昵称不能为空");
            return;
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            sendError(session, "客户端标识无效");
            return;
        }
        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            sendError(session, "房间不存在");
            return;
        }
        roomManager.touch(room);

        // 优先根据 clientId 查找，支持刷新重连
        Player existing = room.findPlayerByClientId(clientId);
        if (existing != null) {
            // 更新 session
            if (existing.getSession() != null && existing.getSession().isOpen()
                    && !existing.getSession().getId().equals(session.getId())) {
                sessionPlayerMap.remove(existing.getSession().getId());
                existing.getSession().close();
            }
            existing.setSession(session);
            existing.setConnected(true);
            existing.setNickname(nickname); // 允许修改昵称
            sessionPlayerMap.put(session.getId(), existing);
            sendJoined(session, existing);
            broadcast(room);
            return;
        }

        // 检查同名但未使用 clientId 的情况（可能是老客户端），暂不支持，按新玩家处理
        Player p = new Player();
        p.setId(UUID.randomUUID().toString().substring(0, 8));
        p.setClientId(clientId);
        p.setNickname(nickname);
        p.setSession(session);
        p.setConnected(true);
        p.setRoomId(roomId);

        if (room.getState() != Room.State.GROUPING) {
            p.setSpectator(true);
            p.setTeam(null);
            p.setRole(null);
            p.setSeatRow(0);
            p.setSeatCol(0);
            room.getSpectators().add(p);
        } else if (room.isFull()) {
            p.setSpectator(true);
            p.setTeam(null);
            p.setRole(null);
            p.setSeatRow(0);
            p.setSeatCol(0);
            room.getSpectators().add(p);
        } else {
            p.setSpectator(false);
            room.assignSeat(p);
            room.getPlayers().add(p);
        }
        sessionPlayerMap.put(session.getId(), p);
        sendJoined(session, p);
        broadcast(room);
    }

    private void handleToggleReady(Player player, Room room, WebSocketSession session) throws IOException {
        if (player == null || room == null || player.isSpectator()) return;
        if (room.getState() != Room.State.GROUPING && room.getState() != Room.State.GAME_OVER) {
            sendError(session, "当前状态不能准备");
            return;
        }
        boolean ready = !room.getReadyMap().getOrDefault(player.getId(), false);
        room.getReadyMap().put(player.getId(), ready);

        // 如果是 GAME_OVER 状态，检查是否全部准备，自动重开
        if (room.getState() == Room.State.GAME_OVER && ready && room.allPlayersReady()) {
            room.startNewGame(roomManager.getWordPoolService());
        }
        broadcast(room);
    }

    private void handleSelectCard(Player player, Room room, Map<String, Object> msg,
                                  WebSocketSession session) throws IOException {
        if (player == null || room == null || player.isSpectator()) return;
        if (room.getState() != Room.State.PLAYING) {
            sendError(session, "当前不是游戏阶段");
            return;
        }
        String turn = room.getCurrentTurnTeam();
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
        if (player == null || room == null || player.isSpectator()) return;
        if (room.getState() != Room.State.PLAYING) return;
        String turn = room.getCurrentTurnTeam();
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
            // 简化处理：当前行动队伍失败，对方获胜（多队时取第一队）
            String win = "RED";
            room.setState(Room.State.GAME_OVER);
            room.setWinner(win);
        }
        broadcast(room);
    }

    private void handleEndTurn(Player player, Room room, WebSocketSession session) throws IOException {
        if (player == null || room == null || player.isSpectator()) return;
        if (room.getState() != Room.State.PLAYING) return;
        String turn = room.getCurrentTurnTeam();
        if (!player.getTeam().name().equals(turn) || player.getRole() != Player.Role.OPERATIVE) {
            sendError(session, "只有当前行动队伍的指认者可以结束行动");
            return;
        }

        room.settleCurrentTurn();
        room.setHighlightedIndex(null);
        String newTurn = getNextTeam(turn, room.getTeamCount());
        room.setCurrentTurnTeam(newTurn);
        room.setTurnStartTime(System.currentTimeMillis());

        if (newTurn.equals(room.getFirstTeam())) {
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
        if (from == null || room == null || room.getState() != Room.State.GROUPING || from.isSpectator()) return;
        String targetId = (String) msg.get("targetPlayerId");
        Player target = room.findPlayerById(targetId);
        if (target == null || target.isSpectator() || !target.isConnected()) {
            sendError(session, "目标玩家不在线或为观战者");
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
        if (from == null || room == null || room.getState() != Room.State.GROUPING || from.isSpectator()) return;
        String fromPlayerId = (String) msg.get("fromPlayerId");
        Player requester = room.findPlayerById(fromPlayerId);
        if (requester == null || requester.isSpectator()) return;

        Player p1 = requester;
        Player p2 = from;
        Player.Team tempTeam = p1.getTeam();
        Player.Role tempRole = p1.getRole();
        int tempRow = p1.getSeatRow();
        int tempCol = p1.getSeatCol();
        boolean tempSpectator = p1.isSpectator();

        p1.setTeam(p2.getTeam());
        p1.setRole(p2.getRole());
        p1.setSeatRow(p2.getSeatRow());
        p1.setSeatCol(p2.getSeatCol());
        p1.setSpectator(p2.isSpectator());

        p2.setTeam(tempTeam);
        p2.setRole(tempRole);
        p2.setSeatRow(tempRow);
        p2.setSeatCol(tempCol);
        p2.setSpectator(tempSpectator);

        broadcast(room);
    }

    private void handleLeaveRoom(Player player, Room room, WebSocketSession session) throws IOException {
        if (player == null || room == null) return;
        boolean wasHost = player.getId().equals(room.getHostId());
        if (player.isSpectator()) {
            room.getSpectators().remove(player);
        } else {
            room.getPlayers().remove(player);
            room.getReadyMap().remove(player.getId());
            if (wasHost && !room.getPlayers().isEmpty()) {
                room.setHostId(room.getPlayers().get(0).getId());
            }
        }
        sessionPlayerMap.remove(session.getId());
        if (room.getPlayers().isEmpty() && room.getSpectators().isEmpty()) {
            roomManager.removeRoom(room.getId());
        } else {
            broadcast(room);
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "left_room");
        sendToSession(session, resp);
    }

    private void handleDissolveRoom(Player player, Room room, WebSocketSession session) throws IOException {
        if (player == null || room == null) return;
        if (!player.getId().equals(room.getHostId())) {
            sendError(session, "只有房主可以解散房间");
            return;
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "room_dissolved");
        for (Player p : room.getPlayers()) {
            if (p.getSession() != null && p.getSession().isOpen()) {
                sendToSession(p.getSession(), resp);
                sessionPlayerMap.remove(p.getSession().getId());
                p.getSession().close();
            }
        }
        for (Player p : room.getSpectators()) {
            if (p.getSession() != null && p.getSession().isOpen()) {
                sendToSession(p.getSession(), resp);
                sessionPlayerMap.remove(p.getSession().getId());
                p.getSession().close();
            }
        }
        roomManager.removeRoom(room.getId());
    }

    private void handlePendingAction(Player player, Room room, Room.PendingActionType actionType, WebSocketSession session) throws IOException {
        if (player == null || room == null || player.isSpectator()) return;
        if (!player.getId().equals(room.getHostId())) {
            sendError(session, "只有房主可以发起该操作");
            return;
        }
        if (room.getState() != Room.State.GAME_OVER && room.getState() != Room.State.PLAYING) {
            sendError(session, "当前状态不能发起该操作");
            return;
        }
        room.setPendingActionType(actionType);
        room.setPendingActionFrom(player.getId());
        room.getPendingActionAgree().clear();
        room.getPendingActionAgree().add(player.getId());
        room.setPendingActionTime(System.currentTimeMillis());

        Map<String, Object> req = new HashMap<>();
        req.put("type", "pending_action_request");
        req.put("action", actionType.name());
        req.put("fromNickname", player.getNickname());
        for (Player p : room.getPlayers()) {
            if (p.getId().equals(player.getId())) continue;
            if (p.getSession() != null && p.getSession().isOpen()) {
                sendToSession(p.getSession(), req);
            }
        }
        broadcast(room);
    }

    private void handlePendingAgree(Player player, Room room, WebSocketSession session) throws IOException {
        if (player == null || room == null || player.isSpectator()) return;
        if (room.getPendingActionType() == null) return;
        room.getPendingActionAgree().add(player.getId());
        boolean allAgree = true;
        for (Player p : room.getPlayers()) {
            if (!room.getPendingActionAgree().contains(p.getId())) {
                allAgree = false;
                break;
            }
        }
        if (allAgree) {
            if (room.getPendingActionType() == Room.PendingActionType.REGROUP) {
                room.setState(Room.State.GROUPING);
                room.getBoard().clear();
                room.setWinner(null);
                room.setHighlightedIndex(null);
                room.getReadyMap().clear();
                room.setBlueElapsedSeconds(0);
                room.setRedElapsedSeconds(0);
                room.setGreenElapsedSeconds(0);
                room.setPurpleElapsedSeconds(0);
                room.setTurnStartTime(System.currentTimeMillis());
            } else if (room.getPendingActionType() == Room.PendingActionType.RESTART) {
                room.startNewGame(roomManager.getWordPoolService());
            }
            room.setPendingActionType(null);
            room.setPendingActionFrom(null);
            room.getPendingActionAgree().clear();
        }
        broadcast(room);
    }

    private void handleDiscardWords(Player player, Room room, Map<String, Object> msg, WebSocketSession session) throws IOException {
        if (room == null || room.getState() != Room.State.GAME_OVER) return;
        List<String> words = (List<String>) msg.get("words");
        if (words != null && !words.isEmpty()) {
            roomManager.getWordPoolService().discardWords(words);
        }
    }

    private String getNextTeam(String current, int teamCount) {
        String[] teams = {"RED", "BLUE", "GREEN", "PURPLE"};
        int idx = -1;
        for (int i = 0; i < teamCount; i++) {
            if (teams[i].equals(current)) { idx = i; break; }
        }
        if (idx == -1) return teams[0];
        return teams[(idx + 1) % teamCount];
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
        List<Player> all = new ArrayList<>(room.getPlayers());
        all.addAll(room.getSpectators());
        for (Player p : all) {
            if (p.getSession() != null && p.getSession().isOpen()) {
                synchronized (p.getSession()) {
                    p.getSession().sendMessage(text);
                }
            }
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

    private Map<String, Object> buildRoomState(Room room) {
        Map<String, Object> state = new HashMap<>();
        state.put("type", "room_state");
        state.put("roomId", room.getId());
        state.put("state", room.getState().name());
        state.put("currentTurnTeam", room.getCurrentTurnTeam());
        state.put("firstTeam", room.getFirstTeam());
        state.put("winner", room.getWinner());
        state.put("highlightedIndex", room.getHighlightedIndex());
        state.put("rows", room.getRows());
        state.put("cols", room.getCols());
        state.put("blackCount", room.getBlackCount());
        state.put("teamCount", room.getTeamCount());
        state.put("teamCardCount", room.getTeamCardCount());
        state.put("configLocked", room.isConfigLocked());
        state.put("hostId", room.getHostId());

        // 计算平民数量
        int total = room.getRows() * room.getCols();
        int civilianCount = total - room.getTeamCardCount() * room.getTeamCount() - room.getBlackCount();
        state.put("civilianCount", Math.max(0, civilianCount));

        long now = System.currentTimeMillis();
        long blueTotal = room.getBlueElapsedSeconds();
        long redTotal = room.getRedElapsedSeconds();
        long greenTotal = room.getGreenElapsedSeconds();
        long purpleTotal = room.getPurpleElapsedSeconds();
        if (room.getState() == Room.State.PLAYING) {
            if ("BLUE".equals(room.getCurrentTurnTeam())) blueTotal += (now - room.getTurnStartTime()) / 1000;
            else if ("RED".equals(room.getCurrentTurnTeam())) redTotal += (now - room.getTurnStartTime()) / 1000;
            else if ("GREEN".equals(room.getCurrentTurnTeam())) greenTotal += (now - room.getTurnStartTime()) / 1000;
            else if ("PURPLE".equals(room.getCurrentTurnTeam())) purpleTotal += (now - room.getTurnStartTime()) / 1000;
        }
        state.put("blueElapsedSeconds", blueTotal);
        state.put("redElapsedSeconds", redTotal);
        state.put("greenElapsedSeconds", greenTotal);
        state.put("purpleElapsedSeconds", purpleTotal);

        state.put("redLeft", room.countUnrevealed("RED"));
        state.put("blueLeft", room.countUnrevealed("BLUE"));
        state.put("greenLeft", room.countUnrevealed("GREEN"));
        state.put("purpleLeft", room.countUnrevealed("PURPLE"));
        state.put("civilianLeft", room.countUnrevealed("CIVILIAN"));

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : room.getPlayers()) {
            Map<String, Object> pm = new HashMap<>();
            pm.put("id", p.getId());
            pm.put("nickname", p.getNickname());
            pm.put("team", p.getTeam() != null ? p.getTeam().name() : null);
            pm.put("roleType", p.getRole() != null ? p.getRole().name() : null);
            pm.put("seatRow", p.getSeatRow());
            pm.put("seatCol", p.getSeatCol());
            pm.put("connected", p.isConnected());
            pm.put("spectator", p.isSpectator());
            pm.put("ready", room.getReadyMap().getOrDefault(p.getId(), false));
            players.add(pm);
        }
        for (Player p : room.getSpectators()) {
            Map<String, Object> pm = new HashMap<>();
            pm.put("id", p.getId());
            pm.put("nickname", p.getNickname());
            pm.put("team", null);
            pm.put("roleType", null);
            pm.put("seatRow", 0);
            pm.put("seatCol", 0);
            pm.put("connected", p.isConnected());
            pm.put("spectator", true);
            pm.put("ready", false);
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