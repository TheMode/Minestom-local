package net.minestom.web.internal.state;

import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.web.Direction;
import net.minestom.web.PlayerState;

import java.util.Map;

import static net.minestom.web.internal.state.StateApplier.entry;
import static net.minestom.web.internal.state.StateApplier.listeners;

/// The player's own body: health, hunger, XP, abilities, status effects, attribute modifiers,
/// last combat damage, and proxied keep-alive round-trip time.
final class VitalsUpdaters {

    static final Map<Class<? extends Packet>, StateApplier.Updater<?>> LISTENERS = listeners(
            entry(UpdateHealthPacket.class, (s, _, _, p) -> {
                s.health = s.set("health", s.health, p.health());
                s.food = s.set("food", s.food, p.food());
                s.saturation = s.set("saturation", s.saturation, p.foodSaturation());
            }),
            entry(DamageEventPacket.class, (s, _, _, p) -> {
                var fresh = new PlayerState.DamageEvent(System.currentTimeMillis(), 0,
                        String.valueOf(p.damageTypeId()), p.sourceEntityId());
                s.lastDamage = s.set("lastDamage", s.lastDamage, fresh);
            }),
            entry(SetExperiencePacket.class, (s, _, _, p) -> {
                s.xpBar = s.set("xpBar", s.xpBar, p.percentage());
                s.xpLevel = s.set("xpLevel", s.xpLevel, p.level());
            }),
            entry(PlayerAbilitiesPacket.class, (s, _, _, p) -> {
                byte flags = p.flags();
                s.invulnerable = s.set("invulnerable", s.invulnerable, (flags & PlayerAbilitiesPacket.FLAG_INVULNERABLE) != 0);
                s.flying = s.set("flying", s.flying, (flags & PlayerAbilitiesPacket.FLAG_FLYING) != 0);
                s.allowFlying = s.set("allowFlying", s.allowFlying, (flags & PlayerAbilitiesPacket.FLAG_ALLOW_FLYING) != 0);
                s.instantBreak = s.set("instantBreak", s.instantBreak, (flags & PlayerAbilitiesPacket.FLAG_INSTANT_BREAK) != 0);
                s.flySpeed = s.set("flySpeed", s.flySpeed, p.flyingSpeed());
                s.walkSpeed = s.set("walkSpeed", s.walkSpeed, p.walkingSpeed());
            }),
            entry(EntityEffectPacket.class, (s, _, _, p) -> {
                var potion = p.potion();
                String id = potion.effect().key().asString();
                var fresh = new PlayerState.ActiveEffect(id, potion.amplifier(), potion.duration(),
                        (potion.flags() & 0x01) != 0, (potion.flags() & 0x02) != 0);
                s.activeEffects.put(id, s.set("activeEffects." + id, s.activeEffects.get(id), fresh));
            }),
            entry(RemoveEntityEffectPacket.class, (s, _, _, p) -> {
                String id = p.potionEffect().key().asString();
                var prev = s.activeEffects.remove(id);
                if (prev != null) s.set("activeEffects." + id, prev, null);
            }),
            entry(EntityAttributesPacket.class, (s, _, _, p) -> {
                for (EntityAttributesPacket.Property prop : p.properties()) {
                    String name = prop.attribute().key().asString();
                    // Box explicitly: attributes is Map<String, Double>; set's primitive double
                    // overload would auto-unbox and we'd lose the null check on first insert.
                    s.attributes.put(name, s.set("attributes." + name, s.attributes.get(name), Double.valueOf(prop.value())));
                    // Surface max health as its own field so the dashboard's health gauge isn't
                    // pinned to 20 (matches both the `generic.max_health` and `max_health` keys).
                    if (name.endsWith("max_health")) {
                        s.maxHealth = s.set("maxHealth", s.maxHealth, (float) prop.value());
                    }
                }
            }),
            // Keep-alive RTT along the proxied path: stamp send time on the outbound (clientbound)
            // keep-alive, measure on the matching client response decoded back from the player.
            entry(KeepAlivePacket.class, (s, dir, _, p) -> {
                if (dir != Direction.CLIENTBOUND) return;
                s.traffic.lastKeepAliveOutId = p.id();
                s.traffic.lastKeepAliveOutAt = System.nanoTime();
            }),
            entry(ClientKeepAlivePacket.class, (s, dir, _, p) -> {
                if (dir != Direction.SERVERBOUND) return;
                final PlayerState.Traffic t = s.traffic;
                if (p.id() != t.lastKeepAliveOutId || t.lastKeepAliveOutAt <= 0) return;
                final long ms = (System.nanoTime() - t.lastKeepAliveOutAt) / 1_000_000L;
                if (ms == t.pingMs) return;
                t.pingMs = s.set("traffic.pingMs", t.pingMs, ms);
                s.append("traffic.pingHistory", t.pingHistory, ms, 200);
            }));

    private VitalsUpdaters() {
    }
}
