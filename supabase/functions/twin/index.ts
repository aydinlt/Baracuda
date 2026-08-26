// supabase/functions/twin/index.ts
// Deploy: supabase functions deploy twin
// Secret:  supabase secrets set ANTHROPIC_API_KEY=sk-ant-...
// Secret (Hafta 60 — bkz. aşağıdaki kimlik doğrulama notu):
//          supabase secrets set ALLOWED_USER_ID=<Aydın'ın auth.users.id'si>
//          (Supabase Dashboard → Authentication → Users'tan alınır)
//
// API anahtarı YALNIZCA burada. Android tarafında yok.

import { serve } from "https://deno.land/std@0.208.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ANTHROPIC_KEY = Deno.env.get("ANTHROPIC_API_KEY")!;
// SUPABASE_URL/SUPABASE_ANON_KEY her Edge Function'a Supabase tarafından
// otomatik sağlanan ayrılmış (reserved) ortam değişkenleridir — elle
// `secrets set` ile ayarlanmaları gerekmez/edilemez.
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const ALLOWED_USER_ID = Deno.env.get("ALLOWED_USER_ID")!;
const SYSTEM_PROMPT = await Deno.readTextFile("./system_twin.md");

// Statik blok ~2.500 token ve hiç değişmiyor → cache'lenmesi zorunlu.
// Cache TTL 5 dk; sabah protokolü + gün içi override'lar aynı cache'i kullanır.
const SYSTEM_BLOCKS = [
  {
    type: "text",
    text: SYSTEM_PROMPT,
    cache_control: { type: "ephemeral" },
  },
];

const MODELS = {
  fast: "claude-haiku-4-5-20251001", // gün içi override, 5–15×/gün
  deep: "claude-sonnet-5",           // sabah protokolü, 1×/gün
  weekly: "claude-opus-5",           // haftalık seyir analizi
};

interface Body {
  trigger: string;
  state_block: string;
  tier?: keyof typeof MODELS;
}

serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  // ── Kimlik doğrulama (Hafta 60 — GÜVENLİK) ──
  // ÖNCEDEN bu kontrol hiç yoktu: fonksiyon yalnızca Supabase'in platform
  // seviyesindeki `verify_jwt=true` ayarına güveniyordu. O ayar YALNIZCA
  // "geçerli imzalı bir Supabase JWT'si var mı" diye bakar — anon key de
  // (istemci APK'sına gömülü, herkese açık olması GEREKEN bir değer) bu
  // testi geçer. Sonuç: anon key'i ele geçiren HERKES (decompile edilmiş
  // bir APK'dan ya da bir ağ trafiği yakalamasından — anon key zaten
  // "sızabilecek" bir değerdir) bu fonksiyonu doğrudan çağırıp
  // ANTHROPIC_API_KEY bütçesini sınırsızca tüketebilirdi; `tier` alanı
  // çağıran tarafından seçildiği için "weekly" (Opus, en pahalı model)
  // bile serbestçe seçilebiliyordu.
  //
  // Fix iki katmanlı: (1) Authorization başlığındaki JWT'nin GERÇEK,
  // oturum açmış bir kullanıcıya ait olduğu doğrulanır (anon key'in
  // kendisi bu kontrolü geçemez — bir kullanıcıya bağlı değildir).
  // (2) Bu uygulama TEK kullanıcı için tasarlandığından (bkz.
  // AuthRepository.kt notu — Supabase Auth herkese açık kayda izin
  // verdiği için yalnızca "biri giriş yapmış" yeterli değildir), o
  // kullanıcının id'si ALLOWED_USER_ID ile eşleşmelidir.
  const authHeader = req.headers.get("Authorization");
  const jwt = authHeader?.replace(/^Bearer\s+/i, "");
  if (!jwt) {
    return new Response(JSON.stringify({ error: "yetkisiz" }), { status: 401 });
  }
  const supabaseClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);
  const { data: { user }, error: authError } = await supabaseClient.auth.getUser(jwt);
  if (authError || !user || user.id !== ALLOWED_USER_ID) {
    return new Response(JSON.stringify({ error: "yetkisiz" }), { status: 401 });
  }

  let body: Body;
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "geçersiz JSON" }), { status: 400 });
  }

  if (!body.state_block) {
    return new Response(JSON.stringify({ error: "state_block zorunlu" }), { status: 400 });
  }

  const model = MODELS[body.tier ?? "fast"] ?? MODELS.fast;

  const userTurn = [
    body.state_block,
    "",
    "Yukarıdaki duruma göre bugünün optimizasyonunu üret.",
    "Ses sınırına (Bölüm D) harfiyen uy: serbest domainde birinci tekil şahıs,",
    "kırmızı bölgede yalnızca clinical_flags.",
    "Kural motoru satırları tartışılmaz — onları gerekçe olarak kullan.",
    "Yalnızca JSON döndür.",
  ].join("\n");

  const upstream = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": ANTHROPIC_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model,
      max_tokens: 1400,
      temperature: 0.3, // düşük: yaratıcılık değil tutarlılık istiyoruz
      system: SYSTEM_BLOCKS,
      // Modelin JSON dışına çıkmasını engelleyen ucuz numara: yanıtı açık
      // parantezle BAŞLATMAYA ZORLA. Yorumda bu teknik anlatılıyordu ama
      // gerçekte uygulanmıyordu — yalnızca stop_sequences vardı, prefill
      // (assistant mesajıyla "{" ile başlatma) hiç eklenmemişti. Anthropic
      // API prefill'i yanıta EKLEMEZ, yalnızca ondan devam eder — bu yüzden
      // aşağıda modelden dönen metnin başına "{" elle ekleniyor.
      messages: [
        { role: "user", content: userTurn },
        { role: "assistant", content: "{" },
      ],
      stop_sequences: ["\n\n\n"],
    }),
  });

  if (!upstream.ok) {
    const err = await upstream.text();
    console.error("anthropic error", upstream.status, err);
    return new Response(
      JSON.stringify({ error: "upstream", status: upstream.status }),
      { status: 502, headers: { "content-type": "application/json" } },
    );
  }

  const data = await upstream.json();

  // Cache verimliliğini logla — ilk çağrıdan sonra
  // cache_read_input_tokens yüksek olmalı
  console.log("usage", JSON.stringify(data.usage));

  // "{" prefill Anthropic'in yanıtına dahil değildir (yalnızca ondan devam
  // eder) — TwinEngine.parse() geçerli JSON bekliyor, bu yüzden başa geri
  // eklenmesi zorunlu; aksi halde her yanıt "{" olmadan başlayan, kırık JSON olur.
  const text = "{" + (data.content ?? [])
    .filter((b: { type: string }) => b.type === "text")
    .map((b: { text: string }) => b.text)
    .join("");

  return new Response(text, {
    headers: { "content-type": "application/json" },
  });
});
