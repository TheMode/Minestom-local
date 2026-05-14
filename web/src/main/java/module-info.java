module net.minestom.web {
    requires transitive net.minestom.server;
    requires io.javalin;
    requires org.slf4j;
    requires java.desktop;
    requires java.naming;
    requires java.sql;

    requires net.kyori.adventure.text.serializer.gson;
    requires net.kyori.adventure.text.serializer.legacy;

    exports net.minestom.web;
}
