-- Trained consultation-length models per clinic (PLAN §8.6, ADR 0013). Every training run stores
-- its candidates, promoted or not, with their metrics, so the accuracy page can show the history.
CREATE TABLE ml_model (
  id uuid PRIMARY KEY,
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  version int NOT NULL,                       -- 1, 2, 3 ... per clinic
  algorithm text NOT NULL,                    -- LINEAR_SGD, CART
  status text NOT NULL CHECK (status IN ('CHAMPION','REJECTED','RETIRED')),
  trained_at timestamptz NOT NULL,
  train_from date NOT NULL,
  train_to date NOT NULL,
  test_from date NOT NULL,
  test_to date NOT NULL,
  training_samples int NOT NULL,
  metrics jsonb NOT NULL,                     -- service-time and wait-time errors, the baseline it faced
  smearing double precision NOT NULL,         -- see ServiceTimeModel
  residual_p20 double precision NOT NULL,
  residual_p80 double precision NOT NULL,
  artifact bytea NOT NULL,                    -- Tribuo protobuf, never Java serialization
  UNIQUE (clinic_id, version)
);
-- At most one champion per clinic, whatever two instances training at once might try.
CREATE UNIQUE INDEX ml_model_one_champion ON ml_model (clinic_id) WHERE status = 'CHAMPION';
