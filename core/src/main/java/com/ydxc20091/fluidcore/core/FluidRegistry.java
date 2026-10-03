package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.util.*;

/** Atomically published immutable registry; a reload can replace only its own registrations. */
public final class FluidRegistry {
    private volatile Snapshot snapshot;
    private final Map<FluidKey, String> fluidOwners = new HashMap<>();
    private final Map<FluidKey, String> codecOwners = new HashMap<>();
    private final Map<FluidKey, String> tagOwners = new HashMap<>();
    private final Map<FluidKey, FluidDefinition> metadataOverrides = new HashMap<>();
    private final Map<FluidKey, String> overrideOwners = new HashMap<>();

    public FluidRegistry() {
        snapshot = new Snapshot(Map.of(), Map.of(), 0);
        replaceOwned("minecraft", List.of(
            FluidDefinition.builder("minecraft:water").displayName("Water").tags(FluidKey.of("fluidcore:water"))
                .properties(FluidProperties.builder().color(0xff3f76e4).texture("minecraft:block/water_still")
                    .bucketItem("minecraft:water_bucket").sound(FluidSound.CONTAINER_FILL, "minecraft:item.bucket.fill")
                    .sound(FluidSound.CONTAINER_EMPTY, "minecraft:item.bucket.empty")
                    .sound(FluidSound.VAPORIZE, "minecraft:block.fire.extinguish").sound(FluidSound.CAULDRON_DRIP, "minecraft:block.pointed_dripstone.drip_water_into_cauldron").build()).build(),
            FluidDefinition.builder("minecraft:lava").displayName("Lava").tags(FluidKey.of("fluidcore:lava"))
                .properties(FluidProperties.builder().density(3000).viscosity(6000).temperature(1300).lightLevel(15)
                    .color(0xffff6600).texture("minecraft:block/lava_still").bucketItem("minecraft:lava_bucket")
                    .sound(FluidSound.CONTAINER_FILL, "minecraft:item.bucket.fill_lava")
                    .sound(FluidSound.CONTAINER_EMPTY, "minecraft:item.bucket.empty_lava")
                    .sound(FluidSound.VAPORIZE, "minecraft:block.fire.extinguish").sound(FluidSound.CAULDRON_DRIP, "minecraft:block.pointed_dripstone.drip_lava_into_cauldron").build()).build(),
            FluidDefinition.builder("minecraft:milk").displayName("Milk").tags(FluidKey.of("fluidcore:milk"))
                .properties(FluidProperties.builder().density(1030).viscosity(1000).temperature(300).color(0xffffffff)
                    .bucketItem("minecraft:milk_bucket").sound(FluidSound.CONTAINER_FILL, "minecraft:item.bucket.fill")
                    .sound(FluidSound.CONTAINER_EMPTY, "minecraft:item.bucket.empty").build()).build(),
            FluidDefinition.builder("minecraft:honey").displayName("Honey").tags(FluidKey.of("fluidcore:honey"), FluidKey.of("c:honey"))
                .properties(FluidProperties.builder().density(1420).viscosity(10000).temperature(300).color(0xffdf9a22)
                    .sound(FluidSound.CONTAINER_FILL, "minecraft:item.bottle.fill").sound(FluidSound.CONTAINER_EMPTY, "minecraft:item.bottle.empty").build()).build()
        ));
    }

