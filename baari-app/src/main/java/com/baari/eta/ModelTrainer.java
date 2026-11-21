package com.baari.eta;

import java.util.ArrayList;
import java.util.List;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.Trainer;
import org.tribuo.math.optimisers.AdaGrad;
import org.tribuo.provenance.SimpleDataSourceProvenance;
import org.tribuo.regression.RegressionFactory;
import org.tribuo.regression.Regressor;
import org.tribuo.regression.rtree.CARTRegressionTrainer;
import org.tribuo.regression.sgd.linear.LinearSGDTrainer;
import org.tribuo.regression.sgd.objectives.SquaredLoss;

/**
 * Trains the candidate consultation-length models (PLAN §8.6 step 3) and scores them on held-out
 * consultations (step 4, service-time part). Both learn the log of the length, which tames the long
 * right tail of consultation times. Seeds are fixed, so the same data gives the same models.
 *
 * <p>No Spring, no clock, no database.
 */
public final class ModelTrainer {

    public static final String LINEAR = "LINEAR_SGD";
    public static final String TREE = "CART";

    /** Depth of the regression tree: deep enough for doctor x hour x fatigue, shallow enough to generalise. */
    static final int TREE_DEPTH = 6;

    static final int LINEAR_EPOCHS = 30;
    static final long SEED = 42L;

    private ModelTrainer() {}

    /** Error of consultation-length predictions, in seconds. */
    public record ServiceMetrics(int n, double maeS, double medianAeS, double p90AeS) {}

    /** A trained model with its score on the test consultations. */
    public record Candidate(ServiceTimeModel model, ServiceMetrics test) {}

    /** One candidate per algorithm, in a fixed order (linear first). */
    public static List<Candidate> train(List<TrainingExamples.Example> train, List<TrainingExamples.Example> test) {
        if (train.isEmpty()) {
            throw new IllegalArgumentException("no training examples");
        }
        List<Candidate> out = new ArrayList<>();
        out.add(candidate(
                LINEAR, new LinearSGDTrainer(new SquaredLoss(), new AdaGrad(0.1), LINEAR_EPOCHS, SEED), train, test));
        out.add(candidate(TREE, new CARTRegressionTrainer(TREE_DEPTH), train, test));
        return List.copyOf(out);
    }

    private static Candidate candidate(
            String name,
            Trainer<Regressor> trainer,
            List<TrainingExamples.Example> train,
            List<TrainingExamples.Example> test) {
        RegressionFactory factory = new RegressionFactory();
        MutableDataset<Regressor> data =
                new MutableDataset<>(new SimpleDataSourceProvenance("baari-consultations", factory), factory);
        for (TrainingExamples.Example e : train) {
            data.add(ServiceTimeModel.example(
                    e.context(), new Regressor(ServiceTimeModel.TARGET, Math.log(e.serviceSeconds()))));
        }
        Model<Regressor> model = trainer.train(data);

        // learn the error spread on the training data: smearing factor and p20/p80 of the log errors
        ServiceTimeModel raw = new ServiceTimeModel(name, model, 1.0, 0.0, 0.0);
        List<Double> residuals = new ArrayList<>(train.size());
        double sumExp = 0;
        for (TrainingExamples.Example e : train) {
            double r = Math.log(e.serviceSeconds()) - raw.predictLog(e.context());
            residuals.add(r);
            sumExp += Math.exp(r);
        }
        List<Double> sorted = residuals.stream().sorted().toList();
        ServiceTimeModel fitted = new ServiceTimeModel(
                name,
                model,
                sumExp / train.size(),
                RollingStatsEstimator.quantile(sorted, 0.2),
                RollingStatsEstimator.quantile(sorted, 0.8));
        return new Candidate(fitted, score(fitted, test));
    }

    /** Mean, median and p90 absolute error of the predicted mean, on the given consultations. */
    public static ServiceMetrics score(ServiceTimeModel model, List<TrainingExamples.Example> examples) {
        return score(examples.stream()
                .map(e -> Math.abs(model.predict(e.context()).meanS() - e.serviceSeconds()))
                .toList());
    }

    /** The same metrics for any estimator, so Rolling can be scored on the same consultations. */
    public static ServiceMetrics score(ServiceTimeEstimator estimator, List<TrainingExamples.Example> examples) {
        return score(examples.stream()
                .map(e -> Math.abs(estimator.estimate(e.context()).meanS() - e.serviceSeconds()))
                .toList());
    }

    private static ServiceMetrics score(List<Double> absErrors) {
        if (absErrors.isEmpty()) {
            return new ServiceMetrics(0, Double.NaN, Double.NaN, Double.NaN);
        }
        List<Double> sorted = absErrors.stream().sorted().toList();
        double mae = sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return new ServiceMetrics(
                sorted.size(),
                mae,
                RollingStatsEstimator.quantile(sorted, 0.5),
                RollingStatsEstimator.quantile(sorted, 0.9));
    }
}
