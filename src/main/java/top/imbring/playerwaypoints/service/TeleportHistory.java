package top.imbring.playerwaypoints.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class TeleportHistory {

    private static final int MAX_HISTORY = 20;
    private final Map<UUID, List<Location>> history = new HashMap<>();

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

    public void clear(Player player) {
        history.remove(player.getUniqueId());
    }
}
