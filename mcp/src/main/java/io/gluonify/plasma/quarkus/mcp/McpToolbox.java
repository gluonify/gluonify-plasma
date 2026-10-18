package io.gluonify.plasma.quarkus.mcp;

/**
 * Marker of a CDI bean that carries {@link McpTool} methods: {@code /mcp} looks the tools up among the beans of this type (which also keeps them
 * from being removed as unused by ArC). Native image: annotate the class with {@code @RegisterForReflection} (its methods are read and invoked by
 * reflection), and the arguments and result records too, or list them in {@code @RegisterForReflection(targets = ...)}; a toolbox in which no
 * tool can be read is reported at startup.
 */
public interface McpToolbox {}
