package org.http4k.openapi.v2

import com.squareup.kotlinpoet.FileSpec
import org.http4k.openapi.ApiGenerator
import org.http4k.openapi.GenerationOptions
import org.http4k.openapi.v3.models.ModelApiGenerator
import org.http4k.openapi.v3.client.ClientApiGenerator as ClientV3
import org.http4k.openapi.v3.server.ServerApiGenerator as ServerV3

object ModelApiGenerator : ApiGenerator<OpenApi2Spec> {
    override fun invoke(spec: OpenApi2Spec, options: GenerationOptions) = ModelApiGenerator(spec.asV3(), options)
}

object ClientApiGenerator : ApiGenerator<OpenApi2Spec> {
    override fun invoke(spec: OpenApi2Spec, options: GenerationOptions) = ClientV3(spec.asV3(), options)
}

object ServerApiGenerator : ApiGenerator<OpenApi2Spec> {
    override fun invoke(spec: OpenApi2Spec, options: GenerationOptions) = ServerV3(spec.asV3(), options)
}
