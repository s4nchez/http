package org.http4k.openapi.v3

import org.http4k.core.ContentType
import org.http4k.core.ContentType.Companion.APPLICATION_FORM_URLENCODED
import org.http4k.core.Method
import org.http4k.openapi.MessageBodySpec
import org.http4k.openapi.NamedSchema
import org.http4k.openapi.SchemaSpec
import org.http4k.openapi.cleanValueName
import org.http4k.openapi.namedSchema

data class Path(val urlPathPattern: String, val method: Method, val spec: OpenApi3PathSpec) {
    val uniqueName = (spec.operationId
        ?: method.toString().toLowerCase() + urlPathPattern.cleanValueName()).capitalize()

    private fun modelName(contentType: String, suffix: String) =
        uniqueName + ContentType(contentType).value.substringAfter('/').capitalize().filter(Char::isLetterOrDigit) + suffix

    fun requestSchemas(): List<NamedSchema> =
        listOfNotNull(spec.requestBody?.content?.entries
            ?.mapNotNull { (contentType, messageSpec) ->
                messageSpec.schema?.namedSchema(modelName(contentType, "Request"))
            }
        ).flatten()

    fun responseSchemas(): List<NamedSchema> = spec.responses.entries
        .flatMap { (code, messageSpec) ->
            messageSpec.content.entries.mapNotNull { (contentType, messageSpec) ->
                messageSpec.schema?.namedSchema(modelName(contentType, "Response$code"))
            }
        }
}

fun OpenApi3Spec.flattenedPaths() = paths.entries.flatMap { (path, verbs) -> verbs.map { Path(path, Method.valueOf(it.key.toUpperCase()), it.value) } }

fun OpenApi3Spec.apiName() = info.title.capitalize()

fun OpenApi3Spec.flatten() = replaceFormsWithParameters().flattenParameterRefsIntoPaths()

/**
 * Forms will render as objects instead of a set of parameters. Replace them upfront.
 */
private fun OpenApi3Spec.replaceFormsWithParameters(): OpenApi3Spec = copy(
    paths = paths.mapValues {
        it.value.mapValues { (_, path) ->
            path.contentFor(APPLICATION_FORM_URLENCODED)
                ?.let { formContent ->
                    when (formContent.schema) {
                        is SchemaSpec.RefSpec -> {
                            when (val newFormSchema = components.schemas[formContent.schema.`$ref`]!!) {
                                is SchemaSpec.ObjectSpec -> path.copy(
                                    parameters = path.parameters + newFormSchema.convertToParameters(),
                                    requestBody = path.remove(APPLICATION_FORM_URLENCODED)
                                )
                                else -> path.add(APPLICATION_FORM_URLENCODED, formContent)
                            }
                        }
                        is SchemaSpec.ObjectSpec -> path.copy(
                            parameters = path.parameters + formContent.schema.convertToParameters(),
                            requestBody = path.remove(APPLICATION_FORM_URLENCODED)
                        )
                        else -> null
                    }
                }
                ?: path
        }
    }
)

private fun OpenApi3PathSpec.contentFor(contentType: ContentType) = requestBody.content[contentType.value]

private fun OpenApi3PathSpec.add(contentType: ContentType, it: MessageBodySpec): OpenApi3PathSpec =
    copy(requestBody = requestBody.copy(content = requestBody.content + (contentType.value to it)))

private fun OpenApi3PathSpec.remove(contentType: ContentType): OpenApi3RequestBodySpec =
    requestBody.copy(content = requestBody.content.minus(contentType.value))

private fun SchemaSpec.ObjectSpec.convertToParameters() = properties.map {
    OpenApi3ParameterSpec.FormFieldSpec(it.key, required.contains(it.key), it.value)
}

/**
 * For all parameters which are common (and represented in the paths as Refs), inline the content into the path so
 * we can tell the type without looking up from the "global" list
 */
private fun OpenApi3Spec.flattenParameterRefsIntoPaths(): OpenApi3Spec = copy(paths = paths.mapValues {
    it.value.mapValues {
        val (refs, nonrefs) = it.value.parameters.partition { it is OpenApi3ParameterSpec.RefSpec }
        it.value.copy(parameters = refs.filterIsInstance<OpenApi3ParameterSpec.RefSpec>().map { components.parameters[it.schemaName]!! } + nonrefs)
    }.toMap()
})
