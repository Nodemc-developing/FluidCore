package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Immutable identity matcher with an optional quantity requirement. Copyright 2026 ydxc20091. */
public final class FluidIngredient {
    private final long amount;
    private final Predicate<FluidStack> matcher;
    private final Function<FluidRegistry, List<FluidVariant>> candidates;

    private FluidIngredient(long amount, Predicate<FluidStack> matcher, Function<FluidRegistry, List<FluidVariant>> candidates) {
        if (amount < 0) throw new IllegalArgumentException("Negative ingredient amount");
        this.amount = amount; this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }
    public static FluidIngredient fluid(FluidKey key) { return fluid(key, 0); }
    public static FluidIngredient fluid(String key) { return fluid(FluidKey.of(key), 0); }
    public static FluidIngredient fluid(FluidKey key, long amount) {
        Objects.requireNonNull(key, "key");
        return new FluidIngredient(amount, stack -> !stack.isEmpty() && stack.variant().fluid().equals(key),
            registry -> registry.contains(key) ? List.of(FluidVariant.of(key)) : List.of());
    }
    public static FluidIngredient tag(FluidRegistry registry, FluidKey tag) { return tag(registry, tag, 0); }
    public static FluidIngredient tag(FluidRegistry registry, FluidKey tag, long amount) {
        Objects.requireNonNull(registry, "registry"); Objects.requireNonNull(tag, "tag");
        return new FluidIngredient(amount, stack -> !stack.isEmpty() && registry.hasTag(stack.variant().fluid(), tag),
            ignored -> registry.tagMembers(tag).stream().filter(registry::contains).sorted().map(FluidVariant::of).toList());
    }
    public static FluidIngredient matching(long amount, Predicate<FluidVariant> matcher) {
        Objects.requireNonNull(matcher, "matcher");
        return new FluidIngredient(amount, stack -> !stack.isEmpty() && matcher.test(stack.variant()),
            registry -> registry.all().stream().map(FluidDefinition::key).sorted().map(FluidVariant::of).filter(matcher).toList());
    }
    /** Matches only the canonical empty stack. */
    public static FluidIngredient empty() { return new FluidIngredient(0, FluidStack::isEmpty, registry -> List.of()); }
    /** Combines identities; use withAmount to apply one quantity requirement to the union. */
    public static FluidIngredient anyOf(FluidIngredient... parts) { return anyOf(List.of(parts)); }
    public static FluidIngredient anyOf(Collection<FluidIngredient> parts) {
        List<FluidIngredient> immutable = List.copyOf(parts);
        if (immutable.isEmpty()) return empty();
        return new FluidIngredient(0, stack -> immutable.stream().anyMatch(part -> part.matchesIdentity(stack)), registry -> {
            var result = new LinkedHashSet<FluidVariant>();
            immutable.forEach(part -> result.addAll(part.candidates(registry))); return List.copyOf(result);
        });
    }
    public static FluidIngredient anyOfFluid(Collection<FluidKey> fluids, long amount) {
        return anyOf(fluids.stream().map(FluidIngredient::fluid).toList()).withAmount(amount);
    }
    public FluidIngredient components(Map<FluidKey, ComponentValue> required, boolean exactComponents) {
        Map<FluidKey, ComponentValue> immutable = Map.copyOf(required);
        return new FluidIngredient(amount, stack -> !stack.isEmpty() && matcher.test(stack) &&
            (exactComponents ? stack.variant().components().equals(immutable) : stack.variant().components().entrySet().containsAll(immutable.entrySet())), registry -> {
                var result = new ArrayList<FluidVariant>();
                for (FluidVariant candidate : candidates(registry)) {
                    var components = new HashMap<>(exactComponents ? Map.<FluidKey, ComponentValue>of() : candidate.components());
                    components.putAll(immutable); var variant = FluidVariant.of(candidate.fluid(), components);
                    if (matcher.test(FluidStack.of(variant, 1))) result.add(variant);
                }
                return List.copyOf(result);
            });
    }
    public <T> FluidIngredient component(ComponentType<T> type, T value) { return components(Map.of(type.key(), type.encode(value)), false); }
    public long amount() { return amount; }
    public FluidIngredient withAmount(long amount) { return new FluidIngredient(amount, matcher, candidates); }
    public FluidIngredient withoutAmount() { return withAmount(0); }
    public boolean matches(FluidVariant variant) { return matcher.test(FluidStack.of(Objects.requireNonNull(variant, "variant"), 1)); }
    public boolean matchesIdentity(FluidStack stack) { return matcher.test(Objects.requireNonNull(stack, "stack")); }
    public boolean matches(FluidStack stack) {
        Objects.requireNonNull(stack, "stack");
        return stack.amount() >= amount && matcher.test(stack);
    }
    public List<FluidVariant> candidates(FluidRegistry registry) { return List.copyOf(candidates.apply(Objects.requireNonNull(registry, "registry"))); }

    /** Parses a fluid id, #tag id, list of alternatives, or an explicitly keyed map. */
    public static FluidIngredient parse(Object raw, FluidRegistry registry) { return parse(raw, registry, 0); }
    public static FluidIngredient parseSized(Object raw, FluidRegistry registry, long defaultAmount) {
        if (defaultAmount < 0) throw new IllegalArgumentException("Negative default ingredient amount");
        return parse(raw, registry, defaultAmount);
    }

    /** Configuration decoding is explicit: registered codecs, never guessed component bytes. */
    public static FluidIngredient parseConfiguration(Object raw, FluidRegistry registry, long defaultAmount) {
        return parse(configuration(raw, registry.snapshot(), 0), registry, defaultAmount);
    }

