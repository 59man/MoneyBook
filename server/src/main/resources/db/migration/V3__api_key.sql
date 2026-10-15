-- Single-user server with one API key from the environment: no passwords any more.
alter table app_user alter column password_hash drop not null;
-- sha256 (hex) of the API key the server last started with; a different key signs every device out.
alter table server_meta add column key_hash text;
