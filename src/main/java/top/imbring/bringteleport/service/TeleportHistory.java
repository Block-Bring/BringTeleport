package top.imbring.bringteleport.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class TeleportHistory {

    private static final int MAX_HISTORY = 20;
    private final Map<UUID, List<Location>> history = new HashMap<>();
    private final Map<UUID, Location> lastBackSource = new HashMap<>();

    public void record(Player player, Location location) {
        List<Location> list = history.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        list.add(0, location.clone());
        if (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
    }

    public @Nullable Location getBackLocation(Player player, int steps) {
        List<Location> list = history.get(player.getUniqueId());
        if (list == null || steps < 1 || steps > list.size()) return null;
        return list.get(steps - 1).clone();
    }

    public int getHistorySize(Player player) {
        List<Location> list = history.get(player.getUniqueId());
        return list == null ? 0 : list.size();
    }

    /** Save where the player was right before /waypoint tp back was used. */
    public void setLastBackSource(Player player, Location location) {
        lastBackSource.put(player.getUniqueId(), location.clone());
    }

    /** Retrieve and clear the undo location. */
    public @Nullable Location getAndClearLastBackSource(Player player) {
        return lastBackSource.remove(player.getUniqueId());
    }

    public boolean hasUndo(Player player) {
        return lastBackSource.containsKey(player.getUniqueId());
    }

    public void clear(Player player) {
        history.remove(player.getUniqueId());
        lastBackSource.remove(player.getUniqueId());
    }
}
