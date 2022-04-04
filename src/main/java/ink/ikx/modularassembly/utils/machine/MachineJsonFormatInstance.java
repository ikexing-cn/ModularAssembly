package ink.ikx.modularassembly.utils.machine;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.mc1120.brackets.BracketHandlerBlockState;
import hellfirepvp.modularmachinery.common.CommonProxy;
import hellfirepvp.modularmachinery.common.machine.MachineLoader;
import hellfirepvp.modularmachinery.common.util.BlockArray;
import ink.ikx.modularassembly.utils.MiscUtil;
import ink.ikx.modularassembly.utils.StackUtil;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.commons.lang3.StringUtils;
import youyihj.modularcontroller.ModularController;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class MachineJsonFormatInstance {

    public static final Map<String, MachineJsonFormatInstance> MACHINES = Maps.newHashMap();
    private static final Gson GSON = new GsonBuilder().registerTypeAdapter(MachineJsonFormatInstance.class, MachineJsonPreReader.INSTANCE).create();

    private final String machineName;
    private final List<Parts> machineParts;

    public MachineJsonFormatInstance(String machineName, List<Parts> machineParts) {
        this.machineName = machineName;
        this.machineParts = machineParts;
    }

    public static MachineJsonFormatInstance getOrCreate(String machineName, List<Parts> machineParts) {
        if (MACHINES.containsKey(machineName)) {
            return MACHINES.get(machineName);
        }
        MachineJsonFormatInstance machineJsonFormatInstance = new MachineJsonFormatInstance(machineName, machineParts);
        MACHINES.put(machineName, machineJsonFormatInstance);
        return machineJsonFormatInstance;
    }

    public static void initAllMachine() {
        for (File file : MachineLoader.discoverDirectory(CommonProxy.dataHolder.getMachineryDirectory()).get(MachineLoader.FileType.MACHINE)) {
            try (InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                GSON.fromJson(isr, MachineJsonFormatInstance.class);
            } catch (JsonParseException e) {
                ModularController.logger.error(file + " is not a valid machine json", e);
            } catch (IOException e) {
                ModularController.logger.error("failed to load custom controllers", e);
            }
        }
    }

    public String getMachineName() {
        return machineName;
    }

    public List<Parts> getMachineParts() {
        return machineParts;
    }

    public MachineJsonFormatInstance copy() {
        return new MachineJsonFormatInstance(machineName, machineParts.stream().map(Parts::copy).collect(Collectors.toList()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MachineJsonFormatInstance that = (MachineJsonFormatInstance) o;

        if (!Objects.equals(machineName, that.machineName)) return false;
        return Objects.equals(machineParts, that.machineParts);
    }

    @Override
    public int hashCode() {
        int result = machineName != null ? machineName.hashCode() : 0;
        result = 31 * result + (machineParts != null ? machineParts.hashCode() : 0);
        return result;
    }

    public static class Parts {

        public final int x;
        public final int y;
        public final int z;
        public final String[] elements;
        public final String[] itemStacks;
        public final String[] blockstates;

        public Parts(int x, int y, int z, String[] itemStacks, String[] blockstates, String[] elements) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.elements = elements;
            this.itemStacks = itemStacks;
            this.blockstates = blockstates;
        }

        public Parts copy() {
            return new Parts(x, y, z, Arrays.copyOf(itemStacks, itemStacks.length),
                    Arrays.copyOf(itemStacks, itemStacks.length), Arrays.copyOf(elements, elements.length));
        }

        public BlockPos getBlockPos() {
            return new BlockPos(x, y, z);
        }

        public BlockPos getBlockPos(BlockPos pos) {
            return pos.add(getBlockPos());
        }

        public boolean matches(IBlockState state) {
            return getStateList().stream().anyMatch(l -> l.stream().anyMatch(state::equals));
        }

        public List<List<IBlockState>> getStateList() {
            List<List<IBlockState>> toReturn = Arrays.stream(blockstates)
                    .filter(s -> s.contains(":"))
                    .map(s -> s.split(":"))
                    .map(s -> BracketHandlerBlockState.getBlockState(s[1] + ":" + s[2], s[3]))
                    .map(CraftTweakerMC::getBlockState).map(Collections::singletonList).collect(Collectors.toList());

            return toReturn.isEmpty() ? Arrays.stream(elements).map(BlockArray.BlockInformation::getDescriptor)
                    .map(d -> d.applicable).collect(Collectors.toList()) : toReturn;
        }

        public List<List<ItemStack>> getStackList() {
            List<List<ItemStack>> toReturn = Arrays.stream(itemStacks).filter(i -> !StringUtils.isBlank(i) && !StackUtil.strToStack(i).isEmpty())
                    .map(StackUtil::strToStack).map(Collections::singletonList).collect(Collectors.toList());
            return toReturn.isEmpty() ? Arrays.stream(elements).map(StackUtil::strToStack2).collect(Collectors.toList()) : toReturn;
        }

        public boolean assembly(EntityPlayer player, BlockPos pos) {
            World world = player.getEntityWorld();
            IBlockState blockState = world.getBlockState(getBlockPos(pos));
            if (!isSkip(blockState, player)) return false;

            if (StringUtils.isNotBlank(this.itemStacks[0])) {
                List<ItemStack> get = this.getStackList().get(0);
                for (int i = 0; i < get.size(); i++) {
                    ItemStack stack = get.get(i);
                    if (!StackUtil.getStacksInInventory(stack, player.inventory.mainInventory).isEmpty()) {
                        IBlockState state = this.getStateList().get(0).get(i);
                        world.setBlockState(getBlockPos(pos), state);
                        return true;
                    }
                }
            } else {
                for (List<ItemStack> stacks : this.getStackList()) {
                    ItemStack stacksInInventory = StackUtil.getStacksInInventory(stacks, player.inventory.mainInventory);
                    IBlockState blockStareFromStack = StackUtil.getBlockStareFromStack(stacksInInventory);
                    if (!stacksInInventory.isEmpty() && blockStareFromStack != null) {
                        world.setBlockState(getBlockPos(pos), blockStareFromStack);
                        return true;
                    } else {
                        MiscUtil.sendTranslateToLocalToPlayer(player, "message.modularassembly.machine.error");
                        return false;
                    }
                }
            }

            return false;
        }

        public boolean isSkip(IBlockState state, EntityPlayer player) {
            if (!this.matches(state) || state.getMaterial() != Material.AIR || !state.getMaterial().isReplaceable()) {
                MiscUtil.sendTranslateToLocalToPlayer(player, "message.modularassembly.machine.block_exist");
                return false;
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Parts parts = (Parts) o;

            if (x != parts.x) return false;
            if (y != parts.y) return false;
            if (z != parts.z) return false;
            // Probably incorrect - comparing Object[] arrays with Arrays.equals
            if (!Arrays.equals(elements, parts.elements)) return false;
            // Probably incorrect - comparing Object[] arrays with Arrays.equals
            return Arrays.equals(itemStacks, parts.itemStacks);
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            result = 31 * result + Arrays.hashCode(elements);
            result = 31 * result + Arrays.hashCode(itemStacks);
            return result;
        }

    }

}
