package com.shinriyo.dataconnect

import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager

class PluginStartup : StartupActivity {
    override fun runActivity(project: Project) {
        val connection = project.messageBus.connect()
        connection.subscribe(VirtualFileManager.VFS_CHANGES, GqlSaveListener())
    }
}
