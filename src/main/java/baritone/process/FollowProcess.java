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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IFollowProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.RayTraceUtils;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Follow an entity
 *
 * @author leijurv
 */
public final class FollowProcess extends BaritoneProcessHelper implements IFollowProcess {

    private Predicate<Entity> filter;
    private List<Entity> cache;
    private boolean into; // walk straight into the target, regardless of settings

    // fight mode state
    private Predicate<ItemStack> dropFilter;
    private Entity target; // the mob we're currently attacking

    public FollowProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        scanWorld();
        if (dropFilter != null) {
            return fightTick(isSafeToCancel);
        }
        Goal goal = new GoalComposite(cache.stream().map(this::towards).toArray(Goal[]::new));
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    /**
     * Attack the nearest cached target, or walk into the nearest matching dropped item.
     */
    private PathingCommand fightTick(boolean isSafeToCancel) {
        if (target != null && (!this.followable(target) || !this.filter.test(target))) {
            target = null; // dead or removed - move on
        }
        if (target == null) {
            double best = Double.MAX_VALUE;
            for (Entity entity : cache) {
                double dist = entity.distanceToSqr(ctx.player());
                if (dist < best) {
                    best = dist;
                    target = entity;
                }
            }
        }
        if (target != null) {
            return attack(target, isSafeToCancel);
        }
        // no targets left: pick up matching drops
        List<Goal> pickups = new ArrayList<>();
        for (Entity entity : ctx.entitiesStream().collect(Collectors.toList())) {
            if (entity instanceof ItemEntity && entity.onGround() && this.dropFilter.test(((ItemEntity) entity).getItem())) {
                pickups.add(new GoalBlock(entity.blockPosition()));
            }
        }
        if (pickups.isEmpty()) {
            onLostControl(); // nothing to fight, nothing to loot: done
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalComposite(pickups.toArray(new Goal[0])), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    /**
     * Face the target and attack once it's in reach and the attack gauge is charged.
     * While out of reach the pathing engine sprints us toward it.
     */
    private PathingCommand attack(Entity target, boolean isSafeToCancel) {
        double reach = ctx.player().entityInteractionRange();
        double size = Math.max(target.getBbWidth(), target.getBbHeight());
        if (size > 6.0) { // huge hitboxes (phantoms, ghasts, dragons) can be attacked well before their center is in reach
            reach += size / 2 - 0.5;
        }
        double dist = ctx.player().getEyePosition(1.0F).distanceToSqr(target.getBoundingBox().getCenter());
        if (dist > reach * reach) {
            // out of reach: chase it down
            return new PathingCommand(towards(target), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if (!isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target.getBoundingBox().getCenter(), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rot, true);
        HitResult trace = RayTraceUtils.rayTraceTowards(ctx.player(), rot, ctx.playerController().getBlockReachDistance());
        if (trace != null && trace.getType() == HitResult.Type.BLOCK) {
            // a block is in the way - keep pathing until we have a clear line
            return new PathingCommand(towards(target), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if (ctx.player().getAttackStrengthScale(0.5F) < 0.9F) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE); // gauge recharging
        }
        ctx.playerController().syncHeldItem();
        ctx.minecraft().gameMode.attack(ctx.player(), target);
        ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private Goal towards(Entity following) {
        BlockPos pos;
        if (Baritone.settings().followOffsetDistance.value == 0 || into) {
            pos = following.blockPosition();
        } else {
            GoalXZ g = GoalXZ.fromDirection(following.position(), Baritone.settings().followOffsetDirection.value, Baritone.settings().followOffsetDistance.value);
            pos = new BetterBlockPos(g.getX(), following.position().y, g.getZ());
        }
        if (into) {
            return new GoalBlock(pos);
        }
        return new GoalNear(pos, Baritone.settings().followRadius.value);
    }


    private boolean followable(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!entity.isAlive()) {
            return false;
        }
        if (entity.equals(ctx.player())) {
            return false;
        }
        int maxDist = Baritone.settings().followTargetMaxDistance.value;
        if (maxDist != 0 && entity.distanceToSqr(ctx.player()) > maxDist * maxDist) {
            return false;
        }
        return ctx.entitiesStream().anyMatch(entity::equals);
    }

    private void scanWorld() {
        cache = ctx.entitiesStream()
                .filter(this::followable)
                .filter(this.filter)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isActive() {
        if (filter == null) {
            return false;
        }
        scanWorld();
        if (dropFilter == null && cache.isEmpty()) {
            return false;
        }
        return true;
    }

    @Override
    public void onLostControl() {
        filter = null;
        cache = null;
        dropFilter = null;
        target = null;
        into = false;
    }

    @Override
    public String displayName0() {
        return dropFilter != null ? "Fighting " + cache : "Following " + cache;
    }

    @Override
    public void follow(Predicate<Entity> filter) {
        this.filter = filter;
        this.into = false;
    }

    @Override
    public void pickup(Predicate<ItemStack> filter) {
        this.filter = e -> e instanceof ItemEntity && filter.test(((ItemEntity) e).getItem());
        this.into = true;
        this.dropFilter = null;
    }

    @Override
    public void fight(Predicate<Entity> targetFilter, Predicate<ItemStack> dropFilter) {
        this.filter = targetFilter;
        this.dropFilter = dropFilter;
        this.into = false;
        this.target = null;
    }

    @Override
    public List<Entity> following() {
        return cache;
    }

    @Override
    public Predicate<Entity> currentFilter() {
        return filter;
    }
}
