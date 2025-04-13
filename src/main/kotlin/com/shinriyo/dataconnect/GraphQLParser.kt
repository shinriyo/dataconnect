package com.shinriyo.dataconnect

data class Field(val name: String, val type: String)
data class Variable(val name: String, val type: String)
data class Operation(
    val type: String,
    val name: String,
    val variables: List<Variable>,
    val fields: List<Field>
)

data class TypeDefinition(
    val name: String,
    val fields: List<Field>,
    val isTable: Boolean
)

class GraphQLParser {
    fun parse(content: String): Operation? {
        val operationType = extractOperationType(content)
        val operationName = extractOperationName(content)
        
        if (operationType == null || operationName == null) {
            return null
        }
        
        val variables = extractVariables(content, operationName)
        val fields = extractFields(content, operationName)
        
        return Operation(operationType, operationName, variables, fields)
    }
    
    private fun extractOperationType(content: String): String? {
        // Extract operation type (query, mutation, subscription) from GraphQL content
        // Ignore commented lines
        val lines = content.lines()
        for (line in lines) {
            if (line.trimStart().startsWith("#")) continue
            val queryMatch = Regex("(query|mutation|subscription)\\s+([a-zA-Z0-9_]+)").find(line)
            if (queryMatch != null) {
                return queryMatch.groupValues[1]
            }
        }
        return null
    }
    
    private fun extractOperationName(content: String): String? {
        // Extract operation name from GraphQL content
        // Ignore commented lines
        val lines = content.lines()
        for (line in lines) {
            if (line.trimStart().startsWith("#")) continue
            val queryMatch = Regex("(query|mutation|subscription)\\s+([a-zA-Z0-9_]+)").find(line)
            if (queryMatch != null) {
                return queryMatch.groupValues[2]
            }
        }
        return null
    }

    private fun extractVariables(content: String, operationName: String): List<Variable> {
        val variables = mutableSetOf<Variable>()

        // Extract variables from GraphQL content
        // Ignore commented lines
        val lines = content.lines()
        var isInComment = false
        var isInTargetOperation = false

        for (line in lines) {
            val trimmedLine = line.trimStart()

            // Handle single-line comments
            if (trimmedLine.startsWith("#")) continue
            
            // Handle multi-line comments
            if (trimmedLine.contains(""""""")) {
                isInComment = !isInComment
                continue
            }
            if (isInComment) continue
            
            // Check if we're in the target operation
            val operationMatch = Regex("(query|mutation|subscription)\\s+([a-zA-Z0-9_]+)").find(line)
            if (operationMatch != null) {
                val currentOperationName = operationMatch.groupValues[2]
                isInTargetOperation = currentOperationName == operationName
                continue
            }
            
            // Only extract variables if we're in the target operation
            if (isInTargetOperation) {
                val variableRegex = Regex("\\$([a-zA-Z0-9_]+)\\s*:\\s*([a-zA-Z0-9_\\[\\]!]+)")
                val matches = variableRegex.findAll(line)
                
                for (match in matches) {
                    val name = match.groupValues[1]
                    val type = match.groupValues[2]
                    variables.add(Variable(name, type))
                }
            }
        }
        
        return variables.toList()
    }
    
    private fun extractFields(content: String, operationName: String): List<Field> {
        val fields = mutableListOf<Field>()
        var isInOperation = false
        var isInComment = false
        
        content.lines().forEach { line ->
            val trimmedLine = line.trim()
            
            // Skip empty lines and comments
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                return@forEach
            }
            
            // Handle multi-line comments
            if (trimmedLine.contains("/*")) {
                isInComment = true
                return@forEach
            }
            if (trimmedLine.contains("*/")) {
                isInComment = false
                return@forEach
            }
            if (isInComment) {
                return@forEach
            }
            
            // Check if we're in an operation
            if (trimmedLine.contains("{")) {
                isInOperation = true
                return@forEach
            }
            if (trimmedLine.contains("}")) {
                isInOperation = false
                return@forEach
            }
            
            if (isInOperation) {
                // Handle nested fields
                val nestedFieldMatch = Regex("([a-zA-Z0-9_]+)\\s*\\{\\s*([^}]*)\\s*\\}").find(trimmedLine)
                if (nestedFieldMatch != null) {
                    val fieldName = nestedFieldMatch.groupValues[1]
                    val nestedContent = nestedFieldMatch.groupValues[2]
                    
                    // Create a nested field type
                    val nestedType = "${operationName.capitalize()}$fieldName"
                    fields.add(Field(fieldName, nestedType))
                    
                    // Extract nested fields
                    val nestedFields = extractFields(nestedContent, operationName)
                    // Store nested fields for later use in Dart code generation
                    nestedFieldDefinitions[nestedType] = nestedFields
                } else {
                    // Handle simple fields
                    val fieldMatch = Regex("([a-zA-Z0-9_]+)(?:\\s*:\\s*([a-zA-Z0-9_\\[\\]!]+))?").find(trimmedLine)
                    if (fieldMatch != null) {
                        val fieldName = fieldMatch.groupValues[1]
                        val fieldType = fieldMatch.groupValues[2].takeIf { it.isNotEmpty() } ?: "String"
                        fields.add(Field(fieldName, fieldType))
                    }
                }
            }
        }
        
        return fields
    }

    // Add a map to store nested field definitions
    private val nestedFieldDefinitions = mutableMapOf<String, List<Field>>()
    
    // Add a method to get nested field definitions
    fun getNestedFields(typeName: String): List<Field>? {
        return nestedFieldDefinitions[typeName]
    }

    fun parseSchema(content: String): List<TypeDefinition> {
        val typeDefinitions = mutableListOf<TypeDefinition>()
        val lines = content.lines()
        var currentType: String? = null
        var currentFields = mutableListOf<Field>()
        var isTable = false

        for (line in lines) {
            val trimmedLine = line.trimStart()

            // Skip comments
            if (trimmedLine.startsWith("#")) continue

            // Check for type definition
            val typeMatch = Regex("type\\s+([a-zA-Z0-9_]+)\\s*(@table)?\\s*\\{").find(trimmedLine)
            if (typeMatch != null) {
                // Save previous type definition if exists
                if (currentType != null) {
                    typeDefinitions.add(TypeDefinition(currentType, currentFields, isTable))
                }

                // Start new type definition
                currentType = typeMatch.groupValues[1]
                currentFields = mutableListOf()
                isTable = typeMatch.groupValues[2] != null
                continue
            }

            // Check for field definition
            if (currentType != null && trimmedLine.contains(":")) {
                val fieldMatch = Regex("([a-zA-Z0-9_]+)\\s*:\\s*([a-zA-Z0-9_!\\[\\]]+)").find(trimmedLine)
                if (fieldMatch != null) {
                    val fieldName = fieldMatch.groupValues[1]
                    val fieldType = fieldMatch.groupValues[2]
                    currentFields.add(Field(fieldName, fieldType))
                }
            }

            // Check for type definition end
            if (trimmedLine == "}") {
                if (currentType != null) {
                    typeDefinitions.add(TypeDefinition(currentType, currentFields, isTable))
                    currentType = null
                    currentFields = mutableListOf()
                    isTable = false
                }
            }
        }

        return typeDefinitions
    }
} 