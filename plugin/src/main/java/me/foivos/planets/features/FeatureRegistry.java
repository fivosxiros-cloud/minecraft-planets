package me.foivos.planets.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of features this server knows how to run, keyed by id.
 * <p>
 * Registration happens once at startup — by this plugin for its built-ins, or
 * by another module through {@link #register(String, PlanetFeature)} — and a
 * profile then switches a feature on with {@code features.<id>: true}. Nothing
 * is loaded dynamically: a feature is a Java class that was on the classpath
 * when the server started, which is exactly what keeps the system honest about
 * the "no dynamic mod loading" rule.
 * <p>
 * Reads happen from the world-change path, so the map is concurrent and the
 * ordered views are rebuilt on demand rather than mutated in place.
 */
public final class FeatureRegistry {

    private final Map<String, PlanetFeature> features = new ConcurrentHashMap<>();

    /**
     * Registers a feature under {@code id}. The id is normalised to lower case;
     * registering the same id twice replaces the previous feature (a reload of
     * a module should not leave two implementations behind).
     *
     * @return the feature that was displaced, or null
     */
    public PlanetFeature register(String id, PlanetFeature feature) {
        if (id == null || id.isBlank() || feature == null) {
            throw new IllegalArgumentException("A feature needs both an id and an implementation");
        }
        return features.put(id.toLowerCase(Locale.ROOT), feature);
    }

    /** Shorthand for registering a feature under its own {@link PlanetFeature#id()}. */
    public PlanetFeature register(PlanetFeature feature) {
        return register(feature.id(), feature);
    }

    /** The feature with this id, or null when nothing implements it. */
    public PlanetFeature get(String id) {
        return id == null ? null : features.get(id.toLowerCase(Locale.ROOT));
    }

    /** Every feature, in id order (stable output for debug commands). */
    public List<PlanetFeature> all() {
        List<PlanetFeature> ordered = new ArrayList<>(features.values());
        ordered.sort(java.util.Comparator.comparing(PlanetFeature::id));
        return ordered;
    }

    /** Every registered id, in order — what {@code /planets debug} prints. */
    public List<String> ids() {
        List<String> ids = new ArrayList<>(features.keySet());
        ids.sort(String::compareTo);
        return ids;
    }

    public boolean isEmpty() {
        return features.isEmpty();
    }

    public int size() {
        return features.size();
    }
}
