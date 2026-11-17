package com.pelakauto.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

internal data class VehicleBrand(val name: String, val brandTerms: List<String>, val vehicleTerms: List<String>)

internal fun vehicleSearchKey(value: String): String = buildString {
    for (char in value.lowercase(Locale.ROOT)) {
        val normalized = when (char) {
            'ي', 'ى' -> 'ی'
            'ك' -> 'ک'
            else -> char.digitToIntOrNull()?.digitToChar() ?: char
        }
        if (normalized.isLetterOrDigit()) append(normalized)
    }
}

internal fun matchingBrands(input: String, catalog: List<VehicleBrand>): List<String> {
    val key = vehicleSearchKey(input)
    if (key.isEmpty()) return emptyList()
    fun rank(brand: VehicleBrand): Int = when {
        brand.brandTerms.any { it == key } -> 0
        brand.brandTerms.any { it.startsWith(key) } -> 1
        brand.brandTerms.any { key in it } -> 2
        brand.vehicleTerms.any { it == key } -> 3
        key.length >= 2 && brand.vehicleTerms.any { key in it } -> 4
        else -> 5
    }
    return catalog.map { it.name to rank(it) }.filter { it.second < 5 }
        .sortedBy { it.second }.map { it.first }.take(6)
}

private fun parseBrandCatalog(body: String): List<VehicleBrand> {
    val json = JSONArray(body)
    return List(json.length()) { index ->
        val brand = json.getJSONObject(index)
        val name = brand.getString("name")
        val aliases = brand.getString("aliases")
        val models = brand.getJSONArray("models")
        val vehicleTerms = buildList {
            for (modelIndex in 0 until models.length()) {
                val model = models.getJSONObject(modelIndex)
                val modelName = model.getString("name")
                val modelAliases = model.getString("aliases")
                add(modelName)
                add(modelAliases)
                add("$name $modelName")
                for (alias in aliases.split(" ")) if (alias.isNotBlank()) add("$alias $modelName")
                val trims = model.getJSONArray("trims")
                for (trimIndex in 0 until trims.length()) add(trims.getString(trimIndex))
            }
        }
        VehicleBrand(name, (listOf(name, aliases) + aliases.split(" ")).map(::vehicleSearchKey).filter(String::isNotEmpty),
            vehicleTerms.map(::vehicleSearchKey).filter(String::isNotEmpty).distinct())
    }
}

@Composable
internal fun VehicleBrandField(value: String, label: String, onChange: (String) -> Unit) {
    val assets = LocalContext.current.assets
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val catalog by produceState(emptyList<VehicleBrand>(), assets) {
        this.value = withContext(Dispatchers.IO) {
            assets.open("pelak-vehicle-catalog.json").bufferedReader().use { parseBrandCatalog(it.readText()) }
        }
    }
    val suggestions = remember(value, catalog, focused) {
        if (focused) matchingBrands(value, catalog).filterNot { it == value } else emptyList()
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        if (suggestions.isNotEmpty()) {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    suggestions.forEach { brand ->
                        Text(brand, modifier = Modifier.fillMaxWidth().clickable {
                            onChange(brand)
                            focus.clearFocus()
                        }.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}
