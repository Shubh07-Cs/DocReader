package com.example.docreader.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object GitHubUpdateManager {

    private const val TAG = "GitHubUpdateManager"
    private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/Shubh07-Cs/DocReader/main/update.json"
    private val executor = Executors.newSingleThreadExecutor()

    fun checkForUpdates(activity: Activity) {
        executor.execute {
            try {
                val url = URL(UPDATE_JSON_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    parseAndShowUpdate(activity, response.toString())
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
            }
        }
    }

    private fun parseAndShowUpdate(activity: Activity, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val latestVersionCode = json.getInt("versionCode")
            val latestVersionName = json.getString("versionName")
            val downloadUrl = json.getString("downloadUrl")
            val changeLog = json.optString("changeLog", "A new update is available!")

            val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
            }

            if (latestVersionCode > currentVersionCode) {
                activity.runOnUiThread {
                    // Check if activity is still valid before showing dialog to prevent WindowLeaked crash
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showUpdateDialog(activity, latestVersionName, changeLog, downloadUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing update JSON", e)
        }
    }

    private fun showUpdateDialog(activity: Activity, versionName: String, changeLog: String, downloadUrl: String) {
        AlertDialog.Builder(activity)
            .setTitle("Update Available (v$versionName)")
            .setMessage(changeLog)
            .setPositiveButton("Download") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch browser", e)
                }
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }
}
