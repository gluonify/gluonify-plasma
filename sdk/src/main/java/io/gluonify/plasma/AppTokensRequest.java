package io.gluonify.plasma;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Demande de jetons par un nœud, pour des applications qu'il fait tourner. */
@Schema(description = "Demande de jetons d'applications.")
public record AppTokensRequest(@Schema(description = "Applications placées sur le nœud dont il veut un jeton", example = "[\"shop\"]") List<String> apps) {}
