package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.baari.queue.Priority;
import com.baari.queue.VisitType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ServiceTimeModelTest {

    private static final UUID FAST = new UUID(0, 1);
    private static final UUID SLOW = new UUID(0, 2);

    private static List<ModelTrainer.Candidate> candidates;
    private static List<TrainingExamples.Example> test;

    /** Dr FAST takes about 4 min, Dr SLOW about 10, with some noise; nothing else matters. */
    private static List<TrainingExamples.Example> examples(int n, long seed) {
        Random r = new Random(seed);
        List<TrainingExamples.Example> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean slow = r.nextBoolean();
            int seconds = (int) Math.round((slow ? 600 : 240) * Math.exp(0.2 * r.nextGaussian()));
            ServiceContext ctx = new ServiceContext(
                    slow ? SLOW : FAST, VisitType.NEW, 10, Priority.NORMAL, DayOfWeek.MONDAY, 1 + i % 20, i % 120, 3);
            out.add(new TrainingExamples.Example(ctx, seconds, Instant.EPOCH.plusSeconds(i), LocalDate.EPOCH));
        }
        return out;
    }

    @BeforeAll
    static void train() {
        test = examples(200, 2);
        candidates = ModelTrainer.train(examples(1000, 1), test);
    }

    private static ServiceContext ctx(UUID doctor) {
        return new ServiceContext(doctor, VisitType.NEW, 10, Priority.NORMAL, DayOfWeek.MONDAY, 5, 30, 3);
    }

    @Test
    void bothCandidatesLearnTheDoctorDifference() {
        assertThat(candidates)
                .extracting(c -> c.model().algorithm())
                .containsExactly(ModelTrainer.LINEAR, ModelTrainer.TREE);
        for (ModelTrainer.Candidate c : candidates) {
            Distribution fast = c.model().predict(ctx(FAST));
            Distribution slow = c.model().predict(ctx(SLOW));
            assertThat(fast.meanS()).as(c.model().algorithm()).isBetween(200.0, 290.0);
            assertThat(slow.meanS()).as(c.model().algorithm()).isBetween(520.0, 700.0);
            assertThat(fast.p20S()).isLessThan(fast.meanS());
            assertThat(fast.p80S()).isGreaterThan(fast.meanS());
            assertThat(c.test().n()).isEqualTo(200);
            assertThat(c.test().maeS()).as("noise is about 20%%").isLessThan(120);
        }
    }

    @Test
    void aModelSurvivesTheProtobufRoundTrip() {
        ServiceTimeModel model = candidates.get(1).model();
        ServiceTimeModel back = ServiceTimeModel.load(
                model.algorithm(), model.artifact(), model.smearing(), model.residualP20(), model.residualP80());
        assertThat(back.predict(ctx(SLOW)).meanS())
                .isCloseTo(model.predict(ctx(SLOW)).meanS(), offset(1e-9));
    }

    @Test
    void corruptBytesAreRejectedWithAClearError() {
        assertThatThrownBy(() -> ServiceTimeModel.load("CART", new byte[] {1, 2, 3, 4, 5}, 1.0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a Tribuo model");
    }

    @Test
    void theMlEstimatorFallsBackWhenTheModelCannotAnswer() {
        ServiceTimeModel model = candidates.get(1).model();
        DefaultEstimator fallback = new DefaultEstimator(360);
        MlEstimator ml = new MlEstimator(model, 3, fallback);

        assertThat(ml.name()).isEqualTo("ML:CART:v3");
        assertThat(ml.estimate(ctx(SLOW)).meanS()).isGreaterThan(500);
        // a model that has never seen any real feature cannot answer, so the fallback does
        MlEstimator broken = new MlEstimator(new ServiceTimeModel("X", brokenModel(), 1.0, 0, 0), 1, fallback);
        assertThat(broken.estimate(ctx(FAST)).meanS()).isEqualTo(360);
    }

    /** A Tribuo model trained on a feature no real context has, so every prediction fails. */
    private static org.tribuo.Model<org.tribuo.regression.Regressor> brokenModel() {
        var factory = new org.tribuo.regression.RegressionFactory();
        var data = new org.tribuo.MutableDataset<org.tribuo.regression.Regressor>(
                new org.tribuo.provenance.SimpleDataSourceProvenance("x", factory), factory);
        for (int i = 0; i < 10; i++) {
            var e = new org.tribuo.impl.ArrayExample<>(new org.tribuo.regression.Regressor("y", i));
            e.add("nothing-real", i);
            data.add(e);
        }
        return new org.tribuo.regression.rtree.CARTRegressionTrainer(2).train(data);
    }
}
