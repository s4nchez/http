package org.http4k.poet

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import org.http4k.core.Body
import org.http4k.format.Jackson
import org.http4k.lens.Cookies
import org.http4k.lens.Header
import org.http4k.lens.LensSpec
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.openapi.v3.ParameterSpec
import org.http4k.openapi.v3.SchemaSpec
import kotlin.reflect.KClass

fun ParameterSpec.asTypeName() = schema.clazz?.asTypeName()?.copy(nullable = !required)

fun FileSpec.Builder.buildFormatted() = this.indent("\t").build()

fun ParameterSpec.quotedName() = "\"$name\""

val ParameterSpec.lensSpecClazz
    get() = when (this) {
        is ParameterSpec.CookieSpec -> Cookies::class
        is ParameterSpec.HeaderSpec -> Header::class
        is ParameterSpec.QuerySpec -> Query::class
        is ParameterSpec.PathSpec -> Path::class
    }

inline fun <reified T : Any> member(name: String) = MemberName(T::class.asClassName(), name)
inline fun <reified T : Any> packageMember(name: String) = T::class.packageMember(name)
fun KClass<*>.packageMember(name: String) = MemberName(qualifiedName!!.split(".").dropLast(1).joinToString("."), name)

fun FunSpec.Builder.addCodeBlocks(blocks: List<CodeBlock>) = blocks.fold(this) { acc, next -> acc.addCode("\n").addCode(next) }.addCode("\n")

fun ParameterSpec.lensConstruct() =
    when (this) {
        is ParameterSpec.PathSpec -> "of"
        else -> if (required) "required" else "optional"
    }

fun org.http4k.openapi.v3.Path.lensDeclarations(): List<CodeBlock> {
    val bodyTypes = allSchemas()
        .mapNotNull {
            when (it.schema) {
                is SchemaSpec.ObjectSpec -> {
                    CodeBlock.of(
                        "val ${it.name.decapitalize()} = %T.%M<%T>().toLens()",
                        Body::class.asTypeName(),
                        member<Jackson>("auto"),
                        ClassName("", it.name)
                    )
                }
                is SchemaSpec.ArraySpec -> {
                    CodeBlock.of(
                        "val ${it.name.decapitalize()} = %T.%M<%T<%T>>().toLens()",
                        Body::class.asTypeName(),
                        member<Jackson>("auto"),
                        List::class.asClassName().parameterizedBy(ClassName("", it.name))
                    )
                }
                is SchemaSpec.RefSpec -> {
                    CodeBlock.of(
                        "val ${it.name.decapitalize()} = %T.%M<%T>().toLens()",
                        Body::class.asTypeName(),
                        member<Jackson>("auto"),
                        ClassName("", it.name)
                    )
                }
                else -> null
            }
        }

    val parameterTypes = pathSpec.parameters.map {
        when (it) {
            is ParameterSpec.CookieSpec -> CodeBlock.of(
                "val ${it.name}Lens = %T.${it.lensConstruct()}(${it.quotedName()})",
                it.lensSpecClazz.asClassName()
            )
            else -> CodeBlock.of(
                "val ${it.name}Lens = %T.%M().${it.lensConstruct()}(${it.quotedName()})",
                it.lensSpecClazz.asClassName(),
                packageMember<LensSpec<*, *>>(it.schema.clazz!!.simpleName!!.toLowerCase())
            )
        }
    }

    return bodyTypes + parameterTypes
}
