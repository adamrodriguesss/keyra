alter table products add column user_keys_enabled boolean not null default false;
alter table products add column default_user_scopes text[] not null default '{}';
alter table products add column max_keys_per_user int not null default 10;

alter table api_keys add column owner_user_id uuid references users (id);

create index ix_api_keys_product_owner on api_keys (product_id, owner_user_id);
