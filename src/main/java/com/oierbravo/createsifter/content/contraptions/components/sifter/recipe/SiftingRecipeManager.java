package com.oierbravo.createsifter.content.contraptions.components.sifter.recipe;

import com.oierbravo.createsifter.content.contraptions.components.sifter.AbstractSifterBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

public class SiftingRecipeManager {
    private record RecipeLookupKey(Item inputItem, Item meshItem, boolean waterlogged, boolean advanced) {}
    private record AcceptedInputsKey(Item meshItem, boolean waterlogged, boolean advanced) {}

    private record WinnerCache(
            Map<RecipeLookupKey, SiftingRecipe> regularWinners,
            Map<RecipeLookupKey, SiftingRecipe> advancedWinners,
            Map<AcceptedInputsKey, List<Ingredient>> acceptedInputsByKey
    ) {}

    private static final Map<Level, WinnerCache> WINNER_CACHE = new WeakHashMap<>();

    /**
     * Deterministic selection for duplicate keys (same input, mesh, and water state).
     * <p>
     * Brass sifters prefer recipes requiring advanced sifter. If none are found, they fall back to
     * regular recipes. Inside each candidate set, the smallest recipe id wins.
     */
    public static Optional<SiftingRecipe> getRecipeForSifter(AbstractSifterBlockEntity sifter) {
        Level level = sifter.getLevel();
        if (level == null)
            return Optional.empty();
        RecipeLookupKey key = toLookupKey(SiftingRecipeInput.fromSifter(sifter), sifter.isWaterlogged(), sifter.isAdvancedSifter());
        if (key == null)
            return Optional.empty();
        return getWinnerForKey(level, key, sifter.isAdvancedSifter());
    }

    public static Optional<SiftingRecipe> getRecipeForHandSifting(Level level, SiftingRecipeInput input,
            boolean waterlogged) {
        if (level == null)
            return Optional.empty();
        RecipeLookupKey key = toLookupKey(input, waterlogged, false);
        if (key == null)
            return Optional.empty();
        return getWinnerForKey(level, key, false);
    }

    public static List<RecipeHolder<SiftingRecipe>> getRecipesMatchingIngredients(SiftingRecipeInput input, Level level) {
        assert level != null;
        return level.getRecipeManager().getRecipesFor(SiftingRecipe.Type.INSTANCE, input, level);
    }

    public static void clearLookupCache() {
        synchronized (WINNER_CACHE) {
            WINNER_CACHE.clear();
        }
    }

    public static void rebuildLookupCache(Level level) {
        if (level == null)
            return;
        synchronized (WINNER_CACHE) {
            WINNER_CACHE.put(level, buildWinnerCache(level));
        }
    }

    /**
     * All input ingredients valid for the given mesh on this sifter configuration.
     */
    public static List<Ingredient> getAcceptedInputs(Level level, ItemStack mesh, boolean waterlogged,
            boolean advancedSifter) {
        if (level == null || mesh.isEmpty())
            return List.of();
        AcceptedInputsKey key = new AcceptedInputsKey(mesh.getItem(), waterlogged, advancedSifter);
        List<Ingredient> cachedInputs = getOrBuildWinnerCache(level).acceptedInputsByKey().get(key);
        return cachedInputs != null ? cachedInputs : List.of();
    }

    public static boolean isAcceptedInput(Level level, ItemStack mesh, boolean waterlogged, boolean advancedSifter,
            ItemStack stack) {
        if (stack.isEmpty())
            return false;
        for (Ingredient ingredient : getAcceptedInputs(level, mesh, waterlogged, advancedSifter)) {
            if (ingredient.test(stack))
                return true;
        }
        return false;
    }

    public static List<RecipeHolder<SiftingRecipe>> getAllHolders() {
        return Objects.requireNonNull(Minecraft.getInstance().getConnection())
                .getRecipeManager()
                .getAllRecipesFor(SiftingRecipe.Type.INSTANCE);
    }

