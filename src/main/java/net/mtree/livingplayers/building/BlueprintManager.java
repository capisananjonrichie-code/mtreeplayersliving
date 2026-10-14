package net.mtree.livingplayers.building;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.stream.Stream;

/** Loads and validates blueprint JSON files from the plugin's blueprints/ folder. Invalid files are reported and skipped. */
public final class BlueprintManager {
    private final Plugin plugin;
    private final Map<String, Blueprint> byName = new TreeMap<>();

    public BlueprintManager(Plugin plugin) { this.plugin = plugin; }

    public void load() {
        byName.clear();
        Path dir = plugin.getDataFolder().toPath().resolve("blueprints");
        try {
            Files.createDirectories(dir);
            Path starter = dir.resolve("starter_hut.json");
            if (!Files.exists(starter)) {
                try (InputStream in = plugin.getResource("starter_hut.json")) {
                    if (in != null) Files.copy(in, starter, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    BlueprintParser.Result r = BlueprintParser.parse(Files.readString(f, StandardCharsets.UTF_8));
                    if (r.ok()) byName.put(r.blueprint().name(), r.blueprint());
                    else plugin.getLogger().warning("Blueprint " + f.getFileName() + " rejected: " + r.error());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read the blueprints folder", e);
        }
        plugin.getLogger().info("Loaded " + byName.size() + " blueprint(s): " + byName.keySet());
    }

    public Optional<Blueprint> get(String name) { return Optional.ofNullable(name == null ? null : byName.get(name.toLowerCase())); }
    public List<String> names() { return new ArrayList<>(byName.keySet()); }
    public Optional<Blueprint> first() { return byName.values().stream().findFirst(); }
}
