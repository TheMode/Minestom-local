package net.minestom.web.internal.state;

import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientCloseWindowPacket;
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.web.PlayerState;

import java.util.LinkedHashMap;
import java.util.Map;

import static net.minestom.web.internal.codec.WebCodecs.nullIfAir;
import static net.minestom.web.internal.state.StateApplier.entry;
import static net.minestom.web.internal.state.StateApplier.listeners;

/// Hotbar / main / armor slots, the cursor, the offhand, and any container the player has open.
/// Live-mirrors slot mutations so the dashboard's inventory tab reflects per-click changes
/// before vanilla's bulk `WindowItemsPacket` resync arrives.
final class InventoryUpdaters {

    static final Map<Class<? extends Packet>, StateApplier.Updater<?>> LISTENERS = listeners(
            entry(HeldItemChangePacket.class, (s, _, _, p) ->
                    s.selectedHotbar = s.set("selectedHotbar", s.selectedHotbar, p.slot())),
            entry(ClientHeldItemChangePacket.class, (s, _, _, p) ->
                    s.selectedHotbar = s.set("selectedHotbar", s.selectedHotbar, p.slot())),
            entry(SetSlotPacket.class, (s, _, _, p) -> {
                if (p.windowId() == -1 && p.slot() == -1) {
                    s.cursor = s.set("cursor", s.cursor, nullIfAir(p.itemStack()));
                    return;
                }
                applyWindowSlot(s, p.windowId(), p.slot(), nullIfAir(p.itemStack()));
            }),
            entry(SetPlayerInventorySlotPacket.class, (s, _, _, p) -> applyPlayerInventorySlot(s, p.slot(), nullIfAir(p.itemStack()))),
            entry(WindowItemsPacket.class, (s, _, _, p) -> {
                if (p.windowId() == 0) {
                    var items = p.items();
                    for (int i = 0; i < items.size(); i++) {
                        applyWindow0Slot(s, i, nullIfAir(items.get(i)));
                    }
                } else {
                    // Snapshot for the currently opened container — the full slot vector arrives in
                    // one packet right after OpenWindow, and again as a resync after large mutations.
                    var win = s.openedWindow;
                    if (win != null && p.windowId() == win.id()) {
                        var items = p.items();
                        ItemStack[] slots = new ItemStack[items.size()];
                        for (int i = 0; i < items.size(); i++) slots[i] = nullIfAir(items.get(i));
                        var fresh = new PlayerState.OpenedWindow(win.id(), win.type(), win.title(), slots, win.properties());
                        s.openedWindow = s.set("openedWindow", win, fresh);
                    }
                }
                s.cursor = s.set("cursor", s.cursor, nullIfAir(p.carriedItem()));
            }),
            entry(SetCursorItemPacket.class, (s, _, _, p) ->
                    s.cursor = s.set("cursor", s.cursor, nullIfAir(p.itemStack()))),
            entry(OpenWindowPacket.class, (s, _, _, p) -> {
                var fresh = new PlayerState.OpenedWindow(p.windowId(), String.valueOf(p.windowType()),
                        p.title(), new ItemStack[0], new LinkedHashMap<>());
                s.openedWindow = s.set("openedWindow", s.openedWindow, fresh);
            }),
            entry(CloseWindowPacket.class, (s, _, _, p) ->
                    s.openedWindow = s.set("openedWindow", s.openedWindow, null)),
            // Client-initiated close (player pressed Esc / closed inventory). The server doesn't
            // echo CloseWindowPacket back, so without this the dashboard would keep the open-window
            // widget around until the next OpenWindow / disconnect.
            entry(ClientCloseWindowPacket.class, (s, _, _, p) ->
                    s.openedWindow = s.set("openedWindow", s.openedWindow, null)),
            // Inbound slot intent. Keep the highlight event, then apply the client's changed-slots
            // prediction so click-driven remove/set effects are visible until server packets reconcile.
            entry(ClientClickWindowPacket.class, (s, _, _, p) -> {
                int containerSize = s.openedWindow != null && p.windowId() == s.openedWindow.id()
                        ? s.openedWindow.slots().length : 0;
                SlotRef ref = classifyClickSlot(p.windowId(), p.slot(), containerSize);
                long seq = s.currentProvenance != null ? s.currentProvenance.seq() : 0L;
                var ev = new PlayerState.ClickEvent(
                        seq, System.currentTimeMillis(),
                        p.windowId(), p.slot(),
                        ref.kind(), ref.localSlot(),
                        p.button() & 0xFF,
                        p.clickType().name());
                s.append("recentClicks", s.recentClicks, ev, 32);

                for (var changed : p.changedSlots().entrySet()) {
                    applyWindowSlot(s, p.windowId(), changed.getKey(), nullIfAir(changed.getValue().asItemStack()));
                }
                s.cursor = s.set("cursor", s.cursor, nullIfAir(p.clickedItem().asItemStack()));
            }));

