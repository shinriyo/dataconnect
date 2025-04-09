package com.shinriyo.dataconnect

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File

class GqlSaveListener : BulkFileListener {
    private val log = Logger.getInstance(GqlSaveListener::class.java)
    private val NOTIFICATION_GROUP_ID = "Firebase Data Connect"

    override fun after(events: MutableList<out VFileEvent>) {
        for (event in events) {
            val file = event.file ?: continue
            if (file.extension != "gql") continue

            val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: continue
            val connectorFile = findConnectorYaml(project.basePath ?: continue)

            if (connectorFile != null) {
                runCodegen(project, connectorFile)
            } else {
                notify(project, "connector.yaml not found in project root.")
            }
        }
    }

    private fun findConnectorYaml(basePath: String): File? {
        val file = File(basePath, "connector.yaml")
        return if (file.exists()) file else null
    }

    private fun runCodegen(project: Project, connectorFile: File) {
        val processBuilder = ProcessBuilder(
            "firebase", "data-connect", "codegen", "--config=${connectorFile.absolutePath}"
        )
        processBuilder.directory(File(project.basePath))

        try {
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                notify(project, "Code generation succeeded.")
            } else {
                notify(project, "Code generation failed.")
            }
        } catch (e: Exception) {
            notify(project, "Failed to run codegen: ${e.message}")
            log.error(e)
        }
    }

    private fun notify(project: Project, content: String) {
        val notification = Notification(
            NOTIFICATION_GROUP_ID,
            "GraphQL to Dart Generator",
            content,
            NotificationType.INFORMATION
        )
        Notifications.Bus.notify(notification, project)
    }
} 