package com.aydin.biyohack.twin

import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.IntakeRecord

/**
 * `data.IntakeRecord` (Room/Supabase'de kalıcı kayıt) ile `twin.IntakeEntry`
 * (İkize gönderilen anlık durum, bkz. TwinState.kt) arasındaki TEK köprü.
 *
 * Yön kasıtlı tek taraflı: twin katmanı data'ya bağımlı olabilir, tersi
 * olmaz (bkz. HealthLogModels.kt'deki not). Bu yüzden dönüşüm burada, twin
 * paketinin altında yaşıyor — data/ hiçbir zaman twin/'i import etmez.
 *
 * Kullanım yeri (bugünün loglarını TwinState.todayIntake'e dökme use-case'i)
 * Hafta 3/4'te TwinEngine ile birlikte gelecek; bu dosya yalnızca tip
 * dönüşümünü sağlar.
 */
fun IntakeKind.toTwinType(): IntakeType = when (this) {
    IntakeKind.MEAL -> IntakeType.MEAL
    IntakeKind.COFFEE -> IntakeType.COFFEE
    IntakeKind.WATER -> IntakeType.WATER
    IntakeKind.SUPPLEMENT -> IntakeType.SUPPLEMENT
}

fun IntakeRecord.toTwinEntry(): IntakeEntry = IntakeEntry(
    ts = ts,
    type = kind.toTwinType(),
    label = label,
    amount = amount,
    unit = unit
)
