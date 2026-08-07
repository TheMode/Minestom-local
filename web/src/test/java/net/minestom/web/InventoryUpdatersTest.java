package net.minestom.web;

import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket;
import net.minestom.server.network.packet.server.play.SetSlotPacket;
import net.minestom.web.internal.state.StateApplier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InventoryUpdatersTest {

    private final PlayerState state = new PlayerState();

    private void apply(Packet packet) {
        state.currentProvenance = new Provenance(1, System.currentTimeMillis(), packet.getClass().getSimpleName(), Direction.CLIENTBOUND);
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY, packet);
        state.currentProvenance = null;
    }

    @Test
    void setSlotPacketUpdatesPlayerWindowSlots() {
        ItemStack stone = ItemStack.of(Material.STONE, 4);
        apply(new SetSlotPacket(0, 0, (short) 36, stone));

        assertEquals(stone, state.hotbar[0]);

        apply(new SetSlotPacket(0, 0, (short) 36, ItemStack.AIR));

        assertNull(state.hotbar[0]);
    }

    @Test
    void setPlayerInventorySlotUsesHelmetFirstArmorOrder() {
        ItemStack helmet = ItemStack.of(Material.DIAMOND_HELMET);
        ItemStack boots = ItemStack.of(Material.DIAMOND_BOOTS);

        apply(new SetPlayerInventorySlotPacket(39, helmet));
        apply(new SetPlayerInventorySlotPacket(36, boots));

        assertEquals(helmet, state.armor[0]);
        assertEquals(boots, state.armor[3]);
    }

    @Test
    void setSlotPacketUpdatesPlayerSlotsInsideOpenWindow() {
        ItemStack[] container = new ItemStack[9];
        state.openedWindow = new PlayerState.OpenedWindow(7, "minecraft:generic_9x1", null, container, new LinkedHashMap<>());
        ItemStack dirt = ItemStack.of(Material.DIRT, 2);

        apply(new SetSlotPacket(7, 0, (short) 9, dirt));
        apply(new SetSlotPacket(7, 0, (short) 36, ItemStack.of(Material.STONE)));

        assertEquals(dirt, state.mainInventory[0]);
        assertEquals(Material.STONE, state.hotbar[0].material());
    }

    @Test
    void clientClickChangedSlotsUpdateMirrorOptimistically() {
        state.hotbar[0] = ItemStack.of(Material.STONE);
        Map<Short, ItemStack.Hash> changed = Map.of(
                (short) 36, ItemStack.Hash.AIR,
                (short) 37, ItemStack.Hash.of(ItemStack.of(Material.DIRT, 3))
        );
        var packet = new ClientClickWindowPacket(
                0, 0, (short) 36, (byte) 0,
                ClientClickWindowPacket.ClickType.PICKUP,
                changed,
                ItemStack.Hash.of(ItemStack.of(Material.STONE)));

        apply(packet);

        assertNull(state.hotbar[0]);
        assertEquals(Material.DIRT, state.hotbar[1].material());
        assertEquals(3, state.hotbar[1].amount());
        assertEquals(Material.STONE, state.cursor.material());
        assertEquals(1, state.recentClicks.size());
    }
}
