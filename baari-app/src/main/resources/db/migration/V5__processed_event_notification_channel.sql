-- Phase 5 (PLAN §7, §11): consumer-side dedup, notifications and the Telegram link.

-- One row per (consumer, event): inserting it is how a consumer claims an event, so a redelivered
-- event is recognised and skipped. The row is written in the same transaction as the side effects.
CREATE TABLE processed_event (
  consumer text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL,
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE notification (
  id uuid PRIMARY KEY,
  token_id uuid NOT NULL REFERENCES token(id),
  kind text NOT NULL,                   -- NEAR_TURN, CALLED, QUEUE_CLOSED
  channel text NOT NULL,                -- LOG, TELEGRAM, SMS
  status text NOT NULL CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
  attempts int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  last_error text,
  created_at timestamptz NOT NULL,
  sent_at timestamptz,
  UNIQUE (token_id, kind, channel)      -- idempotent planning
);
CREATE INDEX notification_due ON notification (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE patient_channel (          -- Telegram link per token (ephemeral, purged with PII)
  token_id uuid PRIMARY KEY REFERENCES token(id),
  telegram_chat_id_enc bytea NOT NULL,
  linked_at timestamptz NOT NULL
);
