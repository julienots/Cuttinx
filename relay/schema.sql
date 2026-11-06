-- File de commandes entre Claude (MCP) et le téléphone. Accès réservé aux Edge Functions (service role).
create table public.vs_devices (
  token_hash text primary key,
  last_seen timestamptz not null default now(),
  info jsonb not null default '{}'::jsonb
);

create table public.vs_commands (
  id uuid primary key default gen_random_uuid(),
  token_hash text not null,
  tool text not null,
  args jsonb not null default '{}'::jsonb,
  status text not null default 'pending' check (status in ('pending','taken','done','error')),
  result jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index vs_commands_pending on public.vs_commands (token_hash, created_at) where status = 'pending';
create index vs_commands_created on public.vs_commands (created_at);

alter table public.vs_devices enable row level security;
alter table public.vs_commands enable row level security;

create or replace function public.vs_claim(p_token_hash text)
returns setof public.vs_commands
language sql
security definer
set search_path = public
as $$
  update public.vs_commands c
     set status = 'taken', updated_at = now()
   where c.id = (
     select id from public.vs_commands
      where token_hash = p_token_hash and status = 'pending'
        and created_at > now() - interval '3 minutes'
      order by created_at
      for update skip locked
      limit 1)
  returning c.*;
$$;
revoke all on function public.vs_claim(text) from public, anon, authenticated;
grant execute on function public.vs_claim(text) to service_role;