    public Snapshot snapshot() { return snapshot; }
    public Optional<FluidDefinition> find(FluidKey key) { return Optional.ofNullable(snapshot.fluids.get(key)); }
    public Optional<FluidDefinition> find(String key) { return find(FluidKey.of(key)); }
    public boolean contains(FluidKey key) { return snapshot.fluids.containsKey(key); }
    public boolean contains(String key) { return contains(FluidKey.of(key)); }
    public Collection<FluidDefinition> all() { return snapshot.fluids.values(); }
    public int size() { return snapshot.fluids.size(); }
    public Set<FluidKey> tagMembers(FluidKey tag) { return snapshot.tagMembers(tag); }
    public Set<FluidKey> tagsOf(FluidKey fluid) { return snapshot.tagsOf(fluid); }
    public Optional<FluidDefinition> baseDefinition(FluidKey key) { return snapshot.baseDefinition(key); }
    public Optional<FluidDefinition> baseDefinition(String key) { return baseDefinition(FluidKey.of(key)); }
    public Optional<FluidDefinition> codeDefinition(FluidKey key) { return baseDefinition(key); }
    public Optional<FluidDefinition> codeDefinition(String key) { return baseDefinition(key); }
    /** Returns an explicitly installed overlay, even when it equals its current base metadata. */
    public synchronized Optional<FluidDefinition> metadataOverride(FluidKey key) {
        return Optional.ofNullable(metadataOverrides.get(Objects.requireNonNull(key, "key")));
    }
    public boolean hasTag(FluidKey fluid, FluidKey tag) {
        return snapshot.hasTag(fluid, tag);
    }

    public synchronized void register(String owner, FluidDefinition definition) {
        checkOwner(owner);
        Objects.requireNonNull(definition, "definition");
        if (fluidOwners.containsKey(definition.key())) throw new IllegalArgumentException("Fluid is already registered: " + definition.key());
        var fluids = new HashMap<>(snapshot.baseDefinitions);
        fluids.put(definition.key(), definition);
        var owners = new HashMap<>(fluidOwners);
        owners.put(definition.key(), owner);
        publishFluids(fluids, owners);
    }

    public synchronized void replaceOwned(String owner, Collection<FluidDefinition> definitions) {
        checkOwner(owner);
        Objects.requireNonNull(definitions, "definitions");
        var replacements = new HashMap<FluidKey, FluidDefinition>();
        for (FluidDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (replacements.putIfAbsent(definition.key(), definition) != null) throw new IllegalArgumentException("Duplicate fluid: " + definition.key());
            String existingOwner = fluidOwners.get(definition.key());
            if (existingOwner != null && !owner.equals(existingOwner)) throw new IllegalArgumentException("Fluid belongs to another owner: " + definition.key());
        }
        var fluids = new HashMap<>(snapshot.baseDefinitions);
        var owners = new HashMap<>(fluidOwners);
        owners.entrySet().removeIf(entry -> {
            if (!owner.equals(entry.getValue())) return false;
            fluids.remove(entry.getKey()); return true;
        });
        fluids.putAll(replacements);
        replacements.keySet().forEach(key -> owners.put(key, owner));
        publishFluids(fluids, owners);
    }

    private void publishFluids(Map<FluidKey, FluidDefinition> fluids, Map<FluidKey, String> owners) {
        Snapshot next = nextSnapshot(fluids, snapshot.componentCodecs, snapshot.tags, metadataOverrides);
        dropMissingOverrides(fluids);
        fluidOwners.clear(); fluidOwners.putAll(owners); snapshot = next;
    }

    public synchronized <T> void registerComponentCodec(String owner, FluidKey key, ComponentCodec<T> codec) {
        checkOwner(owner); Objects.requireNonNull(key, "key"); Objects.requireNonNull(codec, "codec");
        if (codecOwners.containsKey(key)) throw new IllegalArgumentException("Component codec is already registered: " + key);
        var codecs = new HashMap<>(snapshot.componentCodecs); codecs.put(key, codec);
        Snapshot next = nextSnapshot(snapshot.baseDefinitions, codecs, snapshot.tags, metadataOverrides);
        codecOwners.put(key, owner); snapshot = next;
    }

    public synchronized void unregisterComponentCodecs(String owner) {
        checkOwner(owner);
        var codecs = new HashMap<>(snapshot.componentCodecs);
        codecOwners.entrySet().removeIf(entry -> {
            if (!owner.equals(entry.getValue())) return false;
            codecs.remove(entry.getKey()); return true;
        });
        snapshot = nextSnapshot(snapshot.baseDefinitions, codecs, snapshot.tags, metadataOverrides);
    }