    private static WinnerCache getOrBuildWinnerCache(Level level) {
        synchronized (WINNER_CACHE) {
            WinnerCache cache = WINNER_CACHE.get(level);
            if (cache != null)
                return cache;
            WinnerCache built = buildWinnerCache(level);
            WINNER_CACHE.put(level, built);
            return built;
        }
    }

    private static WinnerCache buildWinnerCache(Level level) {
        Map<RecipeLookupKey, SiftingRecipe> regularWinners = new HashMap<>();
        Map<RecipeLookupKey, SiftingRecipe> advancedWinners = new HashMap<>();
        Map<AcceptedInputsKey, List<Ingredient>> acceptedInputsByKey = new HashMap<>();
        List<RecipeHolder<SiftingRecipe>> allRecipes = level.getRecipeManager().getAllRecipesFor(SiftingRecipe.Type.INSTANCE);

        for (RecipeHolder<SiftingRecipe> holder : allRecipes) {
            SiftingRecipe recipe = holder.value();
            if (recipe.getMesh().isEmpty())
                continue;

            Item meshItem = recipe.getMesh().getItem();
            boolean waterlogged = recipe.isWaterlogged();
            boolean requiresAdvanced = recipe.requiresAdvancedSifter();
            AcceptedInputsKey regularInputsKey = new AcceptedInputsKey(meshItem, waterlogged, false);
            AcceptedInputsKey advancedInputsKey = new AcceptedInputsKey(meshItem, waterlogged, true);

            if (requiresAdvanced) {
                acceptedInputsByKey.computeIfAbsent(advancedInputsKey, ignored -> new ArrayList<>())
                        .add(recipe.getInput());
            } else {
                acceptedInputsByKey.computeIfAbsent(regularInputsKey, ignored -> new ArrayList<>())
                        .add(recipe.getInput());
                acceptedInputsByKey.computeIfAbsent(advancedInputsKey, ignored -> new ArrayList<>())
                        .add(recipe.getInput());
            }

            for (ItemStack candidateInput : recipe.getInput().getItems()) {
                if (candidateInput.isEmpty())
                    continue;
                Item inputItem = candidateInput.getItem();
                RecipeLookupKey regularKey = new RecipeLookupKey(inputItem, meshItem, waterlogged, false);
                RecipeLookupKey brassKey = new RecipeLookupKey(inputItem, meshItem, waterlogged, true);

                if (requiresAdvanced) {
                    putLowestId(advancedWinners, brassKey, recipe);
                } else {
                    putLowestId(regularWinners, regularKey, recipe);
                    putLowestId(regularWinners, brassKey, recipe);
                }
            }
        }
        acceptedInputsByKey.replaceAll((key, inputs) -> List.copyOf(inputs));
        return new WinnerCache(regularWinners, advancedWinners, acceptedInputsByKey);
    }

    private static RecipeLookupKey toLookupKey(SiftingRecipeInput input, boolean waterlogged, boolean advanced) {
        if (input.input().isEmpty() || input.mesh().isEmpty())
            return null;
        return new RecipeLookupKey(input.input().getItem(), input.mesh().getItem(), waterlogged, advanced);
    }

    private static void putLowestId(Map<RecipeLookupKey, SiftingRecipe> winners, RecipeLookupKey key, SiftingRecipe candidate) {
        winners.compute(key, (k, existing) -> pickLowestId(existing, candidate));
    }

    private static SiftingRecipe pickLowestId(SiftingRecipe existing, SiftingRecipe candidate) {
        if (existing == null)
            return candidate;
        if (existing.getId() == null)
            return candidate;
        if (candidate.getId() == null)
            return existing;
        return candidate.getId().compareTo(existing.getId()) < 0 ? candidate : existing;
    }

    private static Optional<SiftingRecipe> getWinnerForKey(Level level, RecipeLookupKey key, boolean advanced) {
        WinnerCache cache = getOrBuildWinnerCache(level);
        SiftingRecipe winner = null;
        if (advanced) {
            winner = cache.advancedWinners().get(key);
        }
        if (winner == null) {
            winner = cache.regularWinners().get(key);
        }
        return Optional.ofNullable(winner);
    }
}
