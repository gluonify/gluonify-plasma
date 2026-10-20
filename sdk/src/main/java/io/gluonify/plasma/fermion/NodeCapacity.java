package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a node can take at once and when it takes nothing, announced in {@link FermionMsg.Hello} (extension E4): {@code slots} gives the number of
 * concurrent tasks per named resource ({@code heavy: 1, model: 1, claude: 3}), a skill consumes them through {@link SkillDescriptor#requires()};
 * {@code closed} lists the windows during which the node accepts no new task (a bench night). A node that announces no capacity keeps the old
 * behaviour: fermion places on it by capability and round robin only.
 *
 * @param slots  concurrent tasks per resource name, sorted by name (never {@code null}, empty when the node announces none)
 * @param closed windows during which the node takes no new task (never {@code null})
 * @author David CABILLIC
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeCapacity(Map<String, Integer> slots, List<Window> closed) {

    /**
     * Normalises the collections: {@code null} becomes empty, the values are copied.
     *
     * @param slots  concurrent tasks per resource name
     * @param closed closed windows
     */
    public NodeCapacity {

        slots = slots == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(slots));
        closed = closed == null ? List.of() : List.copyOf(closed);
    }

    /**
     * Whether the node announces neither slots nor windows (fermion then ignores the capacity).
     *
     * @return true when nothing is announced
     */
    @JsonIgnore
    public boolean isEmpty() {

        return slots.isEmpty() && closed.isEmpty();
    }

    /**
     * Whether one of the windows is closed at {@code instant}.
     *
     * @param instant the moment to test
     * @return true when the node takes no new task at that moment
     */
    public boolean closedAt(final Instant instant) {

        return closed.stream().anyMatch(window -> window.contains(instant));
    }

    /**
     * A daily window, in local time of {@code zone}: from {@code from} (included) to {@code to} (excluded), crossing midnight when {@code to} is not
     * after {@code from} ({@code 23:30-07:00}); {@code from == to} closes the whole day. {@code days} restricts the window to the days on which it
     * starts (empty: every day). Text form, for the configuration: {@code [MON,TUE ]HH:mm-HH:mm}.
     *
     * @param from start, {@code HH:mm}
     * @param to   end, {@code HH:mm}
     * @param days days on which the window starts ({@code MON} to {@code SUN}), empty for every day
     * @param zone time zone (IANA), {@code Europe/Paris} when absent
     * @author David CABILLIC
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Window(String from, String to, List<String> days, String zone) {

        /** Zone of a window that names none: the laboratory's (Paris). */
        public static final String DEFAULT_ZONE = "Europe/Paris";

        private static final char RANGE_SEPARATOR = '-';

        /**
         * Validates the times, the days and the zone, and normalises the days to upper case.
         *
         * @param from start, {@code HH:mm}
         * @param to   end, {@code HH:mm}
         * @param days days on which the window starts, empty for every day
         * @param zone time zone, {@code Europe/Paris} when absent
         * @throws IllegalArgumentException when a time, a day or the zone is invalid
         */
        public Window {

            parseTime(from);
            parseTime(to);
            days = days == null ? List.of() : days.stream().map(day -> parseDay(day).name().substring(0, 3)).toList();
            zone = zone == null || zone.isBlank() ? DEFAULT_ZONE : zone;
            parseZone(zone);
        }

        /**
         * Parses the text form {@code [MON,TUE ]HH:mm-HH:mm}, in {@code zone}.
         *
         * @param text the window
         * @param zone time zone, {@code null} for {@code Europe/Paris}
         * @return the window
         * @throws IllegalArgumentException when the text is not a window
         */
        public static Window parse(final String text, final String zone) {

            final String spec = text == null ? "" : text.strip();
            final int space = spec.indexOf(' ');
            final String range = space < 0 ? spec : spec.substring(space + 1).strip();
            final List<String> days = space < 0 ? List.of() : List.of(spec.substring(0, space).split(","));
            final int dash = range.indexOf(RANGE_SEPARATOR);
            if (dash < 0) {
                throw new IllegalArgumentException("window '" + spec + "' is not [DAYS ]HH:mm-HH:mm");
            }
            return new Window(range.substring(0, dash), range.substring(dash + 1), days, zone);
        }

        /**
         * Whether the window covers {@code instant}.
         *
         * @param instant the moment to test
         * @return true when {@code instant} falls inside the window
         */
        public boolean contains(final Instant instant) {

            final ZonedDateTime local = instant.atZone(ZoneId.of(zone));
            final LocalTime time = local.toLocalTime();
            final LocalTime start = LocalTime.parse(from);
            final LocalTime end = LocalTime.parse(to);
            final DayOfWeek today = local.getDayOfWeek();
            if (start.isBefore(end)) {
                return startsOn(today) && !time.isBefore(start) && time.isBefore(end);
            }
            return startsOn(today) && !time.isBefore(start) || startsOn(today.minus(1)) && time.isBefore(end);
        }

        private boolean startsOn(final DayOfWeek day) {

            return days.isEmpty() || days.contains(day.name().substring(0, 3));
        }

        /**
         * The text form of the window, as {@link #parse} reads it (without the zone).
         *
         * @return {@code [DAYS ]HH:mm-HH:mm}
         */
        @Override
        public String toString() {

            return (days.isEmpty() ? "" : String.join(",", days) + " ") + from + RANGE_SEPARATOR + to;
        }

        private static void parseTime(final String time) {

            try {
                LocalTime.parse(time == null ? "" : time);
            }
            catch (final DateTimeException e) {
                throw new IllegalArgumentException("window time '" + time + "' is not HH:mm", e);
            }
        }

        private static DayOfWeek parseDay(final String day) {

            final String code = day == null ? "" : day.strip().toUpperCase(Locale.ROOT);
            for (final DayOfWeek candidate : DayOfWeek.values()) {
                if (code.length() == 3 && candidate.name().startsWith(code)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("window day '" + day + "' is not one of MON, TUE, WED, THU, FRI, SAT, SUN");
        }

        private static void parseZone(final String zone) {

            try {
                ZoneId.of(zone);
            }
            catch (final DateTimeException e) {
                throw new IllegalArgumentException("window zone '" + zone + "' is unknown", e);
            }
        }
    }

    /**
     * Builds a capacity from the configuration form: slots, window texts and their zone.
     *
     * @param slots   concurrent tasks per resource name
     * @param windows texts {@code [DAYS ]HH:mm-HH:mm}
     * @param zone    zone of the windows, {@code null} for {@code Europe/Paris}
     * @return the capacity
     * @throws IllegalArgumentException when a slot count is negative or a window is invalid
     */
    public static NodeCapacity of(final Map<String, Integer> slots, final List<String> windows, final String zone) {

        final Set<String> negative = new TreeSet<>();
        slots.forEach((name, count) -> {
            if (count == null || count < 0) {
                negative.add(name);
            }
        });
        if (!negative.isEmpty()) {
            throw new IllegalArgumentException("capacity slots " + negative + " must be zero or more");
        }
        final List<Window> parsed = new ArrayList<>();
        for (final String window : windows) {
            parsed.add(Window.parse(window, zone));
        }
        return new NodeCapacity(slots, parsed);
    }
}
