package top.imbring.playerwaypoints.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import top.imbring.playerwaypoints.service.TeleportHistory;

public class PlayerTeleportListener implements Listener {

    private final TeleportHistory teleportHistory;

    public PlayerTeleportListener(TeleportHistory teleportHistory) {
        this.teleportHistory = teleportHistory;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getWorld() != null && from.getWorld().equals(to.getWorld())
            && from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        teleportHistory.record(player, from);
    }
}
