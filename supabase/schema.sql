-- ════════════════════════════════════════════════════════════════
-- Baracuda — Hafta 1 Supabase şeması
-- Kapsam: profiles, daily_snapshot, intake_entry, lab_result,
--         clinical_flag, twin_output_log
-- Tüm tablolarda Row Level Security açık: her kullanıcı yalnızca
-- kendi user_id = auth.uid() satırlarını görür/yazar.
-- Uygula: supabase db push  (veya SQL Editor'e yapıştır)
-- ════════════════════════════════════════════════════════════════

create extension if not exists "pgcrypto"; -- gen_random_uuid() için

-- ────────────────────────────────────────────────────────────
-- Ortak trigger: updated_at otomatik güncelleme
-- ────────────────────────────────────────────────────────────
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

-- ────────────────────────────────────────────────────────────
-- 1) profiles — sabit profil (Bölüm A, system_twin.md ile birebir)
-- ────────────────────────────────────────────────────────────
create table if not exists public.profiles (
    id                  uuid primary key references auth.users(id) on delete cascade,
    full_name           text not null default 'Aydın Kırmızıoğlu',
    birth_year          int,
    sex                 text default 'male',
    height_cm           numeric default 180,
    timezone            text not null default 'Europe/Vilnius',
    water_target_ml     int not null default 4000,
    protein_target_min_g int not null default 140,
    protein_target_max_g int not null default 170,
    wake_target         time not null default '07:00',
    bed_earliest        time not null default '23:00',
    -- Hafta 45: su/protein/kalkış hedefi gibi Ayarlar'dan düzenlenebilir hale
    -- getirildi — önceden yalnızca DashboardScreen'de sabit 10_000 olarak
    -- kod içine gömülüydü, system_twin.md Bölüm A "10.000 adım hedefi"nden
    -- söz etse de bu tek kullanıcı için bile değiştirilemiyordu.
    steps_target        int not null default 10000,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

-- `create table if not exists` yeni bir kuruluma yeter ama bu proje Hafta
-- 1'den beri canlı — `profiles` tablosu zaten var, yukarıdaki tanım bu
-- kurulumda no-op'tur. Kolonu zaten dağıtılmış veritabanına da eklemek için
-- ayrı bir `alter table` gerekir (bu şemadaki ilk kolon-ekleme örneği; body_metric/
-- quick_template/lab_result_template gibi öncekiler hep YENİ tablolardı).
alter table public.profiles add column if not exists steps_target int not null default 10000;

alter table public.profiles enable row level security;

create policy "profiles_self_select" on public.profiles
    for select using (auth.uid() = id);
create policy "profiles_self_upsert" on public.profiles
    for insert with check (auth.uid() = id);
create policy "profiles_self_update" on public.profiles
    for update using (auth.uid() = id);

drop trigger if exists trg_profiles_updated_at on public.profiles;
create trigger trg_profiles_updated_at
    before update on public.profiles
    for each row execute function public.set_updated_at();

-- ────────────────────────────────────────────────────────────
-- 2) daily_snapshot — gecelik uyku/SpO2 özeti (Health Connect kaynaklı)
--    com.aydin.biyohack.data.DailySnapshot ile alan alan eşleşir.
-- ────────────────────────────────────────────────────────────
create table if not exists public.daily_snapshot (
    id                          uuid primary key default gen_random_uuid(),
    user_id                     uuid not null references auth.users(id) on delete cascade,
    date                        date not null,
    asleep_min                  int,
    time_in_bed_min             int,
    efficiency_pct              int,
    sleep_score                 int,
    rem_pct                     int,
    deep_pct                    int,
    awake_min                   int,
    bed_time                    timestamptz,
    wake_time                   timestamptz,
    spo2_avg                    numeric(4,1),
    minutes_below_90            int,
    minutes_below_90_is_estimate boolean not null default false,
    snoring_min                 int,
    source                      text not null default 'HEALTH_CONNECT'
                                 check (source in ('HEALTH_CONNECT','SAMSUNG_HEALTH','MANUAL')),
    created_at                  timestamptz not null default now(),
    updated_at                  timestamptz not null default now(),
    unique (user_id, date)
);

alter table public.daily_snapshot enable row level security;

create policy "daily_snapshot_owner_all" on public.daily_snapshot
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists idx_daily_snapshot_user_date
    on public.daily_snapshot (user_id, date desc);

drop trigger if exists trg_daily_snapshot_updated_at on public.daily_snapshot;
create trigger trg_daily_snapshot_updated_at
    before update on public.daily_snapshot
    for each row execute function public.set_updated_at();

-- ────────────────────────────────────────────────────────────
-- 3) intake_entry — öğün / kahve / su / takviye logları
--    com.aydin.biyohack.twin.IntakeEntry ile aynı domain'i besler.
-- ────────────────────────────────────────────────────────────
create table if not exists public.intake_entry (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references auth.users(id) on delete cascade,
    ts          timestamptz not null,
    type        text not null check (type in ('MEAL','COFFEE','WATER','SUPPLEMENT')),
    label       text not null,
    amount      numeric,
    unit        text,
    created_at  timestamptz not null default now()
);

alter table public.intake_entry enable row level security;

create policy "intake_entry_owner_all" on public.intake_entry
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists idx_intake_entry_user_ts
    on public.intake_entry (user_id, ts desc);

