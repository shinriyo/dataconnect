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
import java.util.*

class GqlSaveListener : BulkFileListener {
    private val log = Logger.getInstance(GqlSaveListener::class.java)
    private val notificationGroupId = "Firebase Data Connect"
    private val parser = GraphQLParser()

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
                val success = generateDartFromGraphQL(file, outputDir)
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
    
    private fun generateDartFromGraphQL(graphqlFile: File, outputDir: File): Boolean {
        log.info("Generating Dart file for ${graphqlFile.name}")
        
        try {
            val content = graphqlFile.readText()
            val operation = parser.parse(content)
            
            if (operation == null) {
                log.warn("Could not parse operation from ${graphqlFile.name}")
                return false
            }
            
            // Convert to snake_case for file name
            val snakeCaseName = operation.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(Locale.getDefault())
            val dartFileName = "${snakeCaseName}.dart"
            val dartFile = File(outputDir, dartFileName)
            
            val dartContent = generateDartContent(operation)
            dartFile.writeText(dartContent)
            
            log.info("Generated Dart file: ${dartFile.absolutePath}")
            return true
        } catch (e: Exception) {
            log.error("Failed to generate Dart file for ${graphqlFile.name}", e)
            return false
        }
    }
    
    private fun generateDartContent(operation: Operation): String {
        val className = operation.name.capitalize()
        val dataClassName = "${className}Data"
        val variablesClassName = "${className}Variables"
        val builderClassName = "${className}VariablesBuilder"
        
        val sb = StringBuilder()
        sb.append("part of 'default_connector.dart';\n\n")
        
        // Generate VariablesBuilder class
        sb.append("class $builderClassName {\n")
        
        // Add variables
        for (variable in operation.variables) {
            val dartType = mapGraphQLTypeToDartType(variable.type)
            sb.append("  $dartType ${variable.name};\n")
        }
        
        // Add dataConnect field
        sb.append("\n  final FirebaseDataConnect _dataConnect;\n")
        
        // Add constructor
        sb.append("  $builderClassName(this._dataConnect, {")
        for (variable in operation.variables) {
            sb.append("required this.${variable.name},")
        }
        sb.append("});\n\n")
        
        // Add deserializer and serializer
        sb.append("  Deserializer<$dataClassName> dataDeserializer = (dynamic json) => $dataClassName.fromJson(jsonDecode(json));\n")
        sb.append("  Serializer<$variablesClassName> varsSerializer = ($variablesClassName vars) => jsonEncode(vars.toJson());\n\n")
        
        // Add execute method
        sb.append("  Future<OperationResult<$dataClassName, $variablesClassName>> execute() {\n")
        sb.append("    return ref().execute();\n")
        sb.append("  }\n\n")
        
        // Add ref method
        sb.append("  ${operation.type.capitalize()}Ref<$dataClassName, $variablesClassName> ref() {\n")
        sb.append("    $variablesClassName vars = $variablesClassName(")
        for (variable in operation.variables) {
            sb.append("${variable.name}: ${variable.name},")
        }
        sb.append(");\n")
        sb.append("    return _dataConnect.${operation.type.lowercase(Locale.getDefault())}(\"${operation.name}\", dataDeserializer, varsSerializer, vars);\n")
        sb.append("  }\n")
        
        sb.append("}\n\n")
        
        // Generate field classes
        for (field in operation.fields) {
            val fieldClassName = "${className}${field.name.capitalize()}"
            
            // Add field class
            sb.append("class $fieldClassName {\n")
            
            // Add fields (assuming uid field for now)
            sb.append("  String uid;\n")
            
            // Add fromJson method
            sb.append("  $fieldClassName.fromJson(dynamic json):\n")
            sb.append("  uid = nativeFromJson<String>(json['uid']);\n\n")
            
            // Add toJson method
            sb.append("  Map<String, dynamic> toJson() {\n")
            sb.append("    Map<String, dynamic> json = {};\n")
            sb.append("    json['uid'] = nativeToJson<String>(uid);\n")
            sb.append("    return json;\n")
            sb.append("  }\n\n")
            
            // Add constructor
            sb.append("  $fieldClassName({\n")
            sb.append("    required this.uid,\n")
            sb.append("  });\n")
            
            sb.append("}\n\n")
        }
        
        // Generate Data class
        sb.append("class $dataClassName {\n")
        
        // Add fields with snake_case
        for (field in operation.fields) {
            val fieldClassName = "${className}${field.name.capitalize()}"
            val snakeCaseFieldName = field.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(Locale.getDefault())
            sb.append("  $fieldClassName ${snakeCaseFieldName};\n")
        }
        
        // Add fromJson method
        sb.append("  $dataClassName.fromJson(dynamic json):\n")
        for (field in operation.fields) {
            val fieldClassName = "${className}${field.name.capitalize()}"
            val snakeCaseFieldName = field.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(Locale.getDefault())
            sb.append("  ${snakeCaseFieldName} = $fieldClassName.fromJson(json['${snakeCaseFieldName}']);\n")
        }
        
        // Add toJson method
        sb.append("\n  Map<String, dynamic> toJson() {\n")
        sb.append("    Map<String, dynamic> json = {};\n")
        for (field in operation.fields) {
            val snakeCaseFieldName = field.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(Locale.getDefault())
            sb.append("    json['${snakeCaseFieldName}'] = ${snakeCaseFieldName}.toJson();\n")
        }
        sb.append("    return json;\n")
        sb.append("  }\n\n")
        
        // Add constructor
        sb.append("  $dataClassName({\n")
        for (field in operation.fields) {
            val snakeCaseFieldName = field.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(Locale.getDefault())
            sb.append("    required this.${snakeCaseFieldName},\n")
        }
        sb.append("  });\n")
        
        sb.append("}\n\n")
        
        // Generate Variables class
        sb.append("class $variablesClassName {\n")
        
        // Add variables
        for (variable in operation.variables) {
            val dartType = mapGraphQLTypeToDartType(variable.type)
            sb.append("  $dartType ${variable.name};\n")
        }
        
        // Add fromJson method
        sb.append("  @Deprecated('fromJson is deprecated for Variable classes as they are no longer required for deserialization.')\n")
        sb.append("  $variablesClassName.fromJson(Map<String, dynamic> json):\n")
        for (variable in operation.variables) {
            val dartType = mapGraphQLTypeToDartType(variable.type)
            sb.append("  ${variable.name} = nativeFromJson<$dartType>(json['${variable.name}']),")
        }
        sb.append("\n\n")
        
        // Add toJson method
        sb.append("  Map<String, dynamic> toJson() {\n")
        sb.append("    Map<String, dynamic> json = {};\n")
        for (variable in operation.variables) {
            val dartType = mapGraphQLTypeToDartType(variable.type)
            sb.append("    json['${variable.name}'] = nativeToJson<$dartType>(${variable.name});\n")
        }
        sb.append("    return json;\n")
        sb.append("  }\n\n")
        
        // Add constructor
        sb.append("  $variablesClassName({\n")
        for (variable in operation.variables) {
            sb.append("    required this.${variable.name},\n")
        }
        sb.append("  });\n")
        
        sb.append("}\n")
        
        return sb.toString()
    }
    
    private fun mapGraphQLTypeToDartType(graphqlType: String): String {
        // Map GraphQL types to Dart types
        return when {
            graphqlType.contains("String") -> "String"
            graphqlType.contains("Int") -> "int"
            graphqlType.contains("Float") -> "double"
            graphqlType.contains("Boolean") -> "bool"
            graphqlType.contains("[") -> "List<${mapGraphQLTypeToDartType(graphqlType.substring(1, graphqlType.length - 1))}>"
            else -> "dynamic"
        }
    }
    
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