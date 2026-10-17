package io.gluonify.plasma;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Token request by a node, for applications it runs. */
@Schema(description = "Application token request.")
public record AppTokensRequest(@Schema(description = "Applications placed on the node for which it wants a token", example = "[\"shop\"]") List<String> apps) {}
