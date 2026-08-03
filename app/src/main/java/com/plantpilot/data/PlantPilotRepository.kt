package com.plantpilot.data

import com.plantpilot.network.*
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkModule {
    private var currentUrl = "http://plantpilot.local/"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS) // Increased to 10s
        .readTimeout(10, TimeUnit.SECONDS)    // Increased to 10s
        .build()

    private var _apiService: ApiService? = null
    val apiService: ApiService
        get() = _apiService ?: createService(currentUrl)

    fun updateBaseUrl(newHost: String) {
        if (newHost.isBlank() || newHost.length < 3) return // Ignore invalid/short hosts

        val newUrl = if (newHost.startsWith("http")) {
            if (newHost.endsWith("/")) newHost else "$newHost/"
        } else {
            "http://$newHost/"
        }
        
        if (currentUrl != newUrl) {
            try {
                // Verify it's a valid URL before applying
                "application/json".toMediaType()
                val service = createService(newUrl)
                currentUrl = newUrl
                _apiService = service
            } catch (e: Exception) {
                // Silently ignore invalid URL formats during typing
            }
        }
    }

    private fun createService(url: String): ApiService {
        val service = Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
        _apiService = service
        return service
    }
}

class PlantPilotRepository {
    private val api get() = NetworkModule.apiService

    suspend fun sync(request: SyncRequest): Result<SyncResponse> {
        return try {
            val response = api.sync(request)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                Result.failure(Exception("Sync Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun waterNow(motorId: Int): Result<GenericResponse> {
        return try {
            val response = api.waterNow(motorId)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                Result.failure(Exception("API Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Live connectivity handshake against the ESP32. Used by the "Check
     * Connection" button and lifecycle/poll checks — never derive "connected"
     * from cached state.
     */
    suspend fun checkConnection(): Result<StatusResponse> {
        return try {
            val response = api.getStatus()
            val body = response.body()
            if (response.isSuccessful && body != null && body.status == "ok") {
                Result.success(body)
            } else {
                Result.failure(Exception("Status Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Pulls the ESP32's current per-motor config with last-modified timestamps. */
    suspend fun fetchDeviceConfig(): Result<DeviceConfigResponse> {
        return try {
            val response = api.getConfig()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                Result.failure(Exception("Config Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Pushes a per-sensor dry/wet calibration to the ESP32. */
    suspend fun calibrate(motorId: Int, dry: Int, wet: Int, mlPerSec: Int? = null): Result<GenericResponse> {
        return try {
            val response = api.calibrate(CalibrationRequest(motor = motorId, dry = dry, wet = wet, ml_per_sec = mlPerSec))
            val body = response.body()
            if (response.isSuccessful && body != null && body.status == "ok") {
                Result.success(body)
            } else {
                Result.failure(Exception("Calibration Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
