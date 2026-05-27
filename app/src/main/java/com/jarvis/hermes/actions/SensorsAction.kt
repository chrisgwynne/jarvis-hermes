package com.jarvis.hermes.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.jarvis.hermes.LocalResponse
import androidx.core.content.ContextCompat

/**
 * Sensors action handler: battery, accelerometer, compass, light, proximity, steps.
 */
object SensorsAction {

    private const val ACTION_BATTERY = "battery"
    private const val ACTION_CHARGING = "charging"
    private const val ACTION_ACCELEROMETER = "accelerometer"
    private const val ACTION_COMPASS = "compass"
    private const val ACTION_LIGHT = "light"
    private const val ACTION_PROXIMITY = "proximity"
    private const val ACTION_STEPS = "steps"
    private const val ACTION_GYRO = "gyro"
    private const val ACTION_TEMPERATURE = "temperature"
    private const val ACTION_ALL_SENSORS = "all_sensors"

    fun requiredPermissions() = listOf<String>()

    fun canHandle(text: String): Map<String, String>? {
        return when {
            // Battery
            Regex("""^(what('?s| is)\s+)?my\s+battery|battery\s+(level|status)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_BATTERY)
            }
            Regex("""^is\s+(my\s+)?phone\s+(charging|plugged\s+in)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_CHARGING)
            }
            // Accelerometer
            Regex("""^accelerometer$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_ACCELEROMETER)
            }
            Regex("""^(device\s+)?motion$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_ACCELEROMETER)
            }
            // Compass
            Regex("""^compass$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_COMPASS)
            }
            Regex("""^(what('?s| is)\s+)?heading$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_COMPASS)
            }
            // Light sensor
            Regex("""^(light|illuminance|brightness\s+sensor)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_LIGHT)
            }
            // Proximity sensor
            Regex("""^proximity$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_PROXIMITY)
            }
            // Steps / Pedometer
            Regex("""^(step\s*counter|count\s+steps|steps?|pedometer)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_STEPS)
            }
            // Gyroscope
            Regex("""^gyroscope?$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_GYRO)
            }
            // Temperature
            Regex("""^(temperature|device\s+temperature|heat)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_TEMPERATURE)
            }
            // All sensors
            Regex("""^(all\s+sensors?|sensor\s+status)$""", RegexOption.IGNORE_CASE).matches(text) -> {
                mapOf("action" to ACTION_ALL_SENSORS)
            }
            else -> null
        }
    }

    fun execute(context: Context, params: Map<String, String>): LocalResponse {
        val action = params["action"] ?: return LocalResponse("Sensor action unclear.", "sensors_error")

        return when (action) {
            ACTION_BATTERY -> getBatteryLevel(context)
            ACTION_CHARGING -> getChargingStatus(context)
            ACTION_ACCELEROMETER -> readAccelerometer(context)
            ACTION_COMPASS -> readCompass(context)
            ACTION_LIGHT -> readLightSensor(context)
            ACTION_PROXIMITY -> readProximitySensor(context)
            ACTION_STEPS -> readStepCounter(context)
            ACTION_GYRO -> readGyroscope(context)
            ACTION_TEMPERATURE -> readTemperature(context)
            ACTION_ALL_SENSORS -> readAllSensors(context)
            else -> LocalResponse("Unknown sensor action.", "sensors_error")
        }
    }

    private fun getBatteryLevel(context: Context): LocalResponse {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)

        val isCharging = status == BatteryManager.BATTERY_PROPERTY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_PROPERTY_STATUS_FULL

        val statusText = when {
            isCharging && level == 100 -> "fully charged"
            isCharging -> "charging at $level percent"
            level <= 20 -> " critically low at $level percent"
            else -> "at $level percent"
        }

        return LocalResponse("Battery $statusText.", "sensors_battery",
            mapOf("level" to level.toString(), "charging" to isCharging.toString()))
    }

    private fun getChargingStatus(context: Context): LocalResponse {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)

        return when (status) {
            BatteryManager.BATTERY_PROPERTY_STATUS_CHARGING -> LocalResponse("Phone is charging.", "sensors_charging")
            BatteryManager.BATTERY_PROPERTY_STATUS_DISCHARGING -> LocalResponse("Phone is not charging.", "sensors_charging")
            BatteryManager.BATTERY_PROPERTY_STATUS_FULL -> LocalResponse("Phone is fully charged.", "sensors_charging")
            BatteryManager.BATTERY_PROPERTY_STATUS_NOT_CHARGING -> LocalResponse("Phone is not executing.", "sensors_charging")
            else -> LocalResponse("Unknown charging status.", "sensors_charging")
        }
    }

    private fun readAccelerometer(context: Context): LocalResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensor == null) {
            return LocalResponse("Accelerometer not available.", "sensors_error")
        }

        // Read once (simplified - in production use a listener with delay)
        return LocalResponse("Use a motion app to check accelerometer data.", "sensors_accelerometer")
    }

    private fun readCompass(context: Context): LocalResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (sensor == null) {
            return LocalResponse("Magnetometer not available.", "sensors_error")
        }

        return LocalResponse("Use a compass app for accurate heading.", "sensors_compass")
    }

    private fun readLightSensor(context: Context): LocalResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        if (sensor == null) {
            return LocalResponse("Light sensor not available.", "sensors_error")
        }

        return LocalResponse("Use a light meter app for lux readings.", "sensors_light")
    }

    private fun readProximitySensor(context: Context): LocalResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (sensor == null) {
            return LocalResponse("Proximity sensor not available.", "sensors_error")
        }

        return LocalResponse("Proximity sensor available.", "sensors_proximity")
    }

    private fun readStepCounter(context: Context): LocalResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (sensor == null) {
            return LocalResponse("Step counter not available.", "sensors_error")
        }

        return LocalResponse("Use a fitness app to count steps.", "sensors_steps")
    }

    private fun readGyroscope(context: Context): LocalResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (sensor == null) {
            return LocalResponse("Gyroscope not available.", "sensors_error")
        }

        return LocalResponse("Gyroscope available for rotation detection.", "sensors_gyro")
    }

    private fun readTemperature(context: Context): LocalResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_TEMPERATURE)

        if (sensor == null) {
            return LocalResponse("Temperature sensor not available.", "sensors_error")
        }

        return LocalResponse("Temperature sensor available.", "sensors_temperature")
    }

    private fun readAllSensors(context: Context): LocalResponse {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)

        val names = sensors.take(10).joinToString(", ") { it.name }

        return LocalResponse("${sensors.size} sensors available: $names", "sensors_all")
    }
}