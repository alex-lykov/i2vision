package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.lang.management.ManagementFactory
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.sun.management.OperatingSystemMXBean as SunOperatingSystemMXBean

@Composable
fun SystemMonitor(
    modifier: Modifier = Modifier
) {
    var systemInfo by remember { mutableStateOf<SystemInfo>(SystemInfo()) }
    var ollamaInfo by remember { mutableStateOf<OllamaInfo?>(null) }

    // Update system info every 2 seconds
    LaunchedEffect(Unit) {
        while (true) {
            try {
                systemInfo = getSystemInfo()
            } catch (e: Exception) {
                // Keep last known good state if update fails
                println("System monitor update failed: ${e.message}")
            }
            delay(2000)
        }
    }

    // Update Ollama info every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            ollamaInfo = getOllamaInfo()
            delay(5000)
        }
    }

    RightPanelCard(
        title = "System Monitor",
        modifier = modifier
    ) {
        Column {
            // CPU Section
            MonitorSection(
                title = "CPU",
                value = "${systemInfo.cpuUsage}%",
                progress = systemInfo.cpuUsage / 100f,
                color = when {
                    systemInfo.cpuUsage > 80 -> Color.Red
                    systemInfo.cpuUsage > 60 -> Color(0xFFFFA500)
                    else -> Color.Green
                },
                details = "Cores: ${systemInfo.cpuCores}"
            )

            Spacer(Modifier.height(8.dp))

            // Memory Section
            MonitorSection(
                title = "Memory",
                value = "${systemInfo.memoryUsage}%",
                progress = systemInfo.memoryUsage / 100f,
                color = when {
                    systemInfo.memoryUsage > 85 -> Color.Red
                    systemInfo.memoryUsage > 70 -> Color(0xFFFFA500)
                    else -> Color.Green
                },
                details = "${systemInfo.usedMemoryGB}GB / ${systemInfo.totalMemoryGB}GB"
            )

            Spacer(Modifier.height(8.dp))

            // GPU Section (if available)
            systemInfo.gpuInfo?.let { gpu ->
                MonitorSection(
                    title = "GPU",
                    value = "${gpu.usage}%",
                    progress = gpu.usage / 100f,
                    color = when {
                        gpu.usage > 90 -> Color.Red
                        gpu.usage > 75 -> Color(0xFFFFA500)
                        else -> Color.Green
                    },
                    details = "${gpu.name} • ${gpu.memoryUsed}GB/${gpu.memoryTotal}GB"
                )

                Spacer(Modifier.height(8.dp))
            }

            // Ollama API Status
            OllamaStatusSection(ollamaInfo)
        }
    }
}

