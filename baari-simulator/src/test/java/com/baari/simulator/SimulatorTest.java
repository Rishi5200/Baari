package com.baari.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class SimulatorTest {

    private static final String CONFIG = """
            {
              "clinic": "T", "zone": "Asia/Kolkata",
              "sessions": [ { "start": "09:30", "end": "13:30" } ],
              "doctors": [
                { "name": "A", "medianNewS": 420, "medianFollowUpS": 240, "sigma": 0.4, "arrivalShare": 0.5 },
                { "name": "B", "medianNewS": 500, "medianFollowUpS": 300, "sigma": 0.4, "arrivalShare": 0.5 }
              ],
              "hourEffect": { "12": 1.2 },
              "fatiguePerPatient": 0.004, "lateStartMeanMin": 5,
              "arrivalsPerHour": { "9": 14, "10": 20, "11": 20, "12": 16, "13": 8 },
              "newVisitShare": 0.4, "priorityShare": 0.15, "emergencyShare": 0.02,
              "noShowProbability": 0.1, "qrShare": 0.7
            }
            """;

    private final SimConfig cfg = SimConfig.parse(CONFIG);

    @Test
    void sameSeedGivesTheSameDay() {
        assertThat(DaySimulation.run(cfg, 7)).isEqualTo(DaySimulation.run(cfg, 7));
    }

    @Test
    void differentSeedsGiveDifferentDays() {
        assertThat(DaySimulation.run(cfg, 7)).isNotEqualTo(DaySimulation.run(cfg, 8));
    }

    @Test
    void generatedFilesAreByteIdenticalForTheSameSeed(@TempDir Path dir) throws IOException {
        Path a = dir.resolve("a.csv");
        Path b = dir.resolve("b.csv");
        SimulatorMain.generate(cfg, 5, LocalDate.of(2026, 11, 19), 42, a);
        SimulatorMain.generate(cfg, 5, LocalDate.of(2026, 11, 19), 42, b);
        assertThat(Files.readString(a)).isEqualTo(Files.readString(b));
    }

    @Test
    void aDayDoesNotChangeWhenMoreDaysAreAddedBeforeIt(@TempDir Path dir) throws IOException {
        Path short5 = dir.resolve("s.csv");
        Path long9 = dir.resolve("l.csv");
        SimulatorMain.generate(cfg, 5, LocalDate.of(2026, 11, 19), 42, short5);
        SimulatorMain.generate(cfg, 9, LocalDate.of(2026, 11, 19), 42, long9);
        assertThat(Files.readString(long9))
                .endsWith(Files.readString(short5).substring(SimulatorMain.HEADER.length() + 1));
    }

    @Test
    void timesAreConsistentAndNobodyIsServedTwiceAtOnce() {
        List<SimToken> day = DaySimulation.run(cfg, 11);
        assertThat(day).isNotEmpty();
        for (SimToken t : day) {
            assertThat(t.calledAt()).isGreaterThanOrEqualTo(t.joinedAt());
            if (t.status().equals("COMPLETED")) {
                assertThat(t.consultStartedAt()).isGreaterThan(t.calledAt());
                assertThat(t.completedAt()).isGreaterThan(t.consultStartedAt());
            } else {
                assertThat(t.status()).isEqualTo("NO_SHOW");
                assertThat(t.consultStartedAt()).isEqualTo(-1);
            }
        }
        for (String doctor : List.of("A", "B")) {
            List<SimToken> done = day.stream()
                    .filter(t -> t.doctor().equals(doctor) && t.status().equals("COMPLETED"))
                    .sorted(java.util.Comparator.comparingLong(SimToken::consultStartedAt))
                    .toList();
            for (int i = 1; i < done.size(); i++) {
                assertThat(done.get(i).consultStartedAt())
                        .as("one doctor sees one patient at a time")
                        .isGreaterThanOrEqualTo(done.get(i - 1).completedAt());
            }
        }
    }

    @Test
    void numbersAreUniqueAndGaplessPerDoctor() {
        List<SimToken> day = DaySimulation.run(cfg, 3);
        for (String doctor : List.of("A", "B")) {
            List<Integer> numbers = day.stream()
                    .filter(t -> t.doctor().equals(doctor))
                    .map(SimToken::number)
                    .sorted()
                    .toList();
            for (int i = 0; i < numbers.size(); i++) {
                assertThat(numbers.get(i)).isEqualTo(i + 1);
            }
        }
    }

    @Test
    void anEmergencyIsCalledBeforeEarlierNormalPatients() {
        List<SimToken> all = new java.util.ArrayList<>();
        for (long seed = 0; seed < 30; seed++) {
            all.addAll(DaySimulation.run(cfg, seed));
        }
        // some emergency patient must have been called while a NORMAL patient who joined earlier waited
        boolean jumped = all.stream()
                .filter(e -> e.priority().equals("EMERGENCY"))
                .anyMatch(e -> all.stream()
                        .anyMatch(n -> n.doctor().equals(e.doctor())
                                && n.priority().equals("NORMAL")
                                && n.joinedAt() < e.joinedAt()
                                && n.calledAt() > e.calledAt()
                                && n.joinedAt() < e.calledAt()
                                && n.calledAt() - n.joinedAt() < 86_400
                                && e.calledAt() - e.joinedAt() >= 0
                                && n.calledAt() - e.calledAt() < 7200));
        assertThat(jumped).isTrue();
    }

    @Test
    void badConfigIsRejected() {
        assertThatThrownBy(() ->
                        SimConfig.parse(CONFIG.replace("\"arrivalShare\": 0.5 },\n", "\"arrivalShare\": 0.9 },\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arrivalShare");
        assertThatThrownBy(() -> Json.parse("{\"a\": }")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonReaderHandlesNestingAndEscapes() {
        Object v = Json.parse("{\"a\":[1,2.5,{\"b\":\"x\\ny\"}],\"c\":true,\"d\":null}");
        assertThat(v)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("a", "c", "d");
    }
}
