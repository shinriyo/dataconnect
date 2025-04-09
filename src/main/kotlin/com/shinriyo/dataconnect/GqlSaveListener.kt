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
    private val notificationGroupId = "Firebase Data Connect"

    override fun after(events: MutableList<out VFileEvent>) {
        log.info("File change events received: ${events.size}")
        for (event in events) {
            val file = event.file ?: continue
            log.info("File changed: ${file.name}, extension: ${file.extension}, path: ${file.path}")
            
            // Check if the file is a GraphQL file
            if (file.extension != "gql" && file.extension != "graphql") {
                log.info("Skipping non-GraphQL file: ${file.name}")
                continue
            }
            
            log.info("Processing GraphQL file: ${file.name}")
            
            val project = ProjectLocator.getInstance().guessProjectForFile(file)
            if (project == null) {
                log.warn("Could not find project for file: ${file.name}")
                continue
            }
            
            log.info("Found project: ${project.name}")
            
            val basePath = project.basePath
            if (basePath == null) {
                log.warn("Project base path is null")
                continue
            }
            
            // Try to find connector.yaml in the project root
            val connectorFile = findConnectorYaml(basePath)
            
            if (connectorFile != null) {
                log.info("Found connector.yaml at: ${connectorFile.absolutePath}")
                runCodegen(project, connectorFile)
            } else {
                log.warn("connector.yaml not found in project root")
                notify(project, "connector.yaml not found in project root.")
                
                // Try to generate Dart code directly from the GraphQL file
                generateDartFromGql(project, file)
            }
        }
    }

    private fun findConnectorYaml(basePath: String): File? {
        val file = File(basePath, "connector.yaml")
        return if (file.exists()) file else null
    }
    
    private fun generateDartFromGql(project: Project, gqlFile: com.intellij.openapi.vfs.VirtualFile) {
        log.info("Generating Dart code directly from GraphQL file: ${gqlFile.name}")
        
        try {
            // Get the content of the GraphQL file
            val content = String(gqlFile.contentsToByteArray())
            log.info("GraphQL file content: $content")
            
            // Create a simple Dart model based on the GraphQL content
            val dartCode = generateSimpleDartModel(content, gqlFile.nameWithoutExtension)
            log.info("Generated Dart code: $dartCode")
            
            // Find or create the output directory
            val outputDir = findOrCreateOutputDir(project, gqlFile)
            log.info("Output directory: ${outputDir.absolutePath}")
            
            // Create the Dart file
            val dartFileName = "${gqlFile.nameWithoutExtension}.dart"
            val dartFile = File(outputDir, dartFileName)
            log.info("Dart file path: ${dartFile.absolutePath}")
            
            // Write the Dart code to the file
            dartFile.writeText(dartCode)
            
            log.info("Generated Dart file: ${dartFile.absolutePath}")
            notify(project, "Generated Dart file: ${dartFileName}")
        } catch (e: Exception) {
            log.error("Failed to generate Dart code", e)
            notify(project, "Failed to generate Dart code: ${e.message}")
        }
    }
    
    private fun generateSimpleDartModel(gqlContent: String, className: String): String {
        // This is a simple implementation that creates a basic Dart class
        // In a real implementation, you would parse the GraphQL schema and generate proper models
        
        return """
            // Generated from GraphQL schema
            class ${className} {
              final String id;
              final String name;
              
              ${className}({
                required this.id,
                required this.name,
              });
              
              factory ${className}.fromJson(Map<String, dynamic> json) {
                return ${className}(
                  id: json['id'] as String,
                  name: json['name'] as String,
                );
              }
              
              Map<String, dynamic> toJson() {
                return {
                  'id': id,
                  'name': name,
                };
              }
            }
        """.trimIndent()
    }
    
    private fun findOrCreateOutputDir(project: Project, gqlFile: com.intellij.openapi.vfs.VirtualFile): File {
        // Get the project base directory
        val projectDir = File(project.basePath ?: return File("."))
        
        // Create a 'generated' directory in the project
        val outputDir = File(projectDir, "lib/generated")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        return outputDir
    }

    private fun runCodegen(project: Project, connectorFile: File) {
        log.info("Running codegen with connector file: ${connectorFile.absolutePath}")
        
        val processBuilder = ProcessBuilder(
            "firebase", "data-connect", "codegen", "--config=${connectorFile.absolutePath}"
        )
        processBuilder.directory(File(project.basePath))

        try {
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                log.info("Code generation succeeded")
                notify(project, "Code generation succeeded.")
            } else {
                log.warn("Code generation failed with exit code: $exitCode")
                notify(project, "Code generation failed.")
            }
        } catch (e: Exception) {
            log.error("Failed to run codegen", e)
            notify(project, "Failed to run codegen: ${e.message}")
        }
    }

    private fun notify(project: Project, content: String) {
        val notification = Notification(
            notificationGroupId,
            "GraphQL to Dart Generator",
            content,
            NotificationType.INFORMATION
        )
        Notifications.Bus.notify(notification, project)
    }
} 