package com.oierbravo.createsifter.content.contraptions.components.sifter.recipe;

import com.oierbravo.createsifter.ModConstants;
import com.oierbravo.createsifter.content.contraptions.components.sifter.AbstractSifterBlockEntity;
import com.oierbravo.createsifter.register.ModRecipes;
import com.oierbravo.mechanicals.foundation.recipe.IRecipeRequirement;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class SiftingRecipeManager {
    /**
     * When several recipes match the same input, mesh, and water state, the one with the smallest
     * recipe id wins so behaviour is deterministic (no silent merge of loot tables).
     */
    public static Optional<SiftingRecipe> getRecipeForSifter(AbstractSifterBlockEntity sifter) {
        List<RecipeHolder<SiftingRecipe>> ingredientMatchingRecipes = getRecipesMatchingIngredients(
                SiftingRecipeInput.fromSifter(sifter), sifter.getLevel());
        boolean advanced = sifter.isAdvancedSifter();
        return ingredientMatchingRecipes.stream()
                .filter(holder -> holder.value().isWaterlogged() == sifter.isWaterlogged())
                .filter(holder -> !holder.value().requiresAdvancedSifter() || advanced)
                .min(Comparator.comparing(RecipeHolder::id))
                .map(RecipeHolder::value);
    }

    public static Optional<SiftingRecipe> getRecipeForHandSifting(Level level, SiftingRecipeInput input,
            boolean waterlogged) {
        List<RecipeHolder<SiftingRecipe>> ingredientMatchingRecipes = getRecipesMatchingIngredients(input, level);
        return ingredientMatchingRecipes.stream()
                .filter(holder -> holder.value().isWaterlogged() == waterlogged)
                .filter(holder -> !holder.value().requiresAdvancedSifter())
                .min(Comparator.comparing(RecipeHolder::id))
                .map(RecipeHolder::value);
    }

    public static List<RecipeHolder<SiftingRecipe>> getRecipesMatchingIngredients(SiftingRecipeInput input, Level level) {
        assert level != null;
        return level.getRecipeManager().getRecipesFor(SiftingRecipe.Type.INSTANCE, input, level);
    }

    /**
     * All input ingredients valid for the given mesh on this sifter configuration.
     */
    public static List<Ingredient> getAcceptedInputs(Level level, ItemStack mesh, boolean waterlogged,
            boolean advancedSifter) {
        if (level == null || mesh.isEmpty())
            return List.of();

        List<Ingredient> ingredients = new ArrayList<>();
        for (RecipeHolder<SiftingRecipe> holder : level.getRecipeManager()
                .getAllRecipesFor(SiftingRecipe.Type.INSTANCE)) {
            SiftingRecipe recipe = holder.value();
            if (!ItemStack.isSameItem(recipe.getMesh(), mesh))
                continue;
            if (recipe.isWaterlogged() != waterlogged)
                continue;
            if (recipe.requiresAdvancedSifter() && !advancedSifter)
                continue;
            ingredients.add(recipe.getInput());
        }
        return ingredients;
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
}
