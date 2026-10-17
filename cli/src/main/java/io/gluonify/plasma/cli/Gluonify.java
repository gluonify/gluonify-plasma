package io.gluonify.plasma.cli;

/** Entry point of the {@code gluonify} command ({@code java -jar gluonify-plasma-cli.jar <command>}). */
public final class Gluonify {
    private Gluonify() {
    }

    public static void main(final String[] args) {
        System.exit(Cli.system().run(args));
    }
}
