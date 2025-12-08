package com.vettid.app.features.handlers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.vettid.app.core.network.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for handler execution screen.
 *
 * Features:
 * - Load handler input schema
 * - Build dynamic form from schema
 * - Validate and execute handler
 * - Display execution results
 */
@HiltViewModel
class HandlerExecutionViewModel @Inject constructor(
    private val registryClient: HandlerRegistryClient,
    private val vaultHandlerClient: VaultHandlerClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val handlerId: String = savedStateHandle.get<String>("handlerId") ?: ""

    private val _state = MutableStateFlow<HandlerExecutionState>(HandlerExecutionState.Loading)
    val state: StateFlow<HandlerExecutionState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HandlerExecutionEffect>()
    val effects: SharedFlow<HandlerExecutionEffect> = _effects.asSharedFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    // Form field values
    private val _formValues = MutableStateFlow<Map<String, Any>>(emptyMap())
    val formValues: StateFlow<Map<String, Any>> = _formValues.asStateFlow()

    // Action token for authenticated operations
    private var currentActionToken: String? = null

    init {
        if (handlerId.isNotBlank()) {
            loadHandler()
        }
    }

    /**
     * Set action token for authenticated operations.
     */
    fun setActionToken(token: String) {
        currentActionToken = token
    }

    /**
     * Load handler details and input schema.
     */
    private fun loadHandler() {
        viewModelScope.launch {
            _state.value = HandlerExecutionState.Loading

            registryClient.getHandler(handlerId).fold(
                onSuccess = { handler ->
                    val fields = parseInputSchema(handler.inputSchema)
                    _state.value = HandlerExecutionState.Ready(
                        handler = handler,
                        inputFields = fields
                    )
                },
                onFailure = { error ->
                    _state.value = HandlerExecutionState.Error(
                        message = error.message ?: "Failed to load handler"
                    )
                }
            )
        }
    }

    /**
     * Update a form field value.
     */
    fun updateFieldValue(fieldName: String, value: Any) {
        _formValues.value = _formValues.value + (fieldName to value)
    }

    /**
     * Execute the handler with current form values.
     */
    fun executeHandler() {
        val currentState = _state.value
        if (currentState !is HandlerExecutionState.Ready) return

        val token = currentActionToken
        if (token == null) {
            viewModelScope.launch {
                _effects.emit(HandlerExecutionEffect.RequireAuth)
            }
            return
        }

        // Validate required fields
        val validationError = validateForm(currentState.inputFields, _formValues.value)
        if (validationError != null) {
            viewModelScope.launch {
                _effects.emit(HandlerExecutionEffect.ShowError(validationError))
            }
            return
        }

        // Build input JSON
        val input = buildInputJson(currentState.inputFields, _formValues.value)

        viewModelScope.launch {
            _isExecuting.value = true

            vaultHandlerClient.executeHandler(token, handlerId, input).fold(
                onSuccess = { response ->
                    _isExecuting.value = false

                    when (response.status) {
                        "success" -> {
                            _state.value = HandlerExecutionState.Completed(
                                handler = currentState.handler,
                                output = response.output,
                                executionTimeMs = response.executionTimeMs
                            )
                            _effects.emit(HandlerExecutionEffect.ShowSuccess(
                                "Executed in ${response.executionTimeMs}ms"
                            ))
                        }
                        "error" -> {
                            _state.value = HandlerExecutionState.Failed(
                                handler = currentState.handler,
                                error = response.error ?: "Unknown error"
                            )
                        }
                        "timeout" -> {
                            _state.value = HandlerExecutionState.Failed(
                                handler = currentState.handler,
                                error = "Execution timed out"
                            )
                        }
                        else -> {
                            _state.value = HandlerExecutionState.Failed(
                                handler = currentState.handler,
                                error = "Unexpected status: ${response.status}"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _isExecuting.value = false
                    _state.value = HandlerExecutionState.Failed(
                        handler = currentState.handler,
                        error = error.message ?: "Execution failed"
                    )
                }
            )
        }
    }

    /**
     * Reset to ready state for re-execution.
     */
    fun resetExecution() {
        val currentState = _state.value
        val handler = when (currentState) {
            is HandlerExecutionState.Completed -> currentState.handler
            is HandlerExecutionState.Failed -> currentState.handler
            else -> return
        }

        viewModelScope.launch {
            val fields = parseInputSchema(handler.inputSchema)
            _state.value = HandlerExecutionState.Ready(
                handler = handler,
                inputFields = fields
            )
        }
    }

    /**
     * Navigate back.
     */
    fun navigateBack() {
        viewModelScope.launch {
            _effects.emit(HandlerExecutionEffect.NavigateBack)
        }
    }

    /**
     * Parse JSON schema into input fields.
     */
    private fun parseInputSchema(schema: JsonObject): List<InputField> {
        val fields = mutableListOf<InputField>()
        val properties = schema.getAsJsonObject("properties") ?: return fields
        val required = schema.getAsJsonArray("required")?.map { it.asString } ?: emptyList()

        properties.keySet().forEach { key ->
            val property = properties.getAsJsonObject(key)
            val type = property.get("type")?.asString ?: "string"
            val title = property.get("title")?.asString ?: key
            val description = property.get("description")?.asString
            val defaultValue = property.get("default")
            val enumValues = property.getAsJsonArray("enum")?.map { it.asString }

            val fieldType = when {
                enumValues != null -> InputFieldType.Select(enumValues)
                type == "boolean" -> InputFieldType.Boolean
                type == "integer" -> InputFieldType.Integer
                type == "number" -> InputFieldType.Number
                type == "array" -> InputFieldType.Array
                else -> InputFieldType.String
            }

            fields.add(InputField(
                name = key,
                label = title,
                description = description,
                type = fieldType,
                required = key in required,
                defaultValue = when {
                    defaultValue?.isJsonPrimitive == true -> {
                        val primitive = defaultValue.asJsonPrimitive
                        when {
                            primitive.isBoolean -> primitive.asBoolean
                            primitive.isNumber -> primitive.asNumber
                            else -> primitive.asString
                        }
                    }
                    else -> null
                }
            ))
        }

        return fields
    }

    /**
     * Validate form values against field requirements.
     */
    private fun validateForm(fields: List<InputField>, values: Map<String, Any>): String? {
        for (field in fields) {
            if (field.required) {
                val value = values[field.name]
                if (value == null || (value is String && value.isBlank())) {
                    return "${field.label} is required"
                }
            }
        }
        return null
    }

    /**
     * Build JSON object from form values.
     */
    private fun buildInputJson(fields: List<InputField>, values: Map<String, Any>): JsonObject {
        val json = JsonObject()

        for (field in fields) {
            val value = values[field.name] ?: field.defaultValue ?: continue

            when (field.type) {
                is InputFieldType.Boolean -> {
                    json.add(field.name, JsonPrimitive(value as? Boolean ?: false))
                }
                is InputFieldType.Integer -> {
                    val intValue = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull() ?: 0
                        else -> 0
                    }
                    json.add(field.name, JsonPrimitive(intValue))
                }
                is InputFieldType.Number -> {
                    val numValue = when (value) {
                        is Number -> value.toDouble()
                        is String -> value.toDoubleOrNull() ?: 0.0
                        else -> 0.0
                    }
                    json.add(field.name, JsonPrimitive(numValue))
                }
                is InputFieldType.Array -> {
                    val array = JsonArray()
                    (value as? List<*>)?.forEach { item ->
                        when (item) {
                            is String -> array.add(JsonPrimitive(item))
                            is Number -> array.add(JsonPrimitive(item))
                            is Boolean -> array.add(JsonPrimitive(item))
                        }
                    }
                    json.add(field.name, array)
                }
                else -> {
                    json.add(field.name, JsonPrimitive(value.toString()))
                }
            }
        }

        return json
    }
}

// MARK: - State Types

/**
 * Handler execution state.
 */
sealed class HandlerExecutionState {
    object Loading : HandlerExecutionState()

    data class Ready(
        val handler: HandlerDetailResponse,
        val inputFields: List<InputField>
    ) : HandlerExecutionState()

    data class Completed(
        val handler: HandlerDetailResponse,
        val output: JsonObject?,
        val executionTimeMs: Long
    ) : HandlerExecutionState()

    data class Failed(
        val handler: HandlerDetailResponse,
        val error: String
    ) : HandlerExecutionState()

    data class Error(val message: String) : HandlerExecutionState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class HandlerExecutionEffect {
    object RequireAuth : HandlerExecutionEffect()
    object NavigateBack : HandlerExecutionEffect()
    data class ShowSuccess(val message: String) : HandlerExecutionEffect()
    data class ShowError(val message: String) : HandlerExecutionEffect()
}

// MARK: - Input Field Types

/**
 * Input field definition parsed from JSON schema.
 */
data class InputField(
    val name: String,
    val label: String,
    val description: String?,
    val type: InputFieldType,
    val required: Boolean,
    val defaultValue: Any?
)

/**
 * Input field type variants.
 */
sealed class InputFieldType {
    object String : InputFieldType()
    object Boolean : InputFieldType()
    object Integer : InputFieldType()
    object Number : InputFieldType()
    object Array : InputFieldType()
    data class Select(val options: List<kotlin.String>) : InputFieldType()
}
