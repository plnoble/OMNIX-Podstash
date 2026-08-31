package com.omnix.podstash

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.omnix.podstash.ui.PodstashRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notifyPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.setTreeUri(uri.toString())
        }
    }

    private val pickOpml = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importOpml(uri)
    }

    private val createOpml = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml"),
    ) { uri ->
        if (uri != null) vm.exportOpml(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            LaunchedEffect(Unit) {
                vm.loadTrending("cn")
                vm.refreshSubscriptionsOnOpen()
                vm.checkUpdate()
            }
            PodstashRoot(
                vm = vm,
                onPickFolder = { pickFolder.launch(null) },
                onImportOpml = {
                    pickOpml.launch(arrayOf("text/*", "text/xml", "application/xml", "*/*"))
                },
                onExportOpml = { createOpml.launch("podstash.opml") },
                onOpenFolder = { openLibrary() },
            )
        }
    }

    private fun openLibrary() {
        val path = vm.libraryPath
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("library", path))
        vm.userMessage("已复制下载目录：\n$path")
    }
}
