alter table api_keys add column previous_key_hash varchar(64);
alter table api_keys add column previous_valid_until timestamptz;
alter table api_keys add column revoked_at timestamptz;

create table audit_events (
    id uuid primary key,
    actor_id uuid,
    actor_type varchar(16) not null,
    action varchar(16) not null,
    target_type varchar(16) not null,
    target_id uuid not null,
    created_at timestamptz not null
);

create index ix_audit_events_target on audit_events (target_type, target_id);
