package com.ruslancams.tv.model

import org.json.JSONObject

data class CameraItem(
    val id: String,
    val title: String,
    val defaultKind: String,
    val streams: Map<String, String>
) {
    fun getStreamUrl(kind: String? = null, baseUrl: String): String {
        return when (id) {
            "karamova_5" -> "rtsp://$baseUrl:8554/karamova_5_src"
            "karamova_7" -> "rtsp://$baseUrl:8554/karamova_7_src"
            "kirtbaya_2" -> "rtsp://$baseUrl:8554/kirtbaya_2_src"
            "kirtbaya_3" -> "rtsp://$baseUrl:8554/kirtbaya_3_src"
            "lenina_4" -> "rtsp://$baseUrl:8554/lenina_4_src"
            "lenina_6" -> "rtsp://$baseUrl:8554/lenina_6_src"
            "ezviz_fridge_entry" -> "rtsp://$baseUrl:8554/ezviz_fridge_entry"
            "ezviz_general" -> "rtsp://$baseUrl:8554/ezviz_general"
            else -> {
                val selectedKind = kind ?: defaultKind
                val streamId = streams[selectedKind] ?: streams.values.firstOrNull() ?: id
                "rtsp://$baseUrl:8554/$streamId"
            }
        }
    }

    fun getMainStreamUrl(baseUrl: String): String {
        return when (id) {
            "karamova_5" -> "rtsp://$baseUrl:8554/karamova_5_main_src"
            "karamova_7" -> "rtsp://$baseUrl:8554/karamova_7_main_src"
            "kirtbaya_2" -> "rtsp://$baseUrl:8554/kirtbaya_2_main_src"
            "kirtbaya_3" -> "rtsp://$baseUrl:8554/kirtbaya_3_main_src"
            "lenina_4" -> "rtsp://$baseUrl:8554/lenina_4_src"
            "lenina_6" -> "rtsp://$baseUrl:8554/lenina_6_main_src"
            "ezviz_fridge_entry" -> "rtsp://$baseUrl:8554/ezviz_fridge_entry"
            "ezviz_general" -> "rtsp://$baseUrl:8554/ezviz_general"
            else -> {
                val mainId = streams["main"] ?: streams.values.firstOrNull() ?: id
                "rtsp://$baseUrl:8554/$mainId"
            }
        }
    }
}

data class TvSettings(
    val bufferMs: Int = 2500,
    val clockEnabled: Boolean = true
)

data class GridConfig(
    val go2rtcPort: Int,
    val cameras: List<CameraItem>,
    val tvSettings: TvSettings = TvSettings()
) {
    companion object {
        fun defaultList(): List<CameraItem> = listOf(
            CameraItem("karamova_5", "Карамова · Камера 5", "sub", mapOf("sub" to "karamova_5_src", "main" to "karamova_5_main_src")),
            CameraItem("karamova_7", "Карамова · Камера 7", "sub", mapOf("sub" to "karamova_7_src", "main" to "karamova_7_main_src")),
            CameraItem("kirtbaya_2", "Киртбая · Камера 2", "sub", mapOf("sub" to "kirtbaya_2_src", "main" to "kirtbaya_2_main_src")),
            CameraItem("kirtbaya_3", "Киртбая · Камера 3", "sub", mapOf("sub" to "kirtbaya_3_src", "main" to "kirtbaya_3_main_src")),
            CameraItem("lenina_4", "Ленина · Камера 4", "sub", mapOf("sub" to "lenina_4_src", "main" to "lenina_4_main_src")),
            CameraItem("lenina_6", "Ленина · Камера 6", "sub", mapOf("sub" to "lenina_6_src", "main" to "lenina_6_main_src")),
            CameraItem("ezviz_fridge_entry", "EZVIZ · Хол-ник вход", "default", mapOf("default" to "ezviz_fridge_entry", "main" to "ezviz_fridge_entry")),
            CameraItem("ezviz_general", "EZVIZ · Общий вид", "default", mapOf("default" to "ezviz_general", "main" to "ezviz_general"))
        )

        fun fromJson(jsonStr: String): GridConfig {
            val json = JSONObject(jsonStr)
            val port = json.optInt("go2rtc", 1984)
            val camsArray = json.optJSONArray("cams")
            val list = mutableListOf<CameraItem>()

            if (camsArray != null) {
                for (i in 0 until camsArray.length()) {
                    val obj = camsArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val title = obj.optString("title", id)
                    val def = obj.optString("default", "sub")
                    val streamsMap = mutableMapOf<String, String>()
                    val streamsObj = obj.optJSONObject("streams")
                    if (streamsObj != null) {
                        for (key in streamsObj.keys()) {
                            streamsMap[key] = streamsObj.getString(key)
                        }
                    } else {
                        streamsMap["default"] = id
                    }
                    list.add(CameraItem(id, title, def, streamsMap))
                }
            }

            val tvObj = json.optJSONObject("tv_settings")
            val tvSettings = if (tvObj != null) {
                TvSettings(
                    bufferMs = tvObj.optInt("buffer_ms", 2500),
                    clockEnabled = tvObj.optBoolean("clock_enabled", true)
                )
            } else {
                TvSettings()
            }

            return if (list.isNotEmpty()) GridConfig(port, list, tvSettings) else GridConfig(port, defaultList(), tvSettings)
        }
    }
}
