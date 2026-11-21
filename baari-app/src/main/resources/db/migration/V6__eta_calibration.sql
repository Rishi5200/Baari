-- Nightly range calibration per clinic (PLAN §8.5, ADR 0012). One row per clinic; a clinic without
-- a row uses factor 1.0 (the projector's range unchanged).
CREATE TABLE eta_calibration (
  clinic_id uuid PRIMARY KEY REFERENCES clinic(id),
  factor numeric(3,1) NOT NULL CHECK (factor BETWEEN 1.0 AND 2.5),
  coverage double precision NOT NULL,   -- share of scored predictions inside their range, last 14 days
  predictions int NOT NULL,             -- how many predictions that share was measured on
  updated_on date NOT NULL              -- UTC date of the run; at most one step per clinic per day
);
