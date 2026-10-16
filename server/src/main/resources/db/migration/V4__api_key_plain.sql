-- The page shows the key, so it is stored in plain text. key_hash stays: a changed key still ends every session.
alter table server_meta add column api_key text;
