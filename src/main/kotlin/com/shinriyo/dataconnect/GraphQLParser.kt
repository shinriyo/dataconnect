package com.shinriyo.dataconnect

data class Field(val name: String, val type: String)
data class Variable(val name: String, val type: String)
data class Operation(
    val type: String,
    val name: String,
    val variables: List<Variable>,
    val fields: List<Field>
)

class GraphQLParser {
    fun parse(content: String): Operation? {
        val operationType = extractOperationType(content)
        val operationName = extractOperationName(content)
        
        if (operationType == null || operationName == null) {
            return null
        }
        
        val variables = extractVariables(content)
        val fields = extractFields(content)
        
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

    private fun extractVariables(content: String): List<Variable> {
        val variables = mutableSetOf<Variable>()

        // Extract variables from GraphQL content
        // Ignore commented lines
        val lines = content.lines()
        var isInComment = false

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
            
            val variableRegex = Regex("\\$([a-zA-Z0-9_]+)\\s*:\\s*([a-zA-Z0-9_\\[\\]!]+)")
            val matches = variableRegex.findAll(line)
            
            for (match in matches) {
                val name = match.groupValues[1]
                val type = match.groupValues[2]
                variables.add(Variable(name, type))
            }
        }
        
        return variables.toList()
    }
    
    private fun extractFields(content: String): List<Field> {
        val fields = mutableListOf<Field>()
        
        // Extract fields from GraphQL content
        // Ignore commented lines
        val lines = content.lines()
        var isInComment = false
        var isInOperation = false
        var currentOperation: String? = null
        
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
            
            // Check for operation start
            val operationMatch = Regex("(query|mutation|subscription)\\s+([a-zA-Z0-9_]+)").find(line)
            if (operationMatch != null) {
                isInOperation = true
                currentOperation = operationMatch.groupValues[2]
                continue
            }
            
            // Only process fields if we're in an operation
            if (isInOperation) {
                // Look for field patterns like: field_name(arguments) { nested_fields }
                val fieldRegex = Regex("([a-zA-Z0-9_]+)\\s*\\([^)]*\\)\\s*\\{[^}]*\\}")
                val matches = fieldRegex.findAll(line)
                
                for (match in matches) {
                    val name = match.groupValues[1]
                    fields.add(Field(name, "Object"))
                }
                
                // Also look for simple fields without arguments
                val simpleFieldRegex = Regex("([a-zA-Z0-9_]+)\\s*\\{[^}]*\\}")
                val simpleMatches = simpleFieldRegex.findAll(line)
                
                for (match in simpleMatches) {
                    val name = match.groupValues[1]
                    // Avoid duplicates
                    if (!fields.any { it.name == name }) {
                        fields.add(Field(name, "Object"))
                    }
                }
            }
        }
        
        return fields
    }
} 