-- ────────────────────────────────────────────────────────────
-- 4) lab_result — Šeškinės poliklinika laboratuvar seyri
--    (system_twin.md Bölüm B'deki tüm panelleri tekil satır olarak tutar)
-- ────────────────────────────────────────────────────────────
create table if not exists public.lab_result (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references auth.users(id) on delete cascade,
    panel       text not null,       -- 'BÖBREK' | 'HEMATOLOJİ' | 'LİPİD' | 'METABOLİK' | ...
    marker      text not null,       -- 'eGFR', 'Kreatinin', 'Hematokrit', ...
    value       numeric not null,
    unit        text,
    ref_low     numeric,
    ref_high    numeric,
    taken_at    date not null,
    source_lab  text default 'Šeškinės poliklinika',
    notes       text,
    created_at  timestamptz not null default now()
);

alter table public.lab_result enable row level security;

create policy "lab_result_owner_all" on public.lab_result
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists idx_lab_result_user_marker_date
    on public.lab_result (user_id, marker, taken_at desc);

-- ────────────────────────────────────────────────────────────
-- 5) clinical_flag — kırmızı bölge bayrakları (TwinGuardrails çıktısı)
-- ────────────────────────────────────────────────────────────
create table if not exists public.clinical_flag (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references auth.users(id) on delete cascade,
    finding     text not null,
    status      text not null,
    action      text not null default 'none',
    raised_at   timestamptz not null default now(),
    resolved    boolean not null default false
);

alter table public.clinical_flag enable row level security;

create policy "clinical_flag_owner_all" on public.clinical_flag
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists idx_clinical_flag_user_resolved
    on public.clinical_flag (user_id, resolved);

-- ────────────────────────────────────────────────────────────
-- 6) twin_output_log — TwinEngine / supabase/functions/twin çıktı denetimi
--    (her çağrının ham JSON'ı + kırmızı bölge filtresi ihlalleri arşivlenir)
-- ────────────────────────────────────────────────────────────
create table if not exists public.twin_output_log (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references auth.users(id) on delete cascade,
    trigger     text not null,
    tier        text not null,
    headline    text,
    brief       text,
    raw_json    jsonb not null,
    violations  jsonb not null default '[]'::jsonb,
    created_at  timestamptz not null default now()
);

alter table public.twin_output_log enable row level security;

create policy "twin_output_log_owner_all" on public.twin_output_log
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists idx_twin_output_log_user_created
    on public.twin_output_log (user_id, created_at desc);

-- ────────────────────────────────────────────────────────────
-- 7) body_metric — kilo/bel çevresi seyri (Hafta 11).
--    system_twin.md Bölüm A "Kilo seyri: 118 → 84 kg" burada devam ediyor —
--    22 aylık geçmiş statik metinde, güncel ölçüm burada.
-- ────────────────────────────────────────────────────────────
create table if not exists public.body_metric (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references auth.users(id) on delete cascade,
    date        date not null,
    weight_kg   numeric,
    waist_cm    numeric,
    notes       text,
    created_at  timestamptz not null default now(),
    unique (user_id, date)
);

alter table public.body_metric enable row level security;

create policy "body_metric_owner_all" on public.body_metric
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists idx_body_metric_user_date
    on public.body_metric (user_id, date desc);

-- ────────────────────────────────────────────────────────────
-- 8) quick_template — Hızlı Şablonlar ve Favoriler (Hafta 41).
--    Kullanıcının sık tükettiği kombinasyonları ("Standart Sabah Kahvesi"
--    vb.) tek dokunuşla tekrar loglayabilmesi için kaydettiği kalıplar.
--    Kendisi bir intake_entry değildir — LogScreen'de dokunulduğunda ayrı
--    bir intake_entry satırı üretir.
-- ────────────────────────────────────────────────────────────
create table if not exists public.quick_template (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references auth.users(id) on delete cascade,
    kind        text not null check (kind in ('MEAL','COFFEE','WATER','SUPPLEMENT')),
    label       text not null,
    amount      numeric,
    unit        text,
    created_at  timestamptz not null default now()
);

alter table public.quick_template enable row level security;

create policy "quick_template_owner_all" on public.quick_template
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists idx_quick_template_user_created
    on public.quick_template (user_id, created_at desc);

-- ────────────────────────────────────────────────────────────
-- 9) lab_result_template — "sık tekrarlanan panel" şablonu (Hafta 42).
--    quick_template'in laboratuvar karşılığı: değer/tarih taşımaz, yalnızca
--    panel+marker+birim+referans aralığını sabitler; LabScreen'de dokunulduğunda
--    "sonuç ekle" diyaloğunu bu alanlarla önceden doldurulmuş açar.
-- ────────────────────────────────────────────────────────────
create table if not exists public.lab_result_template (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references auth.users(id) on delete cascade,
    panel       text not null,
    marker      text not null,
    unit        text,
    ref_low     numeric,
    ref_high    numeric,
    created_at  timestamptz not null default now()
);

alter table public.lab_result_template enable row level security;

create policy "lab_result_template_owner_all" on public.lab_result_template
    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists idx_lab_result_template_user_panel
    on public.lab_result_template (user_id, panel, marker);
