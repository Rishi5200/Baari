-- Loads simulated history (baari-simulator tokens.csv) into an existing clinic, so the Rolling
-- estimator and the analytics have data to work with. Plain SQL: the psql wrapper
-- (import-history.psql) fills the two temporary tables, and ImportHistoryIT runs this same file.
--
-- Expects, in the same session:
--   sim_settings(clinic_slug text)                one row: the clinic to load into
--   sim_token(service_date, doctor, number, ...)  the CSV rows, columns as the simulator writes them
--
-- Rules:
--   * the CSV's doctor column must match a doctor's name in that clinic (else the script stops);
--   * each doctor's tokens go to the queue only that doctor serves (SINGLE), else the first queue
--     (by slug) that includes the doctor;
--   * a (queue, date) that already has a queue day is skipped entirely, so the script can be rerun;
--   * only days before today (clinic time) are loaded, as CLOSED days;
--   * no names, phones or token events: history only.

DO $$
DECLARE
  missing text;
BEGIN
  IF (SELECT count(*) FROM sim_settings s JOIN clinic c ON c.slug = s.clinic_slug) <> 1 THEN
    RAISE EXCEPTION 'clinic % not found', (SELECT clinic_slug FROM sim_settings LIMIT 1);
  END IF;
  SELECT string_agg(DISTINCT t.doctor, ', ') INTO missing
    FROM sim_token t
   WHERE NOT EXISTS (SELECT 1 FROM doctor d JOIN clinic c ON c.id = d.clinic_id
                       JOIN sim_settings s ON s.clinic_slug = c.slug
                      WHERE d.name = t.doctor);
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'no doctor named % in this clinic', missing;
  END IF;
END $$;

CREATE TEMP TABLE sim_target ON COMMIT DROP AS
SELECT d.name AS doctor, d.id AS doctor_id, c.id AS clinic_id, c.timezone,
       (SELECT q.id
          FROM service_queue q JOIN queue_doctor qd ON qd.queue_id = q.id
         WHERE qd.doctor_id = d.id
         ORDER BY (q.mode = 'SINGLE' AND (SELECT count(*) FROM queue_doctor x WHERE x.queue_id = q.id) = 1) DESC,
                  q.slug
         LIMIT 1) AS queue_id
  FROM doctor d
  JOIN clinic c ON c.id = d.clinic_id
  JOIN sim_settings s ON s.clinic_slug = c.slug;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM sim_target t WHERE t.queue_id IS NULL
               AND t.doctor IN (SELECT doctor FROM sim_token)) THEN
    RAISE EXCEPTION 'a doctor in the CSV serves no queue';
  END IF;
END $$;

-- one new CLOSED queue day per (queue, date) that does not exist yet
CREATE TEMP TABLE sim_day ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, g.queue_id, g.clinic_id, g.service_date,
       g.last_number, g.opened_at, g.closed_at
  FROM (SELECT t.queue_id, t.clinic_id, s.service_date,
               max(s.number) AS last_number,
               min(s.joined_at) AS opened_at,
               max(coalesce(s.completed_at, s.called_at)) AS closed_at
          FROM sim_token s JOIN sim_target t ON t.doctor = s.doctor
         WHERE s.service_date < (now() AT TIME ZONE t.timezone)::date
         GROUP BY t.queue_id, t.clinic_id, s.service_date) g
 WHERE NOT EXISTS (SELECT 1 FROM queue_day q WHERE q.queue_id = g.queue_id AND q.service_date = g.service_date);

INSERT INTO queue_day (id, queue_id, clinic_id, service_date, status, last_number, last_event_seq,
                       consecutive_priority_served, opened_at, closed_at)
SELECT id, queue_id, clinic_id, service_date, 'CLOSED', last_number, 0, 0, opened_at, closed_at
  FROM sim_day;

INSERT INTO token (id, queue_day_id, clinic_id, number, sort_key, public_code, visit_type, priority, status,
                   source, joined_at, called_at, consult_started_at, completed_at, served_by_doctor_id)
SELECT gen_random_uuid(), d.id, d.clinic_id, s.number, s.number * 1000,
       -- 22 characters like a real public code (hex is a subset of base62); never shown for history
       left(md5(gen_random_uuid()::text) || md5(gen_random_uuid()::text), 22),
       s.visit_type, s.priority, s.status, s.source, s.joined_at, s.called_at, s.consult_started_at,
       s.completed_at, CASE WHEN s.status = 'COMPLETED' THEN t.doctor_id END
  FROM sim_token s
  JOIN sim_target t ON t.doctor = s.doctor
  JOIN sim_day d ON d.queue_id = t.queue_id AND d.service_date = s.service_date;
