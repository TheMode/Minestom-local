package net.minestom.web.internal.state;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.client.play.ClientChatMessagePacket;
import net.minestom.server.network.packet.client.play.ClientCommandChatPacket;
import net.minestom.server.network.packet.client.play.ClientSignedCommandChatPacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.server.scoreboard.Sidebar;
import net.minestom.web.PlayerState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minestom.web.internal.state.StateApplier.entry;
import static net.minestom.web.internal.state.StateApplier.listeners;

final class ChatHudUpdaters {

    static final Map<Class<? extends Packet>, StateApplier.Updater<?>> LISTENERS = listeners(
            entry(SystemChatPacket.class, (s, _, _, p) -> appendReceived(s, null, p.message(),
                    p.overlay() ? "actionbar" : "system")),
            entry(PlayerChatMessagePacket.class, (s, _, _, p) -> appendReceived(s, p.sender().toString(),
                    p.unsignedContent() != null ? p.unsignedContent() : Component.text(p.messageBody().content()),
                    "player")),
            entry(DisguisedChatPacket.class, (s, _, _, p) -> appendReceived(s, null, p.message(), "player")),
            entry(ClientChatMessagePacket.class, (s, _, _, p) -> recordSent(s, "chat", p.message())),
            entry(ClientCommandChatPacket.class, (s, _, _, p) -> recordSent(s, "command", p.message())),
            entry(ClientSignedCommandChatPacket.class, (s, _, _, p) -> recordSent(s, "command", p.message())),
            entry(ActionBarPacket.class, (s, _, _, p) ->
                    s.lastActionBar = s.set("lastActionBar", s.lastActionBar, p.text())),
            entry(BossBarPacket.class, (s, _, _, p) -> {
                UUID id = p.uuid();
                if (p.action() instanceof BossBarPacket.RemoveAction) {
                    s.set("bossBars." + id, s.bossBars.remove(id), null);
                    return;
                }
                var next = applyBossBar(p.action(), s.bossBars.get(id));
                if (next != null) s.bossBars.put(id, s.set("bossBars." + id, s.bossBars.get(id), next));
            }),
            entry(DisplayScoreboardPacket.class, (s, _, _, p) -> {
                if (s.scoreboard == null) {
                    s.scoreboard = s.set("scoreboard", null, new PlayerState.ScoreboardSnapshot(
                            p.scoreName(), null, String.valueOf(p.position()), new LinkedHashMap<>()));
                }
            }),
            entry(ScoreboardObjectivePacket.class, (s, _, _, p) -> {
                LinkedHashMap<String, PlayerState.ScoreboardRow> rows = s.scoreboard != null
                        ? new LinkedHashMap<>(s.scoreboard.rows()) : new LinkedHashMap<>();
                s.scoreboard = s.set("scoreboard", s.scoreboard, new PlayerState.ScoreboardSnapshot(
                        p.objectiveName(), p.objectiveValue(),
                        s.scoreboard != null ? s.scoreboard.slot() : null, rows));
            }),
            entry(UpdateScorePacket.class, (s, _, _, p) -> {
                if (s.scoreboard == null) return;
                Component display = composeRowDisplay(p.entityName(), p.displayName(),
                        s.teams.get(s.teamByMember.get(p.entityName())));
                Sidebar.NumberFormat raw = p.numberFormat();
                PlayerState.NumberFormat fmt = raw == null ? null
                        : new PlayerState.NumberFormat(raw.formatType().name(), raw.content());
                s.scoreboard.rows().put(p.entityName(),
                        new PlayerState.ScoreboardRow(p.score(), display, fmt));
                s.markDirty("scoreboard");
            }),
            entry(ResetScorePacket.class, (s, _, _, p) -> {
                if (s.scoreboard == null) return;
                if (p.objective() != null && !p.objective().equals(s.scoreboard.objectiveName())) return;
                if (s.scoreboard.rows().remove(p.owner()) != null) s.markDirty("scoreboard");
            }),
            entry(TeamsPacket.class, (s, _, _, p) -> applyTeam(s, p)),
            entry(PlayerListHeaderAndFooterPacket.class, (s, _, _, p) ->
                    s.tabList = s.set("tabList", s.tabList, new PlayerState.TabListSnapshot(p.header(), p.footer()))));

    private ChatHudUpdaters() {
    }

    private static void appendReceived(PlayerState s, String sender, Component content, String style) {
        s.append("recentChat", s.chatReceived,
                new PlayerState.ChatLine(System.currentTimeMillis(), sender, content, style), 200);
    }

    private static void recordSent(PlayerState s, String kind, String text) {
        if (text == null) return;
        s.append("sentChat", s.chatSent, new PlayerState.SentChatLine(System.currentTimeMillis(), kind, text), 200);
    }

