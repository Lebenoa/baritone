/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone. If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.EntityClassById;
import baritone.api.command.datatypes.IDatatypeFor;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class FightCommand extends Command {

    public FightCommand(IBaritone baritone) {
        super(baritone, "fightbot");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        // two optional sections separated by ",": mobs to kill, then items to pick up
        List<EntityType> mobs = new ArrayList<>();
        Set<Item> items = new HashSet<>();
        boolean hadComma = false;
        while (args.hasAny()) {
            if (args.peekString().equals(",")) {
                args.get(); // consume the comma
                hadComma = true;
                continue;
            }
            if (!hadComma) {
                mobs.add(args.getDatatypeFor(EntityClassById.INSTANCE));
            } else {
                items.add(args.getDatatypeFor(ItemById.INSTANCE));
            }
        }

        Predicate<Entity> targetFilter;
        if (!mobs.isEmpty()) {
            targetFilter = e -> mobs.stream().anyMatch(t -> e.getType().equals(t));
        } else {
            // default: hostiles and food animals (pigs, cows, chickens, sheep...); spare pets, villagers, golems
            targetFilter = FightCommand::isDefaultTarget;
        }
        Predicate<ItemStack> dropFilter = items.isEmpty()
                ? stack -> true
                : stack -> items.contains(stack.getItem());

        baritone.getFollowProcess().fight(targetFilter, dropFilter);

        if (mobs.isEmpty()) {
            logDirect("Fighting all hostiles and food animals");
        } else {
            logDirect("Fighting these mobs:");
            mobs.stream()
                    .map(BuiltInRegistries.ENTITY_TYPE::getKey)
                    .map(Objects::requireNonNull)
                    .map(Identifier::toString)
                    .forEach(this::logDirect);
        }
        if (items.isEmpty()) {
            logDirect("Picking up all dropped items");
        } else {
            logDirect("Picking up these items:");
            items.stream()
                    .map(BuiltInRegistries.ITEM::getKey)
                    .map(Objects::requireNonNull)
                    .map(Identifier::toString)
                    .forEach(this::logDirect);
        }
    }

    private static boolean isDefaultTarget(Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        if (entity instanceof Enemy) {
            return true; // zombies, skeletons, creepers, slimes, ghasts, hoglins...
        }
        return entity instanceof Animal && !(entity instanceof TamableAnimal);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        boolean pastComma = false;
        int index = 0;
        while (args.has(index + 1)) {
            String str = args.peekString(index);
            if (str.equals(",")) {
                pastComma = true;
            }
            index++;
        }
        if (pastComma) {
            return args.tabCompleteDatatype(ItemById.INSTANCE);
        }
        IDatatypeFor entityType = EntityClassById.INSTANCE;
        while (args.has(2)) {
            if (args.peekDatatypeOrNull(entityType) == null) {
                return Stream.empty();
            }
            args.get();
        }
        return args.tabCompleteDatatype(entityType);
    }

    @Override
    public String getShortDesc() {
        return "Kill mobs and pick up the drops";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The fightbot command kills targeted mobs and picks up their drops.",
                "",
                "With no arguments, targets all hostile mobs and food animals (pigs, cows, chickens, sheep).",
                "Tamed pets, villagers, golems, and armor stands are never targeted by default.",
                "",
                "Usage:",
                "> fightbot - Kill all hostiles + food animals, pick up everything",
                "> fightbot zombie skeleton - Kill specific mobs, pick up everything",
                "> fightbot , beef - Fight default mobs, but only pick up beef",
                "> fightbot zombie , rotten_flesh - Kill zombies, only pick up rotten flesh"
        );
    }

}
