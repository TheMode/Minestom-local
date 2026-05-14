package net.minestom.web;

/// Direction of a packet on the wire, from the player's perspective.
///
/// `SERVERBOUND` flows client → server (player input).
/// `CLIENTBOUND` flows server → client (the player observes the result).
public enum Direction {
    CLIENTBOUND,
    SERVERBOUND
}