    private static void applyTeam(PlayerState s, TeamsPacket packet) {
        final String name = packet.teamName();
        switch (packet.action()) {
            case TeamsPacket.CreateTeamAction create -> {
                s.teams.put(name, new PlayerState.TeamSnapshot(create.teamPrefix(), create.teamSuffix(),
                        teamColorName(create.teamColor())));
                for (String entity : create.entities()) s.teamByMember.put(entity, name);
                recomposeForTeam(s, name);
            }
            case TeamsPacket.UpdateTeamAction update -> {
                s.teams.put(name, new PlayerState.TeamSnapshot(update.teamPrefix(), update.teamSuffix(),
                        teamColorName(update.teamColor())));
                recomposeForTeam(s, name);
            }
            case TeamsPacket.RemoveTeamAction _ -> {
                if (s.teams.remove(name) == null) return;
                List<String> orphaned = new ArrayList<>();
                s.teamByMember.entrySet().removeIf(e -> {
                    if (!name.equals(e.getValue())) return false;
                    orphaned.add(e.getKey());
                    return true;
                });
                for (String entity : orphaned) recomposeForEntity(s, entity);
            }
            case TeamsPacket.AddEntitiesToTeamAction add -> {
                for (String entity : add.entities()) {
                    s.teamByMember.put(entity, name);
                    recomposeForEntity(s, entity);
                }
            }
            case TeamsPacket.RemoveEntitiesToTeamAction remove -> {
                for (String entity : remove.entities()) {
                    if (name.equals(s.teamByMember.get(entity))) s.teamByMember.remove(entity);
                    recomposeForEntity(s, entity);
                }
            }
        }
    }

    private static void recomposeForTeam(PlayerState s, String teamName) {
        if (s.scoreboard == null) return;
        boolean changed = false;
        for (Map.Entry<String, PlayerState.ScoreboardRow> entry : s.scoreboard.rows().entrySet()) {
            if (!teamName.equals(s.teamByMember.get(entry.getKey()))) continue;
            PlayerState.ScoreboardRow row = entry.getValue();
            Component display = composeRowDisplay(entry.getKey(), null, s.teams.get(teamName));
            entry.setValue(new PlayerState.ScoreboardRow(row.score(), display, row.numberFormat()));
            changed = true;
        }
        if (changed) s.markDirty("scoreboard");
    }

    private static void recomposeForEntity(PlayerState s, String entityName) {
        if (s.scoreboard == null) return;
        PlayerState.ScoreboardRow row = s.scoreboard.rows().get(entityName);
        if (row == null) return;
        Component display = composeRowDisplay(entityName, null, s.teams.get(s.teamByMember.get(entityName)));
        s.scoreboard.rows().put(entityName,
                new PlayerState.ScoreboardRow(row.score(), display, row.numberFormat()));
        s.markDirty("scoreboard");
    }

    /// Priority: `displayName` from `UpdateScorePacket`, then `team.prefix + colored(entityName)
    /// + team.suffix`, then the entityName itself (with legacy `§` codes parsed).
    private static Component composeRowDisplay(String entityName, @Nullable Component displayName,
                                               @Nullable PlayerState.TeamSnapshot team) {
        if (displayName != null) return displayName;
        Component name = entityName.indexOf('§') >= 0
                ? LegacyComponentSerializer.legacySection().deserialize(entityName)
                : Component.text(entityName);
        if (team == null) return name;
        if (team.teamColor() != null) {
            NamedTextColor color = NamedTextColor.NAMES.value(team.teamColor());
            if (color != null) name = name.colorIfAbsent(color);
        }
        Component prefix = team.prefix() != null ? team.prefix() : Component.empty();
        Component suffix = team.suffix() != null ? team.suffix() : Component.empty();
        return Component.empty().append(prefix).append(name).append(suffix);
    }

    private static @Nullable String teamColorName(@Nullable NamedTextColor color) {
        return color == null ? null : NamedTextColor.NAMES.key(color);
    }

    private static PlayerState.BossBarSnapshot applyBossBar(BossBarPacket.Action action, PlayerState.BossBarSnapshot p) {
        return switch (action) {
            case BossBarPacket.AddAction a -> new PlayerState.BossBarSnapshot(
                    a.title(), a.health(), a.color().name(), a.overlay().name(), a.flags() & 0xFF);
            case BossBarPacket.UpdateHealthAction h -> p == null ? null : new PlayerState.BossBarSnapshot(
                    p.title(), h.health(), p.color(), p.division(), p.flags());
            case BossBarPacket.UpdateTitleAction t -> p == null ? null : new PlayerState.BossBarSnapshot(
                    t.title(), p.progress(), p.color(), p.division(), p.flags());
            case BossBarPacket.UpdateStyleAction st -> p == null ? null : new PlayerState.BossBarSnapshot(
                    p.title(), p.progress(), st.color().name(), st.overlay().name(), p.flags());
            case BossBarPacket.UpdateFlagsAction f -> p == null ? null : new PlayerState.BossBarSnapshot(
                    p.title(), p.progress(), p.color(), p.division(), f.flags() & 0xFF);
            default -> null;
        };
    }
}