    public <T> void registerComponentCodec(String owner, ComponentType<T> type) {
        Objects.requireNonNull(type, "type"); registerComponentCodec(owner, type.key(), type.codec());
    }

    /** Replaces owned fluid and standalone tag definitions in one validated publication. */
    public synchronized void replaceOwned(String owner, Collection<FluidDefinition> definitions,
                                         Map<FluidKey, ? extends Collection<FluidKey>> tagDefinitions) {
        replaceOwnedBatch(owner, definitions, tagDefinitions, null);
    }
    /** Explicit overlays change effective metadata without taking ownership of a base definition. */
    public synchronized void replaceOwned(String owner, Collection<FluidDefinition> definitions,
                                         Map<FluidKey, ? extends Collection<FluidKey>> tagDefinitions,
                                         Collection<FluidDefinition> overrides) {
        replaceOwnedBatch(owner, definitions, tagDefinitions, Objects.requireNonNull(overrides, "overrides"));
    }
    private void replaceOwnedBatch(String owner, Collection<FluidDefinition> definitions,
                                  Map<FluidKey, ? extends Collection<FluidKey>> tagDefinitions,
                                  Collection<FluidDefinition> overrides) {
        checkOwner(owner); Objects.requireNonNull(definitions, "definitions");
        var replacements = new HashMap<FluidKey, FluidDefinition>();
        for (FluidDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (replacements.putIfAbsent(definition.key(), definition) != null) throw new IllegalArgumentException("Duplicate fluid: " + definition.key());
            checkOwnership(fluidOwners, owner, definition.key(), "Fluid");
        }
        Map<FluidKey, Set<FluidKey>> replacementTags = validateTags(owner, tagDefinitions);
        var fluids = new HashMap<>(snapshot.baseDefinitions); var nextFluidOwners = new HashMap<>(fluidOwners);
        removeOwned(owner, fluids, nextFluidOwners); fluids.putAll(replacements);
        replacements.keySet().forEach(key -> nextFluidOwners.put(key, owner));
        var tags = new HashMap<>(snapshot.tags); var nextTagOwners = new HashMap<>(tagOwners);
        removeOwned(owner, tags, nextTagOwners); tags.putAll(replacementTags);
        replacementTags.keySet().forEach(key -> nextTagOwners.put(key, owner));
        var nextOverrides = new HashMap<>(metadataOverrides); var nextOverrideOwners = new HashMap<>(overrideOwners);
        if (overrides != null) {
            Map<FluidKey, FluidDefinition> replacementOverrides = validateOverrides(owner, overrides, fluids);
            removeOwned(owner, nextOverrides, nextOverrideOwners); nextOverrides.putAll(replacementOverrides);
            replacementOverrides.keySet().forEach(key -> nextOverrideOwners.put(key, owner));
        }
        nextOverrides.keySet().removeIf(key -> !fluids.containsKey(key));
        nextOverrideOwners.keySet().retainAll(nextOverrides.keySet());
        Snapshot next = nextSnapshot(fluids, snapshot.componentCodecs, tags, nextOverrides);
        fluidOwners.clear(); fluidOwners.putAll(nextFluidOwners); tagOwners.clear(); tagOwners.putAll(nextTagOwners);
        metadataOverrides.clear(); metadataOverrides.putAll(nextOverrides); overrideOwners.clear(); overrideOwners.putAll(nextOverrideOwners); snapshot = next;
    }