@Composable
private fun MonitorSection(
    title: String,
    value: String,
    progress: Float,
    color: Color,
    details: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.body2,
                color = color
            )
        }

        Spacer(Modifier.height(4.dp))

        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = details,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun OllamaStatusSection(ollamaInfo: OllamaInfo?) {
    Column {
        Text(
            text = "Ollama API",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(4.dp))

        if (ollamaInfo != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Green)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.caption,
                        color = Color.Green
                    )
                }
                Text(
                    text = ollamaInfo.version,
                    style = MaterialTheme.typography.caption,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(4.dp))

            Column {
                Text(
                    text = "Models Loaded: ${ollamaInfo.modelsLoaded}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "Total VRAM: ${ollamaInfo.totalVRAM}GB",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                if (ollamaInfo.activeModel != null) {
                    Text(
                        text = "Active: ${ollamaInfo.activeModel}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Disconnected",
                        style = MaterialTheme.typography.caption,
                        color = Color.Red
                    )
                }
                Text(
                    text = "localhost:11434",
                    style = MaterialTheme.typography.caption,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

data class SystemInfo(
    val cpuUsage: Int = 0,
    val cpuCores: Int = 0,
    val memoryUsage: Int = 0,
    val usedMemoryGB: Double = 0.0,
    val totalMemoryGB: Double = 0.0,
    val gpuInfo: GPUInfo? = null
)

data class GPUInfo(
    val name: String,
    val usage: Int,
    val memoryUsed: Double,
    val memoryTotal: Double
)

data class OllamaInfo(
    val version: String,
    val modelsLoaded: Int,
    val totalVRAM: Int,
    val activeModel: String?
)

fun getSystemInfo(): SystemInfo {
    val cpuCores = Runtime.getRuntime().availableProcessors()
    val memoryMXBean = ManagementFactory.getMemoryMXBean()
    val heapMemory = memoryMXBean.heapMemoryUsage
    val totalMemoryGB = heapMemory.max / (1024.0 * 1024.0 * 1024.0)
    val usedMemoryGB = heapMemory.used / (1024.0 * 1024.0 * 1024.0)
    val memoryUsage = ((usedMemoryGB / totalMemoryGB) * 100).toInt()

    // Better CPU usage estimation using operating system MXBean
    val cpuUsage = try {
        val osMXBean = ManagementFactory.getOperatingSystemMXBean()
        
        // Try Sun-specific CPU usage first (most accurate)
        if (osMXBean is SunOperatingSystemMXBean) {
            val sunMXBean = osMXBean as SunOperatingSystemMXBean
            val cpuLoad = sunMXBean.processCpuLoad
            if (cpuLoad >= 0) {
                (cpuLoad * 100).toInt().coerceAtMost(100)
            } else {
                // Fallback to system CPU load
                val systemCpuLoad = sunMXBean.systemCpuLoad
                if (systemCpuLoad >= 0) {
                    (systemCpuLoad * 100).toInt().coerceAtMost(100)
                } else {
                    // Use system load average as final fallback
                    val loadAverage = sunMXBean.systemLoadAverage
                    if (loadAverage >= 0) {
                        val cores = Runtime.getRuntime().availableProcessors()
                        ((loadAverage / cores) * 100).toInt().coerceAtMost(100)
                    } else 0
                }
            }
        } else {
            // Generic MXBean fallback
            val processCpuLoad = try {
                val method = osMXBean.javaClass.getMethod("getProcessCpuLoad")
                val cpuLoad = method.invoke(osMXBean) as? Double
                if (cpuLoad != null && cpuLoad >= 0) {
                    (cpuLoad * 100).toInt()
                } else null
            } catch (e: Exception) {
                null
            }
            
            if (processCpuLoad != null) {
                processCpuLoad
            } else {
                // Final fallback - use system load average
                try {
                    val loadAverage = osMXBean.systemLoadAverage
                    if (loadAverage >= 0) {
                        val cores = Runtime.getRuntime().availableProcessors()
                        ((loadAverage / cores) * 100).toInt().coerceAtMost(100)
                    } else 0
                } catch (e2: Exception) {
                    0
                }
            }
        }
    } catch (e: Exception) {
        println("CPU monitoring error: ${e.message}")
        0
    }

    // GPU info (placeholder - would need NVIDIA/AMD libraries)
    val gpuInfo = try {
        // Try to detect GPU using nvidia-smi or similar
        val gpuName = getGPUName()
        if (gpuName != null) {
            val gpuUsage = getGPUUsage()
            val gpuMemory = getGPUMemory()
            GPUInfo(
                name = gpuName,
                usage = gpuUsage,
                memoryUsed = gpuMemory.first,
                memoryTotal = gpuMemory.second
            )
        } else null
    } catch (e: Exception) {
        null
    }

    return SystemInfo(
        cpuUsage = cpuUsage,
        cpuCores = cpuCores,
        memoryUsage = memoryUsage,
        usedMemoryGB = usedMemoryGB,
        totalMemoryGB = totalMemoryGB,
        gpuInfo = gpuInfo
    )
}

private fun getGPUName(): String? {
    return try {
        val process = ProcessBuilder("nvidia-smi", "--query-gpu=name", "--format=csv,noheader,nounits")
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0 && output.isNotBlank()) {
            output.trim().split("\n").firstOrNull()
        } else null
    } catch (e: Exception) {
        null
    }
}

private fun getGPUUsage(): Int {
    return try {
        val process = ProcessBuilder("nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits")
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0 && output.isNotBlank()) {
            output.trim().split("\n").firstOrNull()?.toIntOrNull() ?: 0
        } else 0
    } catch (e: Exception) {
        0
    }
}

private fun getGPUMemory(): Pair<Double, Double> {
    return try {
        val process = ProcessBuilder("nvidia-smi", "--query-gpu=memory.used,memory.total", "--format=csv,noheader,nounits")
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0 && output.isNotBlank()) {
            val parts = output.trim().split("\n").firstOrNull()?.split(",") ?: return Pair(0.0, 0.0)
            val used = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val total = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            Pair(used / 1024.0, total / 1024.0) // Convert MB to GB
        } else Pair(0.0, 0.0)
    } catch (e: Exception) {
        Pair(0.0, 0.0)
    }
}

suspend fun getOllamaInfo(): OllamaInfo? = withContext(Dispatchers.IO) {
    try {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:11434/api/tags"))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            val json = Json { ignoreUnknownKeys = true }
            val jsonObject = json.decodeFromString<kotlinx.serialization.json.JsonObject>(response.body())
            
            val version = jsonObject["version"]?.toString()?.removeSurrounding("\"") ?: "unknown"
            val models = jsonObject["models"]?.toString()?.let { 
                kotlinx.serialization.json.JsonPrimitive(it).content 
            }?.let { 
                try {
                    json.decodeFromString<kotlinx.serialization.json.JsonArray>(it)
                } catch (e: Exception) {
                    null
                }
            }?.size ?: 0
            
            // Try to get active model from /api/ps
            val activeModel = try {
                val psRequest = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/ps"))
                    .build()
                val psResponse = client.send(psRequest, HttpResponse.BodyHandlers.ofString())
                
                if (psResponse.statusCode() == 200) {
                    val psJson = json.decodeFromString<kotlinx.serialization.json.JsonObject>(psResponse.body())
                    psJson["models"]?.toString()?.let { modelStr ->
                        try {
                            val modelsArray = json.decodeFromString<kotlinx.serialization.json.JsonArray>(modelStr)
                            modelsArray.firstOrNull()?.toString()?.let { firstModel ->
                                json.decodeFromString<kotlinx.serialization.json.JsonObject>(firstModel)["name"]?.toString()?.removeSurrounding("\"")
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                } else null
            } catch (e: Exception) {
                null
            }

            OllamaInfo(
                version = version,
                modelsLoaded = models,
                totalVRAM = 24, // Placeholder - would need actual VRAM detection
                activeModel = activeModel
            )
        } else null
    } catch (e: Exception) {
        null
    }
}
