package com.plantpilot.network

import com.plantpilot.model.WateringSchedule
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.*

@Serializable
data class DeviceStatusResponse(
    val water_level: Int,
    val water_level_raw: Int? = null,
    val demo_mode: Boolean? = null,
    val soil: List<Int>,
    val wifi_rssi: Int,
    val wifi_ssid: String? = null,
    val uptime_sec: Long? = null,
    val free_heap: Int? = null,
    val raw_soil: List<Int>? = null,
    val epoch: Long? = null,
    val pumps: List<Boolean>? = null,
    val queued: List<Boolean>? = null,
    val sensor_cadence_sec: Int? = null,
    val use_hw_sensor: Boolean? = null,
    val motors: List<DeviceMotorConfig>? = null
)

@Serializable
data class MotorConfig(
    val id: Int,
    val name: String,
    val mode: String, // "off", "auto", "scheduled"
    val amount_ml: Int,
    val threshold: Int? = null,
    val min_interval_hours: Int = 0,
    val last_watered: Long = 0,
    val version: Int = 1,
    val last_modified: Long = 0, // epoch seconds, used for two-way sync
    val ml_per_sec: Int = 10,
    val max_runtime_minutes: Int = 1,
    val stop_on_disconnect: Boolean = false,
    val schedules: List<WateringSchedule> = emptyList()
)

@Serializable
data class SyncRequest(
    val epoch: Long,
    val motors: List<MotorConfig>,
    val water_level: Int? = null
)

@Serializable
data class SyncResponse(
    val updated: List<Int>,
    val ignored: List<Int>,
    val history: List<DeviceWateringEvent>? = null
)

@Serializable
data class DeviceWateringEvent(
    val motor: Int,
    val amount_ml: Int,
    val trigger: String,
    val epoch: Long,
    val soil_after: Int? = null
)

@Serializable
data class GenericResponse(
    val status: String,
    val motor: Int? = null
)

@Serializable
data class StatusResponse(
    val status: String = "",
    val ip: String? = null,
    val uptime_sec: Long? = null,
    val wifi_rssi: Int? = null,
    val epoch: Long? = null
)

@Serializable
data class DeviceSchedule(
    val hour: Int,
    val minute: Int
)

@Serializable
data class DeviceMotorConfig(
    val id: Int,
    val version: Int = 1,
    val last_modified: Long = 0,
    val mode: String = "off", // "off", "auto", "scheduled"
    val amount_ml: Int = 0,
    val threshold: Int? = null,
    val min_interval_hours: Int? = null,
    val calibration_dry: Int? = null,
    val calibration_wet: Int? = null,
    val last_watered: Long? = null,
    val ml_per_sec: Int? = null,
    val max_runtime_minutes: Int? = null,
    val stop_on_disconnect: Boolean? = null,
    val schedules: List<DeviceSchedule> = emptyList()
)

@Serializable
data class DeviceConfigResponse(
    val sensor_cadence_sec: Int? = null,
    val motors: List<DeviceMotorConfig> = emptyList()
)

@Serializable
data class CalibrationRequest(
    val motor: Int,
    val dry: Int,
    val wet: Int,
    val ml_per_sec: Int? = null
)

interface ApiService {
    @POST("/api/sync")
    suspend fun sync(@Body request: SyncRequest): Response<SyncResponse>

    @POST("/api/motor/{id}/water_now")
    suspend fun waterNow(
        @Path("id") motorId: Int,
        @Query("rate") rate: Int? = null,
        @Query("amount") amount: Int? = null
    ): Response<GenericResponse>

    @GET("/api/status")
    suspend fun getStatus(): Response<StatusResponse>

    @GET("/api/config")
    suspend fun getConfig(): Response<DeviceConfigResponse>

    @POST("/api/calibrate")
    suspend fun calibrate(@Body request: CalibrationRequest): Response<GenericResponse>
}