    public synchronized void registerTag(String owner, FluidKey tag, Collection<FluidKey> members) {
        checkOwner(owner); Objects.requireNonNull(tag, "tag");
        if (tagOwners.containsKey(tag)) throw new IllegalArgumentException("Tag is already registered: " + tag);
        var tags = new HashMap<>(snapshot.tags); tags.put(tag, Set.copyOf(members));
        Snapshot next = nextSnapshot(snapshot.baseDefinitions, snapshot.componentCodecs, tags, metadataOverrides);
        tagOwners.put(tag, owner); snapshot = next;
    }
    public synchronized void replaceOwnedTags(String owner, Map<FluidKey, ? extends Collection<FluidKey>> definitions) {
        checkOwner(owner); var replacements = validateTags(owner, definitions);
        var tags = new HashMap<>(snapshot.tags); var owners = new HashMap<>(tagOwners);
        removeOwned(owner, tags, owners); tags.putAll(replacements); replacements.keySet().forEach(key -> owners.put(key, owner));
        Snapshot next = nextSnapshot(snapshot.baseDefinitions, snapshot.componentCodecs, tags, metadataOverrides);
        tagOwners.clear(); tagOwners.putAll(owners); snapshot = next;
    }
    public void unregisterTags(String owner) { replaceOwnedTags(owner, Map.of()); }
    public synchronized boolean unregisterTag(String owner, FluidKey tag) {
        checkOwner(owner); Objects.requireNonNull(tag, "tag");
        String existing = tagOwners.get(tag); if (existing == null) return false;
        if (!owner.equals(existing)) throw new IllegalArgumentException("Tag belongs to another owner: " + tag);
        var tags = new HashMap<>(snapshot.tags); tags.remove(tag);
        Snapshot next = nextSnapshot(snapshot.baseDefinitions, snapshot.componentCodecs, tags, metadataOverrides);
        tagOwners.remove(tag); snapshot = next; return true;
    }
    public synchronized void replaceOwnedOverrides(String owner, Collection<FluidDefinition> definitions) {
        checkOwner(owner); var replacements = validateOverrides(owner, definitions, snapshot.baseDefinitions);
        var overrides = new HashMap<>(metadataOverrides); var owners = new HashMap<>(overrideOwners);
        removeOwned(owner, overrides, owners); overrides.putAll(replacements); replacements.keySet().forEach(key -> owners.put(key, owner));
        Snapshot next = nextSnapshot(snapshot.baseDefinitions, snapshot.componentCodecs, snapshot.tags, overrides);
        metadataOverrides.clear(); metadataOverrides.putAll(overrides); overrideOwners.clear(); overrideOwners.putAll(owners); snapshot = next;
    }
    public void unregisterOverrides(String owner) { replaceOwnedOverrides(owner, List.of()); }
    private Map<FluidKey, FluidDefinition> validateOverrides(String owner, Collection<FluidDefinition> definitions,
                                                           Map<FluidKey, FluidDefinition> bases) {
        Objects.requireNonNull(definitions, "overrides"); var result = new HashMap<FluidKey, FluidDefinition>();
        for (FluidDefinition definition : definitions) {
            Objects.requireNonNull(definition, "override");
            if (!bases.containsKey(definition.key())) throw new IllegalArgumentException("Override needs a registered base fluid: " + definition.key());
            checkOwnership(overrideOwners, owner, definition.key(), "Metadata override");
            if (result.putIfAbsent(definition.key(), definition) != null) throw new IllegalArgumentException("Duplicate metadata override: " + definition.key());
        }
        return result;
    }
    private Snapshot nextSnapshot(Map<FluidKey, FluidDefinition> bases, Map<FluidKey, ComponentCodec<?>> codecs,
                                  Map<FluidKey, Set<FluidKey>> tags, Map<FluidKey, FluidDefinition> overrides) {
        var effective = new HashMap<>(bases);
        overrides.forEach((key, definition) -> { if (bases.containsKey(key)) effective.put(key, definition); });
        return new Snapshot(effective, codecs, tags, Math.incrementExact(snapshot.generation), bases);
    }
    private void dropMissingOverrides(Map<FluidKey, FluidDefinition> bases) {
        metadataOverrides.keySet().removeIf(key -> !bases.containsKey(key)); overrideOwners.keySet().retainAll(metadataOverrides.keySet());
    }
    private Map<FluidKey, Set<FluidKey>> validateTags(String owner, Map<FluidKey, ? extends Collection<FluidKey>> definitions) {
        Objects.requireNonNull(definitions, "tagDefinitions"); var result = new HashMap<FluidKey, Set<FluidKey>>();
        definitions.forEach((tag, members) -> {
            Objects.requireNonNull(tag, "tag"); checkOwnership(tagOwners, owner, tag, "Tag");
            result.put(tag, Set.copyOf(Objects.requireNonNull(members, "members")));
        }); return result;
    }
    private static void checkOwnership(Map<FluidKey, String> owners, String owner, FluidKey key, String type) {
        String existing = owners.get(key);
        if (existing != null && !owner.equals(existing)) throw new IllegalArgumentException(type + " belongs to another owner: " + key);
    }
    private static <T> void removeOwned(String owner, Map<FluidKey, T> values, Map<FluidKey, String> owners) {
        owners.entrySet().removeIf(entry -> {
            if (!owner.equals(entry.getValue())) return false;
            values.remove(entry.getKey()); return true;
        });
    }

