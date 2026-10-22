package dev.sh3lby.race;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.UUID;

public final class RacePlugin extends org.bukkit.plugin.java.JavaPlugin implements TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PREFIX = "&8[&bГонка&8] &r";

    /** Players the plugin is currently moving itself, so its own work never disqualifies them. */
    private final Set<UUID> internal = new HashSet<>();
    private Race race;
    private RaceSetup setup;
    private NamespacedKey boatKey;
    private File returnsFile;
    private YamlConfiguration returns;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boatKey = new NamespacedKey(this, "race_boat");
        race = new Race(this);
        setup = new RaceSetup(this, race);
        returnsFile = new File(getDataFolder(), "returns.yml");
        returns = YamlConfiguration.loadConfiguration(returnsFile);

        RaceConfig config = freshConfig();
        if (config.valid()) {
            removeRaceBoats(config.world());
        } else {
            getLogger().warning("Заезды недоступны, трасса настроена неверно:");
            config.errors().forEach(error -> getLogger().warning("- " + error));
        }

        getServer().getPluginManager().registerEvents(new RaceListeners(this, race), this);
        getCommand("race").setExecutor(this);
        getServer().getScheduler().runTaskTimer(this, race::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (race != null && race.active()) race.stop(getServer().getConsoleSender());
    }

    RaceConfig freshConfig() {
        reloadConfig();
        return RaceConfig.load(getConfig());
    }

    NamespacedKey boatKey() {
        return boatKey;
    }

    /** The owner of a plugin race boat, or null for any other entity. */
    @Nullable
    String boatOwner(Entity entity) {
        return entity.getPersistentDataContainer().get(boatKey, PersistentDataType.STRING);
    }

    void removeRaceBoats(World world) {
        if (world == null) return;
        for (Boat boat : world.getEntitiesByClass(Boat.class)) {
            if (boatOwner(boat) != null) boat.remove();
        }
    }

    // ponytail: plain flag set, no nesting — every caller wraps one short action.
    void internal(UUID uuid, Runnable action) {
        internal.add(uuid);
        try {
            action.run();
        } finally {
            internal.remove(uuid);
        }
    }

    boolean isInternal(UUID uuid) {
        return internal.contains(uuid);
    }

    void savePendingReturn(UUID uuid, Location location) {
        returns.set(uuid.toString(), location);
        saveReturns();
    }

    @Nullable
    Location pendingReturn(UUID uuid) {
        return returns.getLocation(uuid.toString());
    }

    void clearPendingReturn(UUID uuid) {
        returns.set(uuid.toString(), null);
        saveReturns();
    }

    private void saveReturns() {
        try {
            returnsFile.getParentFile().mkdirs();
            returns.save(returnsFile);
        } catch (IOException exception) {
            getLogger().warning("Не удалось сохранить точки возврата: " + exception.getMessage());
        }
    }

    // --- /race ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        if (setup.handle(sender, args)) return true;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "leave" -> {
                if (sender instanceof Player player) race.leave(player);
                else send(sender, "&cКоманда только для игроков.");
            }
            case "start" -> {
                if (!sender.hasPermission("race.admin")) {
                    send(sender, "&cНужно право race.admin.");
                    return true;
                }
                int laps = freshConfig().laps();
                if (args.length > 1) {
                    try {
                        laps = Integer.parseInt(args[1]);
                    } catch (NumberFormatException exception) {
                        send(sender, "&cКруги: нужно целое число.");
                        return true;
                    }
                    if (laps < 1) {
                        send(sender, "&cКругов должно быть больше нуля.");
                        return true;
                    }
                }
                race.start(sender, laps);
            }
            case "stop" -> {
                if (!sender.hasPermission("race.admin")) {
                    send(sender, "&cНужно право race.admin.");
                    return true;
                }
                race.stop(sender);
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        send(sender, "&7Заезд идёт для всех онлайн, записываться не нужно. &f/race leave &7— выйти.");
        if (sender.hasPermission("race.admin")) {
            send(sender, "&7Заезд: &f/race start [круги]&7, &f/race stop");
            send(sender, "&7Трасса: &f/race setworld&7, &faddstart&7, &fsetfinish <+X|-X|+Y|-Y|+Z|-Z>&7,"
                    + " &faddcp&7, &fclear <starts|cp> [last]");
            send(sender, "&7Зоны берутся из выделения WorldEdit (&f//wand&7).");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> Stream.concat(Stream.of("leave", "start", "stop"), RaceSetup.SUBCOMMANDS.stream()).toList();
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "setfinish" -> RaceSetup.DIRECTIONS;
                case "clear" -> List.of("starts", "cp");
                default -> List.of();
            };
            case 3 -> args[0].equalsIgnoreCase("clear") ? List.of("last") : List.<String>of();
            default -> List.of();
        };
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    // --- messages ---

    static Component text(String legacy) {
        return LEGACY.deserialize(legacy);
    }

    static void send(CommandSender target, String legacy) {
        target.sendMessage(text(PREFIX + legacy));
    }

    static void actionBar(Player player, String legacy) {
        player.sendActionBar(text(legacy));
    }

    static void title(Player player, String main, String subtitle) {
        player.showTitle(Title.title(text(main), text(subtitle),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200))));
    }
}
