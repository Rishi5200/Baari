-- Queue core (PLAN §7, Phase 2): one row per queue per service date, the tokens in it, an
-- append-only event log, and the transactional outbox (written now, relayed to Kafka in Phase 5).

CREATE TABLE queue_day (
  id uuid PRIMARY KEY,
  queue_id uuid NOT NULL REFERENCES service_queue(id),
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  service_date date NOT NULL,
  status text NOT NULL CHECK (status IN ('OPEN','PAUSED','CLOSED')),
  last_number int NOT NULL DEFAULT 0,             -- token counter; row lock = issuance lock (ADR 0003)
  last_event_seq bigint NOT NULL DEFAULT 0,       -- per-day event sequence for SSE ordering
  consecutive_priority_served int NOT NULL DEFAULT 0,
  opened_at timestamptz NOT NULL,
  closed_at timestamptz,
  UNIQUE (queue_id, service_date)
);

CREATE TABLE token (
  id uuid PRIMARY KEY,
  queue_day_id uuid NOT NULL REFERENCES queue_day(id),
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  number int NOT NULL,
  sort_key bigint NOT NULL,
  public_code text NOT NULL UNIQUE,
  patient_name text,                    -- nullable: purged after retention
  phone_enc bytea,                      -- AES-GCM ciphertext; nullable
  phone_hash bytea,                     -- HMAC-SHA256(pepper, E.164); nullable
  phone_last4 text,
  visit_type text NOT NULL CHECK (visit_type IN ('NEW','FOLLOW_UP')),
  priority text NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL','PRIORITY','EMERGENCY')),
  status text NOT NULL CHECK (status IN
    ('WAITING','CALLED','IN_CONSULTATION','COMPLETED','NO_SHOW','CANCELLED','EXPIRED')),
  source text NOT NULL CHECK (source IN ('QR','RECEPTION')),
  consent_version text,                 -- privacy notice version accepted (null for reception walk-ins)
  idempotency_key uuid,
  joined_at timestamptz NOT NULL,
  called_at timestamptz,
  consult_started_at timestamptz,
  completed_at timestamptz,
  cancelled_at timestamptz,
  served_by_doctor_id uuid REFERENCES doctor(id),
  flags text[] NOT NULL DEFAULT '{}',   -- TokenFlag names: OVERRIDE_CALL, DIRECT_START, FORCED_NO_SHOW, AUTO_CLOSED
  pii_purged_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (queue_day_id, number),
  UNIQUE (queue_day_id, idempotency_key)
);
CREATE UNIQUE INDEX token_one_active_per_phone
  ON token (queue_day_id, phone_hash)
  WHERE phone_hash IS NOT NULL AND status IN ('WAITING','CALLED','IN_CONSULTATION','NO_SHOW');
CREATE INDEX token_queue_status ON token (queue_day_id, status, sort_key);

-- token_id is nullable (PLAN §7 has NOT NULL): day-level events (opened, paused, resumed, closed)
-- take a seq too, and recording them here keeps the per-day sequence gap-free and the audit whole.
CREATE TABLE token_event (
  id uuid PRIMARY KEY,
  token_id uuid REFERENCES token(id),
  queue_day_id uuid NOT NULL REFERENCES queue_day(id),
  seq bigint NOT NULL,
  type text NOT NULL,
  actor_type text NOT NULL CHECK (actor_type IN ('PATIENT','STAFF','SYSTEM')),
  actor_id uuid,
  at timestamptz NOT NULL,
  data jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (queue_day_id, seq)
);
CREATE INDEX token_event_token ON token_event (token_id);

CREATE TABLE outbox_event (
  id uuid PRIMARY KEY,                  -- = the domain event's eventId, so consumers can dedupe on it
  aggregate_type text NOT NULL,
  aggregate_id uuid NOT NULL,
  partition_key text NOT NULL,          -- queue_day_id → per-queue ordering in Kafka
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  published_at timestamptz,
  attempts int NOT NULL DEFAULT 0,
  last_error text
);
CREATE INDEX outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;
