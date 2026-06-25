package com.oierbravo.createsifter.content.contraptions.components.sifter;

import com.oierbravo.createsifter.ModLang;
import com.oierbravo.createsifter.content.contraptions.components.meshes.AbstractAdvancedMesh;
import com.oierbravo.createsifter.content.contraptions.components.meshes.MeshUtils;
import com.oierbravo.createsifter.content.contraptions.components.sifter.recipe.SiftingRecipe;
import com.oierbravo.createsifter.content.contraptions.components.sifter.recipe.SiftingRecipeManager;
import com.oierbravo.createsifter.infrastucture.config.MConfigs;
import com.oierbravo.mechanicals.foundation.blockEntity.behaviour.DynamicCycleBehavior;
import com.oierbravo.mechanicals.foundation.blockEntity.behaviour.RecipeRequirementsBehaviour;
import com.oierbravo.mechanicals.register.MechanicalRecipeRequirementTypes;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.sound.SoundScapes;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.world.item.crafting.Ingredient;

public abstract class AbstractSifterBlockEntity extends KineticBlockEntity implements IHaveGoggleInformation, DynamicCycleBehavior.DynamicCycleBehaviorSpecifics, RecipeRequirementsBehaviour.RecipeRequirementsSpecifics<SiftingRecipe> {

    private static final Set<AbstractSifterBlockEntity> LOADED_SIFTERS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<AbstractSifterBlockEntity> PENDING_REBUILD_SET =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final ArrayDeque<AbstractSifterBlockEntity> PENDING_REBUILD_QUEUE = new ArrayDeque<>();

    public float DEFAULT_MINIMUM_SPEED;

    protected float minimumSpeed = getDefaultMinimumSpeed();

    protected int itemsProcessedPerCycle = 1;

    private final ItemStackHandler inputInventory;
    private final ItemStackHandler outputInventory;
    public ItemStackHandler meshInventory;
    protected IItemHandler inputAndMeshCombined;

    public DynamicCycleBehavior dynamicCycleBehaviour;

    public RecipeRequirementsBehaviour<SiftingRecipe> recipeRequirementsBehaviour;

    protected DeployerFakePlayer player;

    protected UUID owner;

    /** Rebuilt when mesh, waterlogging, or recipes change; hot path only runs {@link Ingredient#test}. */
    private List<Ingredient> acceptedInputsCache = List.of();

