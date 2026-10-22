package dev.sh3lby.race;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.UUID;

/**
 * Race rules enforcement, only for racers already placed on the track
 * ({@link Race#inProgress}); whoever just waits in the queue is left alone.
 * Everything the plugin itself does is exempt via {@link RacePlugin#isInternal}.
 */
final class RaceListeners implements Listener {

    private final RacePlugin plugin;
    private final Race race;

    RaceListeners(RacePlugin plugin, Race race) {
        this.plugin = plugin;
        this.race = race;
    }

    // --- taking racers out ---

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (race.inProgress(event.getPlayer().getUniqueId())) {
            race.out(event.getPlayer().getUniqueId(), "смерть");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        race.quit(event.getPlayer());
    }

    /** Stood up from the race boat — out of the race. Handled next tick, mid-dismount is a bad
     *  moment to remove the boat and teleport; the race tick would catch it anyway, but not a
     *  player who hops straight back in within the same tick. */
    @EventHandler(ignoreCancelled = true)
    public void onBoatExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player player) || !guarded(player)) return;
        if (!event.getVehicle().equals(race.boatOf(player.getUniqueId()))) return;
        UUID uuid = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> race.out(uuid, "выход из лодки"));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (race.inProgress(player.getUniqueId()) && !player.getWorld().equals(race.world())) {
            race.out(player.getUniqueId(), "выход из мира гонок");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!guarded(event.getPlayer())) return;
        event.setCancelled(true);
        RacePlugin.send(event.getPlayer(), "&cТелепорты во время заезда запрещены.");
    }

    /** Someone else let the teleport through anyway: the racer is out. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportDone(PlayerTeleportEvent event) {
        if (guarded(event.getPlayer())) race.out(event.getPlayer().getUniqueId(), "посторонний телепорт");
    }

    // --- things racers may not do ---

    @EventHandler(ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        if (guarded(event.getPlayer())) {
            event.setCancelled(true);
            RacePlugin.send(event.getPlayer(), "&cПолёт во время заезда запрещён.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        Entity rider = event.getEntity();
        String owner = plugin.boatOwner(event.getMount());
        if (plugin.isInternal(rider.getUniqueId())) return;
        if (owner != null && !owner.equals(rider.getUniqueId().toString())) {
            event.setCancelled(true);
            return;
        }
        if (race.inProgress(rider.getUniqueId()) && !event.getMount().equals(race.boatOf(rider.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    // --- protecting the race ---

    @EventHandler(ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (plugin.boatOwner(event.getVehicle()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (plugin.boatOwner(event.getVehicle()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (race.inProgress(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inRaceWorld(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (inRaceWorld(event.getPlayer())) event.setCancelled(true);
    }

    // --- bringing disconnected racers back ---

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location back = plugin.pendingReturn(player.getUniqueId());
        if (back == null) return;
        plugin.clearPendingReturn(player.getUniqueId());
        plugin.internal(player.getUniqueId(), () -> player.teleport(back, TeleportCause.PLUGIN));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location back = plugin.pendingReturn(event.getPlayer().getUniqueId());
        if (back == null) return;
        plugin.clearPendingReturn(event.getPlayer().getUniqueId());
        event.setRespawnLocation(back);
    }

    private boolean guarded(Player player) {
        UUID uuid = player.getUniqueId();
        return race.inProgress(uuid) && !plugin.isInternal(uuid);
    }

    private boolean inRaceWorld(Player player) {
        return race.active() && player.getWorld().equals(race.world()) && !player.hasPermission("race.admin");
    }
}