    private InventoryUpdaters() {
    }

    private static void applyWindowSlot(PlayerState s, int windowId, int slot, ItemStack item) {
        if (windowId == 0) {
            applyWindow0Slot(s, slot, item);
            return;
        }

        // Live update for the open container: mutate the slot in-place so the dashboard sees
        // per-click changes without waiting for a full WindowItemsPacket re-send. The patch
        // ships the full `openedWindow` snapshot via `markDirty` because slot indices inside
        // the container don't have stable per-field paths.
        var win = s.openedWindow;
        if (win == null || windowId != win.id() || slot < 0) return;
        int containerSize = win.slots().length;
        if (slot < containerSize) {
            win.slots()[slot] = item;
            s.markDirty("openedWindow");
            return;
        }
        applyContainerPlayerSlot(s, slot - containerSize, item);
    }

    private static void applyWindow0Slot(PlayerState s, int slot, ItemStack item) {
        applySlotRef(s, classifyClickSlot(0, slot, 0), item);
    }

    private static void applyContainerPlayerSlot(PlayerState s, int slot, ItemStack item) {
        if (slot >= 0 && slot < 27) applySlotRef(s, new SlotRef("main", slot), item);
        else if (slot >= 27 && slot < 36) applySlotRef(s, new SlotRef("hotbar", slot - 27), item);
    }

    private static void applyPlayerInventorySlot(PlayerState s, int slot, ItemStack item) {
        if (slot >= 0 && slot <= 8) applySlotRef(s, new SlotRef("hotbar", slot), item);
        else if (slot >= 9 && slot <= 35) applySlotRef(s, new SlotRef("main", slot - 9), item);
        else if (slot >= 36 && slot <= 39) applySlotRef(s, new SlotRef("armor", 39 - slot), item);
        else if (slot == 40) applySlotRef(s, new SlotRef("offhand", 0), item);
    }

    private static void applySlotRef(PlayerState s, SlotRef ref, ItemStack item) {
        switch (ref.kind()) {
            case "hotbar" -> {
                int slot = ref.localSlot();
                if (slot >= 0 && slot < s.hotbar.length)
                    s.hotbar[slot] = s.set("hotbar." + slot, s.hotbar[slot], item);
            }
            case "main" -> {
                int slot = ref.localSlot();
                if (slot >= 0 && slot < s.mainInventory.length)
                    s.mainInventory[slot] = s.set("mainInventory." + slot, s.mainInventory[slot], item);
            }
            case "armor" -> {
                int slot = ref.localSlot();
                if (slot >= 0 && slot < s.armor.length)
                    s.armor[slot] = s.set("armor." + slot, s.armor[slot], item);
            }
            case "offhand" -> s.offHand = s.set("offHand", s.offHand, item);
        }
    }

    /// One resolved click target — the wire `(windowId, slot)` pair translated into a logical
    /// inventory section so the frontend's highlight animation can find the matching cell.
    private record SlotRef(String kind, int localSlot) {
    }

    /// Map a vanilla click `(windowId, slot)` to a `(kind, localSlot)` pair the inventory grid
    /// can address. `slot == -999` (drop-outside) returns the `outside` sentinel.
    ///
    /// Player-inventory layout (windowId == 0):
    /// `0` crafting result · `1..4` crafting grid · `5..8` armor · `9..35` main ·
    /// `36..44` hotbar · `45` offhand.
    ///
    /// Container layout (windowId != 0): first `containerSize` slots are the container, the
    /// rest are the player's main+hotbar (27 + 9) in that order.
    private static SlotRef classifyClickSlot(int windowId, int slot, int containerSize) {
        if (slot < 0) return new SlotRef("outside", slot);
        if (windowId == 0) {
            if (slot == 0) return new SlotRef("crafting", 0);
            if (slot < 5) return new SlotRef("craftingGrid", slot - 1);
            if (slot < 9) return new SlotRef("armor", slot - 5);
            if (slot < 36) return new SlotRef("main", slot - 9);
            if (slot < 45) return new SlotRef("hotbar", slot - 36);
            if (slot == 45) return new SlotRef("offhand", 0);
            return new SlotRef("unknown", slot);
        }
        if (containerSize > 0 && slot < containerSize) return new SlotRef("container", slot);
        if (containerSize > 0) {
            int rel = slot - containerSize;
            if (rel < 27) return new SlotRef("main", rel);
            if (rel < 36) return new SlotRef("hotbar", rel - 27);
        }
        return new SlotRef("container", slot);
    }
}
