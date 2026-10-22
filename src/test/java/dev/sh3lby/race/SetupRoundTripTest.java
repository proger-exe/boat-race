package dev.sh3lby.race;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What /race addcp and friends write must be exactly what the track parser reads back. */
class SetupRoundTripTest {

    @Test
    void setupWritesWhatTheParserReads() throws IOException {
        YamlConfiguration written = new YamlConfiguration();
        written.set("world", "");
        written.set("starts", List.of(RaceSetup.start(1.2345, 63.0, 2.5, -90f)));
        written.set("checkpoints", List.of(RaceSetup.zone(new int[]{10, 60, 0, 12, 70, 0})));
        Map<String, Object> finish = RaceSetup.zone(new int[]{0, 60, 40, 0, 70, 50});
        finish.put("direction", "-Z");
        written.set("finish", finish);

        File file = File.createTempFile("race-config", ".yml");
        file.deleteOnExit();
        written.save(file);

        RaceConfig parsed = RaceConfig.load(YamlConfiguration.loadConfiguration(file));
        assertEquals(List.of("world: мир '' не найден или не загружен"), parsed.errors());
        assertEquals(1, parsed.track().checkpoints().size());
        assertEquals(new Vector(0, 0, -1), parsed.track().finishDirection());
        assertEquals(Zone.ofBlocks(0, 60, 40, 0, 70, 50), parsed.track().finish());
    }

    /** A second /race addcp appends to zones that came back from the file, not from memory. */
    @Test
    void secondZoneAppendsToTheSavedOnes() throws IOException {
        File file = File.createTempFile("race-config", ".yml");
        file.deleteOnExit();
        YamlConfiguration first = new YamlConfiguration();
        first.set("checkpoints", List.of(RaceSetup.zone(new int[]{10, 60, 0, 12, 70, 0})));
        first.save(file);

        YamlConfiguration reopened = YamlConfiguration.loadConfiguration(file);
        List<Object> zones = new ArrayList<>(reopened.getMapList("checkpoints"));
        zones.add(RaceSetup.zone(new int[]{20, 60, 5, 22, 70, 5}));
        reopened.set("checkpoints", zones);
        reopened.save(file);

        YamlConfiguration whole = YamlConfiguration.loadConfiguration(file);
        whole.set("starts", List.of(RaceSetup.start(11.0, 63.0, 0.5, 0f)));
        Map<String, Object> finish = RaceSetup.zone(new int[]{0, 60, 40, 0, 70, 50});
        finish.put("direction", "+Z");
        whole.createSection("finish", finish);
        whole.save(file);

        RaceConfig parsed = RaceConfig.load(YamlConfiguration.loadConfiguration(file));
        assertEquals(List.of("world: мир '' не найден или не загружен"), parsed.errors());
        assertEquals(List.of(Zone.ofBlocks(10, 60, 0, 12, 70, 0), Zone.ofBlocks(20, 60, 5, 22, 70, 5)),
                parsed.track().checkpoints());
    }
}
