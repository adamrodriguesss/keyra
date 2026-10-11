-- EXPIRED is derived from expires_at, never stored.
alter table api_keys drop constraint ck_api_keys_status;
alter table api_keys add constraint ck_api_keys_status check (status in ('ACTIVE', 'REVOKED'));
