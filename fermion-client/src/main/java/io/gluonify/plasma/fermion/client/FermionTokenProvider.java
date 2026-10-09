package io.gluonify.plasma.fermion.client;

/**
 * Fournit le jeton Charm (audience {@code gluonify-fermion}) envoyé dans la première trame {@code auth}. Appelé à chaque (re)connexion :
 * l'implémentation (un bean CDI de l'application) renvoie un jeton valide, au besoin renouvelé.
 */
@FunctionalInterface
public interface FermionTokenProvider {
    String token();
}