    private static void checkOwner(String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Registration owner must not be blank");
    }

    public record Snapshot(Map<FluidKey, FluidDefinition> fluids, Map<FluidKey, ComponentCodec<?>> componentCodecs,
                           Map<FluidKey, Set<FluidKey>> tags, long generation, Map<FluidKey, FluidDefinition> baseDefinitions) {
        public Snapshot {
            fluids = Map.copyOf(fluids); componentCodecs = Map.copyOf(componentCodecs);
            var immutable = new HashMap<FluidKey, Set<FluidKey>>();
            tags.forEach((key, values) -> immutable.put(Objects.requireNonNull(key, "tag"), Set.copyOf(values)));
            tags = Map.copyOf(immutable);
            baseDefinitions = Map.copyOf(baseDefinitions);
        }
        public Snapshot(Map<FluidKey, FluidDefinition> fluids, Map<FluidKey, ComponentCodec<?>> componentCodecs,
                        Map<FluidKey, Set<FluidKey>> tags, long generation) {
            this(fluids, componentCodecs, tags, generation, fluids);
        }
        public Snapshot(Map<FluidKey, FluidDefinition> fluids, Map<FluidKey, ComponentCodec<?>> componentCodecs, long generation) {
            this(fluids, componentCodecs, Map.of(), generation);
        }
        public Optional<FluidDefinition> find(FluidKey key) { return Optional.ofNullable(fluids.get(key)); }
        public Optional<FluidDefinition> baseDefinition(FluidKey key) { return Optional.ofNullable(baseDefinitions.get(key)); }
        public Set<FluidKey> tagsOf(FluidKey fluid) {
            Objects.requireNonNull(fluid, "fluid"); FluidDefinition definition = fluids.get(fluid);
            if (definition == null) return Set.of();
            var result = new HashSet<>(definition.tags());
            tags.forEach((tag, members) -> { if (members.contains(fluid)) result.add(tag); }); return Set.copyOf(result);
        }
        public boolean hasTag(FluidKey fluid, FluidKey tag) {
            Objects.requireNonNull(fluid, "fluid"); Objects.requireNonNull(tag, "tag");
            FluidDefinition definition = fluids.get(fluid);
            return definition != null && (definition.tags().contains(tag) || tags.getOrDefault(tag, Set.of()).contains(fluid));
        }
        /** Includes unresolved standalone members so metadata survives delayed registrations. */
        public Set<FluidKey> tagMembers(FluidKey tag) {
            Objects.requireNonNull(tag, "tag"); var result = new HashSet<>(tags.getOrDefault(tag, Set.of()));
            fluids.values().stream().filter(definition -> definition.tags().contains(tag)).forEach(definition -> result.add(definition.key()));
            return Set.copyOf(result);
        }
    }
}
