package dev.sh3lby.race;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed track setup. {@code errors} is empty only when the track can be raced on;
 * the plugin refuses to start a race otherwise and shows the reasons to the admin.
 */
record RaceConfig(World world, List<Location> starts, LapTracker.Track track,
                  int laps, int countdownSeconds, List<String> errors) {

    boolean valid() {
        return errors.isEmpty();
    }

    static RaceConfig load(FileConfiguration cfg) {
        List<String> errors = new ArrayList<>();

        String worldName = cfg.getString("world", "");
        World world = worldName.isBlank() ? null : Bukkit.getWorld(worldName);
        if (world == null) errors.add("world: мир '" + worldName + "' не найден или не загружен");

        int laps = positive(cfg, "laps", 15, errors);
        int countdown = positive(cfg, "countdown-seconds", 10, errors);

        List<Zone> checkpoints = zones(cfg.getMapList("checkpoints"), "checkpoints", errors);
        if (checkpoints.isEmpty()) errors.add("checkpoints: нужен хотя бы один чекпоинт");

        ConfigurationSection finishSection = cfg.getConfigurationSection("finish");
        Zone finish = finishSection == null ? null : zone(finishSection.getValues(false), "finish", errors);
        if (finishSection == null) errors.add("finish: зона финиша не задана");
        Vector direction = direction(finishSection == null ? null : finishSection.getString("direction"), errors);

        List<Location> starts = new ArrayList<>();
        int startCount = 0;
        for (Object raw : cfg.getList("starts", List.of())) {
            double[] v = numbers(raw, 3, 4, "starts", errors);
            if (v == null) continue;
            startCount++;
            if (world != null) {
                starts.add(new Location(world, v[0], v[1], v[2], v.length > 3 ? (float) v[3] : 0f, 0f));
            }
        }
        if (startCount == 0) errors.add("starts: нужна хотя бы одна стартовая позиция");

        LapTracker.Track track = finish == null || direction == null ? null
                : new LapTracker.Track(checkpoints, finish, direction);
        return new RaceConfig(world, starts, track, laps, countdown, errors);
    }

    private static int positive(FileConfiguration cfg, String path, int fallback, List<String> errors) {
        int value = cfg.getInt(path, fallback);
        if (value < 1) {
            errors.add(path + ": должно быть больше нуля");
            return fallback;
        }
        return value;
    }

    private static List<Zone> zones(List<Map<?, ?>> raw, String path, List<String> errors) {
        List<Zone> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Zone zone = zone(raw.get(i), path + "[" + (i + 1) + "]", errors);
            if (zone != null) out.add(zone);
        }
        return out;
    }

    private static Zone zone(Map<?, ?> map, String path, List<String> errors) {
        double[] a = numbers(map.get("corner1"), 3, 3, path + ".corner1", errors);
        double[] b = numbers(map.get("corner2"), 3, 3, path + ".corner2", errors);
        if (a == null || b == null) return null;
        return Zone.ofBlocks(a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    static Vector direction(String raw, List<String> errors) {
        return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
            case "+X", "X" -> new Vector(1, 0, 0);
            case "-X" -> new Vector(-1, 0, 0);
            case "+Y", "Y" -> new Vector(0, 1, 0);
            case "-Y" -> new Vector(0, -1, 0);
            case "+Z", "Z" -> new Vector(0, 0, 1);
            case "-Z" -> new Vector(0, 0, -1);
            default -> {
                errors.add("finish.direction: ожидалось +X, -X, +Y, -Y, +Z или -Z");
                yield null;
            }
        };
    }

    private static double[] numbers(Object raw, int min, int max, String path, List<String> errors) {
        if (!(raw instanceof List<?> list) || list.size() < min || list.size() > max) {
            errors.add(path + ": ожидался список из " + min + "-" + max + " чисел");
            return null;
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Number number)) {
                errors.add(path + ": '" + list.get(i) + "' не число");
                return null;
            }
            out[i] = number.doubleValue();
        }
        return out;
    }
}
