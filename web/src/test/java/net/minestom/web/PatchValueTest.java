package net.minestom.web;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.Component;
import net.minestom.server.dialog.Dialog;
import net.minestom.server.dialog.DialogActionButton;
import net.minestom.server.dialog.DialogAfterAction;
import net.minestom.server.dialog.DialogMetadata;
import net.minestom.web.internal.codec.PatchValue;
import net.minestom.web.internal.codec.RoutineCodecs;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebJsonBuilders;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.codec.WebJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class PatchValueTest {

    @Test
    void encodesNullTabListHeader() {
        var player = new PlayerState();
        player.tabList = new PlayerState.TabListSnapshot(null, null);
        assertDoesNotThrow(() -> WebJsonBuilders.playerStateJson(player, WebJson.CODER));
    }

    @Test
    void encodesTrafficAsNestedSnapshot() {
        var player = new PlayerState();
        player.traffic.compressionThreshold = 256;
        player.traffic.bytesIn = 12;
        player.traffic.packetsOut = 3;

        JsonObject json = WebJsonBuilders.playerStateJson(player, WebJson.CODER);
        JsonObject traffic = json.getAsJsonObject("traffic");

        assertFalse(json.has("bytesIn"));
        assertFalse(json.has("compressionThreshold"));
        assertEquals(256, traffic.get("compressionThreshold").getAsInt());
        assertEquals(12L, traffic.get("bytesIn").getAsLong());
        assertEquals(3L, traffic.get("packetsOut").getAsLong());
    }

    @Test
    void encodesPlayersSummaryTrafficNested() {
        var player = new PlayerState();
        player.uuid = java.util.UUID.randomUUID();
        player.traffic.pingMs = 45;

        JsonObject json = WebJson.encodeAsObject(WebCodecs.PLAYERS_SUMMARY, new WebPayloads.PlayersSummaryPayload(
                List.of(WebPayloads.PlayersSummaryRow.from(player))));
        JsonObject row = json.getAsJsonArray("players").get(0).getAsJsonObject();

        assertFalse(row.has("pingMs"));
        assertEquals(45L, row.getAsJsonObject("traffic").get("pingMs").getAsLong());
    }

    @Test
    void encodesNullPatchValues() {
        var patch = new StatePatch(1, 2,
                new LinkedHashMap<>(Map.of("cursor", "placeholder")),
                Map.of(), Map.of());
        patch.values().put("hotbar.0", null);

        JsonObject json = WebJson.encodeAsObject(WebCodecs.STATE_PATCH, patch);

        JsonObject values = json.getAsJsonObject("values");
        assertFalse(values.get("cursor").isJsonNull());
        assertInstanceOf(com.google.gson.JsonNull.class, values.get("hotbar.0"));
    }

    @Test
    void encodesComponentMapsAndNestedLists() {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("text", "hello");
        List<Object> extra = new ArrayList<>();
        extra.add(Map.of("text", "world"));
        text.put("extra", extra);

        assertDoesNotThrow(() -> WebJson.encode(PatchValue.CODEC, text));

        var player = new PlayerState();
        player.custom.put("banner", text);
        JsonObject json = assertDoesNotThrow(() -> WebJsonBuilders.playerStateJson(player, WebJson.CODER));
        assertFalse(json.getAsJsonObject("custom").get("banner").isJsonNull());
    }

    @Test
    void decodesRoutineChatComponent() {
        Action.Chat literal = assertInstanceOf(Action.Chat.class, RoutineCodecs.decodeAction(
                com.google.gson.JsonParser.parseString(
                        "{\"type\":\"chat\",\"component\":{\"text\":\"hello\",\"color\":\"red\"}}")
                        .getAsJsonObject(), id -> null));
        Component component = assertInstanceOf(Component.class, literal.component());
        JsonObject content = WebJson.encodeAsObject(net.minestom.server.codec.Codec.COMPONENT, component);
        assertEquals("hello", content.get("text").getAsString());

        Action.Chat expr = assertInstanceOf(Action.Chat.class, RoutineCodecs.decodeAction(
                com.google.gson.JsonParser.parseString(
                        "{\"type\":\"chat\",\"component\":\"\\\"Hello \\\" + name\"}")
                        .getAsJsonObject(), id -> null));
        assertEquals("\"Hello \" + name", expr.component());
    }

    @Test
    void encodesChatLineComponent() {
        var player = new PlayerState();
        player.chatReceived.add(new PlayerState.ChatLine(1, null,
                Component.text("hi", net.kyori.adventure.text.format.NamedTextColor.AQUA), "system"));
        JsonObject json = WebJsonBuilders.playerStateJson(player, WebJson.CODER);
        JsonObject line = json.getAsJsonArray("recentChat").get(0).getAsJsonObject();
        assertEquals("hi", line.getAsJsonObject("content").get("text").getAsString());
        assertEquals("aqua", line.getAsJsonObject("content").get("color").getAsString());
    }

    @Test
    void encodesChatLineClickEventRequiringRegistries() {
        var player = new PlayerState();
        Dialog dialog = new Dialog.Notice(
                new DialogMetadata(Component.text("Notice"), null, true, true,
                        DialogAfterAction.CLOSE, List.of(), List.of()),
                new DialogActionButton(Component.text("OK"), null, DialogActionButton.DEFAULT_WIDTH, null));
        Component component = Component.text("open").clickEvent(ClickEvent.showDialog(dialog));
        player.chatReceived.add(new PlayerState.ChatLine(1, null, component, "system"));

        JsonObject json = WebJsonBuilders.playerStateJson(player, WebJson.CODER);

        JsonObject click = json.getAsJsonArray("recentChat").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonObject("click_event");
        assertEquals("show_dialog", click.get("action").getAsString());
        assertEquals("minecraft:notice", click.getAsJsonObject("dialog").get("type").getAsString());
    }

    @Test
    void encodesChatLinePatchAppendClickEventRequiringRegistries() {
        Dialog dialog = new Dialog.Notice(
                new DialogMetadata(Component.text("Notice"), null, true, true,
                        DialogAfterAction.CLOSE, List.of(), List.of()),
                new DialogActionButton(Component.text("OK"), null, DialogActionButton.DEFAULT_WIDTH, null));
        Component component = Component.text("open").clickEvent(ClickEvent.showDialog(dialog));
        var line = new PlayerState.ChatLine(1, null, component, "system");
        var patch = new StatePatch(1, 2, Map.of(),
                Map.of("recentChat", new StatePatch.Append(List.of(line), 200)), Map.of());

        JsonObject json = WebJson.encodeAsObject(WebCodecs.STATE_PATCH, patch, WebJson.CODER);

        JsonObject click = json.getAsJsonObject("appends").getAsJsonObject("recentChat")
                .getAsJsonArray("elements").get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonObject("click_event");
        assertEquals("show_dialog", click.get("action").getAsString());
    }

    @Test
    void decodesRoutineInjectFields() {
        JsonObject json = com.google.gson.JsonParser.parseString("""
                {
                  "type": "inject",
                  "packet": "SystemChatPacket",
                  "fields": {
                    "message": {"text": "hello"},
                    "overlay": false,
                    "nullable": null
                  }
                }
                """).getAsJsonObject();

        Action.Inject action = assertInstanceOf(Action.Inject.class,
                RoutineCodecs.decodeAction(json, id -> null));
        assertEquals("SystemChatPacket", action.className());
        assertEquals(false, action.fields().get("overlay"));
        assertNull(action.fields().get("nullable"));
        assertEquals("hello", ((Map<?, ?>) action.fields().get("message")).get("text"));
    }

    @Test
    void encodesPlayerPacketEventsInAggregate() {
        PlayerState player = new PlayerState();
        player.uuid = java.util.UUID.randomUUID();
        player.connectionId = java.util.UUID.randomUUID();
        player.username = "Steve";
        PacketEvent event = new PacketEvent(1, 2, Direction.CLIENTBOUND,
                net.minestom.server.network.ConnectionState.PLAY, "KeepAlivePacket", 3,
                "net", "Network", "net", 4);

        JsonObject json = WebJson.encodeAsObject(WebCodecs.PACKETS_AGGREGATE,
                new WebPayloads.PacketsAggregate(List.of(WebPayloads.PlayerPacketEvent.from(player, event))));
        JsonObject row = json.getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals(player.uuid.toString(), row.get("uuid").getAsString());
        assertEquals(player.connectionId.toString(), row.get("connectionId").getAsString());
        assertEquals("Steve", row.get("username").getAsString());
        assertEquals("KeepAlivePacket", row.get("className").getAsString());
    }
}
