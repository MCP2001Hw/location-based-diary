package com.diss.location_based_diary.ui.map

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeafletMapScreen(
    currentLat: Double = 0.0,
    currentLon: Double = 0.0,
    currentUserId: Int // <--- NEW: We need to know whose history to fetch!
) {
    val htmlContent = buildMapHtml(currentLat, currentLon)

    // Memory to hold the JSON string we download from Python
    var historyJson by remember { mutableStateOf("[]") }
    val coroutineScope = rememberCoroutineScope()

    // --- FETCH HISTORY ON LOAD ---
    // This block runs once when the map opens, downloading the trail from the server
    LaunchedEffect(currentUserId) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://lbs-api-server.onrender.com/get_location_history/$currentUserId?requester_id=$currentUserId")
                val connection = url.openConnection() as HttpURLConnection
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val rawJson = reader.readText()
                    reader.close()

                    withContext(Dispatchers.Main) {
                        historyJson = rawJson // Save the raw JSON string
                    }
                }
            } catch (e: Exception) {
                Log.e("LeafletMap", "Failed to fetch history: ${e.message}")
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.e("LeafletMap", "Map Error: ${consoleMessage.message()} -- Line ${consoleMessage.lineNumber()}")
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                webViewClient = WebViewClient()

                loadDataWithBaseURL(
                    "https://com.diss.location_based_diary", // This acts as your Referer
                    htmlContent,                             // Your raw HTML string
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        update = { webView ->
            // 1. Update the live "You are here" pin
            webView.evaluateJavascript("if(typeof updateMapLocation === 'function') { updateMapLocation($currentLat, $currentLon, 'Live Location'); }", null)

            // 2. Draw the history trail we downloaded from Python!
            if (historyJson != "[]") {
                webView.evaluateJavascript("if(typeof drawHistory === 'function') { drawHistory($historyJson); }", null)
            }
        }
    )
}

/**
 * The HTML/JS brain of the map. Upgraded to draw lines and red dots!
 */
private fun buildMapHtml(lat: Double, lon: Double): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <title>LBS Map</title>
            <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.css" />
            <script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js"></script>
            <style>
                body, html { margin: 0; padding: 0; height: 100%; width: 100%; background-color: #e5e5e5; }
                #map { position: absolute; top: 0; bottom: 0; left: 0; right: 0; }
            </style>
        </head>
        <body>
            <div id="map"></div>
            
            <script>
                var map = L.map('map', { zoomControl: false }).setView([$lat, $lon], 15);
                L.control.zoom({ position: 'topright' }).addTo(map);

                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '© OpenStreetMap'
                }).addTo(map);

                var liveMarkerLayer = L.layerGroup().addTo(map);
                var historyLayer = L.layerGroup().addTo(map); // <--- NEW: Dedicated layer for the trail

                // Moves the main pin
                function updateMapLocation(newLat, newLon, title) {
                    liveMarkerLayer.clearLayers(); 
                    var marker = L.marker([newLat, newLon]).addTo(liveMarkerLayer);
                    if (title) marker.bindPopup(title);
                    map.panTo(new L.LatLng(newLat, newLon));
                }
                
                // --- NEW: Draws the Breadcrumb Trail ---
                function drawHistory(historyData) {
                    historyLayer.clearLayers(); // Erase old trail
                    var latlngs = [];

                    historyData.forEach(function(point) {
                        latlngs.push([point.lat, point.lon]);

                        // Is it a normal step, or an ALARM TRIGGER?
                        if (point.is_trigger) {
                            // RED DOT for triggered alarms
                            L.circleMarker([point.lat, point.lon], {
                                color: 'red',
                                fillColor: '#ff0000',
                                fillOpacity: 0.9,
                                radius: 8
                            }).bindPopup('<b>Alarm Triggered!</b><br>Time: ' + point.time).addTo(historyLayer);
                        } else {
                            // BLUE DOT for normal walking
                            L.circleMarker([point.lat, point.lon], {
                                color: '#3388ff',
                                fillColor: '#3388ff',
                                fillOpacity: 0.5,
                                radius: 4
                            }).bindPopup('Walked by at: ' + point.time).addTo(historyLayer);
                        }
                    });

                    // Draw a solid blue line connecting all the dots!
                    if (latlngs.length > 1) {
                        L.polyline(latlngs, {color: '#3388ff', weight: 3, opacity: 0.6}).addTo(historyLayer);
                    }
                }

                updateMapLocation($lat, $lon, 'You are here!');
            </script>
        </body>
        </html>
    """.trimIndent()
}