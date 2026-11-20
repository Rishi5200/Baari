-- One row per prediction we log (PLAN §7, §8.7): at JOIN and when the patient's position first
-- reaches 10, 5, 3 and 1. actual_wait_s is filled in when the consultation starts, so the table
-- is the record of how good the predictions were.
CREATE TABLE eta_prediction (
  id uuid PRIMARY KEY,
  token_id uuid NOT NULL REFERENCES token(id),
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  predicted_at timestamptz NOT NULL,
  trigger text NOT NULL CHECK (trigger IN ('JOIN','POSITION_10','POSITION_5','POSITION_3','POSITION_1')),
  position int NOT NULL,
  predicted_wait_s int NOT NULL,
  range_min_s int NOT NULL,
  range_max_s int NOT NULL,
  estimator text NOT NULL,              -- DEFAULT, ROLLING, later ML:<version>
  actual_wait_s int,                    -- null until the consultation starts
  -- A token is logged at most once per trigger, however many instances or retries see the change
  -- (the insert uses ON CONFLICT DO NOTHING).
  UNIQUE (token_id, trigger)
);
CREATE INDEX eta_prediction_clinic_time ON eta_prediction (clinic_id, predicted_at);
