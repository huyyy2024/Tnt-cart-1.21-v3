package com.firebow.event;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Quản lý queue đặt Rail + TNT Cart.
 *
 * Tick T   : FireArrowMixin predict landing → enqueue(railPos)
 * Tick T+1 : phase 1 → đặt Rail
 * Tick T+4 : phase 2 → đặt TNT Cart (delay 3 tick để rail load)
 * Tick T+5 : phase 3 → restore hotbar slot
 *
 * Delay giữa Rail và Cart tăng lên 3 tick để:
 *   1. Server kịp confirm rail đã đặt
 *   2. Tránh anti-cheat đọc 2 packet liên tiếp
 *   3. Cart có đủ thời gian spawn trước khi tên đến
 */
public final class FireBowRuntime {

    private static final FireBowRuntime INSTANCE = new FireBowRuntime();
    public static FireBowRuntime get() { return INSTANCE; }

    private final Queue<BlockPos> queue = new ConcurrentLinkedQueue<>();
    private static final int MAX_QUEUE = 3;

    private BlockPos current   = null;
    private int      phase     = 0;
    private int      phaseWait = 0; // tick counter trong phase
    private int      savedSlot = -1;

    private FireBowRuntime() {}

    public void enqueue(BlockPos railPos) {
        if (queue.size() < MAX_QUEUE) {
            queue.add(railPos);
        }
    }

    public void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        switch (phase) {

            case 1: {
                savedSlot = placeRail(client, current);
                phase     = 2;
                phaseWait = 0;
                break;
            }

            case 2: {
                // Chờ 3 tick để server confirm rail
                phaseWait++;
                if (phaseWait >= 3) {
                    phase     = 3;
                    phaseWait = 0;
                }
                break;
            }

            case 3: {
                placeCart(client, current);
                phase     = 4;
                phaseWait = 0;
                break;
            }

            case 4: {
                // Restore slot tick tiếp theo
                if (savedSlot != -1 && client.player != null) {
                    client.player.getInventory().selectedSlot = savedSlot;
                    savedSlot = -1;
                }
                current = null;
                phase   = 0;
                break;
            }

            default: {
                if (!queue.isEmpty()) {
                    current = queue.poll();
                    phase   = 1;
                }
                break;
            }
        }
    }

    private int placeRail(MinecraftClient client, BlockPos railPos) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (im == null) return -1;

        int railSlot = findInHotbar(player, Items.RAIL);
        if (railSlot == -1) railSlot = findInHotbar(player, Items.POWERED_RAIL);
        if (railSlot == -1) return -1;

        int prevSlot = player.getInventory().selectedSlot;
        player.getInventory().selectedSlot = railSlot;

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(railPos),
            Direction.UP,
            railPos.down(),
            false
        );
        im.interactBlock(player, Hand.MAIN_HAND, hit);
        return prevSlot;
    }

    private void placeCart(MinecraftClient client, BlockPos railPos) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (im == null) return;

        boolean hasRail = client.world.getBlockState(railPos).isOf(Blocks.RAIL)
                       || client.world.getBlockState(railPos).isOf(Blocks.POWERED_RAIL);
        if (!hasRail) return;

        int cartSlot = findInHotbar(player, Items.TNT_MINECART);
        if (cartSlot == -1) return;

        player.getInventory().selectedSlot = cartSlot;

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(railPos),
            Direction.UP,
            railPos,
            false
        );
        im.interactBlock(player, Hand.MAIN_HAND, hit);
    }

    private int findInHotbar(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }
}
