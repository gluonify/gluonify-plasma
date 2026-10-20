/**
 * Common format of analysis findings (specification {@code gluonify-codex/analysis.md} § 3.6), shared by Bup, geiger (the site-analysis service) and Prism.
 *
 * <p>A finding contains <strong>no sentence</strong>: a stable rule code ({@code N-ELF}, {@code R-SECRET}, {@code GN04}…) and
 * arguments. The interface (Prism) translates the code into the five languages (keys {@code analysis.rules.<code>.title|why|fix}).
 * The types read ignore unknown properties: a field added later does not break an old reader.
 */
package io.gluonify.plasma.analysis;
