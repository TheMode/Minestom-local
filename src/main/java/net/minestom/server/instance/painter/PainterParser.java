package net.minestom.server.instance.painter;

public final class PainterParser {
    public static Painter parse(String input) {
        Tokenizer tokenizer = new Tokenizer(input);
        PainterParser parser = new PainterParser(tokenizer);
        return parser.parse();
    }

    private final Tokenizer tokenizer;

    private PainterParser(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    private Painter parse() {
        return Painter.paint(world -> {
        });
    }
}
