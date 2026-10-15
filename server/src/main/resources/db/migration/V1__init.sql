create table app_user (
  id uuid primary key,
  username text not null unique,          -- stored lowercased and trimmed
  password_hash text not null,
  created_at timestamptz not null default now()
);
create table device (
  user_id uuid not null references app_user(id),
  device_id text not null,
  name text not null,
  last_seen timestamptz not null default now(),
  primary key (user_id, device_id)
);
create table refresh_token (
  token_hash bytea primary key,           -- sha256 of the token
  user_id uuid not null references app_user(id),
  device_id text not null,
  expires_at timestamptz not null,
  used_at timestamptz,                    -- set when rotated
  revoked_at timestamptz
);
create sequence sync_version;
create table sync_record (
  user_id uuid not null references app_user(id),
  entity text not null,
  id text not null,
  payload jsonb not null,
  updated_at bigint not null,
  deleted boolean not null,
  device_id text not null,
  version bigint not null,
  primary key (user_id, entity, id)
);
create index sync_record_user_version on sync_record (user_id, version);
