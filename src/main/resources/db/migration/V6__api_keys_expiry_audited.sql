-- Set once a key's derived expiry has been written to audit_events, so it is audited exactly once.
alter table api_keys add column expiry_audited boolean not null default false;
