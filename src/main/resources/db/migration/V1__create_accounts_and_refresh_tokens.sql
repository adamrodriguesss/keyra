create table users (
    id uuid primary key,
    email varchar(254) not null,
    password_hash varchar(100) not null,
    status varchar(16) not null,
    created_at timestamptz not null,
    constraint uk_users_email unique (email),
    constraint ck_users_status check (status in ('ACTIVE', 'DISABLED'))
);

create table managers (
    id uuid primary key,
    email varchar(254) not null,
    password_hash varchar(100) not null,
    role varchar(16) not null,
    status varchar(16) not null,
    created_at timestamptz not null,
    constraint uk_managers_email unique (email),
    constraint ck_managers_role check (role in ('ADMIN', 'MANAGER')),
    constraint ck_managers_status check (status in ('ACTIVE', 'DISABLED'))
);

create table refresh_tokens (
    id uuid primary key,
    subject_id uuid not null,
    subject_type varchar(16) not null,
    token_hash varchar(64) not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null,
    constraint uk_refresh_tokens_token_hash unique (token_hash),
    constraint ck_refresh_tokens_subject_type check (subject_type in ('USER', 'MANAGER'))
);

create index ix_refresh_tokens_subject on refresh_tokens (subject_type, subject_id);
