package dev.sh3lby.race;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.entity.boat.OakBoat;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The single race: registration, countdown, lap tracking, results. */
final class Race {

    enum State { IDLE, COUNTDOWN, RUNNING }

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int SPAWN_LIFT = 3;

    /** A finish time in milliseconds, or {@code millis < 0} with a reason for a DNF. */
    private record Result(UUID uuid, String name, long millis, String reason) {
    }

    private static final class Racer {
        final UUID uuid;
        final String name;
        /** Where the player stood when the race took them. */
        final Location origin;
        /** Start position lifted by {@link #SPAWN_LIFT}, so the boat drops into place instead of into a block. */
        final Location spawn;
        final boolean couldFly;
        final LapTracker tracker;
        Boat boat;
        Vector last;

        Racer(Player player, Location spawn, LapTracker.Track track) {
            this.uuid = player.getUniqueId();
            this.name = player.getName();
            this.origin = player.getLocation();
            this.spawn = spawn;
            this.couldFly = player.getAllowFlight();
            this.tracker = new LapTracker(track);
        }
    }

    private final RacePlugin plugin;
    private final Map<UUID, Racer> racers = new LinkedHashMap<>();
    private final List<Result> results = new ArrayList<>();
    private State state = State.IDLE;
    private RaceConfig cfg;
    private String raceId;
    private int laps;
    private long phaseStart;
    private int shownSecond;

    Race(RacePlugin plugin) {
        this.plugin = plugin;
    }

    boolean active() {
        return state != State.IDLE;
    }

    World world() {
        return cfg == null ? null : cfg.world();
    }

    /** True while this player is on the track: placed, counting laps and bound by the race rules. */
    boolean inProgress(UUID uuid) {
        return state != State.IDLE && racers.containsKey(uuid);
    }

    /** The race boat handed to this player, or null when they have none. */
    Boat boatOf(UUID uuid) {
        Racer racer = racers.get(uuid);
        return racer == null ? null : racer.boat;
    }

    // --- commands ---

    void leave(Player player) {
        Racer racer = racers.get(player.getUniqueId());
        if (racer == null) {
            RacePlugin.send(player, "&cВы не участвуете в заезде.");
            return;
        }
        out(racer, "выход по команде");
    }

