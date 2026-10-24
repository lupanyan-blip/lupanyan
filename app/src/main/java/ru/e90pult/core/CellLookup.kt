package ru.e90pult.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class CellLocation(val lat: Double, val lon: Double, val range: Int?, val samples: Int?)

sealed class CellResult {
    data class Ok(val location: CellLocation) : CellResult()
    data class Error(val message: String) : CellResult()
}

/** Запрос и разбор ответа OpenCellID (необязательная функция, нужен токен пользователя). */
object CellLookup {

    /** LAC и CI в ответе модуля — шестнадцатеричные, OpenCellID ждёт десятичные. */
    fun url(token: String, mcc: Int, mnc: Int, lacHex: String, ciHex: String): String? {
        val lac = lacHex.toLongOrNull(16) ?: return null
        val ci = ciHex.toLongOrNull(16) ?: return null
        val key = java.net.URLEncoder.encode(token.trim(), "UTF-8")
        return "https://opencellid.org/cell/get?key=$key&mcc=$mcc&mnc=$mnc&lac=$lac&cellid=$ci&format=json"
    }

    fun parse(body: String, httpCode: Int): CellResult {
        val obj = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return CellResult.Error(
            if (httpCode in 200..299) "Непонятный ответ OpenCellID" else "OpenCellID: HTTP $httpCode"
        )
        val err = (obj["error"] as? JsonPrimitive)?.contentOrNull
        if (!err.isNullOrBlank()) return CellResult.Error("OpenCellID: $err")
        val lat = (obj["lat"] as? JsonPrimitive)?.doubleOrNull
        val lon = (obj["lon"] as? JsonPrimitive)?.doubleOrNull
        if (lat == null || lon == null) return CellResult.Error("OpenCellID: координаты соты не найдены")
        return CellResult.Ok(
            CellLocation(
                lat = lat,
                lon = lon,
                range = (obj["range"] as? JsonPrimitive)?.intOrNull,
                samples = (obj["samples"] as? JsonPrimitive)?.intOrNull
            )
        )
    }

    fun geoUri(lat: Double, lon: Double): String =
        "geo:$lat,$lon?q=$lat,$lon(Машина, приблизительно)"

    fun osmUrl(lat: Double, lon: Double): String =
        "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon"
}
