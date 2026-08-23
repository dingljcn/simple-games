package io.github.dingljcn.games.guessword.service;

import io.github.dingljcn.games.guessword.entity.Room;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class RoomManager {

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final WordPoolService wordPoolService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public RoomManager(WordPoolService wordPoolService) {
        this.wordPoolService = wordPoolService;
    }

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::cleanupRooms, 1, 1, TimeUnit.MINUTES);
    }

    public Room getOrCreateRoom(String roomId) {
        return rooms.computeIfAbsent(roomId, Room::new);
    }

    public Room getRoom(String roomId) {
        return rooms.get(roomId);
    }

    public WordPoolService getWordPoolService() {
        return wordPoolService;
    }

    public void touch(Room room) {
        if (room != null) {
            room.setLastActivity(System.currentTimeMillis());
        }
    }

    private void cleanupRooms() {
        long now = System.currentTimeMillis();
        rooms.values().removeIf(room ->
                now - room.getLastActivity() > TimeUnit.MINUTES.toMillis(5)
        );
    }
}
