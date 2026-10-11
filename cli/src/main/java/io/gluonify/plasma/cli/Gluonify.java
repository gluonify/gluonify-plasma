package io.gluonify.plasma.cli;

/** Point d'entrée de la commande {@code gluonify} ({@code java -jar gluonify-plasma-cli.jar <commande>}). */
public final class Gluonify {
    private Gluonify() {
    }

    public static void main(final String[] args) {
        System.exit(Cli.system().run(args));
    }
}
