package com.baari.simulator;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point of the clinic simulator.
 *
 * <pre>generate --config sim/demo-clinic.json --days 60 --out build/sim [--seed 42] [--end 2026-11-19]</pre>
 *
 * Writes one {@code tokens.csv} with a row per simulated patient. The last simulated day is
 * {@code --end} (default: yesterday is not guessed, so it is required for reproducible files).
 */
public final class SimulatorMain {

    static final String HEADER =
            "service_date,doctor,number,visit_type,priority,source,status,joined_at,called_at,consult_started_at,completed_at";

    private SimulatorMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || !args[0].equals("generate")) {
            System.err.println("usage: generate --config FILE --days N --out DIR --end YYYY-MM-DD [--seed N]");
            System.exit(2);
        }
        Map<String, String> opts = options(args);
        SimConfig cfg = SimConfig.parse(Files.readString(Path.of(require(opts, "config"))));
        int days = Integer.parseInt(require(opts, "days"));
        LocalDate end = LocalDate.parse(require(opts, "end"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "42"));
        Path out = Path.of(require(opts, "out"));
        Files.createDirectories(out);
        int rows = generate(cfg, days, end, seed, out.resolve("tokens.csv"));
        System.out.println("wrote " + rows + " tokens for " + days + " days to " + out.resolve("tokens.csv"));
    }

    /** Simulates {@code days} days ending on {@code end}; returns the number of rows written. */
    static int generate(SimConfig cfg, int days, LocalDate end, long seed, Path file) throws IOException {
        int rows = 0;
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(HEADER + "\n");
            for (int i = days - 1; i >= 0; i--) {
                LocalDate date = end.minusDays(i);
                // one stream per day, so changing --days does not change the days it shares
                List<SimToken> tokens = DaySimulation.run(cfg, seed * 1_000_003L + date.toEpochDay());
                for (SimToken t : tokens) {
                    w.write(row(date, cfg.zone(), t));
                    rows++;
                }
            }
        }
        return rows;
    }

    static String row(LocalDate date, ZoneId zone, SimToken t) {
        return String.join(
                        ",",
                        date.toString(),
                        t.doctor(),
                        Integer.toString(t.number()),
                        t.visitType(),
                        t.priority(),
                        t.source(),
                        t.status(),
                        time(date, zone, t.joinedAt()),
                        time(date, zone, t.calledAt()),
                        time(date, zone, t.consultStartedAt()),
                        time(date, zone, t.completedAt()))
                + "\n";
    }

    private static String time(LocalDate date, ZoneId zone, long secondsAfterMidnight) {
        if (secondsAfterMidnight < 0) {
            return "";
        }
        ZonedDateTime z = date.atStartOfDay(zone).plusSeconds(secondsAfterMidnight);
        return OffsetDateTime.from(z).toString();
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected an option, got " + args[i]);
            }
            m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return v;
    }
}
