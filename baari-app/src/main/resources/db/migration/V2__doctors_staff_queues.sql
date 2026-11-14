CREATE TABLE doctor (
  id uuid PRIMARY KEY,
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  name text NOT NULL,
  room_label text,
  active boolean NOT NULL DEFAULT true
);
CREATE INDEX ON doctor (clinic_id);

CREATE TABLE staff_user (
  id uuid PRIMARY KEY,
  clinic_id uuid REFERENCES clinic(id),          -- null only for PLATFORM_ADMIN
  username text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  role text NOT NULL CHECK (role IN ('PLATFORM_ADMIN','CLINIC_ADMIN','DOCTOR','RECEPTIONIST')),
  doctor_id uuid REFERENCES doctor(id),
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL
);

CREATE TABLE service_queue (
  id uuid PRIMARY KEY,
  clinic_id uuid NOT NULL REFERENCES clinic(id),
  slug text NOT NULL,
  name text NOT NULL,
  mode text NOT NULL CHECK (mode IN ('SINGLE','POOL')),
  qr_mode text NOT NULL DEFAULT 'STATIC' CHECK (qr_mode IN ('STATIC','ROTATING')),
  display_code_prefix text NOT NULL DEFAULT 'A',
  display_key_hash text NOT NULL,                  -- for TV display access
  qr_secret bytea NOT NULL,                        -- HMAC key for rotating QR
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (clinic_id, slug)
);

CREATE TABLE queue_doctor (
  queue_id uuid NOT NULL REFERENCES service_queue(id),
  doctor_id uuid NOT NULL REFERENCES doctor(id),
  PRIMARY KEY (queue_id, doctor_id)
);