    private static Object configuration(Object raw, FluidRegistry.Snapshot registry, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Fluid alternatives are nested too deeply");
        if (raw instanceof Collection<?> alternatives) {
            if (alternatives.isEmpty() || alternatives.size() > 256) throw new IllegalArgumentException("Fluid alternatives must contain 1..256 entries");
            return alternatives.stream().map(value -> configuration(value, registry, depth + 1)).toList();
        }
        if (!(raw instanceof Map<?, ?> source)) return raw;
        Map<String, Object> map = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!name.startsWith("x-") && !name.equals("extensions")) map.put(name, value);
        });
        if (map.containsKey("id")) {
            if (map.containsKey("fluid")) throw new IllegalArgumentException("Specify fluid or id, not both");
            map.put("fluid", map.remove("id"));
        }
        if (map.containsKey("any-of")) map.put("any-of", configuration(map.get("any-of"), registry, depth + 1));
        if (map.get("components") instanceof Map<?, ?> values) {
            if (values.size() > 64) throw new IllegalArgumentException("Too many required fluid components");
            Map<FluidKey, ComponentValue> encoded = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                FluidKey id = identifier(key, "component key");
                ComponentCodec<?> codec = registry.componentCodecs().get(id);
                if (codec == null) throw new IllegalArgumentException("Unregistered fluid component codec: " + id);
                encoded.put(id, ComponentValue.of(codec.encodeConfiguration(value instanceof ComponentValue component ? component.bytes() : value)));
            });
            map.put("components", encoded);
        }
        return map;
    }
    private static FluidIngredient parse(Object raw, FluidRegistry registry, long defaultAmount) {
        Objects.requireNonNull(raw, "raw"); Objects.requireNonNull(registry, "registry");
        if (raw instanceof FluidIngredient ingredient) return ingredient.withAmount(defaultAmount == 0 ? ingredient.amount() : defaultAmount);
        if (raw instanceof String key) {
            return (key.startsWith("#") ? tag(registry, FluidKey.of(key.substring(1))) : fluid(FluidKey.of(key))).withAmount(defaultAmount);
        }
        if (raw instanceof Collection<?> alternatives) {
            return anyOf(alternatives.stream().map(value -> parse(value, registry)).toList()).withAmount(defaultAmount);
        }
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("Ingredient must be an id, list, or map");
        Set<String> allowed = Set.of("fluid", "tag", "any-of", "empty", "amount", "components", "exact-components");
        for (Object key : map.keySet()) if (!(key instanceof String text) || !allowed.contains(text)) throw new IllegalArgumentException("Unknown ingredient field: " + key);
        long amount = map.containsKey("amount") ? amount(map.get("amount")) : defaultAmount;
        int forms = 0; for (String key : List.of("fluid", "tag", "any-of", "empty")) if (map.containsKey(key)) forms++;
        if (forms != 1) throw new IllegalArgumentException("Ingredient needs exactly one of fluid, tag, any-of, empty");
        FluidIngredient ingredient;
        if (map.containsKey("fluid")) ingredient = fluid(identifier(map.get("fluid"), "fluid"));
        else if (map.containsKey("tag")) ingredient = tag(registry, identifier(map.get("tag"), "tag"));
        else if (map.containsKey("empty")) {
            if (!Boolean.TRUE.equals(map.get("empty"))) throw new IllegalArgumentException("empty must be true");
            if (amount != 0) throw new IllegalArgumentException("An empty ingredient cannot require a positive amount");
            ingredient = empty();
        } else {
            if (!(map.get("any-of") instanceof Collection<?> alternatives)) throw new IllegalArgumentException("any-of must be a list");
            ingredient = anyOf(alternatives.stream().map(value -> parse(value, registry)).toList());
        }
        if (map.containsKey("components")) {
            if (!(map.get("components") instanceof Map<?, ?> values)) throw new IllegalArgumentException("components must be an encoded component map");
            var encoded = new HashMap<FluidKey, ComponentValue>();
            values.forEach((key, value) -> {
                FluidKey id = identifier(key, "component key");
                ComponentValue data = value instanceof ComponentValue component ? component : value instanceof byte[] bytes ? ComponentValue.of(bytes) : null;
                if (data == null) throw new IllegalArgumentException("Component data must be ComponentValue or byte[]");
                if (encoded.putIfAbsent(id, data) != null) throw new IllegalArgumentException("Duplicate component: " + id);
            });
            Object exact = map.get("exact-components");
            if (exact != null && !(exact instanceof Boolean)) throw new IllegalArgumentException("exact-components must be boolean");
            ingredient = ingredient.components(encoded, Boolean.TRUE.equals(exact));
        } else if (map.containsKey("exact-components")) throw new IllegalArgumentException("exact-components needs components");
        return ingredient.withAmount(amount);
    }
    private static FluidKey identifier(Object value, String field) {
        if (value instanceof FluidKey key) return key;
        if (value instanceof String key) return FluidKey.of(key.startsWith("#") ? key.substring(1) : key);
        throw new IllegalArgumentException(field + " must be a namespaced id");
    }
    private static long amount(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Ingredient amount must be an integer");
        long amount;
        try { amount = new java.math.BigDecimal(number.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException failure) { throw new IllegalArgumentException("Ingredient amount must fit a long integer", failure); }
        if (amount < 0) throw new IllegalArgumentException("Negative ingredient amount");
        return amount;
    }
}
