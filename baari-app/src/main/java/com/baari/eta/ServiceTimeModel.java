package com.baari.eta;

import java.util.Map;
import java.util.Objects;
import org.tribuo.Model;
import org.tribuo.impl.ArrayExample;
import org.tribuo.protos.core.ModelProto;
import org.tribuo.regression.RegressionFactory;
import org.tribuo.regression.Regressor;

/**
 * A trained consultation-length model (PLAN §8.6). The Tribuo model predicts the natural log of the
 * length; three numbers learned from its training errors turn that into a {@link Distribution}:
 *
 * <ul>
 *   <li>{@code smearing}: the mean of e^error, because e^(mean of log) is the median, not the mean
 *       (Duan's smearing estimate);
 *   <li>{@code residualP20} and {@code residualP80}: the 20th and 80th percentiles of the log errors,
 *       so the range is p20 = e^(prediction + residualP20), p80 likewise.
 * </ul>
 *
 * Tribuo stays behind this class: the rest of the app sees contexts and distributions only.
 */
public final class ServiceTimeModel {

    private static final RegressionFactory FACTORY = new RegressionFactory();
    static final String TARGET = "logServiceSeconds";

    private final String algorithm;
    private final Model<Regressor> model;
    private final double smearing;
    private final double residualP20;
    private final double residualP80;

    public ServiceTimeModel(
            String algorithm, Model<Regressor> model, double smearing, double residualP20, double residualP80) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.model = Objects.requireNonNull(model, "model");
        if (!(smearing > 0) || residualP20 > residualP80) {
            throw new IllegalArgumentException("need smearing > 0 and residualP20 <= residualP80");
        }
        this.smearing = smearing;
        this.residualP20 = residualP20;
        this.residualP80 = residualP80;
    }

    public String algorithm() {
        return algorithm;
    }

    public double smearing() {
        return smearing;
    }

    public double residualP20() {
        return residualP20;
    }

    public double residualP80() {
        return residualP80;
    }

    /** The model's raw output: predicted log of the consultation length. */
    double predictLog(ServiceContext ctx) {
        ArrayExample<Regressor> example = example(ctx, FACTORY.getUnknownOutput());
        return model.predict(example).getOutput().getValues()[0];
    }

    /** @throws IllegalArgumentException if none of the context's features was seen in training */
    public Distribution predict(ServiceContext ctx) {
        double log = predictLog(ctx);
        double median = Math.exp(log);
        return new Distribution(median * smearing, Math.exp(log + residualP20), Math.exp(log + residualP80));
    }

    /** The Tribuo model in its protobuf form (never Java serialization, PLAN §8.6). */
    public byte[] artifact() {
        return model.serialize().toByteArray();
    }

    /** Rebuilds a model saved with {@link #artifact()}; throws if the bytes are not a regression model. */
    @SuppressWarnings("unchecked")
    public static ServiceTimeModel load(
            String algorithm, byte[] artifact, double smearing, double residualP20, double residualP80) {
        Model<?> raw;
        try {
            raw = Model.deserialize(ModelProto.parseFrom(artifact));
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("model artifact is not a Tribuo model: " + e.getMessage(), e);
        }
        if (!raw.validate(Regressor.class)) {
            throw new IllegalArgumentException("model artifact is not a regression model");
        }
        return new ServiceTimeModel(algorithm, (Model<Regressor>) raw, smearing, residualP20, residualP80);
    }

    static ArrayExample<Regressor> example(ServiceContext ctx, Regressor output) {
        ArrayExample<Regressor> example = new ArrayExample<>(output);
        for (Map.Entry<String, Double> f : ServiceFeatures.of(ctx).entrySet()) {
            example.add(f.getKey(), f.getValue());
        }
        return example;
    }
}
