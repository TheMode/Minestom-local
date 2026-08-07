/// Public API for the Minestom Web Interface.
///
/// A transparent Minecraft-protocol proxy with an in-memory state engine plus an
/// HTTP + WebSocket dashboard. Wire it in with:
///
/// ```java
/// ProxyServer web = ProxyServer.builder()
///         .bindProxy(new InetSocketAddress("0.0.0.0", 25565))
///         .defaultBackend(new InetSocketAddress("127.0.0.1", 25566))
///         .bindDashboard(new InetSocketAddress("127.0.0.1", 8080))
///         .token(System.getenv("WEB_TOKEN"))
///         .build();
/// web.start();
/// ```
///
/// Types in this package form the stable surface area; anything under
/// `net.minestom.web.internal.*` is implementation detail.
///
/// **Doc style.** Source documentation uses JEP 467 markdown comments (`///`), never legacy
/// `/** … */` Javadoc.
package net.minestom.web;
