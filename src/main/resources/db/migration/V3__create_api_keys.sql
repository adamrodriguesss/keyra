create table api_keys (
    id uuid primary key,
    product_id uuid not null references products (id),
    name varchar(100) not null,
    prefix char(8) not null,
    key_hash varchar(64) not null,
    scopes text[] not null,
    rate_limit_per_minute int not null,
    expires_at timestamptz,
    status varchar(16) not null,
    created_by uuid references managers (id) on delete set null,
    created_at timestamptz not null,
    constraint uk_api_keys_prefix unique (prefix),
    constraint ck_api_keys_status check (status in ('ACTIVE', 'REVOKED', 'EXPIRED'))
);

create index ix_api_keys_product on api_keys (product_id);
