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
            
            // Try to find connector.yaml in the project
            val connectorFile = findConnectorYaml(project)
            
            if (connectorFile != null) {
                log.info("Found connector.yaml at: ${connectorFile.absolutePath}")
                runCodegen(project, connectorFile)
            } else {
                log.warn("connector.yaml not found in project")
                notify(project, "connector.yaml not found in project. Please create a connector.yaml file to generate Dart code.")
            }
        }
    }

    private fun findConnectorYaml(project: Project): File? {
        // Search in project root
        val rootFile = File(project.basePath ?: return null, "connector.yaml")
        if (rootFile.exists()) {
            return rootFile
        }
        
        // Search in common locations
        val commonLocations = listOf(
            "lib",
            "src",
            "config",
            "graphql",
            "."
        )
        
        for (location in commonLocations) {
            val file = File(project.basePath ?: return null, location)
            if (file.exists() && file.isDirectory) {
                val connectorFile = File(file, "connector.yaml")
                if (connectorFile.exists()) {
                    return connectorFile
                }
            }
        }
        
        // Search recursively in the project (limited depth to avoid performance issues)
        return findConnectorYamlRecursively(File(project.basePath ?: return null), 3)
    }
    
    private fun findConnectorYamlRecursively(directory: File, depth: Int): File? {
        if (depth <= 0) {
            return null
        }
        
        // Check if connector.yaml exists in the current directory
        val connectorFile = File(directory, "connector.yaml")
        if (connectorFile.exists()) {
            return connectorFile
        }
        
        // Search in subdirectories
        if (directory.isDirectory) {
            for (file in directory.listFiles() ?: return null) {
                if (file.isDirectory && !file.name.startsWith(".")) { // Skip hidden directories
                    val result = findConnectorYamlRecursively(file, depth - 1)
                    if (result != null) {
                        return result
                    }
                }
            }
        }
        
        return null
    }

    private fun runCodegen(project: Project, connectorFile: File) {
        log.info("Running codegen with connector file: ${connectorFile.absolutePath}")
        
        // Check if connector.yaml is valid
        try {
            val connectorContent = connectorFile.readText()
            log.info("Connector.yaml content: $connectorContent")
        } catch (e: Exception) {
            log.error("Failed to read connector.yaml", e)
            notify(project, "Failed to read connector.yaml: ${e.message}")
            return
        }
        
        // Parse connector.yaml to get output directory
        val outputDir = parseConnectorYaml(connectorFile, project)
        if (outputDir == null) {
            log.error("Failed to parse connector.yaml")
            notify(project, "Failed to parse connector.yaml. Please check the file format.")
            return
        }
        
        // Find all GraphQL files in the project
        val graphqlFiles = findGraphQLFiles(project)
        if (graphqlFiles.isEmpty()) {
            log.warn("No GraphQL files found in the project")
            notify(project, "No GraphQL files found in the project.")
            return
        }
        
        // Generate Dart files for each GraphQL file
        var successCount = 0
        var failureCount = 0
        
        for (file in graphqlFiles) {
            try {
                val success = generateDartFromGraphQL(project, file, outputDir)
                if (success) {
                    successCount++
                } else {
                    failureCount++
                }
            } catch (e: Exception) {
                log.error("Failed to generate Dart file for ${file.name}", e)
                failureCount++
            }
        }
        
        if (successCount > 0) {
            log.info("Generated $successCount Dart files successfully")
            notify(project, "Generated $successCount Dart files successfully.")
        }
        
        if (failureCount > 0) {
            log.warn("Failed to generate $failureCount Dart files")
            notify(project, "Failed to generate $failureCount Dart files. Check the logs for details.")
        }
    }
    
    private fun parseConnectorYaml(connectorFile: File, project: Project): File? {
        try {
            val content = connectorFile.readText()
            val lines = content.split("\n")
            
            var outputPath = "lib/generated" // Default output path
            
            for (line in lines) {
                if (line.contains("output:") && line.contains("path:")) {
                    val pathMatch = Regex("path:\\s*([^\\s]+)").find(line)
                    if (pathMatch != null) {
                        outputPath = pathMatch.groupValues[1]
                    }
                }
            }
            
            val basePath = project.basePath
            if (basePath == null) {
                log.error("Project base path is null")
                return null
            }
            
            val outputDir = File(basePath, outputPath)
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            
            return outputDir
        } catch (e: Exception) {
            log.error("Failed to parse connector.yaml", e)
            return null
        }
    }
    
    private fun findGraphQLFiles(project: Project): List<File> {
        val graphqlFiles = mutableListOf<File>()
        val basePath = project.basePath ?: return graphqlFiles
        
        findGraphQLFilesRecursively(File(basePath), graphqlFiles)
        
        return graphqlFiles
    }
    
    private fun findGraphQLFilesRecursively(directory: File, graphqlFiles: MutableList<File>) {
        if (!directory.isDirectory) {
            return
        }
        
        for (file in directory.listFiles() ?: return) {
            if (file.isDirectory && !file.name.startsWith(".")) { // Skip hidden directories
                findGraphQLFilesRecursively(file, graphqlFiles)
            } else if (file.isFile && (file.extension == "gql" || file.extension == "graphql")) {
                graphqlFiles.add(file)
            }
        }
    }
    
    private fun generateDartFromGraphQL(project: Project, graphqlFile: File, outputDir: File): Boolean {
        log.info("Generating Dart file for ${graphqlFile.name}")
        
        try {
            val content = graphqlFile.readText()
            val operationName = extractOperationName(content)
            val fields = extractFields(content)
            
            if (operationName == null) {
                log.warn("Could not extract operation name from ${graphqlFile.name}")
                return false
            }
            
            val dartFileName = "${operationName.toLowerCase()}.dart"
            val dartFile = File(outputDir, dartFileName)
            
            val dartContent = generateDartContent(operationName, fields)
            dartFile.writeText(dartContent)
            
            log.info("Generated Dart file: ${dartFile.absolutePath}")
            return true
        } catch (e: Exception) {
            log.error("Failed to generate Dart file for ${graphqlFile.name}", e)
            return false
        }
    }
    
    private fun extractOperationName(content: String): String? {
        // Extract operation name from GraphQL content
        // This is a simple implementation and may not work for all GraphQL files
        val queryMatch = Regex("(query|mutation|subscription)\\s+([a-zA-Z0-9_]+)").find(content)
        if (queryMatch != null) {
            return queryMatch.groupValues[2]
        }
        
        return null
    }
    
    private fun extractFields(content: String): List<Field> {
        val fields = mutableListOf<Field>()
        
        // Extract fields from GraphQL content
        // This is a simple implementation and may not work for all GraphQL files
        val fieldRegex = Regex("\\s*([a-zA-Z0-9_]+)\\s*:\\s*([a-zA-Z0-9_\\[\\]!]+)")
        val matches = fieldRegex.findAll(content)
        
        for (match in matches) {
            val name = match.groupValues[1]
            val type = match.groupValues[2]
            fields.add(Field(name, type))
        }
        
        return fields
    }
    
    private fun generateDartContent(operationName: String, fields: List<Field>): String {
        val className = operationName.capitalize() + "Response"
        
        val sb = StringBuilder()
        sb.append("// Generated by Firebase Data Connect\n")
        sb.append("// Do not modify by hand\n\n")
        sb.append("class $className {\n")
        
        // Add fields
        for (field in fields) {
            val dartType = mapGraphQLTypeToDartType(field.type)
            sb.append("  final $dartType ${field.name};\n")
        }
        
        // Add constructor
        sb.append("\n  $className({\n")
        for (field in fields) {
            sb.append("    required this.${field.name},\n")
        }
        sb.append("  });\n\n")
        
        // Add fromJson method
        sb.append("  factory $className.fromJson(Map<String, dynamic> json) {\n")
        sb.append("    return $className(\n")
        for (field in fields) {
            sb.append("      ${field.name}: json['${field.name}'],\n")
        }
        sb.append("    );\n")
        sb.append("  }\n\n")
        
        // Add toJson method
        sb.append("  Map<String, dynamic> toJson() {\n")
        sb.append("    return {\n")
        for (field in fields) {
            sb.append("      '${field.name}': ${field.name},\n")
        }
        sb.append("    };\n")
        sb.append("  }\n")
        
        sb.append("}\n")
        
        return sb.toString()
    }
    
    private fun mapGraphQLTypeToDartType(graphqlType: String): String {
        // Map GraphQL types to Dart types
        // This is a simple implementation and may not work for all GraphQL types
        return when {
            graphqlType.contains("String") -> "String"
            graphqlType.contains("Int") -> "int"
            graphqlType.contains("Float") -> "double"
            graphqlType.contains("Boolean") -> "bool"
            graphqlType.contains("[") -> "List<${mapGraphQLTypeToDartType(graphqlType.substring(1, graphqlType.length - 1))}>"
            else -> "dynamic"
        }
    }
    
    data class Field(val name: String, val type: String)
    
    private fun String.capitalize(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
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