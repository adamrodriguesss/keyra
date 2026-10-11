create table products (
    id uuid primary key,
    name varchar(100) not null,
    slug varchar(64) not null,
    description varchar(500),
    active boolean not null,
    created_at timestamptz not null,
    constraint uk_products_slug unique (slug)
);

create table product_managers (
    product_id uuid not null references products (id),
    manager_id uuid not null references managers (id) on delete cascade,
    primary key (product_id, manager_id)
);

create index ix_product_managers_manager on product_managers (manager_id);