    void start(CommandSender sender, int laps) {
        if (state != State.IDLE) {
            RacePlugin.send(sender, "&cЗаезд уже идёт.");
            return;
        }
        RaceConfig config = plugin.freshConfig();
        if (!config.valid()) {
            RacePlugin.send(sender, "&cЗапуск невозможен, трасса настроена неверно:");
            config.errors().forEach(error -> RacePlugin.send(sender, "&c- " + error));
            return;
        }
        this.cfg = config;
        this.laps = laps;
        this.raceId = LocalDateTime.now().format(ID_FORMAT);
        int slot = 0;
        int leftOut = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) continue;
            if (slot >= config.starts().size()) {
                leftOut++;
                continue;
            }
            place(player, config, config.starts().get(slot++));
        }
        if (racers.isEmpty()) {
            RacePlugin.send(sender, "&cНекого ставить на старт.");
            this.cfg = null;
            return;
        }
        if (leftOut > 0) {
            RacePlugin.send(sender, "&eПозиций всего &f" + config.starts().size()
                    + "&e, без заезда остались игроки: &f" + leftOut);
        }
        this.state = State.COUNTDOWN;
        this.phaseStart = System.nanoTime();
        this.shownSecond = -1;
        announce("&aЗаезд начинается! Кругов: &f" + laps);
    }

    void stop(CommandSender sender) {
        if (state == State.IDLE) {
            RacePlugin.send(sender, "&cАктивного заезда нет.");
            return;
        }
        announce("&eЗаезд отменён.");
        for (Racer racer : List.copyOf(racers.values())) {
            release(racer, Bukkit.getPlayer(racer.uuid));
        }
        end();
    }

    // --- per tick ---

    void tick() {
        if (state == State.IDLE) return;
        long now = System.nanoTime();
        if (state == State.COUNTDOWN) {
            countdown(now);
        } else {
            run(now);
        }
    }

    private void countdown(long now) {
        int remaining = (int) Math.ceil(cfg.countdownSeconds() - (now - phaseStart) / 1.0e9);
        boolean announce = remaining != shownSecond;
        shownSecond = remaining;
        for (Racer racer : List.copyOf(racers.values())) {
            Player player = Bukkit.getPlayer(racer.uuid);
            if (player == null) continue;
            String problem = problem(racer, player);
            if (problem != null) {
                out(racer, problem);
                continue;
            }
            hold(racer);
            if (announce && remaining > 0) {
                RacePlugin.title(player, "&e" + remaining, "&7Приготовьтесь");
            }
        }
        if (racers.isEmpty()) {
            end();
            return;
        }
        if (remaining > 0) return;
        state = State.RUNNING;
        phaseStart = System.nanoTime();
        for (Racer racer : racers.values()) {
            racer.last = racer.boat == null ? null : racer.boat.getLocation().toVector();
            Player player = Bukkit.getPlayer(racer.uuid);
            if (player != null) RacePlugin.title(player, "&aСТАРТ", "");
        }
    }

    /** Until the start signal paddling gets cancelled, while falling to the ground is left alone. */
    private void hold(Racer racer) {
        Boat boat = racer.boat;
        boat.setVelocity(new Vector(0, Math.min(boat.getVelocity().getY(), 0), 0));
        Location at = boat.getLocation();
        if (at.getWorld().equals(racer.spawn.getWorld())
                && Math.abs(at.getX() - racer.spawn.getX()) < 0.5
                && Math.abs(at.getZ() - racer.spawn.getZ()) < 0.5) {
            return;
        }
        Location back = racer.spawn.clone();
        if (at.getWorld().equals(racer.spawn.getWorld())) back.setY(at.getY());
        plugin.internal(racer.uuid, () -> {
            boat.teleport(back, TeleportCause.PLUGIN, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            Player player = Bukkit.getPlayer(racer.uuid);
            if (player != null && !boat.equals(player.getVehicle())) boat.addPassenger(player);
        });
    }

    private void run(long now) {
        long elapsed = (now - phaseStart) / 1_000_000L;
        for (Racer racer : List.copyOf(racers.values())) {
            Player player = Bukkit.getPlayer(racer.uuid);
            if (player == null) continue;
            String problem = problem(racer, player);
            if (problem != null) {
                out(racer, problem);
                continue;
            }
            Vector position = racer.boat.getLocation().toVector();
            if (racer.last == null) racer.last = position;
            if (racer.tracker.move(racer.last, position) && racer.tracker.laps() >= laps) {
                finish(racer, player, elapsed);
                continue;
            }
            racer.last = position;
            RacePlugin.actionBar(player, "&fКруги: &b" + racer.tracker.laps() + "&f/&b" + laps
                    + " &8| &fВремя: &b" + time(elapsed));
        }
        if (racers.isEmpty()) end();
    }

    private String problem(Racer racer, Player player) {
        if (racer.boat == null || !racer.boat.isValid()) return "лодка уничтожена";
        if (!racer.boat.equals(player.getVehicle())) return "выход из лодки";
        if (!racer.boat.getWorld().equals(cfg.world())) return "выход из мира гонок";
        return null;
    }

    // --- leaving the race ---

    private void finish(Racer racer, Player player, long elapsed) {
        results.add(new Result(racer.uuid, racer.name, elapsed, null));
        announce("&a" + racer.name + " финишировал: &f" + time(elapsed));
        release(racer, player);
    }

    /** Takes the racer out with a DNF; the result is recorded once and cannot change. */
    void out(UUID uuid, String reason) {
        Racer racer = racers.get(uuid);
        if (racer != null) out(racer, reason);
    }

    private void out(Racer racer, String reason) {
        Player player = Bukkit.getPlayer(racer.uuid);
        if (state != State.IDLE) {
            results.add(new Result(racer.uuid, racer.name, -1, reason));
            if (player != null) RacePlugin.send(player, "&cВы сняты с заезда: &f" + reason);
        }
        release(racer, player);
    }

    private void release(Racer racer, Player player) {
        racers.remove(racer.uuid);
        plugin.internal(racer.uuid, () -> {
            if (racer.boat != null && racer.boat.isValid()) {
                racer.boat.eject();
                racer.boat.remove();
            }
            if (player == null || !player.isOnline() || player.isDead()) {
                plugin.savePendingReturn(racer.uuid, racer.origin);
                return;
            }
            player.setAllowFlight(racer.couldFly);
            player.teleport(racer.origin, TeleportCause.PLUGIN);
            plugin.clearPendingReturn(racer.uuid);
        });
    }

    /** Called from the quit listener: nothing to teleport now, the return waits for the next login. */
    void quit(Player player) {
        Racer racer = racers.get(player.getUniqueId());
        if (racer == null) return;
        if (state != State.IDLE) {
            results.add(new Result(racer.uuid, racer.name, -1, "выход с сервера"));
        }
        release(racer, null);
    }

    void end() {
        if (state == State.IDLE) return;
        state = State.IDLE;
        for (Racer racer : List.copyOf(racers.values())) {
            results.add(new Result(racer.uuid, racer.name, -1, "не финишировал"));
            release(racer, Bukkit.getPlayer(racer.uuid));
        }
        if (!results.isEmpty()) {
            showTable();
            saveResults();
        }
        plugin.removeRaceBoats(cfg.world());
        results.clear();
        cfg = null;
    }

    // --- results ---

    /** Finishers by time (equal times share a place), then the DNFs with place 0. */
    private List<Map.Entry<Integer, Result>> standings() {
        List<Result> finished = results.stream()
                .filter(result -> result.millis() >= 0)
                .sorted(Comparator.comparingLong(Result::millis))
                .toList();
        List<Map.Entry<Integer, Result>> standings = new ArrayList<>();
        int place = 0;
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < finished.size(); i++) {
            Result result = finished.get(i);
            if (result.millis() != previous) {
                place = i + 1;
                previous = result.millis();
            }
            standings.add(Map.entry(place, result));
        }
        results.stream().filter(result -> result.millis() < 0)
                .forEach(result -> standings.add(Map.entry(0, result)));
        return standings;
    }

    private void showTable() {
        Bukkit.broadcast(RacePlugin.text("&8--- &bРезультаты заезда &f" + raceId + " &8---"));
        for (Map.Entry<Integer, Result> entry : standings()) {
            Result result = entry.getValue();
            Bukkit.broadcast(RacePlugin.text(entry.getKey() == 0
                    ? "&7-. " + result.name() + " &8- &cDNF &7(" + result.reason() + ")"
                    : "&f" + entry.getKey() + ". &b" + result.name() + " &8- &f" + time(result.millis())));
        }
    }

    private void saveResults() {
        YamlConfiguration out = new YamlConfiguration();
        out.set("id", raceId);
        out.set("date", LocalDateTime.now().toString());
        out.set("world", cfg.world().getName());
        out.set("laps", laps);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, Result> entry : standings()) {
            Result result = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("place", entry.getKey() == 0 ? "DNF" : entry.getKey());
            row.put("name", result.name());
            row.put("uuid", result.uuid().toString());
            if (entry.getKey() == 0) {
                row.put("reason", result.reason());
            } else {
                row.put("time", time(result.millis()));
                row.put("time-ms", result.millis());
            }
            rows.add(row);
        }
        out.set("results", rows);
        File file = new File(plugin.getDataFolder(), "results/" + raceId + ".yml");
        file.getParentFile().mkdirs();
        try {
            out.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Не удалось сохранить результаты: " + exception.getMessage());
        }
    }

    // --- helpers ---

    /** Puts one player on the grid; the boat is dropped from above so it never spawns inside a block. */
    private void place(Player player, RaceConfig config, Location start) {
        Racer racer = new Racer(player, start.clone().add(0, SPAWN_LIFT, 0), config.track());
        racers.put(racer.uuid, racer);
        plugin.savePendingReturn(racer.uuid, racer.origin);
        plugin.internal(racer.uuid, () -> {
            player.leaveVehicle();
            player.setFlying(false);
            player.setAllowFlight(false);
            player.teleport(racer.spawn, TeleportCause.PLUGIN);
            racer.boat = spawnBoat(racer.spawn, player);
        });
        if (racer.boat == null) {
            RacePlugin.send(player, "&cНе удалось выдать лодку, вы вне заезда.");
            release(racer, player);
        }
    }

    private Boat spawnBoat(Location start, Player player) {
        Boat boat = start.getWorld().spawn(start, OakBoat.class);
        boat.getPersistentDataContainer().set(plugin.boatKey(), PersistentDataType.STRING,
                player.getUniqueId().toString());
        boat.setInvulnerable(true);
        if (!boat.addPassenger(player)) {
            boat.remove();
            return null;
        }
        return boat;
    }

    private void announce(String message) {
        Bukkit.broadcast(RacePlugin.text(message));
    }

    static String time(long millis) {
        return String.format("%02d:%02d.%02d", millis / 60_000, millis / 1_000 % 60, millis % 1_000 / 10);
    }
}
