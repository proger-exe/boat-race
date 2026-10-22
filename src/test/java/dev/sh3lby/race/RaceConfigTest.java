package dev.sh3lby.race;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RaceConfigTest {

    /** The shipped example track must be complete: only its world is missing outside a server. */
    @Test
    void bundledConfigIsComplete() {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        cfg.set("world", "");
        assertEquals(List.of("world: мир '' не найден или не загружен"), RaceConfig.load(cfg).errors());
    }

    @Test
    void brokenSetupNamesEveryReason() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("laps", 0);
        List<String> errors = RaceConfig.load(cfg).errors();
        assertEquals(List.of(
                "world: мир '' не найден или не загружен",
                "laps: должно быть больше нуля",
                "checkpoints: нужен хотя бы один чекпоинт",
                "finish: зона финиша не задана",
                "finish.direction: ожидалось +X, -X, +Y, -Y, +Z или -Z",
                "starts: нужна хотя бы одна стартовая позиция"), errors);
    }
}
