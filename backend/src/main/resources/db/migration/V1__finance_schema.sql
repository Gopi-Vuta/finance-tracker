create table app_users (
 id varchar(36) primary key,
 google_subject varchar(255) not null unique,
 email varchar(320) not null,
 name varchar(100) not null,
 timezone varchar(100) not null default 'Asia/Kolkata',
 revision bigint not null default 0,
 recurring_json text not null,
 reminders_json text not null,
 created_at timestamp with time zone not null default current_timestamp
);
create table monthly_records (
 user_id varchar(36) not null references app_users(id) on delete cascade,
 month_key varchar(7) not null,
 document_json text not null,
 primary key(user_id, month_key)
);
create table families (
 id varchar(36) primary key,
 name varchar(100) not null,
 created_by varchar(36) not null references app_users(id),
 created_at timestamp with time zone not null default current_timestamp
);
create table family_members (
 user_id varchar(36) primary key references app_users(id) on delete cascade,
 family_id varchar(36) not null references families(id) on delete cascade,
 joined_at timestamp with time zone not null default current_timestamp
);
create index family_members_family on family_members(family_id);
create table family_invites (
 token_hash varchar(64) primary key,
 family_id varchar(36) not null references families(id) on delete cascade,
 invited_email varchar(320) not null,
 expires_at timestamp with time zone not null,
 used_at timestamp with time zone,
 created_by varchar(36) not null references app_users(id)
);
create table reminder_deliveries (
 user_id varchar(36) not null references app_users(id) on delete cascade,
 schedule_id varchar(100) not null,
 month_key varchar(7) not null,
 status varchar(20) not null,
 attempts integer not null default 0,
 next_attempt timestamp with time zone not null,
 sent_at timestamp with time zone,
 primary key(user_id,schedule_id,month_key)
);
