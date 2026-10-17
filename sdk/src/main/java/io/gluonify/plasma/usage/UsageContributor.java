package io.gluonify.plasma.usage;

import io.gluonify.plasma.usage.UsageReport.AppShare;
import java.util.Collection;

/**
 * What a component can attribute to each application ({@code usage-mcp.md} § 3.3), to be implemented (CDI bean) by the component that can.
 * Without an implementation, {@link UsageResource} answers {@code attribution: none}, a valid state: the contract covers {@code process} and the shape,
 * not an attribution the component cannot provide. Reads counters or cached values only (never a disk walk).
 */
public interface UsageContributor {
    /** The shares per application (cumulative CPU in seconds, memory in bytes); empty if nothing to attribute. */
    Collection<AppShare> byApp();

    /**
     * Same thing, given the process as {@link UsageResource} has just measured it: for a component that splits its own totals pro rata of some measured quantity
     * (bytes, requests) without reading {@code /proc} again. Defaults to {@link #byApp()}.
     */
    default Collection<AppShare> byApp(final UsageReport.Process process) {
        return byApp();
    }

    /**
     * The bytes this component stores in total (disk, measured or from its last snapshot; read from a cache, never a disk walk), the {@code storageBytes} of the
     * report; null (default) when it stores nothing. Reported even when {@code byApp} is empty ({@code attribution: none}). Rows may carry their own share
     * ({@link AppShare#storageBytes()}), whose sum must not exceed this total.
     */
    default Long storageBytes() {
        return null;
    }

    /** {@link UsageReport#MEASURED}, {@link UsageReport#ESTIMATED} or {@link UsageReport#PRORATA}; {@code estimated} by default (indicative). */
    default String attribution() {
        return UsageReport.ESTIMATED;
    }
}
