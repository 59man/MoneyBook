-- One row. restore.sh replaces the epoch after a pg_restore, so clients holding a cursor from before the restore notice.
create table server_meta (
  epoch uuid not null
);
insert into server_meta (epoch) values (gen_random_uuid());
