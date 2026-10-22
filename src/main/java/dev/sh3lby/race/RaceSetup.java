package dev.sh3lby.race;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Track setup in game: zones come from the WorldEdit //wand selection, starts from where you stand. */
final class RaceSetup {

    static final List<String> SUBCOMMANDS =
            List.of("setworld", "addstart", "setfinish", "addcp", "clear");
    static final List<String> DIRECTIONS = List.of("+X", "-X", "+Y", "-Y", "+Z", "-Z");

    private final RacePlugin plugin;
    private final Race race;

    RaceSetup(RacePlugin plugin, Race race) {
        this.plugin = plugin;
        this.race = race;
    }

    /** Returns false when this is not a setup subcommand. */
    boolean handle(CommandSender sender, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) return false;
        if (!sender.hasPermission("race.admin")) {
            RacePlugin.send(sender, "&cНужно право race.admin.");
            return true;
        }
        if (race.active()) {
            RacePlugin.send(sender, "&cТрассу нельзя менять во время заезда.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            RacePlugin.send(sender, "&cНастройка трассы — только для игроков.");
            return true;
        }
        switch (sub) {
            case "setworld" -> setWorld(player);
            case "addstart" -> addStart(player);
            case "setfinish" -> setFinish(player, args);
            case "addcp" -> addZone(player, "checkpoints", "Чекпоинт");
            default -> clear(player, args);
        }
        return true;
    }

    private void setWorld(Player player) {
        plugin.getConfig().set("world", player.getWorld().getName());
        plugin.saveConfig();
        RacePlugin.send(player, "&aМир гонок: &f" + player.getWorld().getName());
    }

    private void addStart(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        if (!raceWorld(player, cfg)) return;
        Location at = player.getLocation();
        List<Object> starts = new ArrayList<>(cfg.getList("starts", new ArrayList<>()));
        starts.add(start(at.getX(), at.getY(), at.getZ(), at.getYaw()));
        cfg.set("starts", starts);
        plugin.saveConfig();
        RacePlugin.send(player, "&aСтартовая позиция №" + starts.size() + ": &f"
                + round(at.getX()) + ", " + round(at.getY()) + ", " + round(at.getZ()));
    }

    private void addZone(Player player, String path, String label) {
        FileConfiguration cfg = plugin.getConfig();
        if (!raceWorld(player, cfg)) return;
        int[] box = selection(player);
        if (box == null) return;
        List<Object> zones = new ArrayList<>(cfg.getMapList(path));
        zones.add(zone(box));
        cfg.set(path, zones);
        plugin.saveConfig();
        RacePlugin.send(player, "&a" + label + " №" + zones.size() + ": &f" + describe(box));
    }

    private void setFinish(Player player, String[] args) {
        if (args.length < 2) {
            RacePlugin.send(player, "&cНужно направление пересечения: &f/race setfinish +X");
            return;
        }
        List<String> errors = new ArrayList<>();
        if (RaceConfig.direction(args[1], errors) == null) {
            RacePlugin.send(player, "&c" + errors.get(0));
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        if (!raceWorld(player, cfg)) return;
        int[] box = selection(player);
        if (box == null) return;
        Map<String, Object> finish = zone(box);
        finish.put("direction", args[1].toUpperCase(Locale.ROOT));
        cfg.createSection("finish", finish);
        plugin.saveConfig();
        RacePlugin.send(player, "&aФиниш: &f" + describe(box) + "&a, направление &f"
                + args[1].toUpperCase(Locale.ROOT));
    }

    private void clear(Player player, String[] args) {
        String path = args.length < 2 ? null : switch (args[1].toLowerCase(Locale.ROOT)) {
            case "starts" -> "starts";
            case "cp", "checkpoints" -> "checkpoints";
            default -> null;
        };
        if (path == null) {
            RacePlugin.send(player, "&cЧто чистить: &fstarts&c или &fcp&c. "
                    + "Добавь &flast&c, чтобы убрать только последнюю запись.");
            return;
        }
        FileConfiguration cfg = plugin.getConfig();
        List<Object> kept = new ArrayList<>(cfg.getList(path, new ArrayList<>()));
        if (args.length > 2 && args[2].equalsIgnoreCase("last")) {
            if (!kept.isEmpty()) kept.remove(kept.size() - 1);
        } else {
            kept.clear();
        }
        cfg.set(path, kept);
        plugin.saveConfig();
        RacePlugin.send(player, "&aОсталось записей в &f" + path + "&a: &f" + kept.size());
    }

    /** Keeps the whole track in one world; an empty setting adopts the world you stand in. */
    private boolean raceWorld(Player player, FileConfiguration cfg) {
        String configured = cfg.getString("world", "");
        if (configured.isBlank()) {
            cfg.set("world", player.getWorld().getName());
            RacePlugin.send(player, "&aМир гонок: &f" + player.getWorld().getName());
            return true;
        }
        if (configured.equals(player.getWorld().getName())) return true;
        RacePlugin.send(player, "&cЭто не мир гонок (&f" + configured
                + "&c). Смени его командой &f/race setworld&c.");
        return false;
    }

    private int[] selection(Player player) {
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null
                && Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") == null) {
            RacePlugin.send(player, "&cДля зон нужен WorldEdit: выделение берётся из &f//wand&c.");
            return null;
        }
        int[] box = WorldEditSelection.corners(player);
        if (box == null) {
            RacePlugin.send(player, "&cСначала выдели зону: &f//wand&c, затем левый и правый клик по углам.");
        }
        return box;
    }

    static List<Object> start(double x, double y, double z, float yaw) {
        return List.of(round(x), round(y), round(z), Math.round(yaw));
    }

    static Map<String, Object> zone(int[] box) {
        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("corner1", List.of(box[0], box[1], box[2]));
        zone.put("corner2", List.of(box[3], box[4], box[5]));
        return zone;
    }

    private static String describe(int[] box) {
        return box[0] + "," + box[1] + "," + box[2] + " .. " + box[3] + "," + box[4] + "," + box[5];
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
