-- After a load test: every queue day issued numbers 1..N with no gaps and no duplicates (PLAN Phase 8).
-- psql "postgresql://baari:baari@localhost:5432/baari" -f load/verify-numbering.sql
-- Prints one row per day of today; ok must be true for every row.
SELECT q.slug AS queue,
       d.service_date,
       count(t.id) AS tokens,
       max(t.number) AS highest,
       d.last_number,
       count(DISTINCT t.number) = count(t.id)
         AND coalesce(min(t.number), 1) = 1
         AND coalesce(max(t.number), 0) = count(t.id)
         AND d.last_number = count(t.id) AS ok
  FROM queue_day d
  JOIN service_queue q ON q.id = d.queue_id
  LEFT JOIN token t ON t.queue_day_id = d.id
 WHERE d.service_date = (now() AT TIME ZONE 'Asia/Kolkata')::date
 GROUP BY q.slug, d.service_date, d.last_number
 ORDER BY q.slug;
