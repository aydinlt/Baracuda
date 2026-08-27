// supabase/functions/twin/generate_system_twin.mjs
//
// system_twin.md içeriği değiştiğinde (klinik profil güncellemesi, yeni lab
// sonucu vb.) bu script çalıştırılıp system_twin.ts yeniden üretilmelidir:
//
//   node supabase/functions/twin/generate_system_twin.mjs
//
// Neden gerekli: index.ts artık system_twin.md'yi ÇALIŞMA ZAMANINDA
// Deno.readTextFile ile okumuyor (bkz. index.ts'teki Hafta 63 notu) —
// `supabase functions deploy` yalnızca statik import grafiğini paketler,
// yol string'i olarak verilen bir dosyayı içermez. Bu yüzden içerik
// system_twin.ts içinde bir string sabiti olarak statik import edilir.
// system_twin.md insan tarafından okunabilir/düzenlenebilir kaynak olarak
// kalır; system_twin.ts ondan türetilen, deploy edilen üründür — elle
// düzenlenmemelidir.
import { readFileSync, writeFileSync } from "node:fs";

const content = readFileSync(
  new URL("./system_twin.md", import.meta.url),
  "utf8",
);

const out =
  "// supabase/functions/twin/system_twin.ts\n" +
  "// OTOMATİK ÜRETİLDİ — elle düzenlemeyin. Kaynak: system_twin.md\n" +
  "// Yeniden üretmek için: node supabase/functions/twin/generate_system_twin.mjs\n" +
  "export const SYSTEM_TWIN_MD = " + JSON.stringify(content) + ";\n";

writeFileSync(new URL("./system_twin.ts", import.meta.url), out);
console.log("system_twin.ts yeniden üretildi (" + out.length + " bayt).");