    protected abstract boolean isValidMesh(ItemStack meshStack);

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        dynamicCycleBehaviour = new DynamicCycleBehavior(this);
        behaviours.add(dynamicCycleBehaviour);
        recipeRequirementsBehaviour = new RecipeRequirementsBehaviour<>(this);
        behaviours.add(recipeRequirementsBehaviour);
    }



    public AbstractSifterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        inputInventory = createInputInventory();
        outputInventory = createOutputInventory();
        meshInventory = createMeshInventory();
        inputAndMeshCombined = new SifterInventoryHandler(inputInventory,outputInventory,meshInventory);
    }

    @Override
    public void initialize() {
        super.initialize();
        initHandler();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LOADED_SIFTERS.add(this);
            rebuildAcceptedInputsCache();
        }
    }

    @Override
    public void setBlockState(BlockState state) {
        BlockState previous = getBlockState();
        super.setBlockState(state);
        if (level == null || level.isClientSide || previous == null)
            return;
        if (previous.getValue(BlockStateProperties.WATERLOGGED) != state.getValue(BlockStateProperties.WATERLOGGED))
            rebuildAcceptedInputsCache();
    }
    private void initHandler() {
        if (level instanceof ServerLevel sLevel) {
            player = new DeployerFakePlayer(sLevel, owner);
            Vec3 initialPos = VecHelper.getCenterOf(worldPosition);
            player.setPos(initialPos.x, initialPos.y, initialPos.z);
        }
    }
    private @NotNull ItemStackHandler createMeshInventory() {
        return new ItemStackHandler(1) {
            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                return MeshUtils.isMeshItem(stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                rebuildAcceptedInputsCache();
                sendData();
            }
        };
    }

    protected ItemStackHandler createInputInventory(){
        return new ItemStackHandler(1){
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                return acceptsInput(stack);
            }
            @Override
            protected void onContentsChanged(int slot) {
                sendData();
            }
        };
    }
    protected ItemStackHandler createOutputInventory(){ return new ItemStackHandler(MConfigs.server().sifter.outputCapacity.get());}

    public ItemStackHandler getInputInventory(){
        return inputInventory;
    }
    public ItemStackHandler getOutputInventory(){
        return outputInventory;
    }
    public ItemStackHandler getMeshInventory(){
        return meshInventory;
    }
    public @Nullable IItemHandler getItemHandler() {
        return inputAndMeshCombined;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean added = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if(!this.meshInventory.getStackInSlot(0).isEmpty()) {
            ModLang.translate("tooltip.mesh", this.meshInventory.getStackInSlot(0).getDisplayName().getString()).style(ChatFormatting.GREEN).forGoggles(tooltip);
            added = true;
        }

        boolean addedRequirements = recipeRequirementsBehaviour.addToGoggleTooltip(tooltip, isPlayerSneaking, added);
        if(addedRequirements)
            added = true;

        return added;
    }

    @Override
    public void invalidate() {
        LOADED_SIFTERS.remove(this);
        PENDING_REBUILD_SET.remove(this);
        super.invalidate();
        invalidateCapabilities();
    }


    @Override
    public boolean tryProcess(boolean simulate) {
        Optional<SiftingRecipe> recipe = getRecipe();
        if(recipe.isEmpty()){
            recipeRequirementsBehaviour.cleanRequirements();
            return false;
        }
        SiftingRecipe siftingRecipe = recipe.get();

        if(!isSpeedRequirementFulfilled(siftingRecipe)){
            return false;
        }

        if(!recipeRequirementsBehaviour.checkRequirements(siftingRecipe))
            return false;
        //if(siftingRecipe.requiresAdvancedSifter() && !isAdvancedSifter())
        //    return false;

        if(simulate)
            return true;

        ItemStack stackInSlot = inputInventory.getStackInSlot(0);
        boolean processedAny = false;
        for(int i = 0;i <getItemsPerCycle();i++){
            if(stackInSlot.isEmpty())
                break;

            List<ItemStack> rolledResults = siftingRecipe.rollResults(level.random);
            if (!canFullyInsertOutputs(outputInventory, rolledResults))
                break;

            stackInSlot.shrink(1);
            inputInventory.setStackInSlot(0, stackInSlot);
            insertOutputs(outputInventory, rolledResults);
            processedAny = true;
        }
        if(!processedAny)
            return false;

        if(MConfigs.server().mesh.useMeshDurabilityWithSifter.get()){
            ItemStack meshStack = getMeshInventory().getStackInSlot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, meshStack.copy());
            getMeshInventory().getStackInSlot(0).hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }

        return true;
    }
    public boolean isAdvancedSifter(){
        return false;
    }
    protected int getItemsPerCycle(){
        return 1;
    }


    private Optional<SiftingRecipe> getRecipe(){
        if(this.level == null)
            return Optional.empty();
        Optional<SiftingRecipe> recipe = SiftingRecipeManager.getRecipeForSifter(this);
        //Optional<SiftingRecipe> recipe = ModRecipes.findMergedRecipesWithMatchingIngredients(this);
        return recipe;
    }

    protected ItemStack tryToInsertOutputItem(ItemStackHandler outputInv, ItemStack stack, boolean simulate){
        return ItemHandlerHelper.insertItemStacked(outputInv, stack, simulate);
    }
    protected int getItemsProcessedPerCycle(){
        return itemsProcessedPerCycle;
    }

    public void showParticles() {
        if (inputInventory.getStackInSlot(0).isEmpty() || meshInventory.getStackInSlot(0).isEmpty())
            return;
        if(!isSpeedRequirementFulfilled())
            return;
        if(getAbsSpeed() == 0)
            return;

        ItemParticleOption data = new ItemParticleOption(ParticleTypes.ITEM, inputInventory.getStackInSlot(0));
        float angle = level.random.nextFloat() * 360;
        Vec3 offset = new Vec3(0, 0, 0.5f);
        offset = VecHelper.rotate(offset, angle, Direction.Axis.Y);
        Vec3 target = VecHelper.rotate(offset, getSpeed() > 0 ? 25 : -25, Direction.Axis.Y);

        Vec3 center = offset.add(VecHelper.getCenterOf(worldPosition));
        target = VecHelper.offsetRandomly(target.subtract(offset), level.random, 1 / 128f);
        level.addParticle(data, center.x, center.y, center.z, target.x, target.y, target.z);
    }

    @Override
    public void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        compound.put("InputInventory", inputInventory.serializeNBT(registries));
        compound.put("OutputInventory", outputInventory.serializeNBT(registries));
        compound.put("MeshInventory", meshInventory.serializeNBT(registries));
        if (owner != null)
            compound.putUUID("Owner", owner);
        super.write(compound, registries, clientPacket);

    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        inputInventory.deserializeNBT(registries, compound.getCompound("InputInventory"));
        outputInventory.deserializeNBT(registries, compound.getCompound("OutputInventory"));
        meshInventory.deserializeNBT(registries, compound.getCompound("MeshInventory"));
        if (compound.contains("Owner"))
            owner = compound.getUUID("Owner");
        if (!clientPacket)
            rebuildAcceptedInputsCache();
        super.read(compound, registries, clientPacket);

    }
    @Override
    public boolean isSpeedRequirementFulfilled() {
        Optional<SiftingRecipe> recipe = getRecipe();
        if(recipe.isEmpty())
            return getAbsSpeed() >= minimumSpeed;
        return isSpeedRequirementFulfilled(recipe.get());
    }

    private boolean isSpeedRequirementFulfilled(SiftingRecipe recipe) {
        var minSpeedRequirement = recipe.getRequirement(MechanicalRecipeRequirementTypes.MIN_SPEED.get());
        if(minSpeedRequirement.isPresent())
            return minSpeedRequirement.get().test(level, this);
        var maxSpeedRequirement = recipe.getRequirement(MechanicalRecipeRequirementTypes.MAX_SPEED.get());
        if(maxSpeedRequirement.isPresent())
            return maxSpeedRequirement.get().test(level, this);
        return super.isSpeedRequirementFulfilled();
    }

    protected float getDefaultMinimumSpeed() {
        return DEFAULT_MINIMUM_SPEED;
    }

    public boolean tryToInsertMesh(ItemStack meshStack, Player player, boolean simulate) {
        if(!isValidMesh(meshStack))
            return false;

        ItemStack meshToInsert = meshStack.copy();
        meshToInsert.setCount(1);
        if(getMeshItemStack().is(meshStack.getItem()))
            return false;

        if(simulate)
            return true;

        meshStack.shrink(1);
        if(!meshInventory.getStackInSlot(0).isEmpty() && player != null) {
            removeMesh(player);
        }
        meshInventory.setStackInSlot(0, meshToInsert);
        rebuildAcceptedInputsCache();
        setChanged();

        return true;
    }

    public boolean hasMesh(){
        return !getMeshItemStack().isEmpty();
    }
    public ItemStack getMeshItemStack(){
        return meshInventory.getStackInSlot(0);
    }
    public boolean hasAdvancedMesh(){
        return !meshInventory.getStackInSlot(0).isEmpty() && meshInventory.getStackInSlot(0).getItem() instanceof AbstractAdvancedMesh;
    }


    public void removeMesh(Player player) {
        player.getInventory().placeItemBackInInventory(meshInventory.getStackInSlot(0));
        meshInventory.setStackInSlot(0, ItemStack.EMPTY);
        minimumSpeed = getDefaultMinimumSpeed();
        rebuildAcceptedInputsCache();
        sendData();
    }

    public boolean isWaterlogged() {
        return this.getBlockState().getValue(BlockStateProperties.WATERLOGGED);
    }

    public float getAbsSpeed(){
        return Math.abs(getSpeed());
    }

    public ItemStack getInputItemStack(){
        return this.inputInventory.getStackInSlot(0);
    }

    public boolean acceptsInput(ItemStack stack) {
        if (stack.isEmpty() || !hasMesh())
            return false;
        for (Ingredient ingredient : acceptedInputsCache) {
            if (ingredient.test(stack))
                return true;
        }
        return false;
    }

    protected void rebuildAcceptedInputsCache() {
        if (level == null || level.isClientSide || !hasMesh()) {
            acceptedInputsCache = List.of();
            return;
        }
        acceptedInputsCache = SiftingRecipeManager.getAcceptedInputs(level, getMeshItemStack(), isWaterlogged(),
                isAdvancedSifter());
    }

    public static void rebuildAllAcceptedInputCaches() {
        for (AbstractSifterBlockEntity sifter : LOADED_SIFTERS) {
            if (!sifter.isRemoved())
                enqueueAcceptedInputsRebuild(sifter);
        }
    }

    public static void processPendingAcceptedInputCacheRebuilds(int maxPerTick) {
        int budget = Math.max(0, maxPerTick);
        for (int i = 0; i < budget; i++) {
            AbstractSifterBlockEntity sifter = PENDING_REBUILD_QUEUE.pollFirst();
            if (sifter == null)
                break;
            PENDING_REBUILD_SET.remove(sifter);
            if (sifter.isRemoved() || sifter.level == null || sifter.level.isClientSide)
                continue;
            sifter.rebuildAcceptedInputsCache();
        }
    }

    private static void enqueueAcceptedInputsRebuild(AbstractSifterBlockEntity sifter) {
        if (PENDING_REBUILD_SET.add(sifter))
            PENDING_REBUILD_QUEUE.addLast(sifter);
    }

    private boolean canFullyInsertOutputs(ItemStackHandler outputInv, List<ItemStack> outputs) {
        if (outputs.isEmpty())
            return true;
        ItemStackHandler simulatedOutput = copyInventory(outputInv);
        for (ItemStack output : outputs) {
            if (output.isEmpty())
                continue;
            ItemStack remainder = tryToInsertOutputItem(simulatedOutput, output.copy(), false);
            if (!remainder.isEmpty())
                return false;
        }
        return true;
    }

    private void insertOutputs(ItemStackHandler outputInv, List<ItemStack> outputs) {
        for (ItemStack output : outputs) {
            if (output.isEmpty())
                continue;
            tryToInsertOutputItem(outputInv, output.copy(), false);
        }
    }

    private ItemStackHandler copyInventory(ItemStackHandler source) {
        ItemStackHandler copy = new ItemStackHandler(source.getSlots());
        for (int slot = 0; slot < source.getSlots(); slot++) {
            copy.setStackInSlot(slot, source.getStackInSlot(slot).copy());
        }
        return copy;
    }

    protected boolean hasFreeOutputSlot() {
        for (int slot = 0; slot < outputInventory.getSlots(); slot++) {
            ItemStack stack = outputInventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize())
                return true;
        }
        return false;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void playRunningSound() {
        float pitch = Mth.clamp((Math.abs(getSpeed()) / 256f) + .45f, .85f, 1f);
        SoundScapes.play(SoundScapes.AmbienceGroup.MILLING, worldPosition, pitch);    }

    @Override
    public boolean matchesIngredients(SiftingRecipe siftingRecipeRecipeHolder) {
        boolean incorrectInput = Arrays.stream(siftingRecipeRecipeHolder.getInput().getItems()).filter(itemStack -> ItemStack.isSameItem(itemStack,inputInventory.getStackInSlot(0))).toList().isEmpty();
        if(incorrectInput)
            return false;
        return ItemStack.isSameItem(meshInventory.getStackInSlot(0),siftingRecipeRecipeHolder.getMesh());
    }

    @Override
    public boolean hasEnoughOutputSpace(SiftingRecipe siftingRecipe) {
        return hasFreeOutputSlot();
    }

    @Override
    public float getKineticSpeed() {
        return getSpeed();
    }

    @Override
    public int getProcessingTime() {
        Optional<SiftingRecipe> recipe = getRecipe();
        if(recipe.isEmpty())
            return 1;
        return recipe.get().getProcessingTime();
    }


    private class SifterInventoryHandler extends CombinedInvWrapper {

        public SifterInventoryHandler(ItemStackHandler inputInventory, ItemStackHandler outputInventory, ItemStackHandler meshInventory) {
            super(inputInventory, outputInventory, meshInventory);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (outputInventory == getHandlerFromIndex(getIndexForSlot(slot)))
                return false;
            if (meshInventory == getHandlerFromIndex(getIndexForSlot(slot)))
                return MeshUtils.isMeshItem(stack);
            if (inputInventory == getHandlerFromIndex(getIndexForSlot(slot)))
                return acceptsInput(stack);
            return super.isItemValid(slot, stack);
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (outputInventory == getHandlerFromIndex(getIndexForSlot(slot)))
                return stack;
            if (!isItemValid(slot, stack))
                return stack;
            return super.insertItem(slot, stack, simulate);
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (inputInventory == getHandlerFromIndex(getIndexForSlot(slot)))
                return ItemStack.EMPTY;
            if (meshInventory == getHandlerFromIndex(getIndexForSlot(slot)))
                return ItemStack.EMPTY;
            return super.extractItem(slot, amount, simulate);
        }

    }
}
